#ifndef MOTRO_VIDEO_RENDERER_H
#define MOTRO_VIDEO_RENDERER_H

#include <jni.h>
#include <cstdint>
#include <mutex>
#include <string>

struct ANativeWindow;

struct RenderResult {
    bool success;
    int errorCode;
    std::string errorMessage;
};

class VideoRenderer {
public:
    VideoRenderer();
    ~VideoRenderer();

    VideoRenderer(const VideoRenderer &) = delete;
    VideoRenderer &operator=(const VideoRenderer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface, int width, int height);
    RenderResult renderRgba(const uint8_t *rgbaData, int lineSize, int width, int height);
    void release();
    bool hasSurface() const;

private:
    mutable std::mutex mutex_;
    ANativeWindow *window_ = nullptr;
    int width_ = 0;
    int height_ = 0;
};

#endif // MOTRO_VIDEO_RENDERER_H