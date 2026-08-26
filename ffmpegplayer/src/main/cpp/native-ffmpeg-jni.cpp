#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "native/NativePlayer.h"
#include "native/NativeOesRenderer.h"
#include "native/PlayerOptions.h"
#include "native/TestHookPolicy.h"

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
std::atomic<int64_t> g_next_player_handle{1};

struct PlayerEntry {
    PlayerEntry(jlong handleValue, std::unique_ptr<NativePlayer> playerValue)
            : handle(handleValue), player(std::move(playerValue)) {
    }

    const jlong handle;
    std::unique_ptr<NativePlayer> player;
    std::mutex lifetimeMutex;
    std::condition_variable lifetimeCv;
    bool closing = false;
    uint32_t activeOperations = 0;
};

// Lock order: registry mutex -> entry lifetime mutex. Player API calls and
// release drain waits must hold neither lock.
std::mutex g_player_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<PlayerEntry>> g_player_registry;

class PlayerOperationGuard {
public:
    PlayerOperationGuard() = default;
    explicit PlayerOperationGuard(std::shared_ptr<PlayerEntry> entry)
            : entry_(std::move(entry)) {
    }

    PlayerOperationGuard(const PlayerOperationGuard &) = delete;
    PlayerOperationGuard &operator=(const PlayerOperationGuard &) = delete;
    PlayerOperationGuard(PlayerOperationGuard &&other) noexcept
            : entry_(std::move(other.entry_)) {
    }
    PlayerOperationGuard &operator=(PlayerOperationGuard &&) = delete;

    ~PlayerOperationGuard() {
        if (entry_ == nullptr) {
            return;
        }
        std::shared_ptr<PlayerEntry> entry = std::move(entry_);
        bool drained = false;
        {
            std::lock_guard<std::mutex> lock(entry->lifetimeMutex);
            if (entry->activeOperations == 0) {
                LOGE("player operation guard imbalance handle=%lld",
                     static_cast<long long>(entry->handle));
                return;
            }
            --entry->activeOperations;
            drained = entry->activeOperations == 0;
        }
        if (drained) {
            entry->lifetimeCv.notify_all();
        }
    }

    explicit operator bool() const {
        return entry_ != nullptr;
    }

    NativePlayer *player() const {
        return entry_ == nullptr ? nullptr : entry_->player.get();
    }

private:
    std::shared_ptr<PlayerEntry> entry_;
};

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

bool wasPlayerHandleIssued(jlong handle) {
    return handle > 0 && handle < g_next_player_handle.load();
}

std::string snapshotJniError(const std::string &errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":\"" << escapeJson(errorCode) << "\","
        << "\"message\":\"" << escapeJson(message) << "\","
        << "\"errorMessage\":\"" << escapeJson(message) << "\","
        << "\"snapshotCaptureMode\":\"unsupported\"}";
    return out.str();
}

PlayerOperationGuard acquirePlayer(jlong handle, std::string &errorMessage) {
    if (handle == 0) {
        errorMessage = "player handle is 0";
        return {};
    }

    std::shared_ptr<PlayerEntry> entry;
    {
        std::unique_lock<std::mutex> registryLock(g_player_registry_mutex);
        const auto iterator = g_player_registry.find(handle);
        if (iterator == g_player_registry.end()) {
            errorMessage = wasPlayerHandleIssued(handle)
                           ? "player already released"
                           : "invalid player handle";
            return {};
        }
        entry = iterator->second;

        std::lock_guard<std::mutex> entryLock(entry->lifetimeMutex);
        if (entry->closing || entry->player == nullptr) {
            errorMessage = "player already released";
            return {};
        }
        ++entry->activeOperations;
    }
    return PlayerOperationGuard(std::move(entry));
}

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
std::string runPlayerLifetimeStressTest();
#endif

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
    if (ffmpegplayer::testHookCommandPolicy(first)
            == ffmpegplayer::TestHookCommandPolicy::UnsupportedInRelease) {
        return toJString(env, std::string(ffmpegplayer::kTestHookUnsupportedJson));
    }
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
    if (first == "-latency-config") {
        return toJString(env, latencyProfilesJson());
    }
    if (first == "-sourceinfo") {
        if (command.size() < 2 || command[1].empty()) {
            return toJString(env, jsonError(-1, "-sourceinfo requires url"));
        }
        return toJString(env, sourceInfoJson(command[1]));
    }
    if (first == "-rtsp-low-latency-help") {
        return toJString(env, rtspLowLatencyHelpJson());
    }
    if (first == "-ultra-low-latency-help" || first == "-rtsp-ultra-low-latency-help") {
        return toJString(env, ultraLowLatencyHelpJson());
    }
    if (first == "-latency-report-help" || first == "-player-stats-help") {
        return toJString(env, latencyReportHelpJson());
    }
    if (first == "-hardware-decode-help") {
        return toJString(env, hardwareDecodeHelpJson());
    }
#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
    if (first == "-player-lifetime-stress") {
        return toJString(env, runPlayerLifetimeStressTest());
    }
    if (first == "-audio-backpressure-test") {
        // Test-only: make the audio output worker's null sink sleep per block to
        // validate that a slow consumer never blocks the playback thread.
        const int delayMs = command.size() >= 2 ? std::atoi(command[1].c_str()) : 0;
        setAudioWorkerBackpressureTestDelayMs(delayMs);
        return toJString(env, std::string("{\"success\":true,\"audioWorkerTestDelayMs\":")
                              + std::to_string(delayMs) + "}");
    }
#endif
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

jlong createPlayerEntry() {
    const jlong handle = static_cast<jlong>(g_next_player_handle.fetch_add(1));
    if (handle <= 0) {
        LOGE("createPlayer failed: handle space exhausted");
        return 0;
    }

    std::unique_ptr<NativePlayer> player(new (std::nothrow) NativePlayer(handle));
    if (player == nullptr) {
        LOGE("createPlayer failed: allocation failed");
        return 0;
    }

    try {
        auto entry = std::make_shared<PlayerEntry>(handle, std::move(player));
        size_t activePlayerCount = 0;
        {
            std::lock_guard<std::mutex> lock(g_player_registry_mutex);
            g_player_registry.emplace(handle, std::move(entry));
            activePlayerCount = g_player_registry.size();
        }
        LOGI("createPlayer handle=%lld activePlayerCount=%zu",
             static_cast<long long>(handle), activePlayerCount);
        return handle;
    } catch (const std::bad_alloc &) {
        LOGE("createPlayer failed: registry allocation failed handle=%lld",
             static_cast<long long>(handle));
        return 0;
    }
}

jlong nativeCreatePlayer(JNIEnv *, jclass) {
    return createPlayerEntry();
}

jstring nativeSetPlayerSurface(JNIEnv *env, jclass, jlong handle, jobject surface) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setSurface(env, surface));
}


jstring nativeSetAudioCallback(JNIEnv *env, jclass, jlong handle, jobject callback) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setAudioCallback(env, callback));
}

jstring nativeSetPlayerEventListener(JNIEnv *env, jclass, jlong handle, jobject listener) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setPlayerEventListener(env, listener));
}

jstring nativeEnableAudio(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->enableAudio(enabled == JNI_TRUE));
}
jstring nativePreparePlayer(JNIEnv *env, jclass, jlong handle, jstring url, jint timeoutMs) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
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
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->start());
}

jstring nativePausePlayer(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->pause());
}

jstring nativeStopPlayer(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->stop());
}

jstring nativeGetPlayerState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getState());
}


jstring nativeSetPlayerReconnectOptions(JNIEnv *env, jclass, jlong handle, jboolean enabled, jint maxRetryCount, jint retryDelayMs) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setReconnectOptions(enabled == JNI_TRUE, maxRetryCount, retryDelayMs));
}

jstring nativeGetPlayerReconnectState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getReconnectState());
}

jstring nativeSetPlayerRtspTransport(JNIEnv *env, jclass, jlong handle, jstring transport) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (transport == nullptr) {
        return toJString(env, jsonError(-1, "transport is null"));
    }

    const char *chars = env->GetStringUTFChars(transport, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read transport"));
    }
    std::string transportValue(chars);
    env->ReleaseStringUTFChars(transport, chars);
    return toJString(env, player->setRtspTransport(transportValue));
}

jstring nativeGetPlayerRtspTransportState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getRtspTransportState());
}

jstring nativeSetRtspTransport(JNIEnv *env, jclass clazz, jlong handle, jstring transport) {
    return nativeSetPlayerRtspTransport(env, clazz, handle, transport);
}

jstring nativeSetPlayerLatencyMode(JNIEnv *env, jclass, jlong handle, jstring mode) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (mode == nullptr) {
        return toJString(env, jsonError(-1, "mode is null"));
    }

    const char *chars = env->GetStringUTFChars(mode, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read mode"));
    }
    std::string modeValue(chars);
    env->ReleaseStringUTFChars(mode, chars);
    return toJString(env, player->setLatencyMode(modeValue));
}

jstring nativeSetPlayerOption(JNIEnv *env, jclass, jlong handle, jstring key, jstring value) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (key == nullptr) {
        return toJString(env, jsonError(-1, "key is null"));
    }
    if (value == nullptr) {
        return toJString(env, jsonError(-1, "value is null"));
    }

    const char *keyChars = env->GetStringUTFChars(key, nullptr);
    if (keyChars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read key"));
    }
    std::string keyValue(keyChars);
    env->ReleaseStringUTFChars(key, keyChars);

    const char *valueChars = env->GetStringUTFChars(value, nullptr);
    if (valueChars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read value"));
    }
    std::string optionValue(valueChars);
    env->ReleaseStringUTFChars(value, valueChars);

    return toJString(env, player->setOption(keyValue, optionValue));
}

jstring nativeSetHardwareDecode(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setHardwareDecode(enabled == JNI_TRUE));
}

jstring nativeSetHardwareRenderMode(JNIEnv *env, jclass, jlong handle, jstring mode) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (mode == nullptr) {
        return toJString(env, jsonError(-1, "mode is null"));
    }

    const char *chars = env->GetStringUTFChars(mode, nullptr);
    if (chars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read mode"));
    }
    std::string modeValue(chars);
    env->ReleaseStringUTFChars(mode, chars);
    return toJString(env, player->setHardwareRenderMode(modeValue));
}

jstring nativeGetPlayerLatencyConfig(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getLatencyConfig());
}

jstring nativeTakePlayerSnapshot(JNIEnv *env, jclass, jlong handle, jstring outputPath) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, snapshotJniError("SNAPSHOT_PLAYER_RELEASED", error));
    }
    if (outputPath == nullptr) {
        return toJString(env, snapshotJniError("SNAPSHOT_IO_ERROR", "outputPath is null"));
    }

    const char *chars = env->GetStringUTFChars(outputPath, nullptr);
    if (chars == nullptr) {
        return toJString(env, snapshotJniError("SNAPSHOT_IO_ERROR", "failed to read outputPath"));
    }
    std::string outputPathValue(chars);
    env->ReleaseStringUTFChars(outputPath, chars);
    return toJString(env, player->takeSnapshot(outputPathValue));
}

jstring nativeGetPlayerStats(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getStats());
}

jstring nativeClearPlayerSurface(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->clearSurface());
}
jstring nativeStartPlayerRecord(JNIEnv *env, jclass, jlong handle, jstring outputPath) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
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
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
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

jstring nativeStartPlayerRecordWithConfig(JNIEnv *env, jclass, jlong handle, jstring outputPathOrPattern, jstring format, jint segmentDurationSec) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    if (outputPathOrPattern == nullptr) {
        return toJString(env, jsonError(-1, "outputPathOrPattern is null"));
    }

    const char *pathChars = env->GetStringUTFChars(outputPathOrPattern, nullptr);
    if (pathChars == nullptr) {
        return toJString(env, jsonError(-1, "failed to read outputPathOrPattern"));
    }
    std::string outputValue(pathChars);
    env->ReleaseStringUTFChars(outputPathOrPattern, pathChars);

    std::string formatValue;
    if (format != nullptr) {
        const char *formatChars = env->GetStringUTFChars(format, nullptr);
        if (formatChars == nullptr) {
            return toJString(env, jsonError(-1, "failed to read format"));
        }
        formatValue = formatChars;
        env->ReleaseStringUTFChars(format, formatChars);
    }

    return toJString(env, player->startRecordWithConfig(outputValue, formatValue, segmentDurationSec));
}

jstring nativeStopPlayerRecord(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->stopRecord());
}

jstring nativeGetPlayerRecordState(JNIEnv *env, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->getRecordState());
}

jstring nativeSetThermalEnabled(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setThermalEnabled(enabled == JNI_TRUE));
}

jstring nativeSetThermalPalette(JNIEnv *env, jclass, jlong handle, jint palette) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setThermalPalette(palette));
}

jstring nativeSetThermalAgcEnabled(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setThermalAgcEnabled(enabled == JNI_TRUE));
}

jstring nativeSetThermalGamma(JNIEnv *env, jclass, jlong handle, jfloat gamma) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setThermalGamma(gamma));
}

jstring nativeSetThermalWindow(JNIEnv *env, jclass, jlong handle, jfloat blackPoint, jfloat whitePoint) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player == nullptr) {
        return toJString(env, jsonError(-1, error));
    }
    return toJString(env, player->setThermalWindow(blackPoint, whitePoint));
}

void nativeNotifyOesFrameAvailable(JNIEnv *, jclass, jlong handle) {
    std::string error;
    PlayerOperationGuard guard = acquirePlayer(handle, error);
    NativePlayer *player = guard.player();
    if (player != nullptr) {
        player->notifyOesFrameAvailable();
    }
}

std::string releasePlayerEntry(jlong handle) {
    if (handle == 0) {
        return jsonError(-1, "player handle is 0");
    }

    std::shared_ptr<PlayerEntry> entry;
    uint32_t activeOperations = 0;
    size_t remainingPlayerCount = 0;
    {
        std::unique_lock<std::mutex> registryLock(g_player_registry_mutex);
        const auto iterator = g_player_registry.find(handle);
        if (iterator == g_player_registry.end()) {
            return wasPlayerHandleIssued(handle)
                   ? jsonSuccess("player already released")
                   : jsonError(-1, "invalid player handle");
        }
        entry = iterator->second;
        {
            std::lock_guard<std::mutex> entryLock(entry->lifetimeMutex);
            entry->closing = true;
            activeOperations = entry->activeOperations;
        }
        g_player_registry.erase(iterator);
        remainingPlayerCount = g_player_registry.size();
    }

    LOGI("release begin handle=%lld activeOperations=%u remainingPlayerCount=%zu",
         static_cast<long long>(handle), activeOperations, remainingPlayerCount);
    {
        std::unique_lock<std::mutex> entryLock(entry->lifetimeMutex);
        if (entry->activeOperations > 0) {
            LOGI("release waiting handle=%lld activeOperations=%u",
                 static_cast<long long>(handle), entry->activeOperations);
        }
        entry->lifetimeCv.wait(entryLock, [&entry] {
            return entry->activeOperations == 0;
        });
    }
    LOGI("release operations drained handle=%lld", static_cast<long long>(handle));

    const std::string result = entry->player->release();
    entry->player.reset();
    LOGI("release completed handle=%lld remainingPlayerCount=%zu",
         static_cast<long long>(handle), remainingPlayerCount);
    return result;
}

jstring nativeReleasePlayer(JNIEnv *env, jclass, jlong handle) {
    return toJString(env, releasePlayerEntry(handle));
}

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
std::string runPlayerLifetimeStressTest() {
    constexpr int kCreateReleaseCycles = 100;
    constexpr int kConcurrentReleaseCycles = 20;
    constexpr int kConcurrentOperations = 4;
    const auto succeeded = [](const std::string &result) {
        return result.find("\"success\":true") != std::string::npos;
    };

    bool createReleasePassed = true;
    bool uniqueHandles = true;
    bool staleHandleSafe = true;
    bool duplicateReleaseSafe = true;
    std::vector<jlong> issuedHandles;
    issuedHandles.reserve(kCreateReleaseCycles + kConcurrentReleaseCycles + 2);

    for (int cycle = 0; cycle < kCreateReleaseCycles; ++cycle) {
        const jlong handle = createPlayerEntry();
        if (handle == 0
            || std::find(issuedHandles.begin(), issuedHandles.end(), handle) != issuedHandles.end()) {
            createReleasePassed = false;
            uniqueHandles = false;
            break;
        }
        issuedHandles.push_back(handle);

        std::string acquireError;
        {
            PlayerOperationGuard guard = acquirePlayer(handle, acquireError);
            if (!guard || !succeeded(guard.player()->getState())) {
                createReleasePassed = false;
            }
        }
        if (!succeeded(releasePlayerEntry(handle))) {
            createReleasePassed = false;
        }
        if (!succeeded(releasePlayerEntry(handle))) {
            duplicateReleaseSafe = false;
        }
        std::string staleError;
        PlayerOperationGuard staleGuard = acquirePlayer(handle, staleError);
        if (staleGuard || staleError != "player already released") {
            staleHandleSafe = false;
        }
    }

    const jlong oldHandle = issuedHandles.empty() ? 0 : issuedHandles.front();
    const jlong newHandle = createPlayerEntry();
    bool oldHandleCannotTargetNewPlayer = oldHandle > 0 && newHandle > 0 && oldHandle != newHandle;
    if (newHandle > 0) {
        issuedHandles.push_back(newHandle);
        {
            std::string oldError;
            PlayerOperationGuard oldGuard = acquirePlayer(oldHandle, oldError);
            std::string newError;
            PlayerOperationGuard newGuard = acquirePlayer(newHandle, newError);
            oldHandleCannotTargetNewPlayer = oldHandleCannotTargetNewPlayer
                                             && !oldGuard && newGuard;
        }
        if (!succeeded(releasePlayerEntry(newHandle))) {
            createReleasePassed = false;
        }
    } else {
        createReleasePassed = false;
        oldHandleCannotTargetNewPlayer = false;
    }

    bool releaseWaitedForActiveOperation = false;
    bool closingRejectedNewOperations = false;
    const jlong drainHandle = createPlayerEntry();
    if (drainHandle != 0) {
        std::atomic<bool> releaseCompleted{false};
        std::string releaseResult;
        std::thread releaseThread;
        {
            std::string acquireError;
            PlayerOperationGuard heldGuard = acquirePlayer(drainHandle, acquireError);
            if (heldGuard) {
                releaseThread = std::thread([&] {
                    releaseResult = releasePlayerEntry(drainHandle);
                    releaseCompleted.store(true);
                });
                for (int attempt = 0; attempt < 100; ++attempt) {
                    std::string probeError;
                    PlayerOperationGuard probeGuard = acquirePlayer(drainHandle, probeError);
                    if (!probeGuard) {
                        closingRejectedNewOperations = true;
                        break;
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                releaseWaitedForActiveOperation = !releaseCompleted.load();
            }
        }
        if (releaseThread.joinable()) {
            releaseThread.join();
        }
        if (!succeeded(releaseResult)) {
            createReleasePassed = false;
        }
    } else {
        createReleasePassed = false;
    }

    bool concurrentReleasePassed = true;
    int64_t minimumReleaseWaitMs = INT64_MAX;
    for (int cycle = 0; cycle < kConcurrentReleaseCycles; ++cycle) {
        const jlong handle = createPlayerEntry();
        if (handle == 0) {
            concurrentReleasePassed = false;
            break;
        }

        std::mutex gateMutex;
        std::condition_variable gateCv;
        int readyOperations = 0;
        bool startOperations = false;
        std::atomic<bool> operationFailed{false};
        std::vector<std::thread> operationThreads;
        operationThreads.reserve(kConcurrentOperations);
        for (int operation = 0; operation < kConcurrentOperations; ++operation) {
            operationThreads.emplace_back([&, operation] {
                std::string acquireError;
                PlayerOperationGuard guard = acquirePlayer(handle, acquireError);
                {
                    std::unique_lock<std::mutex> gateLock(gateMutex);
                    ++readyOperations;
                    gateCv.notify_all();
                    gateCv.wait(gateLock, [&] { return startOperations; });
                }
                if (!guard) {
                    operationFailed.store(true);
                    return;
                }
                const std::string result = operation % 2 == 0
                                           ? guard.player()->getStats()
                                           : guard.player()->setThermalEnabled(operation == 1);
                if (!succeeded(result)) {
                    operationFailed.store(true);
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            });
        }

        {
            std::unique_lock<std::mutex> gateLock(gateMutex);
            gateCv.wait(gateLock, [&] { return readyOperations == kConcurrentOperations; });
            startOperations = true;
        }
        gateCv.notify_all();

        const auto releaseStart = std::chrono::steady_clock::now();
        const std::string releaseResult = releasePlayerEntry(handle);
        const int64_t releaseWaitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - releaseStart).count();
        minimumReleaseWaitMs = std::min(minimumReleaseWaitMs, releaseWaitMs);

        for (std::thread &operationThread : operationThreads) {
            operationThread.join();
        }
        if (!succeeded(releaseResult) || operationFailed.load()) {
            concurrentReleasePassed = false;
        }
    }

    size_t activePlayerCount = 0;
    {
        std::lock_guard<std::mutex> lock(g_player_registry_mutex);
        activePlayerCount = g_player_registry.size();
    }
    const bool registryEmpty = activePlayerCount == 0;
    const bool success = createReleasePassed
                         && uniqueHandles
                         && staleHandleSafe
                         && duplicateReleaseSafe
                         && oldHandleCannotTargetNewPlayer
                         && releaseWaitedForActiveOperation
                         && closingRejectedNewOperations
                         && concurrentReleasePassed
                         && registryEmpty;

    std::ostringstream out;
    out << "{\"success\":" << boolJson(success) << ","
        << "\"createReleaseCycles\":" << kCreateReleaseCycles << ","
        << "\"concurrentReleaseCycles\":" << kConcurrentReleaseCycles << ","
        << "\"uniqueHandles\":" << boolJson(uniqueHandles) << ","
        << "\"duplicateReleaseSafe\":" << boolJson(duplicateReleaseSafe) << ","
        << "\"staleHandleSafe\":" << boolJson(staleHandleSafe) << ","
        << "\"oldHandleCannotTargetNewPlayer\":" << boolJson(oldHandleCannotTargetNewPlayer) << ","
        << "\"releaseWaitedForActiveOperation\":" << boolJson(releaseWaitedForActiveOperation) << ","
        << "\"closingRejectedNewOperations\":" << boolJson(closingRejectedNewOperations) << ","
        << "\"concurrentStatsThermalReleaseSafe\":" << boolJson(concurrentReleasePassed) << ","
        << "\"minimumReleaseWaitMs\":"
        << (minimumReleaseWaitMs == INT64_MAX ? -1 : minimumReleaseWaitMs) << ","
        << "\"activePlayerCount\":" << activePlayerCount << "}";
    LOGI("player lifetime stress result=%s", out.str().c_str());
    return out.str();
}
#endif

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
            {"setPlayerEventListener", "(JLcom/example/motro/ffmpeg/FFmpegNative$PlayerEventListener;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerEventListener)},
            {"enableAudio", "(JZ)Ljava/lang/String;", reinterpret_cast<void *>(nativeEnableAudio)},
            {"setPlayerReconnectOptions", "(JZII)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerReconnectOptions)},
            {"getPlayerReconnectState", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerReconnectState)},
            {"setPlayerRtspTransport", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerRtspTransport)},
            {"getPlayerRtspTransportState", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerRtspTransportState)},
            {"setRtspTransport", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetRtspTransport)},
            {"setPlayerLatencyMode", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerLatencyMode)},
            {"setPlayerOption", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetPlayerOption)},
            {"setHardwareDecode", "(JZ)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetHardwareDecode)},
            {"setHardwareRenderMode", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetHardwareRenderMode)},
            {"setThermalEnabled", "(JZ)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetThermalEnabled)},
            {"setThermalPalette", "(JI)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetThermalPalette)},
            {"setThermalAgcEnabled", "(JZ)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetThermalAgcEnabled)},
            {"setThermalGamma", "(JF)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetThermalGamma)},
            {"setThermalWindow", "(JFF)Ljava/lang/String;", reinterpret_cast<void *>(nativeSetThermalWindow)},
            {"nativeNotifyOesFrameAvailable", "(J)V", reinterpret_cast<void *>(nativeNotifyOesFrameAvailable)},
            {"getPlayerLatencyConfig", "(J)Ljava/lang/String;", reinterpret_cast<void *>(nativeGetPlayerLatencyConfig)},
            {"startPlayerRecord", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayerRecord)},
            {"startPlayerSegmentRecord", "(JLjava/lang/String;I)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayerSegmentRecord)},
            {"startPlayerRecordWithConfig", "(JLjava/lang/String;Ljava/lang/String;I)Ljava/lang/String;", reinterpret_cast<void *>(nativeStartPlayerRecordWithConfig)},
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
    NativePlayer::setJavaVm(vm);
    NativeOesRenderer::setJavaVm(vm);
    jclass oesListenerClass = env->FindClass("com/example/motro/ffmpeg/FFmpegNative$OesFrameListener");
    if (oesListenerClass == nullptr) {
        LOGE("JNI_OnLoad OesFrameListener class not found");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    } else {
        NativeOesRenderer::setFrameListenerClass(env, oesListenerClass);
        env->DeleteLocalRef(oesListenerClass);
    }
    const int setJavaVmResult = av_jni_set_java_vm(vm, nullptr);
    g_jni_initialized = setJavaVmResult >= 0;
    if (g_jni_initialized) {
        LOGI("av_jni_set_java_vm success");
    } else {
        LOGE("av_jni_set_java_vm failed ret=%d", setJavaVmResult);
    }

    if (!registerNativeMethods(env)) {
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad success, jniInitialized=%d", g_jni_initialized);
    return JNI_VERSION_1_6;
}
