#include "NativeOesRenderer.h"

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
    // Surface change invalidates the current EGL window surface and OES texture;
    // tear down so a later prepareForOesDecode recreates everything from the new window.
    if (prepared_.load()) {
        releaseGlLocked();
        releaseJavaLocked(env);
        prepared_.store(false);
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

bool NativeOesRenderer::renderOesFrame(JNIEnv *env) {
    if (env == nullptr || !prepared_.load()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!prepared_.load()) {
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        LOGE("OES eglMakeCurrent failed eglError=0x%x", eglGetError());
        return false;
    }

    env->CallVoidMethod(surfaceTextureGlobalRef_, updateTexImageMethod_);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }

    GLfloat transform[16] = {0};
    jfloatArray matrixArray = env->NewFloatArray(16);
    env->CallVoidMethod(surfaceTextureGlobalRef_, getTransformMatrixMethod_, matrixArray);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(matrixArray);
        return false;
    }
    env->GetFloatArrayRegion(matrixArray, 0, 16, transform);
    env->DeleteLocalRef(matrixArray);

    const int viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : 1;
    const int viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : 1;
    glViewport(0, 0, viewportWidth, viewportHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(program_);
    glUniformMatrix4fv(stMatrixLocation_, 1, GL_FALSE, transform);

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

    glEnableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glVertexAttribPointer(static_cast<GLuint>(positionLocation_), 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));
    glVertexAttribPointer(static_cast<GLuint>(texCoordLocation_), 4, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));

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
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    if (program_ == 0) {
        return false;
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
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream out;
        out << "OES program setup failed glError=0x" << std::hex << error;
        errorMessage = out.str();
        return false;
    }
    return true;
}
