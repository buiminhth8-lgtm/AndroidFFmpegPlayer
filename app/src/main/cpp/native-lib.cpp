#include <jni.h>
#include <string>
#include <android/log.h>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavcodec/jni.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_java_vm = nullptr;

extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_java_vm = vm;

    // 使用 FFmpeg MediaCodec 时建议设置 JavaVM
    avformat_network_init();
    const int result = av_jni_set_java_vm(vm, nullptr);
    if (result >= 0) {
        LOGI("av_jni_set_java_vm success");
    } else {
        LOGE("av_jni_set_java_vm failed ret=%d", result);
    }

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_ffmpegdemo_FFmpegNative_getFFmpegVersion(JNIEnv *env, jobject thiz) {
    const char *version = av_version_info();
    return env->NewStringUTF(version);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_ffmpegdemo_FFmpegNative_getCodecInfo(JNIEnv *env, jobject thiz) {
    const AVCodec *h264 = avcodec_find_decoder(AV_CODEC_ID_H264);
    const AVCodec *hevc = avcodec_find_decoder(AV_CODEC_ID_HEVC);

    std::string info;
    info += "FFmpeg version: ";
    info += av_version_info();
    info += "\n";

    info += "H264 decoder: ";
    info += h264 ? h264->name : "not found";
    info += "\n";

    info += "HEVC decoder: ";
    info += hevc ? hevc->name : "not found";
    info += "\n";

    const AVCodec *h264MediaCodec = avcodec_find_decoder_by_name("h264_mediacodec");
    const AVCodec *hevcMediaCodec = avcodec_find_decoder_by_name("hevc_mediacodec");

    info += "h264_mediacodec: ";
    info += h264MediaCodec ? "found" : "not found";
    info += "\n";

    info += "hevc_mediacodec: ";
    info += hevcMediaCodec ? "found" : "not found";
    info += "\n";

    return env->NewStringUTF(info.c_str());
}
