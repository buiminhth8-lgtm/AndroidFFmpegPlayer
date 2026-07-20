#include "NativeYuvGlRenderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string jsonSuccess(const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"" << message << "\"}";
    return out.str();
}

std::string jsonError(int errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":" << errorCode
        << ",\"errorMessage\":\"" << message << "\"}";
    return out.str();
}

int64_t steadyNowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string eglErrorString(const char *stage) {
    std::ostringstream out;
    out << stage << " eglError=0x" << std::hex << eglGetError();
    return out.str();
}

std::string glErrorString(const char *stage) {
    std::ostringstream out;
    out << stage << " glError=0x" << std::hex << glGetError();
    return out.str();
}

const char *kVertexShader = R"(
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
)";

const char *kFragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
uniform sampler2D uTexture;
uniform sampler2D vTexture;
void main() {
    float y = texture2D(yTexture, vTexCoord).r;
    float u = texture2D(uTexture, vTexCoord).r - 0.5;
    float v = texture2D(vTexture, vTexCoord).r - 0.5;
    float r = y + 1.402 * v;
    float g = y - 0.344136 * u - 0.714136 * v;
    float b = y + 1.772 * u;
    gl_FragColor = vec4(r, g, b, 1.0);
}
)";

GLuint compileShader(GLenum type, const char *source, std::string &errorMessage) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        errorMessage = glErrorString("glCreateShader");
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint logLength = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLength);
        std::string log(static_cast<size_t>(std::max(logLength, 1)), '\0');
        glGetShaderInfoLog(shader, logLength, nullptr, log.data());
        errorMessage = "shader compile failed: " + log;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace

NativeYuvGlRenderer::NativeYuvGlRenderer() = default;

NativeYuvGlRenderer::~NativeYuvGlRenderer() {
    release();
}

std::string NativeYuvGlRenderer::setSurface(JNIEnv *env, jobject surface, int width, int height) {
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is null");
    }
    if (surface == nullptr) {
        return jsonError(-1, "Surface is null");
    }

    ANativeWindow *newWindow = ANativeWindow_fromSurface(env, surface);
    if (newWindow == nullptr) {
        return jsonError(-1, "ANativeWindow_fromSurface failed");
    }

    std::lock_guard<std::mutex> lock(mutex_);
    releaseGlLocked();
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = newWindow;
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    frameWidth_ = 0;
    frameHeight_ = 0;
    LOGI("setSurface GL YUV success surface=%dx%d", width, height);
    return jsonSuccess("gl yuv surface set");
}

RenderResult NativeYuvGlRenderer::renderI420(const uint8_t *yData, int yStride,
                                             const uint8_t *uData, int uStride,
                                             const uint8_t *vData, int vStride,
                                             int width, int height) {
    if (yData == nullptr || uData == nullptr || vData == nullptr
        || yStride <= 0 || uStride <= 0 || vStride <= 0
        || width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
        return {false, -1, "invalid I420 frame", {}};
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) {
        return {false, -1, "Surface is not set", {}};
    }

    RenderStats stats;
    const int64_t renderStartUs = steadyNowUs();

    std::string error;
    if (!ensureGlLocked(error)) {
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, error, stats};
    }

    const uint8_t *uploadY = compactPlane(yData, yStride, width, height, compactY_);
    const uint8_t *uploadU = compactPlane(uData, uStride, width / 2, height / 2, compactU_);
    const uint8_t *uploadV = compactPlane(vData, vStride, width / 2, height / 2, compactV_);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    const int64_t uploadStartUs = steadyNowUs();
    if (!uploadPlane(0, uploadY, width, height, error)
        || !uploadPlane(1, uploadU, width / 2, height / 2, error)
        || !uploadPlane(2, uploadV, width / 2, height / 2, error)) {
        stats.copyCostUs = steadyNowUs() - uploadStartUs;
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, error, stats};
    }
    stats.copyCostUs = steadyNowUs() - uploadStartUs;

    const int viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
    const int viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
    glViewport(0, 0, viewportWidth, viewportHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(program_);

    static const GLfloat vertices[] = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };
    static const GLfloat texCoords[] = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    const GLint positionLocation = glGetAttribLocation(program_, "aPosition");
    const GLint texCoordLocation = glGetAttribLocation(program_, "aTexCoord");
    if (positionLocation < 0 || texCoordLocation < 0) {
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, "GL YUV shader attribute not found", stats};
    }
    glEnableVertexAttribArray(static_cast<GLuint>(positionLocation));
    glVertexAttribPointer(static_cast<GLuint>(positionLocation), 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLocation));
    glVertexAttribPointer(static_cast<GLuint>(texCoordLocation), 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(positionLocation));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLocation));

    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        std::ostringstream out;
        out << "glDrawArrays failed glError=0x" << std::hex << glError;
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, out.str(), stats};
    }

    const int64_t swapStartUs = steadyNowUs();
    if (eglSwapBuffers(eglDisplay_, eglSurface_) != EGL_TRUE) {
        stats.postCostUs = steadyNowUs() - swapStartUs;
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, eglErrorString("eglSwapBuffers"), stats};
    }
    stats.postCostUs = steadyNowUs() - swapStartUs;
    stats.totalCostUs = steadyNowUs() - renderStartUs;

    frameWidth_ = width;
    frameHeight_ = height;
    ++renderCount_;
    if (renderCount_ == 1 || renderCount_ % 100 == 0) {
        LOGI("GL YUV render count=%lld frame=%dx%d surface=%dx%d uploadUs=%lld totalUs=%lld",
             static_cast<long long>(renderCount_), width, height, viewportWidth, viewportHeight,
             static_cast<long long>(stats.copyCostUs), static_cast<long long>(stats.totalCostUs));
    }
    return {true, 0, "", stats};
}

void NativeYuvGlRenderer::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    releaseGlLocked();
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    surfaceWidth_ = 0;
    surfaceHeight_ = 0;
    frameWidth_ = 0;
    frameHeight_ = 0;
}

bool NativeYuvGlRenderer::hasSurface() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool NativeYuvGlRenderer::ensureGlLocked(std::string &errorMessage) {
    if (eglDisplay_ != EGL_NO_DISPLAY && eglSurface_ != EGL_NO_SURFACE
        && eglContext_ != EGL_NO_CONTEXT && program_ != 0) {
        if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
            errorMessage = eglErrorString("eglMakeCurrent");
            return false;
        }
        return true;
    }

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        errorMessage = eglErrorString("eglGetDisplay");
        return false;
    }
    if (eglInitialize(eglDisplay_, nullptr, nullptr) != EGL_TRUE) {
        errorMessage = eglErrorString("eglInitialize");
        releaseGlLocked();
        return false;
    }

    const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    EGLint numConfigs = 0;
    if (eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) != EGL_TRUE || numConfigs <= 0) {
        errorMessage = eglErrorString("eglChooseConfig");
        releaseGlLocked();
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        errorMessage = eglErrorString("eglCreateContext");
        releaseGlLocked();
        return false;
    }

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window_, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        errorMessage = eglErrorString("eglCreateWindowSurface");
        releaseGlLocked();
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        errorMessage = eglErrorString("eglMakeCurrent");
        releaseGlLocked();
        return false;
    }
    if (!compileProgramLocked(errorMessage)) {
        releaseGlLocked();
        return false;
    }
    return true;
}

void NativeYuvGlRenderer::releaseGlLocked() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        if (eglSurface_ != EGL_NO_SURFACE && eglContext_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
        }
        if (textures_[0] != 0 || textures_[1] != 0 || textures_[2] != 0) {
            glDeleteTextures(3, textures_);
            textures_[0] = textures_[1] = textures_[2] = 0;
        }
        if (program_ != 0) {
            glDeleteProgram(program_);
            program_ = 0;
        }
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }
    eglConfig_ = nullptr;
}

bool NativeYuvGlRenderer::compileProgramLocked(std::string &errorMessage) {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kVertexShader, errorMessage);
    if (vertexShader == 0) {
        return false;
    }
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kFragmentShader, errorMessage);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return false;
    }

    program_ = glCreateProgram();
    if (program_ == 0) {
        errorMessage = glErrorString("glCreateProgram");
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return false;
    }
    glAttachShader(program_, vertexShader);
    glAttachShader(program_, fragmentShader);
    glLinkProgram(program_);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint logLength = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &logLength);
        std::string log(static_cast<size_t>(std::max(logLength, 1)), '\0');
        glGetProgramInfoLog(program_, logLength, nullptr, log.data());
        errorMessage = "program link failed: " + log;
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    glUseProgram(program_);
    glGenTextures(3, textures_);
    const char *samplers[] = {"yTexture", "uTexture", "vTexture"};
    for (int i = 0; i < 3; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, textures_[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUniform1i(glGetUniformLocation(program_, samplers[i]), i);
    }
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream out;
        out << "GL YUV program setup failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}

const uint8_t *NativeYuvGlRenderer::compactPlane(const uint8_t *src, int srcStride, int width, int height, std::vector<uint8_t> &buffer) {
    if (srcStride == width) {
        return src;
    }
    buffer.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
    for (int y = 0; y < height; ++y) {
        std::memcpy(buffer.data() + static_cast<size_t>(y) * static_cast<size_t>(width),
                    src + static_cast<size_t>(y) * static_cast<size_t>(srcStride),
                    static_cast<size_t>(width));
    }
    return buffer.data();
}

bool NativeYuvGlRenderer::uploadPlane(int textureIndex, const uint8_t *data, int width, int height, std::string &errorMessage) {
    glActiveTexture(GL_TEXTURE0 + textureIndex);
    glBindTexture(GL_TEXTURE_2D, textures_[textureIndex]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream out;
        out << "glTexImage2D plane=" << textureIndex << " failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}
