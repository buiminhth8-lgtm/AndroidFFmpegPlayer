#include "NativeOesRenderer.h"
#include "ThermalPaletteLut.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_oes_java_vm = nullptr;
jclass g_oes_frame_listener_class = nullptr;  // global ref, cached at JNI_OnLoad
jmethodID g_oes_frame_listener_ctor = nullptr;

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

const char *kOesVertexShader = R"(
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uSTMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * aTexCoord).xy;
}
)";

const char *kOesFragmentShader = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
)";

const char *kOesWhiteHotFragmentShader = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform float uGamma;
void main() {
    vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
    float intensity = dot(rgb, vec3(0.299, 0.587, 0.114));
    intensity = clamp(intensity, 0.0, 1.0);
    intensity = pow(intensity, max(uGamma, 0.001));
    gl_FragColor = vec4(intensity, intensity, intensity, 1.0);
}
)";

const char *kOesIronbowFragmentShader = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform sampler2D uPaletteTexture;
uniform float uGamma;
void main() {
    vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
    float intensity = dot(rgb, vec3(0.299, 0.587, 0.114));
    intensity = clamp(intensity, 0.0, 1.0);
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

void NativeOesRenderer::setJavaVm(JavaVM *vm) {
    g_oes_java_vm = vm;
}

void NativeOesRenderer::setFrameListenerClass(JNIEnv *env, jclass clazz) {
    if (clazz == nullptr) {
        return;
    }
    if (g_oes_frame_listener_class != nullptr) {
        env->DeleteGlobalRef(g_oes_frame_listener_class);
        g_oes_frame_listener_class = nullptr;
        g_oes_frame_listener_ctor = nullptr;
    }
    g_oes_frame_listener_class = static_cast<jclass>(env->NewGlobalRef(clazz));
    g_oes_frame_listener_ctor = env->GetMethodID(clazz, "<init>", "(J)V");
}

NativeOesRenderer::NativeOesRenderer() = default;

NativeOesRenderer::~NativeOesRenderer() {
    release();
}

std::string NativeOesRenderer::setSurface(JNIEnv *env, jobject surface, int width, int height) {
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
    if (prepared_.load()) {
        // Surface recreated during OES playback: keep SurfaceTexture / OES texture /
        // program (decoder output unchanged); only rebuild the EGL window surface.
        if (!rebindEglSurfaceLocked(newWindow, width, height)) {
            LOGE("OES EGL surface rebind failed, full teardown; re-prepare required");
            releaseGlLocked();
            releaseJavaLocked(env);
            prepared_.store(false);
        } else {
            surfaceRecreateCount_.fetch_add(1);
        }
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = newWindow;
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    LOGI("setSurface OES success surface=%dx%d", width, height);
    return jsonSuccess("oes surface set");
}

bool NativeOesRenderer::prepareForOesDecode(JNIEnv *env, intptr_t handle, std::string &errorMessage) {
    if (env == nullptr) {
        errorMessage = "JNIEnv is null";
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (prepared_.load()) {
        return true;
    }
    if (!ensureGlLocked(env, errorMessage)) {
        return false;
    }

    jclass surfaceTextureClass = env->FindClass("android/graphics/SurfaceTexture");
    if (surfaceTextureClass == nullptr) {
        errorMessage = "SurfaceTexture class not found";
        releaseGlLocked();
        return false;
    }
    jclass surfaceClass = env->FindClass("android/view/Surface");
    jclass looperClass = env->FindClass("android/os/Looper");
    jclass handlerClass = env->FindClass("android/os/Handler");
    if (surfaceClass == nullptr || looperClass == nullptr || handlerClass == nullptr) {
        errorMessage = "framework class not found";
        env->DeleteLocalRef(surfaceTextureClass);
        releaseGlLocked();
        return false;
    }

    jmethodID getMainLooper = env->GetStaticMethodID(looperClass, "getMainLooper", "()Landroid/os/Looper;");
    jobject mainLooper = getMainLooper == nullptr ? nullptr : env->CallStaticObjectMethod(looperClass, getMainLooper);
    if (mainLooper == nullptr) {
        errorMessage = "getMainLooper failed";
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }
    jmethodID handlerCtor = env->GetMethodID(handlerClass, "<init>", "(Landroid/os/Looper;)V");
    jobject handler = handlerCtor == nullptr ? nullptr : env->NewObject(handlerClass, handlerCtor, mainLooper);
    if (handler == nullptr) {
        errorMessage = "Handler creation failed";
        env->DeleteLocalRef(mainLooper);
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }

    jmethodID surfaceTextureCtor = env->GetMethodID(surfaceTextureClass, "<init>", "(ILandroid/os/Handler;)V");
    jobject surfaceTexture = surfaceTextureCtor == nullptr
                             ? nullptr
                             : env->NewObject(surfaceTextureClass, surfaceTextureCtor,
                                              static_cast<jint>(oesTexture_), handler);
    if (surfaceTexture == nullptr) {
        errorMessage = "SurfaceTexture creation failed";
        env->DeleteLocalRef(mainLooper);
        env->DeleteLocalRef(handler);
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }

    if (g_oes_frame_listener_class == nullptr || g_oes_frame_listener_ctor == nullptr) {
        errorMessage = "OES frame listener class not cached";
        env->DeleteLocalRef(mainLooper);
        env->DeleteLocalRef(handler);
        env->DeleteLocalRef(surfaceTexture);
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }
    jobject listener = env->NewObject(g_oes_frame_listener_class, g_oes_frame_listener_ctor,
                                      static_cast<jlong>(handle));
    if (listener == nullptr) {
        errorMessage = "OES frame listener creation failed";
        env->DeleteLocalRef(mainLooper);
        env->DeleteLocalRef(handler);
        env->DeleteLocalRef(surfaceTexture);
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }

    jmethodID setListener = env->GetMethodID(surfaceTextureClass, "setOnFrameAvailableListener",
                                             "(Landroid/graphics/SurfaceTexture$OnFrameAvailableListener;Landroid/os/Handler;)V");
    if (setListener != nullptr) {
        env->CallVoidMethod(surfaceTexture, setListener, listener, handler);
    }

    jmethodID surfaceCtor = env->GetMethodID(surfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
    jobject decoderSurface = surfaceCtor == nullptr ? nullptr : env->NewObject(surfaceClass, surfaceCtor, surfaceTexture);
    if (decoderSurface == nullptr) {
        errorMessage = "decoder Surface creation failed";
        env->DeleteLocalRef(mainLooper);
        env->DeleteLocalRef(handler);
        env->DeleteLocalRef(listener);
        env->DeleteLocalRef(surfaceTexture);
        env->DeleteLocalRef(surfaceTextureClass);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(looperClass);
        env->DeleteLocalRef(handlerClass);
        releaseGlLocked();
        return false;
    }

    surfaceTextureGlobalRef_ = env->NewGlobalRef(surfaceTexture);
    decoderSurfaceGlobalRef_ = env->NewGlobalRef(decoderSurface);
    frameListenerGlobalRef_ = env->NewGlobalRef(listener);
    transformMatrixArrayGlobalRef_ = static_cast<jfloatArray>(env->NewGlobalRef(env->NewFloatArray(16)));
    updateTexImageMethod_ = env->GetMethodID(surfaceTextureClass, "updateTexImage", "()V");
    getTransformMatrixMethod_ = env->GetMethodID(surfaceTextureClass, "getTransformMatrix", "([F)V");

    env->DeleteLocalRef(mainLooper);
    env->DeleteLocalRef(handler);
    env->DeleteLocalRef(listener);
    env->DeleteLocalRef(surfaceTexture);
    env->DeleteLocalRef(decoderSurface);
    env->DeleteLocalRef(surfaceTextureClass);
    env->DeleteLocalRef(surfaceClass);
    env->DeleteLocalRef(looperClass);
    env->DeleteLocalRef(handlerClass);

    prepared_.store(true);
    LOGI("OES prepare success textureId=%u surface=%dx%d", static_cast<unsigned int>(oesTexture_),
         surfaceWidth_, surfaceHeight_);
    return true;
}

bool NativeOesRenderer::renderOesFrame(JNIEnv *env, int frameWidth, int frameHeight, int thermalMode, float gamma) {
    if (env == nullptr || !prepared_.load()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!prepared_.load()) {
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        const EGLint eglError = eglGetError();
        LOGE("OES eglMakeCurrent failed eglError=0x%x", eglError);
        if (eglError == EGL_CONTEXT_LOST) {
            contextRecreateCount_.fetch_add(1);
            LOGE("OES EGL context lost; GL resources invalid, teardown for re-prepare");
            releaseGlLocked();
            prepared_.store(false);
        }
        return false;
    }

    env->CallVoidMethod(surfaceTextureGlobalRef_, updateTexImageMethod_);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        updateTexImageErrorCount_.fetch_add(1);
        const int64_t errors = updateTexImageErrorCount_.load();
        if (errors == 1 || errors % 100 == 0) {
            LOGE("OES updateTexImage failed count=%lld",
                 static_cast<long long>(errors));
        }
        return false;
    }

    GLfloat transform[16] = {0};
    env->CallVoidMethod(surfaceTextureGlobalRef_, getTransformMatrixMethod_, transformMatrixArrayGlobalRef_);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        updateTexImageErrorCount_.fetch_add(1);
        return false;
    }
    env->GetFloatArrayRegion(transformMatrixArrayGlobalRef_, 0, 16, transform);

    // Aspect-fit letterbox viewport (no forced stretch), honoring 90-degree
    // rotation from the SurfaceTexture transform matrix.
    GLint viewportX = 0;
    GLint viewportY = 0;
    GLint viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : 1;
    GLint viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : 1;
    if (frameWidth > 0 && frameHeight > 0) {
        const bool transposed = std::abs(transform[0]) < 0.0001f && std::abs(transform[5]) < 0.0001f;
        const float contentAspect = transposed
                                    ? static_cast<float>(frameHeight) / static_cast<float>(frameWidth)
                                    : static_cast<float>(frameWidth) / static_cast<float>(frameHeight);
        const float surfaceAspect = viewportHeight > 0
                                    ? static_cast<float>(viewportWidth) / static_cast<float>(viewportHeight)
                                    : 1.0f;
        if (contentAspect > surfaceAspect) {
            viewportWidth = static_cast<GLint>(viewportWidth);
            viewportHeight = static_cast<GLint>(static_cast<float>(viewportWidth) / contentAspect);
            if (viewportHeight > (surfaceHeight_ > 0 ? surfaceHeight_ : 1)) {
                viewportHeight = surfaceHeight_;
            }
        } else {
            viewportHeight = static_cast<GLint>(viewportHeight);
            viewportWidth = static_cast<GLint>(static_cast<float>(viewportHeight) * contentAspect);
            if (viewportWidth > (surfaceWidth_ > 0 ? surfaceWidth_ : 1)) {
                viewportWidth = surfaceWidth_;
            }
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : 1;
            viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : 1;
        }
        viewportX = (surfaceWidth_ - viewportWidth) / 2;
        viewportY = (surfaceHeight_ - viewportHeight) / 2;
    }
    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Select program: original / white hot / ironbow. If the requested thermal
    // program is unavailable, fall back (ironbow -> white hot -> original).
    GLuint program = program_;
    GLint stMatrix = stMatrixLocation_;
    GLint positionLoc = positionLocation_;
    GLint texCoordLoc = texCoordLocation_;
    bool useIronbow = false;
    if (thermalMode == 1 && whiteHotProgram_ != 0) {
        program = whiteHotProgram_;
        stMatrix = whiteHotStMatrixLocation_;
        positionLoc = whiteHotPositionLocation_;
        texCoordLoc = whiteHotTexCoordLocation_;
    } else if (thermalMode == 2) {
        if (ironbowProgram_ != 0 && ironbowTexture_ != 0) {
            program = ironbowProgram_;
            stMatrix = ironbowStMatrixLocation_;
            positionLoc = ironbowPositionLocation_;
            texCoordLoc = ironbowTexCoordLocation_;
            useIronbow = true;
        } else if (whiteHotProgram_ != 0) {
            program = whiteHotProgram_;
            stMatrix = whiteHotStMatrixLocation_;
            positionLoc = whiteHotPositionLocation_;
            texCoordLoc = whiteHotTexCoordLocation_;
        }
    }

    glUseProgram(program);
    glUniformMatrix4fv(stMatrix, 1, GL_FALSE, transform);

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

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture_);

    static const GLfloat vertices[] = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };
    static const GLfloat texCoords[] = {
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f
    };

    glEnableVertexAttribArray(static_cast<GLuint>(positionLoc));
    glVertexAttribPointer(static_cast<GLuint>(positionLoc), 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLoc));
    glVertexAttribPointer(static_cast<GLuint>(texCoordLoc), 4, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(positionLoc));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLoc));

    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        LOGE("OES glDrawArrays failed glError=0x%x", glError);
        return false;
    }

    if (eglSwapBuffers(eglDisplay_, eglSurface_) != EGL_TRUE) {
        LOGE("OES eglSwapBuffers failed eglError=0x%x", eglGetError());
        return false;
    }
    return true;
}

void NativeOesRenderer::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (g_oes_java_vm != nullptr) {
        JNIEnv *env = nullptr;
        const jint getEnvResult = g_oes_java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (getEnvResult == JNI_OK && env != nullptr) {
            releaseJavaLocked(env);
        } else if (getEnvResult == JNI_EDETACHED) {
            if (g_oes_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env != nullptr) {
                releaseJavaLocked(env);
                g_oes_java_vm->DetachCurrentThread();
            }
        }
    }
    releaseGlLocked();
    updateTexImageMethod_ = nullptr;
    getTransformMatrixMethod_ = nullptr;
    prepared_.store(false);
}

bool NativeOesRenderer::hasSurface() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool NativeOesRenderer::isPrepared() const {
    return prepared_.load();
}

jobject NativeOesRenderer::getDecoderSurfaceGlobalRef() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return decoderSurfaceGlobalRef_;
}

int64_t NativeOesRenderer::getSurfaceRecreateCount() const {
    return surfaceRecreateCount_.load();
}

int64_t NativeOesRenderer::getContextRecreateCount() const {
    return contextRecreateCount_.load();
}

int64_t NativeOesRenderer::getUpdateTexImageErrorCount() const {
    return updateTexImageErrorCount_.load();
}

void NativeOesRenderer::resetDiagnostics() {
    surfaceRecreateCount_.store(0);
    contextRecreateCount_.store(0);
    updateTexImageErrorCount_.store(0);
}

bool NativeOesRenderer::ensureGlLocked(JNIEnv *env, std::string &errorMessage) {
    if (window_ == nullptr) {
        errorMessage = "OES requires valid Surface before prepare";
        return false;
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

    glGenTextures(1, &oesTexture_);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture_);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    if (!compileProgramLocked(errorMessage)) {
        releaseGlLocked();
        return false;
    }
    return true;
}

bool NativeOesRenderer::rebindEglSurfaceLocked(ANativeWindow *newWindow, int width, int height) {
    if (eglDisplay_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT || newWindow == nullptr) {
        return false;
    }
    releaseEglSurfaceLocked();
    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, newWindow, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        LOGE("OES recreate EGLSurface failed eglError=0x%x", eglGetError());
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        LOGE("OES recreate EGLSurface makeCurrent failed eglError=0x%x", eglGetError());
        return false;
    }
    glViewport(0, 0, width > 0 ? width : 1, height > 0 ? height : 1);
    return true;
}

void NativeOesRenderer::releaseEglSurfaceLocked() {
    if (eglDisplay_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }
    if (eglContext_ != EGL_NO_CONTEXT) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    eglDestroySurface(eglDisplay_, eglSurface_);
    eglSurface_ = EGL_NO_SURFACE;
}

void NativeOesRenderer::releaseGlLocked() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        if (eglSurface_ != EGL_NO_SURFACE && eglContext_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
        }
        if (oesTexture_ != 0) {
            glDeleteTextures(1, &oesTexture_);
            oesTexture_ = 0;
        }
        if (program_ != 0) {
            glDeleteProgram(program_);
            program_ = 0;
        }
        stMatrixLocation_ = -1;
        positionLocation_ = -1;
        texCoordLocation_ = -1;
        if (whiteHotProgram_ != 0) {
            glDeleteProgram(whiteHotProgram_);
            whiteHotProgram_ = 0;
        }
        whiteHotStMatrixLocation_ = -1;
        whiteHotPositionLocation_ = -1;
        whiteHotTexCoordLocation_ = -1;
        whiteHotGammaLocation_ = -1;
        if (ironbowTexture_ != 0) {
            glDeleteTextures(1, &ironbowTexture_);
            ironbowTexture_ = 0;
        }
        if (ironbowProgram_ != 0) {
            glDeleteProgram(ironbowProgram_);
            ironbowProgram_ = 0;
        }
        ironbowStMatrixLocation_ = -1;
        ironbowPositionLocation_ = -1;
        ironbowTexCoordLocation_ = -1;
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
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

void NativeOesRenderer::releaseJavaLocked(JNIEnv *env) {
    if (decoderSurfaceGlobalRef_ != nullptr) {
        jmethodID surfaceRelease = env->GetMethodID(
                env->GetObjectClass(decoderSurfaceGlobalRef_), "release", "()V");
        if (surfaceRelease != nullptr) {
            env->CallVoidMethod(decoderSurfaceGlobalRef_, surfaceRelease);
        }
        env->DeleteGlobalRef(decoderSurfaceGlobalRef_);
        decoderSurfaceGlobalRef_ = nullptr;
    }
    if (surfaceTextureGlobalRef_ != nullptr) {
        jmethodID stRelease = env->GetMethodID(
                env->GetObjectClass(surfaceTextureGlobalRef_), "release", "()V");
        if (stRelease != nullptr) {
            env->CallVoidMethod(surfaceTextureGlobalRef_, stRelease);
        }
        env->DeleteGlobalRef(surfaceTextureGlobalRef_);
        surfaceTextureGlobalRef_ = nullptr;
    }
    if (frameListenerGlobalRef_ != nullptr) {
        env->DeleteGlobalRef(frameListenerGlobalRef_);
        frameListenerGlobalRef_ = nullptr;
    }
    if (transformMatrixArrayGlobalRef_ != nullptr) {
        env->DeleteGlobalRef(transformMatrixArrayGlobalRef_);
        transformMatrixArrayGlobalRef_ = nullptr;
    }
}

bool NativeOesRenderer::compileProgramLocked(std::string &errorMessage) {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kOesVertexShader, errorMessage);
    if (vertexShader == 0) {
        return false;
    }
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kOesFragmentShader, errorMessage);
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
    GLuint whiteHotFragmentShader = compileShader(GL_FRAGMENT_SHADER, kOesWhiteHotFragmentShader, whiteHotError);
    whiteHotProgram_ = whiteHotFragmentShader == 0 ? 0 : linkProgram(vertexShader, whiteHotFragmentShader, whiteHotError);
    if (whiteHotFragmentShader != 0) {
        glDeleteShader(whiteHotFragmentShader);
    }
    if (whiteHotProgram_ == 0) {
        LOGE("OES white hot shader setup failed, fallback to original OES: %s", whiteHotError.c_str());
        whiteHotProgram_ = 0;
        whiteHotStMatrixLocation_ = -1;
        whiteHotPositionLocation_ = -1;
        whiteHotTexCoordLocation_ = -1;
        whiteHotGammaLocation_ = -1;
    }

    std::string ironbowError;
    GLuint ironbowFragmentShader = compileShader(GL_FRAGMENT_SHADER, kOesIronbowFragmentShader, ironbowError);
    ironbowProgram_ = ironbowFragmentShader == 0 ? 0 : linkProgram(vertexShader, ironbowFragmentShader, ironbowError);
    // vertexShader is no longer needed after all programs are linked.
    glDeleteShader(vertexShader);
    if (ironbowFragmentShader != 0) {
        glDeleteShader(ironbowFragmentShader);
    }
    if (ironbowProgram_ == 0) {
        LOGE("OES ironbow shader setup failed, fallback to white hot/original: %s", ironbowError.c_str());
        ironbowProgram_ = 0;
        ironbowStMatrixLocation_ = -1;
        ironbowPositionLocation_ = -1;
        ironbowTexCoordLocation_ = -1;
        ironbowGammaLocation_ = -1;
        ironbowPaletteLocation_ = -1;
        ironbowTexture_ = 0;
    }

    glUseProgram(program_);
    stMatrixLocation_ = glGetUniformLocation(program_, "uSTMatrix");
    positionLocation_ = glGetAttribLocation(program_, "aPosition");
    texCoordLocation_ = glGetAttribLocation(program_, "aTexCoord");
    if (stMatrixLocation_ < 0 || positionLocation_ < 0 || texCoordLocation_ < 0) {
        errorMessage = "OES shader attribute/uniform not found";
        return false;
    }
    glUniform1i(glGetUniformLocation(program_, "uTexture"), 0);

    if (whiteHotProgram_ != 0) {
        glUseProgram(whiteHotProgram_);
        whiteHotStMatrixLocation_ = glGetUniformLocation(whiteHotProgram_, "uSTMatrix");
        whiteHotPositionLocation_ = glGetAttribLocation(whiteHotProgram_, "aPosition");
        whiteHotTexCoordLocation_ = glGetAttribLocation(whiteHotProgram_, "aTexCoord");
        whiteHotGammaLocation_ = glGetUniformLocation(whiteHotProgram_, "uGamma");
        if (whiteHotStMatrixLocation_ < 0 || whiteHotPositionLocation_ < 0 || whiteHotTexCoordLocation_ < 0
            || whiteHotGammaLocation_ < 0) {
            LOGE("OES white hot shader attribute/uniform not found, fallback to original OES");
            glDeleteProgram(whiteHotProgram_);
            whiteHotProgram_ = 0;
            whiteHotStMatrixLocation_ = -1;
            whiteHotPositionLocation_ = -1;
            whiteHotTexCoordLocation_ = -1;
            whiteHotGammaLocation_ = -1;
        } else {
            glUniform1i(glGetUniformLocation(whiteHotProgram_, "uTexture"), 0);
        }
        glUseProgram(program_);
    }

    if (ironbowProgram_ != 0) {
        glUseProgram(ironbowProgram_);
        ironbowStMatrixLocation_ = glGetUniformLocation(ironbowProgram_, "uSTMatrix");
        ironbowPositionLocation_ = glGetAttribLocation(ironbowProgram_, "aPosition");
        ironbowTexCoordLocation_ = glGetAttribLocation(ironbowProgram_, "aTexCoord");
        ironbowGammaLocation_ = glGetUniformLocation(ironbowProgram_, "uGamma");
        ironbowPaletteLocation_ = glGetUniformLocation(ironbowProgram_, "uPaletteTexture");
        if (ironbowStMatrixLocation_ < 0 || ironbowPositionLocation_ < 0 || ironbowTexCoordLocation_ < 0
            || ironbowGammaLocation_ < 0 || ironbowPaletteLocation_ < 0) {
            LOGE("OES ironbow shader attribute/uniform not found, fallback to white hot/original");
            glDeleteProgram(ironbowProgram_);
            ironbowProgram_ = 0;
            ironbowStMatrixLocation_ = -1;
            ironbowPositionLocation_ = -1;
            ironbowTexCoordLocation_ = -1;
            ironbowGammaLocation_ = -1;
            ironbowPaletteLocation_ = -1;
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
        out << "OES program setup failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}
