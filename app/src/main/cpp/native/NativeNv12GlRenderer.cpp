#include "NativeNv12GlRenderer.h"

#include <android/log.h>
#include <sstream>

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

NativeNv12GlRenderer::NativeNv12GlRenderer() = default;

NativeNv12GlRenderer::~NativeNv12GlRenderer() {
    release();
}

std::string NativeNv12GlRenderer::setSurface(JNIEnv *env, jobject surface, int width, int height) {
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is null");
    }
    if (surface == nullptr) {
        return jsonError(-1, "Surface is null");
    }
    surfaceSet_ = true;
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    LOGI("setSurface NV12 GL (boundary, not ready) surface=%dx%d", width, height);
    return jsonSuccess("nv12 gl surface recorded");
}

void NativeNv12GlRenderer::clearSurface() {
    surfaceSet_ = false;
    surfaceWidth_ = 0;
    surfaceHeight_ = 0;
}

void NativeNv12GlRenderer::release() {
    clearSurface();
}

bool NativeNv12GlRenderer::supportsFrameFormat(int frameFormat) const {
    // NV12 GL rendering is not implemented in Revised Phase 2 Slice 0.
    (void)frameFormat;
    return false;
}

bool NativeNv12GlRenderer::isReady() const {
    return false;
}
