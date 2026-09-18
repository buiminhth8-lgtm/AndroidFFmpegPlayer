#include "NativeNv12GlRenderer.h"
#include "ThermalPaletteLut.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <sstream>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" {
#include "libavutil/pixfmt.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

int64_t currentThreadId() {
    return static_cast<int64_t>(syscall(__NR_gettid));
}

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

// NV12 White Hot: sample Y only, range normalize, manual window, gamma, grayscale output.
const char *kNv12WhiteHotFragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform float uYMin;
uniform float uYScale;
uniform float uBlackPoint;
uniform float uWindowInvRange;
uniform float uGamma;
void main() {
    float rawY = texture2D(uTextureY, vTexCoord).r;
    float intensity = clamp((rawY - uYMin) * uYScale, 0.0, 1.0);
    intensity = clamp((intensity - uBlackPoint) * uWindowInvRange, 0.0, 1.0);
    intensity = pow(intensity, max(uGamma, 0.001));
    gl_FragColor = vec4(intensity, intensity, intensity, 1.0);
}
)";

// NV12 Ironbow: Y -> range -> window -> gamma -> 256x1 LUT (shared Phase 1 color table).
const char *kNv12IronbowFragmentShader = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform sampler2D uPaletteTexture;
uniform float uYMin;
uniform float uYScale;
uniform float uBlackPoint;
uniform float uWindowInvRange;
uniform float uGamma;
void main() {
    float rawY = texture2D(uTextureY, vTexCoord).r;
    float intensity = clamp((rawY - uYMin) * uYScale, 0.0, 1.0);
    intensity = clamp((intensity - uBlackPoint) * uWindowInvRange, 0.0, 1.0);
    intensity = pow(intensity, max(uGamma, 0.001));
    vec3 color = texture2D(uPaletteTexture, vec2(intensity, 0.5)).rgb;
    gl_FragColor = vec4(color, 1.0);
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
    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
    }
    pendingWindow_ = newWindow;
    pendingSurfaceWidth_ = width;
    pendingSurfaceHeight_ = height;
    pendingSurfaceAction_ = PendingSurfaceAction::ATTACH;
    ++surfaceGeneration_;
    LOGI("NV12 GL surface request attach generation=%llu thread=%lld surface=%dx%d",
         static_cast<unsigned long long>(surfaceGeneration_),
         static_cast<long long>(currentThreadId()), width, height);
    return jsonSuccess("nv12 gl surface attach requested");
}

RenderResult NativeNv12GlRenderer::renderNv12(const uint8_t *yData, int yStride,
                                              const uint8_t *uvData, int uvStride,
                                              int width, int height, int colorRange, int colorspace,
                                              int thermalMode, float gamma, float blackPoint, float whitePoint) {
    if (yData == nullptr || uvData == nullptr || yStride <= 0 || uvStride <= 0
        || width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
        return {false, -1, "invalid NV12 frame", {}};
    }

    std::lock_guard<std::mutex> lock(mutex_);
    applyPendingSurfaceLocked();
    if (window_ == nullptr) {
        return {false, kRenderErrorNoSurface, "Surface is not set", {}};
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

    // Select program: original (Y+UV->RGB), white hot (Y only), or ironbow (Y+LUT).
    // Unavailable thermal program falls back: ironbow -> white hot -> original.
    GLuint program = program_;
    GLint yMinLoc = yMinLocation_;
    GLint yScaleLoc = yScaleLocation_;
    GLint blackLoc = -1;
    GLint windowInvRangeLoc = -1;
    GLint gammaLoc = -1;
    GLint paletteLoc = -1;
    GLint posLoc = positionLocation_;
    GLint texLoc = texCoordLocation_;
    bool useIronbow = false;
    if (thermalMode == 1 && whiteHotProgram_ != 0) {
        program = whiteHotProgram_;
        yMinLoc = whiteHotYMinLocation_;
        yScaleLoc = whiteHotYScaleLocation_;
        blackLoc = whiteHotBlackPointLocation_;
        windowInvRangeLoc = whiteHotWindowInvRangeLocation_;
        gammaLoc = whiteHotGammaLocation_;
        posLoc = whiteHotPositionLocation_;
        texLoc = whiteHotTexCoordLocation_;
    } else if (thermalMode == 2) {
        if (ironbowProgram_ != 0 && ironbowTexture_ != 0) {
            program = ironbowProgram_;
            yMinLoc = ironbowYMinLocation_;
            yScaleLoc = ironbowYScaleLocation_;
            blackLoc = ironbowBlackPointLocation_;
            windowInvRangeLoc = ironbowWindowInvRangeLocation_;
            gammaLoc = ironbowGammaLocation_;
            paletteLoc = ironbowPaletteLocation_;
            posLoc = ironbowPositionLocation_;
            texLoc = ironbowTexCoordLocation_;
            useIronbow = true;
        } else if (whiteHotProgram_ != 0) {
            program = whiteHotProgram_;
            yMinLoc = whiteHotYMinLocation_;
            yScaleLoc = whiteHotYScaleLocation_;
            blackLoc = whiteHotBlackPointLocation_;
            windowInvRangeLoc = whiteHotWindowInvRangeLocation_;
            gammaLoc = whiteHotGammaLocation_;
            posLoc = whiteHotPositionLocation_;
            texLoc = whiteHotTexCoordLocation_;
        }
    }

    float yMin = 0.0f;
    float yScale = 1.0f;
    if (colorRange == AVCOL_RANGE_MPEG) {
        yMin = 16.0f / 255.0f;
        yScale = 255.0f / 219.0f;
    }
    glUseProgram(program);
    glUniform1f(yMinLoc, yMin);
    glUniform1f(yScaleLoc, yScale);
    // Manual window in the range-normalized intensity 0..1 domain.
    const float windowRange = std::max(whitePoint - blackPoint, 0.001f);
    const float windowInvRange = 1.0f / windowRange;
    if (blackLoc >= 0) {
        glUniform1f(blackLoc, blackPoint);
    }
    if (windowInvRangeLoc >= 0) {
        glUniform1f(windowInvRangeLoc, windowInvRange);
    }
    if (gammaLoc >= 0) {
        glUniform1f(gammaLoc, gamma);
    }
    if (useIronbow && paletteLoc >= 0) {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, ironbowTexture_);
        glActiveTexture(GL_TEXTURE0);
    }
    if (program == program_) {
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
    }
    lastAppliedThermalMode_.store(useIronbow ? 2 : (program == whiteHotProgram_ ? 1 : 0));

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

    glEnableVertexAttribArray(static_cast<GLuint>(posLoc));
    glVertexAttribPointer(static_cast<GLuint>(posLoc), 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(texLoc));
    glVertexAttribPointer(static_cast<GLuint>(texLoc), 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(posLoc));
    glDisableVertexAttribArray(static_cast<GLuint>(texLoc));

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
    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    pendingSurfaceWidth_ = 0;
    pendingSurfaceHeight_ = 0;
    pendingSurfaceAction_ = PendingSurfaceAction::DETACH;
    ++surfaceGeneration_;
    LOGI("NV12 GL surface request detach generation=%llu thread=%lld",
         static_cast<unsigned long long>(surfaceGeneration_),
         static_cast<long long>(currentThreadId()));
}

void NativeNv12GlRenderer::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    pendingSurfaceAction_ = PendingSurfaceAction::NONE;
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

bool NativeNv12GlRenderer::syncSurface() {
    std::lock_guard<std::mutex> lock(mutex_);
    applyPendingSurfaceLocked();
    return window_ != nullptr;
}

bool NativeNv12GlRenderer::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (pendingSurfaceAction_ == PendingSurfaceAction::ATTACH) {
        return pendingWindow_ != nullptr;
    }
    if (pendingSurfaceAction_ == PendingSurfaceAction::DETACH) {
        return false;
    }
    return window_ != nullptr;
}

int64_t NativeNv12GlRenderer::getEglContextCreateCount() const {
    return eglContextCreateCount_.load();
}

int64_t NativeNv12GlRenderer::getEglSurfaceCreateCount() const {
    return eglSurfaceCreateCount_.load();
}

int64_t NativeNv12GlRenderer::getEglOwnerThreadId() const {
    return eglOwnerThreadId_.load();
}

uint64_t NativeNv12GlRenderer::getSurfaceGeneration() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return surfaceGeneration_;
}

uint64_t NativeNv12GlRenderer::getAppliedSurfaceGeneration() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return appliedSurfaceGeneration_;
}

int NativeNv12GlRenderer::getLastAppliedThermalMode() const {
    return lastAppliedThermalMode_.load();
}

void NativeNv12GlRenderer::applyPendingSurfaceLocked() {
    if (pendingSurfaceAction_ == PendingSurfaceAction::NONE) {
        return;
    }

    const PendingSurfaceAction action = pendingSurfaceAction_;
    ANativeWindow *newWindow = pendingWindow_;
    const int newWidth = pendingSurfaceWidth_;
    const int newHeight = pendingSurfaceHeight_;
    const uint64_t generation = surfaceGeneration_;
    pendingWindow_ = nullptr;
    pendingSurfaceWidth_ = 0;
    pendingSurfaceHeight_ = 0;
    pendingSurfaceAction_ = PendingSurfaceAction::NONE;

    if (action == PendingSurfaceAction::DETACH) {
        releaseEglSurfaceLocked();
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        surfaceWidth_ = 0;
        surfaceHeight_ = 0;
        appliedSurfaceGeneration_ = generation;
        eglOwnerThreadId_.store(currentThreadId());
        LOGI("NV12 GL surface apply detach generation=%llu ownerThread=%lld contextPreserved=%d",
             static_cast<unsigned long long>(generation),
             static_cast<long long>(currentThreadId()),
             eglContext_ != EGL_NO_CONTEXT ? 1 : 0);
        return;
    }

    if (newWindow == nullptr) {
        appliedSurfaceGeneration_ = generation;
        return;
    }
    if (eglDisplay_ != EGL_NO_DISPLAY && eglContext_ != EGL_NO_CONTEXT
        && !rebindEglSurfaceLocked(newWindow, newWidth, newHeight)) {
        LOGE("NV12 GL EGLSurface rebind failed; owner thread will rebuild GL on next render");
        releaseGlLocked();
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = newWindow;
    surfaceWidth_ = newWidth;
    surfaceHeight_ = newHeight;
    appliedSurfaceGeneration_ = generation;
    eglOwnerThreadId_.store(currentThreadId());
    LOGI("NV12 GL surface apply attach generation=%llu ownerThread=%lld surface=%dx%d",
         static_cast<unsigned long long>(generation),
         static_cast<long long>(currentThreadId()), newWidth, newHeight);
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
    eglContextCreateCount_.fetch_add(1);
    eglOwnerThreadId_.store(currentThreadId());
    LOGI("NV12 GL EGL context created ownerThread=%lld count=%lld",
         static_cast<long long>(currentThreadId()),
         static_cast<long long>(eglContextCreateCount_.load()));

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window_, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        errorMessage = eglErrorString("eglCreateWindowSurface");
        releaseGlLocked();
        return false;
    }
    eglSurfaceCreateCount_.fetch_add(1);
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
        bool glContextCurrent = false;
        if (eglSurface_ != EGL_NO_SURFACE && eglContext_ != EGL_NO_CONTEXT) {
            glContextCurrent = eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) == EGL_TRUE;
        }
        if (textures_[0] != 0 || textures_[1] != 0) {
            if (glContextCurrent) {
                glDeleteTextures(2, textures_);
            }
            textures_[0] = textures_[1] = 0;
        }
        if (program_ != 0) {
            if (glContextCurrent) {
                glDeleteProgram(program_);
            }
            program_ = 0;
        }
        yMinLocation_ = -1;
        yScaleLocation_ = -1;
        coeffsLocation_ = -1;
        positionLocation_ = -1;
        texCoordLocation_ = -1;
        if (whiteHotProgram_ != 0) {
            if (glContextCurrent) {
                glDeleteProgram(whiteHotProgram_);
            }
            whiteHotProgram_ = 0;
        }
        whiteHotYMinLocation_ = -1;
        whiteHotYScaleLocation_ = -1;
        whiteHotBlackPointLocation_ = -1;
        whiteHotWindowInvRangeLocation_ = -1;
        whiteHotGammaLocation_ = -1;
        whiteHotPositionLocation_ = -1;
        whiteHotTexCoordLocation_ = -1;
        if (ironbowTexture_ != 0) {
            if (glContextCurrent) {
                glDeleteTextures(1, &ironbowTexture_);
            }
            ironbowTexture_ = 0;
        }
        if (ironbowProgram_ != 0) {
            if (glContextCurrent) {
                glDeleteProgram(ironbowProgram_);
            }
            ironbowProgram_ = 0;
        }
        ironbowYMinLocation_ = -1;
        ironbowYScaleLocation_ = -1;
        ironbowBlackPointLocation_ = -1;
        ironbowWindowInvRangeLocation_ = -1;
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
        ironbowPositionLocation_ = -1;
        ironbowTexCoordLocation_ = -1;
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
    eglSurfaceCreateCount_.fetch_add(1);
    eglOwnerThreadId_.store(currentThreadId());
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
    glDeleteShader(fragmentShader);
    if (program_ == 0) {
        glDeleteShader(vertexShader);
        return false;
    }

    std::string whiteHotError;
    GLuint whiteHotFragmentShader = compileShader(GL_FRAGMENT_SHADER, kNv12WhiteHotFragmentShader, whiteHotError);
    whiteHotProgram_ = whiteHotFragmentShader == 0 ? 0 : linkProgram(vertexShader, whiteHotFragmentShader, whiteHotError);
    if (whiteHotFragmentShader != 0) {
        glDeleteShader(whiteHotFragmentShader);
    }
    if (whiteHotProgram_ == 0) {
        LOGE("NV12 white hot shader setup failed, fallback to original NV12: %s", whiteHotError.c_str());
        whiteHotProgram_ = 0;
        whiteHotYMinLocation_ = -1;
        whiteHotYScaleLocation_ = -1;
        whiteHotGammaLocation_ = -1;
        whiteHotPositionLocation_ = -1;
        whiteHotTexCoordLocation_ = -1;
    }

    std::string ironbowError;
    GLuint ironbowFragmentShader = compileShader(GL_FRAGMENT_SHADER, kNv12IronbowFragmentShader, ironbowError);
    ironbowProgram_ = ironbowFragmentShader == 0 ? 0 : linkProgram(vertexShader, ironbowFragmentShader, ironbowError);
    // vertexShader is no longer needed after all programs are linked.
    glDeleteShader(vertexShader);
    if (ironbowFragmentShader != 0) {
        glDeleteShader(ironbowFragmentShader);
    }
    if (ironbowProgram_ == 0) {
        LOGE("NV12 ironbow shader setup failed, fallback to white hot/original: %s", ironbowError.c_str());
        ironbowProgram_ = 0;
        ironbowYMinLocation_ = -1;
        ironbowYScaleLocation_ = -1;
        ironbowBlackPointLocation_ = -1;
        ironbowWindowInvRangeLocation_ = -1;
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
        ironbowPositionLocation_ = -1;
        ironbowTexCoordLocation_ = -1;
        ironbowTexture_ = 0;
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

    if (whiteHotProgram_ != 0) {
        glUseProgram(whiteHotProgram_);
        whiteHotYMinLocation_ = glGetUniformLocation(whiteHotProgram_, "uYMin");
        whiteHotYScaleLocation_ = glGetUniformLocation(whiteHotProgram_, "uYScale");
        whiteHotBlackPointLocation_ = glGetUniformLocation(whiteHotProgram_, "uBlackPoint");
        whiteHotWindowInvRangeLocation_ = glGetUniformLocation(whiteHotProgram_, "uWindowInvRange");
        whiteHotGammaLocation_ = glGetUniformLocation(whiteHotProgram_, "uGamma");
        whiteHotPositionLocation_ = glGetAttribLocation(whiteHotProgram_, "aPosition");
        whiteHotTexCoordLocation_ = glGetAttribLocation(whiteHotProgram_, "aTexCoord");
        if (whiteHotYMinLocation_ < 0 || whiteHotYScaleLocation_ < 0
            || whiteHotBlackPointLocation_ < 0 || whiteHotWindowInvRangeLocation_ < 0
            || whiteHotGammaLocation_ < 0 || whiteHotPositionLocation_ < 0 || whiteHotTexCoordLocation_ < 0) {
            LOGE("NV12 white hot shader attribute/uniform not found, fallback to original NV12");
            glDeleteProgram(whiteHotProgram_);
            whiteHotProgram_ = 0;
            whiteHotYMinLocation_ = -1;
            whiteHotYScaleLocation_ = -1;
            whiteHotBlackPointLocation_ = -1;
            whiteHotWindowInvRangeLocation_ = -1;
            whiteHotGammaLocation_ = -1;
            whiteHotPositionLocation_ = -1;
            whiteHotTexCoordLocation_ = -1;
        } else {
            glUniform1i(glGetUniformLocation(whiteHotProgram_, "uTextureY"), 0);
        }
        glUseProgram(program_);
    }

    if (ironbowProgram_ != 0) {
        glUseProgram(ironbowProgram_);
        ironbowYMinLocation_ = glGetUniformLocation(ironbowProgram_, "uYMin");
        ironbowYScaleLocation_ = glGetUniformLocation(ironbowProgram_, "uYScale");
        ironbowBlackPointLocation_ = glGetUniformLocation(ironbowProgram_, "uBlackPoint");
        ironbowWindowInvRangeLocation_ = glGetUniformLocation(ironbowProgram_, "uWindowInvRange");
        ironbowGammaLocation_ = glGetUniformLocation(ironbowProgram_, "uGamma");
        ironbowPaletteLocation_ = glGetUniformLocation(ironbowProgram_, "uPaletteTexture");
        ironbowPositionLocation_ = glGetAttribLocation(ironbowProgram_, "aPosition");
        ironbowTexCoordLocation_ = glGetAttribLocation(ironbowProgram_, "aTexCoord");
        if (ironbowYMinLocation_ < 0 || ironbowYScaleLocation_ < 0
            || ironbowBlackPointLocation_ < 0 || ironbowWindowInvRangeLocation_ < 0
            || ironbowGammaLocation_ < 0 || ironbowPaletteLocation_ < 0
            || ironbowPositionLocation_ < 0 || ironbowTexCoordLocation_ < 0) {
            LOGE("NV12 ironbow shader attribute/uniform not found, fallback to white hot/original");
            glDeleteProgram(ironbowProgram_);
            ironbowProgram_ = 0;
            ironbowYMinLocation_ = -1;
            ironbowYScaleLocation_ = -1;
            ironbowBlackPointLocation_ = -1;
            ironbowWindowInvRangeLocation_ = -1;
            ironbowGammaLocation_ = -1;
            ironbowPaletteLocation_ = -1;
            ironbowPositionLocation_ = -1;
            ironbowTexCoordLocation_ = -1;
            ironbowTexture_ = 0;
        } else {
            glActiveTexture(GL_TEXTURE3);
            glGenTextures(1, &ironbowTexture_);
            glBindTexture(GL_TEXTURE_2D, ironbowTexture_);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            const std::array<uint8_t, kIronbowLutSize> lut = createIronbowLut();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 256, 1, 0, GL_RGB, GL_UNSIGNED_BYTE, lut.data());
            glUniform1i(ironbowPaletteLocation_, 3);
            glActiveTexture(GL_TEXTURE0);
            glUseProgram(program_);
        }
    }

    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream out;
        out << "NV12 program setup failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}
