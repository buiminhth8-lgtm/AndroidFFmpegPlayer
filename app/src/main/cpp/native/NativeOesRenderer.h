#ifndef MOTRO_NATIVE_OES_RENDERER_H
#define MOTRO_NATIVE_OES_RENDERER_H

#include <jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct ANativeWindow;

// Experimental/future zero-copy OES renderer: owns the EGL output to the SurfaceView and the
// MediaCodec decoder SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) consumer.
//
// Lifecycle:
//   setSurface(...)              - publish latest SurfaceView request (any thread)
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
    bool prepareForOesDecode(JNIEnv *env, int64_t handle, std::string &errorMessage);
    // thermalMode: 0 = original, 1 = white_hot, 2 = ironbow. gamma / blackPoint /
    // whitePoint apply to white_hot and ironbow only (window in luminance 0..1 domain).
    // agcEnabled selects the OES AGC effective window when valid; runAgc triggers a
    // 64x64 luminance downsample + readback + histogram analysis on this frame.
    bool renderOesFrame(JNIEnv *env, int frameWidth, int frameHeight, int thermalMode,
                        float gamma, float blackPoint, float whitePoint,
                        bool agcEnabled, bool runAgc);
    void clearSurface();
    void release();
    bool isPrepared() const;
    bool hasSurface() const;
    jobject getDecoderSurfaceGlobalRef() const;
    int64_t getSurfaceRecreateCount() const;
    int64_t getContextRecreateCount() const;
    int64_t getUpdateTexImageErrorCount() const;
    void resetDiagnostics();
    void resetAgc();
    bool isAgcValid() const;
    float getAgcBlackPoint() const;
    float getAgcWhitePoint() const;
    int64_t getAgcUpdateCount() const;
    int64_t getAgcReadbackErrorCount() const;

private:
    enum class PendingSurfaceAction {
        NONE,
        ATTACH,
        DETACH
    };

    bool applyPendingSurfaceLocked(JNIEnv *env);
    bool ensureGlLocked(JNIEnv *env, std::string &errorMessage);
    bool compileProgramLocked(std::string &errorMessage);
    bool ensureAgcGlLocked(std::string &errorMessage);
    bool runAgcAnalysis(JNIEnv *env, const GLfloat *transform);
    void updateAgcFromReadback();
    bool rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height);
    void releaseEglSurfaceLocked();
    void releaseGlLocked();
    void releaseJavaLocked(JNIEnv *env);

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
    GLint stMatrixLocation_ = -1;
    GLint positionLocation_ = -1;
    GLint texCoordLocation_ = -1;
    GLuint whiteHotProgram_ = 0;
    GLint whiteHotStMatrixLocation_ = -1;
    GLint whiteHotPositionLocation_ = -1;
    GLint whiteHotTexCoordLocation_ = -1;
    GLint whiteHotGammaLocation_ = -1;
    GLint whiteHotBlackPointLocation_ = -1;
    GLint whiteHotWhitePointLocation_ = -1;
    GLuint ironbowProgram_ = 0;
    GLint ironbowStMatrixLocation_ = -1;
    GLint ironbowPositionLocation_ = -1;
    GLint ironbowTexCoordLocation_ = -1;
    GLint ironbowGammaLocation_ = -1;
    GLint ironbowBlackPointLocation_ = -1;
    GLint ironbowWhitePointLocation_ = -1;
    GLint ironbowPaletteLocation_ = -1;
    GLuint ironbowTexture_ = 0;
    GLuint oesTexture_ = 0;
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    // AGC (luminance downsample -> readback -> histogram) resources + state.
    GLuint agcFbo_ = 0;
    GLuint agcTexture_ = 0;
    GLuint agcProgram_ = 0;
    GLint agcStMatrixLocation_ = -1;
    GLint agcPositionLocation_ = -1;
    GLint agcTexCoordLocation_ = -1;
    std::vector<uint8_t> agcReadbackBuffer_;
    std::atomic<bool> agcValid_{false};
    std::atomic<float> agcBlackPoint_{0.0f};
    std::atomic<float> agcWhitePoint_{1.0f};
    std::atomic<int64_t> agcUpdateCount_{0};
    std::atomic<int64_t> agcReadbackErrorCount_{0};

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
