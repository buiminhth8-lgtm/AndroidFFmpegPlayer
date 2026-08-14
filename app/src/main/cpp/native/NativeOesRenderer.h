#ifndef MOTRO_NATIVE_OES_RENDERER_H
#define MOTRO_NATIVE_OES_RENDERER_H

#include <jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

struct ANativeWindow;

// Phase 2 OES renderer: owns the EGL output to the SurfaceView and the
// MediaCodec decoder SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) consumer.
//
// Lifecycle:
//   setSurface(...)              - store the SurfaceView ANativeWindow (any thread)
//   prepareForOesDecode(env,...) - create EGL + OES texture + SurfaceTexture +
//                                  decoder Surface + frame listener (prepare thread)
//   renderOesFrame(env)          - updateTexImage + draw + swap (EGL owner / playback thread)
//   release()                    - release Java refs + GL/EGL resources
//
// All GL/EGL calls are confined to whichever thread owns the EGL context
// (created during prepare, made current during render). The OnFrameAvailable
// callback only sets an atomic flag.
class NativeOesRenderer {
public:
    static void setJavaVm(JavaVM *vm);
    static void setFrameListenerClass(JNIEnv *env, jclass clazz);

    NativeOesRenderer();
    ~NativeOesRenderer();

    NativeOesRenderer(const NativeOesRenderer &) = delete;
    NativeOesRenderer &operator=(const NativeOesRenderer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    bool prepareForOesDecode(JNIEnv *env, intptr_t handle, std::string &errorMessage);
    // whiteHot selects the OES white-hot luminance program (false = original OES).
    bool renderOesFrame(JNIEnv *env, int frameWidth, int frameHeight, bool whiteHot);
    void release();
    bool hasSurface() const;
    bool isPrepared() const;
    jobject getDecoderSurfaceGlobalRef() const;
    int64_t getSurfaceRecreateCount() const;
    int64_t getContextRecreateCount() const;
    int64_t getUpdateTexImageErrorCount() const;
    void resetDiagnostics();

private:
    bool ensureGlLocked(JNIEnv *env, std::string &errorMessage);
    bool compileProgramLocked(std::string &errorMessage);
    bool rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height);
    void releaseEglSurfaceLocked();
    void releaseGlLocked();
    void releaseJavaLocked(JNIEnv *env);

    mutable std::mutex mutex_;
    ANativeWindow *window_ = nullptr;
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLConfig eglConfig_ = nullptr;
    GLuint program_ = 0;
    GLint stMatrixLocation_ = -1;
    GLint positionLocation_ = -1;
    GLint texCoordLocation_ = -1;
    GLuint whiteHotProgram_ = 0;
    GLint whiteHotStMatrixLocation_ = -1;
    GLint whiteHotPositionLocation_ = -1;
    GLint whiteHotTexCoordLocation_ = -1;
    GLuint oesTexture_ = 0;
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    jobject surfaceTextureGlobalRef_ = nullptr;
    jobject decoderSurfaceGlobalRef_ = nullptr;
    jobject frameListenerGlobalRef_ = nullptr;
    jmethodID updateTexImageMethod_ = nullptr;
    jmethodID getTransformMatrixMethod_ = nullptr;
    jfloatArray transformMatrixArrayGlobalRef_ = nullptr;
    std::atomic<bool> prepared_{false};
    std::atomic<int64_t> surfaceRecreateCount_{0};
    std::atomic<int64_t> contextRecreateCount_{0};
    std::atomic<int64_t> updateTexImageErrorCount_{0};
};

#endif // MOTRO_NATIVE_OES_RENDERER_H
