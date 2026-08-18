#ifndef MOTRO_NATIVE_NV12_GL_RENDERER_H
#define MOTRO_NATIVE_NV12_GL_RENDERER_H

#include "VideoRenderer.h"

#include <jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct ANativeWindow;

// Revised Phase 2 hardware mainline renderer: MediaCodec CPU NV12 -> OpenGL.
// Input: AV_PIX_FMT_NV12 (Y plane + interleaved UV), stride-aware.
// Output: current SurfaceView via EGL; no sws_scale, no RGBA CPU conversion.
//
// Lifecycle: set/clear only publish the latest Surface request. The playback
// thread applies it from syncSurface/renderNv12, owns EGL/GL, and preserves the
// context/program/textures across a transient EGLSurface detach.
class NativeNv12GlRenderer {
public:
    NativeNv12GlRenderer();
    ~NativeNv12GlRenderer();

    NativeNv12GlRenderer(const NativeNv12GlRenderer &) = delete;
    NativeNv12GlRenderer &operator=(const NativeNv12GlRenderer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    void clearSurface();
    void release();
    bool syncSurface();
    bool isReady() const;
    int64_t getEglContextCreateCount() const;
    int64_t getEglSurfaceCreateCount() const;
    int64_t getEglOwnerThreadId() const;
    uint64_t getSurfaceGeneration() const;
    uint64_t getAppliedSurfaceGeneration() const;
    // Actual thermal mode applied on the last successful render (0 original, 1 white_hot, 2 ironbow).
    int getLastAppliedThermalMode() const;

    // colorRange: AVCOL_RANGE_UNSPECIFIED(0) / AVCOL_RANGE_MPEG(1) / AVCOL_RANGE_JPEG(2).
    // colorspace: AVColorSpace (BT.601 / BT.709 selection; unknown -> BT.601).
    // thermalMode: 0 = original, 1 = white_hot, 2 = ironbow. gamma / blackPoint /
    // whitePoint apply to white_hot and ironbow only (window in intensity 0..1 domain).
    RenderResult renderNv12(const uint8_t *yData, int yStride,
                            const uint8_t *uvData, int uvStride,
                            int width, int height, int colorRange, int colorspace,
                            int thermalMode, float gamma, float blackPoint, float whitePoint);

private:
    enum class PendingSurfaceAction {
        NONE,
        ATTACH,
        DETACH
    };

    void applyPendingSurfaceLocked();
    bool ensureGlLocked(std::string &errorMessage);
    bool compileProgramLocked(std::string &errorMessage);
    bool rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height);
    void releaseEglSurfaceLocked();
    void releaseGlLocked();
    const uint8_t *compactPlane(const uint8_t *src, int srcStride, int width, int height, std::vector<uint8_t> &buffer);

    mutable std::mutex mutex_;
    ANativeWindow *window_ = nullptr;
    ANativeWindow *pendingWindow_ = nullptr;
    PendingSurfaceAction pendingSurfaceAction_ = PendingSurfaceAction::NONE;
    uint64_t surfaceGeneration_ = 0;
    uint64_t appliedSurfaceGeneration_ = 0;
    int pendingSurfaceWidth_ = 0;
    int pendingSurfaceHeight_ = 0;
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLConfig eglConfig_ = nullptr;
    GLuint program_ = 0;
    GLint yMinLocation_ = -1;
    GLint yScaleLocation_ = -1;
    GLint coeffsLocation_ = -1;
    GLint positionLocation_ = -1;
    GLint texCoordLocation_ = -1;
    GLuint whiteHotProgram_ = 0;
    GLint whiteHotYMinLocation_ = -1;
    GLint whiteHotYScaleLocation_ = -1;
    GLint whiteHotBlackPointLocation_ = -1;
    GLint whiteHotWindowInvRangeLocation_ = -1;
    GLint whiteHotGammaLocation_ = -1;
    GLint whiteHotPositionLocation_ = -1;
    GLint whiteHotTexCoordLocation_ = -1;
    GLuint ironbowProgram_ = 0;
    GLint ironbowYMinLocation_ = -1;
    GLint ironbowYScaleLocation_ = -1;
    GLint ironbowBlackPointLocation_ = -1;
    GLint ironbowWindowInvRangeLocation_ = -1;
    GLint ironbowGammaLocation_ = -1;
    GLint ironbowPaletteLocation_ = -1;
    GLint ironbowPositionLocation_ = -1;
    GLint ironbowTexCoordLocation_ = -1;
    GLuint ironbowTexture_ = 0;
    GLuint textures_[2] = {0, 0};
    std::atomic<int> lastAppliedThermalMode_{0};
    std::atomic<int64_t> eglContextCreateCount_{0};
    std::atomic<int64_t> eglSurfaceCreateCount_{0};
    std::atomic<int64_t> eglOwnerThreadId_{0};
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;
    int frameWidth_ = 0;
    int frameHeight_ = 0;
    std::vector<uint8_t> yStaging_;
    std::vector<uint8_t> uvStaging_;
};

#endif // MOTRO_NATIVE_NV12_GL_RENDERER_H
