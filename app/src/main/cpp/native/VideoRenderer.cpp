#include "VideoRenderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <cstring>
#include <sstream>

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

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

} // namespace

VideoRenderer::VideoRenderer() = default;

VideoRenderer::~VideoRenderer() {
    release();
}

std::string VideoRenderer::setSurface(JNIEnv *env, jobject surface, int width, int height) {
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
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    window_ = newWindow;
    width_ = width;
    height_ = height;
    if (width > 0 && height > 0) {
        const int result = ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
        if (result < 0) {
            LOGE("ANativeWindow_setBuffersGeometry failed: %d", result);
        }
    }

    LOGI("setSurface success width=%d height=%d", width, height);
    return jsonSuccess("surface set");
}

RenderResult VideoRenderer::renderRgba(const uint8_t *rgbaData, int lineSize, int width, int height) {
    if (rgbaData == nullptr || lineSize <= 0 || width <= 0 || height <= 0) {
        return {false, -1, "invalid RGBA frame"};
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) {
        return {false, -1, "Surface is not set"};
    }

    if (width_ != width || height_ != height) {
        const int geometryResult = ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
        if (geometryResult < 0) {
            LOGE("ANativeWindow_setBuffersGeometry failed: %d", geometryResult);
            return {false, geometryResult, "ANativeWindow_setBuffersGeometry failed"};
        }
        width_ = width;
        height_ = height;
    }

    ANativeWindow_Buffer buffer;
    const int lockResult = ANativeWindow_lock(window_, &buffer, nullptr);
    if (lockResult < 0) {
        LOGE("ANativeWindow_lock failed: %d", lockResult);
        return {false, lockResult, "ANativeWindow_lock failed"};
    }

    auto *dst = static_cast<uint8_t *>(buffer.bits);
    const int dstStride = buffer.stride * 4;
    const int copyWidth = std::min(width, buffer.width) * 4;
    const int copyHeight = std::min(height, buffer.height);

    for (int y = 0; y < copyHeight; ++y) {
        std::memcpy(dst + y * dstStride, rgbaData + y * lineSize, copyWidth);
    }

    const int unlockResult = ANativeWindow_unlockAndPost(window_);
    if (unlockResult < 0) {
        LOGE("ANativeWindow_unlockAndPost failed: %d", unlockResult);
        return {false, unlockResult, "ANativeWindow_unlockAndPost failed"};
    }

    return {true, 0, ""};
}

void VideoRenderer::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    width_ = 0;
    height_ = 0;
}

bool VideoRenderer::hasSurface() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}