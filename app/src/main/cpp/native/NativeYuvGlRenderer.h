#ifndef MOTRO_NATIVE_YUV_GL_RENDERER_H
#define MOTRO_NATIVE_YUV_GL_RENDERER_H

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

struct ThermalRenderParams {
    float yMin = 0.0f;
    float yScale = 1.0f;
    float blackPoint = 0.0f;
    float whitePoint = 1.0f;
    float gamma = 1.0f;
};

class NativeYuvGlRenderer {
public:
    NativeYuvGlRenderer();
    ~NativeYuvGlRenderer();

    NativeYuvGlRenderer(const NativeYuvGlRenderer &) = delete;
    NativeYuvGlRenderer &operator=(const NativeYuvGlRenderer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    // thermalMode: 0 = normal, 1 = white_hot, 2 = ironbow
    RenderResult renderI420(const uint8_t *yData, int yStride,
                            const uint8_t *uData, int uStride,
                            const uint8_t *vData, int vStride,
                            int width, int height, int thermalMode, const ThermalRenderParams &params);
    void clearSurface();
    void release();
    bool syncSurface();
    bool hasSurface() const;
    int64_t getEglContextCreateCount() const;
    int64_t getEglSurfaceCreateCount() const;
    int64_t getEglOwnerThreadId() const;
    uint64_t getSurfaceGeneration() const;
    uint64_t getAppliedSurfaceGeneration() const;

private:
    enum class PendingSurfaceAction {
        NONE,
        ATTACH,
        DETACH
    };

    void applyPendingSurfaceLocked();
    bool ensureGlLocked(std::string &errorMessage);
    bool rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height);
    void releaseEglSurfaceLocked();
    void releaseGlLocked();
    bool compileProgramLocked(std::string &errorMessage);
    const uint8_t *compactPlane(const uint8_t *src, int srcStride, int width, int height, std::vector<uint8_t> &buffer);
    bool uploadPlane(int textureIndex, const uint8_t *data, int width, int height, std::string &errorMessage);

    struct ThermalUniformSet {
        GLint yMin = -1;
        GLint yScale = -1;
        GLint blackPoint = -1;
        GLint whitePoint = -1;
        GLint gamma = -1;
    };

    ThermalUniformSet fetchThermalUniformSet(GLuint program);
    void setThermalUniforms(const ThermalUniformSet &uniforms, const ThermalRenderParams &params);

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
    GLuint normalProgram_ = 0;
    GLuint whiteHotProgram_ = 0;
    ThermalUniformSet whiteHotUniforms_;
    GLuint ironbowProgram_ = 0;
    ThermalUniformSet ironbowUniforms_;
    GLint ironbowPaletteLocation_ = -1;
    GLuint ironbowTexture_ = 0;
    GLuint textures_[3] = {0, 0, 0};
    std::atomic<int64_t> eglContextCreateCount_{0};
    std::atomic<int64_t> eglSurfaceCreateCount_{0};
    std::atomic<int64_t> eglOwnerThreadId_{0};
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;
    std::vector<uint8_t> compactY_;
    std::vector<uint8_t> compactU_;
    std::vector<uint8_t> compactV_;
};

#endif // MOTRO_NATIVE_YUV_GL_RENDERER_H
