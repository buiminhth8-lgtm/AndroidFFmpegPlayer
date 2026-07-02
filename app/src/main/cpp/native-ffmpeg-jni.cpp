#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

#include "native/NativePlayer.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/jni.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/error.h"
#include "libavutil/rational.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_java_vm = nullptr;
bool g_jni_initialized = false;
std::mutex g_player_mutex;
std::unordered_set<NativePlayer *> g_active_players;
std::unordered_set<uintptr_t> g_released_players;

std::string escapeJson(const std::string &value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u00";
                    const char *hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string ffmpegErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (av_strerror(errorCode, buffer, sizeof(buffer)) < 0) {
        return "Unknown FFmpeg error";
    }
    return std::string(buffer);
}

jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string jsonError(int errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":" << errorCode
        << ",\"errorMessage\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string jsonSuccess(const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string boolJson(bool value) {
    return value ? "true" : "false";
}

bool hasDecoderById(AVCodecID codecId) {
    return avcodec_find_decoder(codecId) != nullptr;
}

bool hasDecoderByName(const char *name) {
    return avcodec_find_decoder_by_name(name) != nullptr;
}

std::string getAvailableDecodersJson() {
    const bool h264 = hasDecoderById(AV_CODEC_ID_H264);
    const bool hevc = hasDecoderById(AV_CODEC_ID_HEVC);
    const bool h264MediaCodec = hasDecoderByName("h264_mediacodec");
    const bool hevcMediaCodec = hasDecoderByName("hevc_mediacodec");

    LOGI("decoder h264=%d hevc=%d h264_mediacodec=%d hevc_mediacodec=%d",
         h264, hevc, h264MediaCodec, hevcMediaCodec);

    std::ostringstream out;
    out << "{"
        << "\"h264\":" << boolJson(h264) << ","
        << "\"hevc\":" << boolJson(hevc) << ","
        << "\"h264_mediacodec\":" << boolJson(h264MediaCodec) << ","
        << "\"hevc_mediacodec\":" << boolJson(hevcMediaCodec)
        << "}";
    return out.str();
}

std::string codecName(AVCodecID codecId) {
    const char *name = avcodec_get_name(codecId);
    return name == nullptr ? "unknown" : name;
}

double rationalToDouble(AVRational rational) {
    if (rational.den == 0) {
        return 0.0;
    }
    return av_q2d(rational);
}

std::string probeUrl(const std::string &url, int timeoutMs) {
    if (url.empty()) {
        return jsonError(-1, "url is empty");
    }

    AVFormatContext *formatContext = nullptr;
    AVDictionary *options = nullptr;
    const int64_t timeoutUs = static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
    const std::string timeoutValue = std::to_string(timeoutUs);

    avformat_network_init();
    av_dict_set(&options, "rtsp_transport", "tcp", 0);
    av_dict_set(&options, "stimeout", timeoutValue.c_str(), 0);
    av_dict_set(&options, "timeout", timeoutValue.c_str(), 0);

    int result = avformat_open_input(&formatContext, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (result < 0) {
        const std::string error = ffmpegErrorToString(result);
        LOGE("probe open failed url=%s error=%s", url.c_str(), error.c_str());
        return jsonError(result, error);
    }

    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) {
        const std::string error = ffmpegErrorToString(result);
        avformat_close_input(&formatContext);
        return jsonError(result, error);
    }

    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"format\":\"" << escapeJson(formatContext->iformat && formatContext->iformat->name
                                               ? formatContext->iformat->name : "unknown") << "\",";
    out << "\"durationUs\":" << static_cast<long long>(formatContext->duration) << ",";
    out << "\"bitRate\":" << static_cast<long long>(formatContext->bit_rate) << ",";
    out << "\"streams\":[";

    bool first = true;
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) {
            continue;
        }

        AVCodecParameters *params = stream->codecpar;
        if (!first) {
            out << ",";
        }
        first = false;

        out << "{"
            << "\"index\":" << static_cast<int>(i) << ","
            << "\"type\":\"" << escapeJson(av_get_media_type_string(params->codec_type)
                                              ? av_get_media_type_string(params->codec_type) : "unknown") << "\","
            << "\"codec\":\"" << escapeJson(codecName(params->codec_id)) << "\"";

        if (params->codec_type == AVMEDIA_TYPE_VIDEO) {
            const double fps = rationalToDouble(stream->avg_frame_rate.num != 0
                                                ? stream->avg_frame_rate
                                                : stream->r_frame_rate);
            out << ",\"width\":" << params->width
                << ",\"height\":" << params->height
                << ",\"fps\":" << fps;
        } else if (params->codec_type == AVMEDIA_TYPE_AUDIO) {
            out << ",\"sampleRate\":" << params->sample_rate
                << ",\"channels\":" << params->ch_layout.nb_channels;
        }

        out << "}";
    }

    out << "]}";
    avformat_close_input(&formatContext);
    return out.str();
}

std::vector<std::string> toStringVector(JNIEnv *env, jobjectArray args) {
    std::vector<std::string> values;
    if (args == nullptr) {
        return values;
    }

    const jsize count = env->GetArrayLength(args);
    values.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        if (item == nullptr) {
            values.emplace_back();
            continue;
        }
        const char *chars = env->GetStringUTFChars(item, nullptr);
        values.emplace_back(chars == nullptr ? "" : chars);
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(item, chars);
        }
        env->DeleteLocalRef(item);
    }
    return values;
}

NativePlayer *getPlayer(jlong handle, std::string &errorMessage) {
    if (handle == 0) {
        errorMessage = "player handle is 0";
        return nullptr;
    }

    auto *player = reinterpret_cast<NativePlayer *>(static_cast<intptr_t>(handle));
    const uintptr_t rawHandle = reinterpret_cast<uintptr_t>(player);
    std::lock_guard<std::mutex> lock(g_player_mutex);
    if (g_active_players.find(player) != g_active_players.end()) {
        return player;
    }
    if (g_released_players.find(rawHandle) != g_released_players.end()) {
        errorMessage = "player already released";
    } else {
        errorMessage = "invalid player handle";
    }
    return nullptr;
}

jstring nativeGetFFmpegVersion(JNIEnv *env, jclass) {
    return toJString(env, av_version_info());
}

jstring nativeGetFFmpegBuildConfig(JNIEnv *env, jclass) {
    return toJString(env, avcodec_configuration());
}

jstring nativeGetAvailableDecoders(JNIEnv *env, jclass) {
    return toJString(env, getAvailableDecodersJson());
}

jstring nativeGetMediaCodecInfo(JNIEnv *env, jclass) {
    const bool h264MediaCodec = hasDecoderByName("h264_mediacodec");
    const bool hevcMediaCodec = hasDecoderByName("hevc_mediacodec");

    std::ostringstream out;
    out << "{"
        << "\"jniInitialized\":" << boolJson(g_jni_initialized) << ","
        << "\"h264_mediacodec\":" << boolJson(h264MediaCodec) << ","
        << "\"hevc_mediacodec\":" << boolJson(hevcMediaCodec)
        << "}";
    return toJString(env, out.str());
}

jstring nativeProbe(JNIEnv *env, jclass, jstring url, jint timeoutMs) {
    if (url == nullptr) {
        return toJString(env, jsonError(-1, "url is null"));
    }

    const char *chars = env->GetStringUTFChars(url, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read url"));
    }

    std::string urlValue(chars);
    env->ReleaseStringUTFChars(url, chars);
    return toJString(env, probeUrl(urlValue, timeoutMs));
}

jstring nativeRunDebugCommand(JNIEnv *env, jclass, jobjectArray args) {
    const std::vector<std::string> command = toStringVector(env, args);
    if (command.empty()) {
        return toJString(env, jsonError(-1, "args is empty"));
    }

    const std::string &first = command[0];
    if (first == "-version") {
        return toJString(env, std::string("{\"success\":true,\"version\":\"")
                              + escapeJson(av_version_info()) + "\"}");
    }
    if (first == "-buildconf") {
        return toJString(env, std::string("{\"success\":true,\"buildConfig\":\"")
                              + escapeJson(avcodec_configuration()) + "\"}");
    }
    if (first == "-decoders") {
        return toJString(env, std::string("{\"success\":true,\"decoders\":")
                              + getAvailableDecodersJson() + "}");
    }
    if (first == "-probe") {
        if (command.size() < 2 || command[1].empty()) {
            return toJString(env, jsonError(-1, "-probe requires url"));
        }
        return toJString(env, probeUrl(command[1], 5000));
    }
    if (first == "ffprobe") {
        if (command.size() < 2 || command[1].empty()) {
            return toJString(env, jsonError(-1, "ffprobe requires url"));
        }
        return toJString(env, probeUrl(command[1], 5000));
    }
    if (first == "ffplay") {
        return toJString(env, "{\"success\":false,\"message\":\"ffplay is not supported in phase 1. Use Surface-based player API later.\"}");
    }

    return toJString(env, std::string("{\"success\":false,\"message\":\"unsupported debug command: ")
                          + escapeJson(first) + "\"}");
}

jlong nativeCreatePlayer(JNIEnv *, jclass) {
    auto *player = new (std::nothrow) NativePlayer();
    if (player == nullptr) {
        LOGE("createPlayer failed: allocation failed");
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_player_mutex);
    g_active_players.insert(player);
    g_released_players.erase(reinterpret_cast<uintptr_t>(player));
    LOGI("createPlayer handle=%p", player);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(player));
}

jstring nativeSetPlayerSurface(JNIEnv *env, jclass, jlong handle, jobject surface) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setSurface(env, surface));
}


jstring nativeSetAudioCallback(JNIEnv *env, jclass, jlong handle, jobject callback) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setAudioCallback(env, callback));
}

jstring nativeEnableAudio(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->enableAudio(enabled == JNI_TRUE));
}
jstring nativePreparePlayer(JNIEnv *env, jclass, jlong handle, jstring url, jint timeoutMs) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (url == nullptr) {
        return toJString(env, jsonError(-1, "url is null"));
    }

    const char *chars = env->GetStringUTFChars(url, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read url"));
    }
    std::string urlValue(chars);
    env->ReleaseStringUTFChars(url, chars);
    return toJString(env, player->prepare(urlValue, timeoutMs));
}

jstring nativeStartPlayer(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->start());
}

jstring nativePausePlayer(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->pause());
}

jstring nativeStopPlayer(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->stop());
}

jstring nativeGetPlayerState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getState());
}


jstring nativeSetPlayerReconnectOptions(JNIEnv *env, jclass, jlong handle, jboolean enabled, jint maxRetryCount, jint retryDelayMs) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setReconnectOptions(enabled == JNI_TRUE, maxRetryCount, retryDelayMs));
}

jstring nativeGetPlayerReconnectState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getReconnectState());
}
jstring nativeTakePlayerSnapshot(JNIEnv *env, jclass, jlong handle, jstring outputPath) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (outputPath == nullptr) {
        return toJString(env, jsonError(-1, "outputPath is null"));
    }

    const char *chars = env->GetStringUTFChars(outputPath, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read outputPath"));
    }
    std::string outputPathValue(chars);
    env->ReleaseStringUTFChars(outputPath, chars);
    return toJString(env, player->takeSnapshot(outputPathValue));
}

jstring nativeGetPlayerStats(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getStats());
}

jstring nativeClearPlayerSurface(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->clearSurface());
}
jstring nativeStartPlayerRecord(JNIEnv *env, jclass, jlong handle, jstring outputPath) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (outputPath == nullptr) {
        return toJString(env, jsonError(-1, "outputPath is null"));
    }

    const char *chars = env->GetStringUTFChars(outputPath, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read outputPath"));
    }
    std::string outputPathValue(chars);
    env->ReleaseStringUTFChars(outputPath, chars);
    return toJString(env, player->startRecord(outputPathValue));
}


jstring nativeStartPlayerSegmentRecord(JNIEnv *env, jclass, jlong handle, jstring outputPattern, jint segmentDurationSec) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (outputPattern == nullptr) {
        return toJString(env, jsonError(-1, "outputPattern is null"));
    }

    const char *chars = env->GetStringUTFChars(outputPattern, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read outputPattern"));
    }
    std::string outputPatternValue(chars);
    env->ReleaseStringUTFChars(outputPattern, chars);
    return toJString(env, player->startSegmentRecord(outputPatternValue, segmentDurationSec));
}
jstring nativeStopPlayerRecord(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->stopRecord());
}

jstring nativeGetPlayerRecordState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    NativePlayer *player = getPlayer(handle, error);
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getRecordState());
}
jstring nativeReleasePlayer(JNIEnv *env, jclass, jlong handle) {
    if (handle == 0) {
        return toJString(env, jsonError(-1, "player handle is 0"));
    }

    auto *player = reinterpret_cast<NativePlayer *>(static_cast<intptr_t>(handle));
    const uintptr_t rawHandle = reinterpret_cast<uintptr_t>(player);
    {
        std::lock_guard<std::mutex> lock(g_player_mutex);
        if (g_released_players.find(rawHandle) != g_released_players.end()) {
            return toJString(env, jsonSuccess("player already released"));
        }
        if (g_active_players.find(player) == g_active_players.end()) {
            return toJString(env, jsonError(-1, "invalid player handle"));
        }
        g_active_players.erase(player);
        g_released_players.insert(rawHandle);
    }

    const std::string result = player->release();
    delete player;
    return toJString(env, result);
}

bool registerNativeMethods(JNIEnv *env) {
    jclass clazz = env->FindClass("com/example/motro/ffmpeg/FFmpegNative");
    if (clazz == nullptr) {
        LOGE("FindClass failed for FFmpegNative");
        return false;
    }

    static JNINativeMethod methods[] = {
            {"getFFmpegVersion", "()Ljava/lang/String;", reinterpret_cast<void *>(nativeGetFFmpegVersion)},
            {"getFFmpegBuildConfig", "()Ljava/lang/String;", reinterpret_cast<void *>(nativeGetFFmpegBuildConfig)},
            {"getAvailableDecoders", "()Ljava/lang/String;", reinterpret_cast<void *>(nativeGetAvailableDecoders)},
            {"getMediaCodecInfo", "()Ljava/lang/String;", reinterpret_cast<void *>(nativeGetMediaCodecInfo)},
            {"probe", "(Ljava/lang/String;I)Ljava/lang/String;", reinterpret_cast<void *>(nativeProbe)},
            {"runDebugCommand", "([Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeRunDebugCommand)},
            {"createPlayer", "()J", reinterpret_cast<void *>(nativeCreatePlayer)},
            {"setPlayerSurface", "(JLandroid/view/Surface;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerSurface)},
            {"preparePlayer", "(JLjava/lang/String;I)Ljava/lang/String;", reinterpret_cast<void *>(nativePreparePlayer)},
            {"startPlayer", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayer)},
            {"pausePlayer", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativePausePlayer)},
            {"stopPlayer", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeStopPlayer)},
            {"getPlayerState", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerState)},
            {"takePlayerSnapshot", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeTakePlayerSnapshot)},
            {"getPlayerStats", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerStats)},
            {"clearPlayerSurface", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeClearPlayerSurface)},
            {"setAudioCallback", "(JLjava/lang/Object;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetAudioCallback)},
            {"enableAudio", "(JZ)Ljava/lang/String;", reinterpret_cast<void *>(nativeEnableAudio)},
            {"setPlayerReconnectOptions", "(JZII)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerReconnectOptions)},
            {"getPlayerReconnectState", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerReconnectState)},
            {"startPlayerRecord", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayerRecord)},
            {"startPlayerSegmentRecord", "(JLjava/lang/String;I)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayerSegmentRecord)},
            {"stopPlayerRecord", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeStopPlayerRecord)},
            {"getPlayerRecordState", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerRecordState)},
            {"releasePlayer", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeReleasePlayer)},
    };

    const int result = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(clazz);
    if (result != JNI_OK) {
        LOGE("RegisterNatives failed: %d", result);
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_java_vm = vm;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        LOGE("JNI_OnLoad GetEnv failed");
        return JNI_ERR;
    }

    avformat_network_init();
    g_jni_initialized = av_jni_set_java_vm(vm, nullptr) >= 0;

    if (!registerNativeMethods(env)) {
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad success, jniInitialized=%d", g_jni_initialized);
    return JNI_VERSION_1_6;
}