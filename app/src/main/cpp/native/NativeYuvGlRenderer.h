#ifndef MOTRO_NATIVE_YUV_GL_RENDERER_H
#define MOTRO_NATIVE_YUV_GL_RENDERER_H

#include "VideoRenderer.h"

#include <jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

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
    void release();
    bool hasSurface() const;

private:
    bool ensureGlLocked(std::string &errorMessage);
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
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;
    int frameWidth_ = 0;
    int frameHeight_ = 0;
    int64_t renderCount_ = 0;
    std::vector<uint8_t> compactY_;
    std::vector<uint8_t> compactU_;
    std::vector<uint8_t> compactV_;
};

#endif // MOTRO_NATIVE_YUV_GL_RENDERER_H
