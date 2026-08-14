#include "NativeNv12GlRenderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <sstream>

extern "C" {
#include "libavutil/pixfmt.h"
}

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

const char *kNv12VertexShader = R"(
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
)";

// NV12: Y plane (GL_LUMINANCE) + interleaved UV plane (GL_LUMINANCE_ALPHA: L=U, A=V).
// BT.601/BT.709 coefficients selected via uCoeffs = (cR_V, cG_U, cG_V, cB_U).
const char *kNv12FragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform sampler2D uTextureUV;
uniform float uYMin;
uniform float uYScale;
uniform vec4 uCoeffs;
void main() {
    float y = texture2D(uTextureY, vTexCoord).r;
    y = clamp((y - uYMin) * uYScale, 0.0, 1.0);
    vec2 uv = texture2D(uTextureUV, vTexCoord).ra;
    float u = uv.x - 0.5;
    float v = uv.y - 0.5;
    float r = y + uCoeffs.x * v;
    float g = y - uCoeffs.y * u - uCoeffs.z * v;
    float b = y + uCoeffs.w * u;
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

GLuint linkProgram(GLuint vertexShader, GLuint fragmentShader, std::string &errorMessage) {
    GLuint program = glCreateProgram();
    if (program == 0) {
        errorMessage = glErrorString("glCreateProgram");
        return 0;
    }
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint logLength = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLength);
        std::string log(static_cast<size_t>(std::max(logLength, 1)), '\0');
        glGetProgramInfoLog(program, logLength, nullptr, log.data());
        errorMessage = "program link failed: " + log;
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

} // namespace

NativeNv12GlRenderer::NativeNv12GlRenderer() = default;

NativeNv12GlRenderer::~NativeNv12GlRenderer() {
    release();
}

std::string NativeNv12GlRenderer::setSurface(JNIEnv *env, jobject surface, int width, int height) {
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
    if (eglDisplay_ != EGL_NO_DISPLAY && eglContext_ != EGL_NO_CONTEXT) {
        // Surface recreated: keep EGL context / program / textures, rebuild only
        // the EGL window surface. Resolution change reallocates textures on the
        // next render (glTexImage2D on size change).
        if (!rebindEglSurfaceLocked(newWindow, width, height)) {
            LOGI("NV12 GL EGLSurface rebind failed, full teardown on next render");
            releaseGlLocked();
        }
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = newWindow;
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    LOGI("setSurface NV12 GL success surface=%dx%d", width, height);
    return jsonSuccess("nv12 gl surface set");
}

RenderResult NativeNv12GlRenderer::renderNv12(const uint8_t *yData, int yStride,
                                              const uint8_t *uvData, int uvStride,
                                              int width, int height, int colorRange, int colorspace) {
    if (yData == nullptr || uvData == nullptr || yStride <= 0 || uvStride <= 0
        || width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
        return {false, -1, "invalid NV12 frame", {}};
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

    const bool sizeChanged = frameWidth_ != width || frameHeight_ != height;
    if (sizeChanged) {
        yStaging_.clear();
        uvStaging_.clear();
        yStaging_.shrink_to_fit();
        uvStaging_.shrink_to_fit();
    }
    if (yStaging_.size() < static_cast<size_t>(width) * static_cast<size_t>(height)) {
        yStaging_.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
    }
    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    const int uvVisibleRowBytes = chromaWidth * 2;  // NV12 interleaved U,V
    const size_t uvBytes = static_cast<size_t>(uvVisibleRowBytes) * static_cast<size_t>(chromaHeight);
    if (uvStaging_.size() < uvBytes) {
        uvStaging_.resize(uvBytes);
    }

    const uint8_t *uploadY = yStride == width
                             ? yData
                             : compactPlane(yData, yStride, width, height, yStaging_);
    const uint8_t *uploadUV = uvStride == uvVisibleRowBytes
                              ? uvData
                              : compactPlane(uvData, uvStride, uvVisibleRowBytes, chromaHeight, uvStaging_);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    const int64_t uploadStartUs = steadyNowUs();
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textures_[0]);
    if (sizeChanged) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0,
                     GL_LUMINANCE, GL_UNSIGNED_BYTE, uploadY);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                        GL_LUMINANCE, GL_UNSIGNED_BYTE, uploadY);
    }
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, textures_[1]);
    if (sizeChanged) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, chromaWidth, chromaHeight, 0,
                     GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, uploadUV);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, chromaWidth, chromaHeight,
                        GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, uploadUV);
    }
    GLenum uploadError = glGetError();
    stats.copyCostUs = steadyNowUs() - uploadStartUs;
    if (uploadError != GL_NO_ERROR) {
        std::ostringstream out;
        out << "NV12 texture upload failed glError=0x" << std::hex << uploadError;
        stats.totalCostUs = steadyNowUs() - renderStartUs;
        return {false, -1, out.str(), stats};
    }

    glUseProgram(program_);
    float yMin = 0.0f;
    float yScale = 1.0f;
    if (colorRange == AVCOL_RANGE_MPEG) {
        yMin = 16.0f / 255.0f;
        yScale = 255.0f / 219.0f;
    }
    glUniform1f(yMinLocation_, yMin);
    glUniform1f(yScaleLocation_, yScale);
    // BT.601 / BT.709 matrix coefficients (cR_V, cG_U, cG_V, cB_U). Unknown -> BT.601.
    float cR_V = 1.402f;
    float cG_U = 0.344136f;
    float cG_V = 0.714136f;
    float cB_U = 1.772f;
    if (colorspace == AVCOL_SPC_BT709) {
        cR_V = 1.5748f;
        cG_U = 0.1873f;
        cG_V = 0.4681f;
        cB_U = 1.8556f;
    }
    glUniform4f(coeffsLocation_, cR_V, cG_U, cG_V, cB_U);

    GLint viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
    GLint viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
    const float contentAspect = static_cast<float>(width) / static_cast<float>(height);
    const float surfaceAspect = viewportHeight > 0
                                ? static_cast<float>(viewportWidth) / static_cast<float>(viewportHeight)
                                : 1.0f;
    GLint viewportX = 0;
    GLint viewportY = 0;
    if (contentAspect > surfaceAspect) {
        viewportHeight = static_cast<GLint>(static_cast<float>(viewportWidth) / contentAspect);
        if (viewportHeight > (surfaceHeight_ > 0 ? surfaceHeight_ : 1)) {
            viewportHeight = surfaceHeight_;
        }
        viewportY = (surfaceHeight_ - viewportHeight) / 2;
    } else {
        viewportWidth = static_cast<GLint>(static_cast<float>(viewportHeight) * contentAspect);
        if (viewportWidth > (surfaceWidth_ > 0 ? surfaceWidth_ : 1)) {
            viewportWidth = surfaceWidth_;
        }
        viewportX = (surfaceWidth_ - viewportWidth) / 2;
    }
    if (viewportWidth <= 0 || viewportHeight <= 0) {
        viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
        viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
    }
    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

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

    glEnableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glVertexAttribPointer(static_cast<GLuint>(positionLocation_), 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));
    glVertexAttribPointer(static_cast<GLuint>(texCoordLocation_), 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));

    GLenum drawError = glGetError();
    if (drawError != GL_NO_ERROR) {
        std::ostringstream out;
        out << "NV12 glDrawArrays failed glError=0x" << std::hex << drawError;
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
    return {true, 0, "", stats};
}

void NativeNv12GlRenderer::clearSurface() {
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
    yStaging_.clear();
    uvStaging_.clear();
}

void NativeNv12GlRenderer::release() {
    clearSurface();
}

bool NativeNv12GlRenderer::hasSurface() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool NativeNv12GlRenderer::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool NativeNv12GlRenderer::supportsFrameFormat(int frameFormat) const {
    return frameFormat == AV_PIX_FMT_NV12;
}

bool NativeNv12GlRenderer::ensureGlLocked(std::string &errorMessage) {
    if (eglDisplay_ != EGL_NO_DISPLAY && eglSurface_ != EGL_NO_SURFACE
        && eglContext_ != EGL_NO_CONTEXT && program_ != 0) {
        if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
            const EGLint eglError = eglGetError();
            if (eglError == EGL_CONTEXT_LOST) {
                LOGE("NV12 GL EGL context lost; rebuilding GL resources");
                releaseGlLocked();
                frameWidth_ = 0;
                frameHeight_ = 0;
            } else {
                errorMessage = eglErrorString("eglMakeCurrent");
                return false;
            }
        } else {
            return true;
        }
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

    glGenTextures(2, textures_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textures_[0]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, textures_[1]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (!compileProgramLocked(errorMessage)) {
        releaseGlLocked();
        return false;
    }
    frameWidth_ = 0;
    frameHeight_ = 0;
    return true;
}

void NativeNv12GlRenderer::releaseGlLocked() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        if (eglSurface_ != EGL_NO_SURFACE && eglContext_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
        }
        if (textures_[0] != 0 || textures_[1] != 0) {
            glDeleteTextures(2, textures_);
            textures_[0] = textures_[1] = 0;
        }
        if (program_ != 0) {
            glDeleteProgram(program_);
            program_ = 0;
        }
        yMinLocation_ = -1;
        yScaleLocation_ = -1;
        coeffsLocation_ = -1;
        positionLocation_ = -1;
        texCoordLocation_ = -1;
        releaseEglSurfaceLocked();
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }
    eglConfig_ = nullptr;
}

bool NativeNv12GlRenderer::rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height) {
    if (eglDisplay_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT || newWindow == nullptr) {
        return false;
    }
    releaseEglSurfaceLocked();
    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, newWindow, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        LOGE("NV12 GL recreate EGLSurface failed eglError=0x%x", eglGetError());
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        LOGE("NV12 GL recreate EGLSurface makeCurrent failed eglError=0x%x", eglGetError());
        return false;
    }
    glViewport(0, 0, width > 0 ? width : 1, height > 0 ? height : 1);
    return true;
}

void NativeNv12GlRenderer::releaseEglSurfaceLocked() {
    if (eglDisplay_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }
    if (eglContext_ != EGL_NO_CONTEXT) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    eglDestroySurface(eglDisplay_, eglSurface_);
    eglSurface_ = EGL_NO_SURFACE;
}

const uint8_t *NativeNv12GlRenderer::compactPlane(const uint8_t *src, int srcStride, int width, int height, std::vector<uint8_t> &buffer) {
    if (srcStride == width) {
        return src;
    }
    const size_t needed = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (buffer.size() < needed) {
        buffer.resize(needed);
    }
    for (int y = 0; y < height; ++y) {
        std::memcpy(buffer.data() + static_cast<size_t>(y) * static_cast<size_t>(width),
                    src + static_cast<size_t>(y) * static_cast<size_t>(srcStride),
                    static_cast<size_t>(width));
    }
    return buffer.data();
}

bool NativeNv12GlRenderer::compileProgramLocked(std::string &errorMessage) {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kNv12VertexShader, errorMessage);
    if (vertexShader == 0) {
        return false;
    }
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kNv12FragmentShader, errorMessage);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return false;
    }
    program_ = linkProgram(vertexShader, fragmentShader, errorMessage);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    if (program_ == 0) {
        return false;
    }

    glUseProgram(program_);
    yMinLocation_ = glGetUniformLocation(program_, "uYMin");
    yScaleLocation_ = glGetUniformLocation(program_, "uYScale");
    coeffsLocation_ = glGetUniformLocation(program_, "uCoeffs");
    positionLocation_ = glGetAttribLocation(program_, "aPosition");
    texCoordLocation_ = glGetAttribLocation(program_, "aTexCoord");
    if (yMinLocation_ < 0 || yScaleLocation_ < 0 || coeffsLocation_ < 0 || positionLocation_ < 0 || texCoordLocation_ < 0) {
        errorMessage = "NV12 shader attribute/uniform not found";
        return false;
    }
    glUniform1i(glGetUniformLocation(program_, "uTextureY"), 0);
    glUniform1i(glGetUniformLocation(program_, "uTextureUV"), 1);
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream out;
        out << "NV12 program setup failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}
