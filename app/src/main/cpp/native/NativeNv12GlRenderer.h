#ifndef MOTRO_NATIVE_NV12_GL_RENDERER_H
#define MOTRO_NATIVE_NV12_GL_RENDERER_H

#include "VideoRenderer.h"

#include <jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct ANativeWindow;

// MediaCodec NV12 -> OpenGL renderer (Revised Phase 2 Slice 1).
// Input: AV_PIX_FMT_NV12 (Y plane + interleaved UV), stride-aware.
// Output: current SurfaceView via EGL; no sws_scale, no RGBA CPU conversion.
//
// Lifecycle: setSurface stores the SurfaceView window; renderNv12 lazily creates
// EGL + program + textures on the calling (playback) thread; release tears down.
class NativeNv12GlRenderer {
public:
    NativeNv12GlRenderer();
    ~NativeNv12GlRenderer();

    NativeNv12GlRenderer(const NativeNv12GlRenderer &) = delete;
    NativeNv12GlRenderer &operator=(const NativeNv12GlRenderer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    void clearSurface();
    void release();
    bool hasSurface() const;
    bool isReady() const;
    bool supportsFrameFormat(int frameFormat) const;

    // colorRange: AVCOL_RANGE_UNSPECIFIED(0) / AVCOL_RANGE_MPEG(1) / AVCOL_RANGE_JPEG(2).
    RenderResult renderNv12(const uint8_t *yData, int yStride,
                            const uint8_t *uvData, int uvStride,
                            int width, int height, int colorRange);

private:
    bool ensureGlLocked(std::string &errorMessage);
    bool compileProgramLocked(std::string &errorMessage);
    void releaseGlLocked();
    const uint8_t *compactPlane(const uint8_t *src, int srcStride, int width, int height, std::vector<uint8_t> &buffer);

    mutable std::mutex mutex_;
    ANativeWindow *window_ = nullptr;
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLConfig eglConfig_ = nullptr;
    GLuint program_ = 0;
    GLint yMinLocation_ = -1;
    GLint yScaleLocation_ = -1;
    GLint positionLocation_ = -1;
    GLint texCoordLocation_ = -1;
    GLuint textures_[2] = {0, 0};
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;
    int frameWidth_ = 0;
    int frameHeight_ = 0;
    std::vector<uint8_t> yStaging_;
    std::vector<uint8_t> uvStaging_;
};

#endif // MOTRO_NATIVE_NV12_GL_RENDERER_H
