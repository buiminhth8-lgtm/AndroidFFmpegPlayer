#include "NativeYuvGlRenderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <array>
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

const char *kWhiteHotFragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
uniform float uGamma;
void main() {
    float gray = texture2D(yTexture, vTexCoord).r;
    gray = clamp(gray, 0.0, 1.0);
    gray = pow(gray, max(uGamma, 0.001));
    gl_FragColor = vec4(gray, gray, gray, 1.0);
}
)";

const char *kIronbowFragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
uniform sampler2D paletteTexture;
uniform float uGamma;
void main() {
    float gray = texture2D(yTexture, vTexCoord).r;
    gray = clamp(gray, 0.0, 1.0);
    gray = pow(gray, max(uGamma, 0.001));
    vec3 color = texture2D(paletteTexture, vec2(gray, 0.5)).rgb;
    gl_FragColor = vec4(color, 1.0);
}
)";

struct RgbPoint {
    float t;
    uint8_t r;
    uint8_t g;
    uint8_t b;
};

// Ironbow color control points, piecewise-linear interpolated to 256 entries.
// index is monotonically increasing with Y brightness.
const RgbPoint kIronbowPoints[] = {
        {0.00f, 10, 0, 30},     // near-black dark blue
        {0.15f, 0, 0, 120},     // dark blue
        {0.30f, 120, 0, 200},   // violet
        {0.45f, 200, 0, 200},   // magenta
        {0.60f, 230, 40, 60},   // red
        {0.75f, 250, 140, 20},  // orange
        {0.90f, 250, 220, 60},  // yellow
        {1.00f, 255, 245, 235}  // white
};

std::array<uint8_t, 256 * 3> createIronbowLut() {
    constexpr int pointCount = static_cast<int>(sizeof(kIronbowPoints) / sizeof(kIronbowPoints[0]));
    std::array<uint8_t, 256 * 3> lut{};
    for (int i = 0; i < 256; ++i) {
        const float t = i / 255.0f;
        int seg = 0;
        for (int s = 0; s < pointCount - 1; ++s) {
            if (t <= kIronbowPoints[s + 1].t) {
                seg = s;
                break;
            }
            seg = s;
        }
        const RgbPoint &a = kIronbowPoints[seg];
        const RgbPoint &b = kIronbowPoints[seg + 1];
        const float span = (b.t - a.t) > 0.0f ? (b.t - a.t) : 1.0f;
        const float f = (t - a.t) / span;
        lut[i * 3 + 0] = static_cast<uint8_t>(a.r + (b.r - a.r) * f);
        lut[i * 3 + 1] = static_cast<uint8_t>(a.g + (b.g - a.g) * f);
        lut[i * 3 + 2] = static_cast<uint8_t>(a.b + (b.b - a.b) * f);
    }
    return lut;
}

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
                                             int width, int height, int thermalMode, float gamma) {
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

    GLuint program = normalProgram_;
    bool useIronbow = false;
    if (thermalMode == 1 && whiteHotProgram_ != 0) {
        program = whiteHotProgram_;
    } else if (thermalMode == 2) {
        if (ironbowProgram_ != 0 && ironbowTexture_ != 0) {
            program = ironbowProgram_;
            useIronbow = true;
        } else if (whiteHotProgram_ != 0) {
            program = whiteHotProgram_;
        }
    }
    glUseProgram(program);
    if (useIronbow) {
        if (ironbowGammaLocation_ >= 0) {
            glUniform1f(ironbowGammaLocation_, gamma);
        }
        if (ironbowPaletteLocation_ >= 0) {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, ironbowTexture_);
            glActiveTexture(GL_TEXTURE0);
        }
    } else if (program == whiteHotProgram_ && whiteHotGammaLocation_ >= 0) {
        glUniform1f(whiteHotGammaLocation_, gamma);
    }

    const int viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
    const int viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
    glViewport(0, 0, viewportWidth, viewportHeight);
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

    const GLint positionLocation = glGetAttribLocation(program, "aPosition");
    const GLint texCoordLocation = glGetAttribLocation(program, "aTexCoord");
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
        && eglContext_ != EGL_NO_CONTEXT && normalProgram_ != 0) {
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
        if (normalProgram_ != 0) {
            glDeleteProgram(normalProgram_);
            normalProgram_ = 0;
        }
        if (whiteHotProgram_ != 0) {
            glDeleteProgram(whiteHotProgram_);
            whiteHotProgram_ = 0;
        }
        whiteHotGammaLocation_ = -1;
        if (ironbowTexture_ != 0) {
            glDeleteTextures(1, &ironbowTexture_);
            ironbowTexture_ = 0;
        }
        if (ironbowProgram_ != 0) {
            glDeleteProgram(ironbowProgram_);
            ironbowProgram_ = 0;
        }
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
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

    normalProgram_ = linkProgram(vertexShader, fragmentShader, errorMessage);
    if (normalProgram_ == 0) {
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return false;
    }

    std::string whiteHotError;
    GLuint whiteHotFragmentShader = compileShader(GL_FRAGMENT_SHADER, kWhiteHotFragmentShader, whiteHotError);
    whiteHotProgram_ = whiteHotFragmentShader == 0 ? 0 : linkProgram(vertexShader, whiteHotFragmentShader, whiteHotError);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    if (whiteHotFragmentShader != 0) {
        glDeleteShader(whiteHotFragmentShader);
    }
    if (whiteHotProgram_ == 0) {
        LOGE("white hot shader setup failed, fallback to normal program: %s", whiteHotError.c_str());
        whiteHotProgram_ = 0;
    }

    glUseProgram(normalProgram_);
    glGenTextures(3, textures_);
    const char *samplers[] = {"yTexture", "uTexture", "vTexture"};
    for (int i = 0; i < 3; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, textures_[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUniform1i(glGetUniformLocation(normalProgram_, samplers[i]), i);
    }
    if (whiteHotProgram_ != 0) {
        glUseProgram(whiteHotProgram_);
        glUniform1i(glGetUniformLocation(whiteHotProgram_, "yTexture"), 0);
        whiteHotGammaLocation_ = glGetUniformLocation(whiteHotProgram_, "uGamma");
        if (whiteHotGammaLocation_ < 0) {
            LOGE("white hot shader missing uGamma uniform, fallback to normal program");
            glDeleteProgram(whiteHotProgram_);
            whiteHotProgram_ = 0;
            whiteHotGammaLocation_ = -1;
        }
        glUseProgram(normalProgram_);
    }

    std::string ironbowError;
    GLuint ironbowFragmentShader = compileShader(GL_FRAGMENT_SHADER, kIronbowFragmentShader, ironbowError);
    ironbowProgram_ = ironbowFragmentShader == 0 ? 0 : linkProgram(vertexShader, ironbowFragmentShader, ironbowError);
    if (ironbowFragmentShader != 0) {
        glDeleteShader(ironbowFragmentShader);
    }
    if (ironbowProgram_ == 0) {
        LOGE("ironbow shader setup failed, fallback to white hot/normal: %s", ironbowError.c_str());
        ironbowProgram_ = 0;
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
    } else {
        glUseProgram(ironbowProgram_);
        glUniform1i(glGetUniformLocation(ironbowProgram_, "yTexture"), 0);
        ironbowGammaLocation_ = glGetUniformLocation(ironbowProgram_, "uGamma");
        ironbowPaletteLocation_ = glGetUniformLocation(ironbowProgram_, "paletteTexture");
        if (ironbowGammaLocation_ < 0 || ironbowPaletteLocation_ < 0) {
            LOGE("ironbow shader missing required uniform, fallback to white hot/normal");
            glDeleteProgram(ironbowProgram_);
            ironbowProgram_ = 0;
            ironbowGammaLocation_ = -1;
            ironbowPaletteLocation_ = -1;
        } else {
            glActiveTexture(GL_TEXTURE3);
            glGenTextures(1, &ironbowTexture_);
            glBindTexture(GL_TEXTURE_2D, ironbowTexture_);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            const std::array<uint8_t, 256 * 3> lut = createIronbowLut();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 256, 1, 0, GL_RGB, GL_UNSIGNED_BYTE, lut.data());
            glUniform1i(ironbowPaletteLocation_, 3);
            glUseProgram(normalProgram_);
            glActiveTexture(GL_TEXTURE0);
        }
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
