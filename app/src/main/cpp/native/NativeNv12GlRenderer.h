#ifndef MOTRO_NATIVE_NV12_GL_RENDERER_H
#define MOTRO_NATIVE_NV12_GL_RENDERER_H

#include <jni.h>

#include <string>

// Boundary for the future MediaCodec NV12 -> OpenGL renderer
// (Phase 2 Revised Slice 1). This slice only establishes the interface and
// integration point; no GL/EGL work is performed.
//
// Slice 1 contract:
//   input  : AV_PIX_FMT_NV12 AVFrame
//            frame->data[0] = Y      (stride = linesize[0], width x height)
//            frame->data[1] = UV     (stride = linesize[1], width/2 x height/2)
//   output : SurfaceView via EGL (stride aware, no sws_scale, no RGBA copy)
class NativeNv12GlRenderer {
public:
    NativeNv12GlRenderer();
    ~NativeNv12GlRenderer();

    NativeNv12GlRenderer(const NativeNv12GlRenderer &) = delete;
    NativeNv12GlRenderer &operator=(const NativeNv12GlRenderer &) = delete;

    // Placeholder: records the target Surface/dimensions only (no GL yet).
    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    void clearSurface();
    void release();

    // Always false until Slice 1 implements the NV12 GL path.
    bool supportsFrameFormat(int frameFormat) const;
    bool isReady() const;

private:
    bool surfaceSet_ = false;
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;
};

#endif // MOTRO_NATIVE_NV12_GL_RENDERER_H
