#include "NativePlayer.h"

#include "SnapshotManager.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstring>
#include <sstream>
#include <utility>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/error.h"
#include "libavutil/imgutils.h"
#include "libavutil/pixfmt.h"
#include "libavutil/rational.h"
#include "libavutil/samplefmt.h"
#include "libavutil/time.h"
#include "libswscale/swscale.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

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
                    const char *hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string jsonSuccess(const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string jsonError(int errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":" << errorCode
        << ",\"errorMessage\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string ffmpegErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (av_strerror(errorCode, buffer, sizeof(buffer)) < 0) {
        return "Unknown FFmpeg error";
    }
    return std::string(buffer);
}

const char *stateName(PlayerState state) {
    switch (state) {
        case PlayerState::Idle: return "idle";
        case PlayerState::Preparing: return "preparing";
        case PlayerState::Prepared: return "prepared";
        case PlayerState::Playing: return "playing";
        case PlayerState::Paused: return "paused";
        case PlayerState::Reconnecting: return "reconnecting";
        case PlayerState::Stopping: return "stopping";
        case PlayerState::Stopped: return "stopped";
        case PlayerState::Error: return "error";
        case PlayerState::Released: return "released";
    }
    return "unknown";
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

int64_t nowMs() {
    return av_gettime_relative() / 1000;
}
bool isNetworkUrl(const std::string &url) {
    std::string lower = url;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return lower.rfind("rtsp://", 0) == 0
           || lower.rfind("rtsps://", 0) == 0
           || lower.rfind("http://", 0) == 0
           || lower.rfind("https://", 0) == 0
           || lower.rfind("rtmp://", 0) == 0
           || lower.rfind("tcp://", 0) == 0
           || lower.rfind("udp://", 0) == 0;
}

} // namespace

NativePlayer::NativePlayer() {
    LOGI("createPlayer NativePlayer=%p", this);
}

NativePlayer::~NativePlayer() {
    release();
}

std::string NativePlayer::setSurface(JNIEnv *env, jobject surface) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    int width = 0;
    int height = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        width = videoWidth_;
        height = videoHeight_;
    }

    LOGI("setSurface player=%p width=%d height=%d", this, width, height);
    return renderer_.setSurface(env, surface, width, height);
}

std::string NativePlayer::clearSurface() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    LOGI("clearPlayerSurface player=%p", this);
    renderer_.release();
    return jsonSuccess("surface cleared");
}


int NativePlayer::openInput(const std::string &url, int timeoutMs, bool resetStreamMetadata, std::string &errorMessage) {
    if (resetStreamMetadata) {
        videoStreamIndex_ = -1;
        audioStreamIndex_ = -1;
        videoWidth_ = 0;
        videoHeight_ = 0;
        audioSampleRate_ = 0;
        audioChannels_ = 0;
        audioSampleFormat_ = -1;
        audioSampleFormatName_.clear();
        audioDecodeError_.clear();
        audioPlayError_.clear();
        sourceHasVideo_.store(false);
        sourceHasAudio_.store(false);
        audioDecodeOpened_.store(false);
        audioPlayable_.store(false);
        fps_ = 25.0;
        videoCodec_.clear();
        audioCodec_.clear();
        swsSourceFormat_ = -1;
    }

    avformat_network_init();
    formatContext_ = avformat_alloc_context();
    if (formatContext_ == nullptr) {
        errorMessage = "avformat_alloc_context failed";
        return -1;
    }
    formatContext_->interrupt_callback.callback = NativePlayer::interruptCallback;
    formatContext_->interrupt_callback.opaque = this;

    AVDictionary *options = nullptr;
    const int64_t timeoutUs = static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
    const std::string timeoutValue = std::to_string(timeoutUs);
    av_dict_set(&options, "rtsp_transport", "tcp", 0);
    av_dict_set(&options, "stimeout", timeoutValue.c_str(), 0);
    av_dict_set(&options, "timeout", timeoutValue.c_str(), 0);

    int result = avformat_open_input(&formatContext_, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("RTSP open failed url=%s error=%s", url.c_str(), errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }
    LOGI("RTSP open success url=%s", url.c_str());

    result = avformat_find_stream_info(formatContext_, nullptr);
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("avformat_find_stream_info failed: %s", errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }

    for (unsigned int i = 0; i < formatContext_->nb_streams; ++i) {
        AVStream *stream = formatContext_->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) {
            continue;
        }
        AVCodecParameters *params = stream->codecpar;
        if (params->codec_type == AVMEDIA_TYPE_VIDEO && videoStreamIndex_ < 0) {
            videoStreamIndex_ = static_cast<int>(i);
            videoWidth_ = params->width;
            videoHeight_ = params->height;
            videoCodec_ = codecName(params->codec_id);
            sourceHasVideo_.store(true);
            fps_ = rationalToDouble(stream->avg_frame_rate.num != 0 ? stream->avg_frame_rate : stream->r_frame_rate);
            if (fps_ <= 1.0 || std::isnan(fps_) || std::isinf(fps_)) {
                fps_ = 25.0;
            }
        } else if (params->codec_type == AVMEDIA_TYPE_AUDIO && audioStreamIndex_ < 0) {
            audioStreamIndex_ = static_cast<int>(i);
            audioCodec_ = codecName(params->codec_id);
            audioSampleRate_ = params->sample_rate;
            audioChannels_ = params->ch_layout.nb_channels;
            audioSampleFormat_ = params->format;
            const char *sampleFormatName = params->format >= 0 ? av_get_sample_fmt_name(static_cast<AVSampleFormat>(params->format)) : nullptr;
            audioSampleFormatName_ = sampleFormatName == nullptr ? "unknown" : sampleFormatName;
            sourceHasAudio_.store(true);
        }
    }

    if (videoStreamIndex_ < 0) {
        errorMessage = "video stream not found";
        releaseFfmpegResources();
        return -1;
    }

    AVStream *videoStream = formatContext_->streams[videoStreamIndex_];
    const AVCodec *decoder = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (decoder == nullptr) {
        errorMessage = "decoder not found for codec " + videoCodec_;
        LOGE("%s", errorMessage.c_str());
        releaseFfmpegResources();
        return -1;
    }

    videoCodecContext_ = avcodec_alloc_context3(decoder);
    if (videoCodecContext_ == nullptr) {
        errorMessage = "avcodec_alloc_context3 failed";
        releaseFfmpegResources();
        return -1;
    }

    result = avcodec_parameters_to_context(videoCodecContext_, videoStream->codecpar);
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("avcodec_parameters_to_context failed: %s", errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }

    result = avcodec_open2(videoCodecContext_, decoder, nullptr);
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("decoder open failed: %s", errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }

    packet_ = av_packet_alloc();
    decodedFrame_ = av_frame_alloc();
    rgbaFrame_ = av_frame_alloc();
    if (packet_ == nullptr || decodedFrame_ == nullptr || rgbaFrame_ == nullptr) {
        errorMessage = "failed to allocate packet/frame";
        releaseFfmpegResources();
        return -1;
    }

    LOGI("video stream index=%d codec=%s width=%d height=%d fps=%.2f", videoStreamIndex_, videoCodec_.c_str(), videoWidth_, videoHeight_, fps_);
    LOGI("decoder open success codec=%s", videoCodec_.c_str());
    if (audioStreamIndex_ >= 0) {
        AVStream *audioStream = formatContext_->streams[audioStreamIndex_];
        const AVCodec *audioDecoder = audioStream != nullptr && audioStream->codecpar != nullptr
                                      ? avcodec_find_decoder(audioStream->codecpar->codec_id)
                                      : nullptr;
        if (audioDecoder == nullptr) {
            audioDecodeError_ = "audio decoder not found for codec " + audioCodec_;
            audioPlayError_ = audioDecodeError_;
            audioDecodeOpened_.store(false);
            audioPlayable_.store(false);
            LOGE("audio decoder open skipped: %s", audioDecodeError_.c_str());
        } else {
            audioCodecContext_ = avcodec_alloc_context3(audioDecoder);
            if (audioCodecContext_ == nullptr) {
                audioDecodeError_ = "avcodec_alloc_context3 failed for audio";
                audioPlayError_ = audioDecodeError_;
                audioDecodeOpened_.store(false);
                audioPlayable_.store(false);
                LOGE("%s", audioDecodeError_.c_str());
            } else {
                result = avcodec_parameters_to_context(audioCodecContext_, audioStream->codecpar);
                if (result < 0) {
                    audioDecodeError_ = ffmpegErrorToString(result);
                    audioPlayError_ = audioDecodeError_;
                    avcodec_free_context(&audioCodecContext_);
                    audioDecodeOpened_.store(false);
                    audioPlayable_.store(false);
                    LOGE("audio avcodec_parameters_to_context failed: %s", audioDecodeError_.c_str());
                } else {
                    result = avcodec_open2(audioCodecContext_, audioDecoder, nullptr);
                    if (result < 0) {
                        audioDecodeError_ = ffmpegErrorToString(result);
                        audioPlayError_ = audioDecodeError_;
                        avcodec_free_context(&audioCodecContext_);
                        audioDecodeOpened_.store(false);
                        audioPlayable_.store(false);
                        LOGE("audio decoder open failed: %s", audioDecodeError_.c_str());
                    } else {
                        audioDecodeError_.clear();
                        audioDecodeOpened_.store(true);
                        const bool playable = audioEnabled_.load() && audioCallbackSet_.load();
                        audioPlayable_.store(playable);
                        audioPlayError_ = playable || !audioEnabled_.load() ? "" : "audio callback is not set";
                        LOGI("audio stream index=%d codec=%s sampleRate=%d channels=%d sampleFormat=%s decoderOpened=1",
                             audioStreamIndex_, audioCodec_.c_str(), audioSampleRate_, audioChannels_, audioSampleFormatName_.c_str());
                    }
                }
            }
        }
    } else {
        audioEnabled_.store(false);
        audioPlayable_.store(false);
        audioDecodeOpened_.store(false);
        audioPlayError_.clear();
        audioDecodeError_.clear();
        LOGI("prepare source has no audio stream; video-only playback/recording is allowed");
    }
    return 0;
}


std::string NativePlayer::setAudioCallback(JNIEnv *, jobject callback) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    const bool callbackSet = callback != nullptr;
    audioCallbackSet_.store(callbackSet);
    if (!callbackSet) {
        audioPlayable_.store(false);
        if (audioEnabled_.load() && sourceHasAudio_.load()) {
            audioPlayError_ = "audio callback is not set";
        }
    } else if (sourceHasAudio_.load() && audioDecodeOpened_.load() && audioEnabled_.load()) {
        audioPlayable_.store(true);
        audioPlayError_.clear();
    }

    LOGI("setAudioCallback callbackSet=%d sourceHasAudio=%d audioEnabled=%d audioPlayable=%d",
         callbackSet ? 1 : 0, sourceHasAudio_.load() ? 1 : 0,
         audioEnabled_.load() ? 1 : 0, audioPlayable_.load() ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"audio callback updated\","
        << "\"audioCallbackSet\":" << (audioCallbackSet_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"audioPlayable\":" << (audioPlayable_.load() ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::enableAudio(bool enabled) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    if (!enabled) {
        audioEnabled_.store(false);
        audioPlayable_.store(false);
        audioPlayError_.clear();
    } else if (!sourceHasAudio_.load()) {
        audioEnabled_.store(false);
        audioPlayable_.store(false);
        audioPlayError_.clear();
    } else {
        audioEnabled_.store(true);
        const bool playable = audioDecodeOpened_.load() && audioCallbackSet_.load();
        audioPlayable_.store(playable);
        if (!audioDecodeOpened_.load()) {
            audioPlayError_ = audioDecodeError_.empty() ? "audio decoder not opened" : audioDecodeError_;
        } else if (!audioCallbackSet_.load()) {
            audioPlayError_ = "audio callback is not set";
        } else {
            audioPlayError_.clear();
        }
    }

    LOGI("enableAudio requested=%d sourceHasAudio=%d audioEnabled=%d audioPlayable=%d error=%s",
         enabled ? 1 : 0, sourceHasAudio_.load() ? 1 : 0,
         audioEnabled_.load() ? 1 : 0, audioPlayable_.load() ? 1 : 0, audioPlayError_.c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"audio option updated\","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"audioEnabled\":" << (audioEnabled_.load() ? "true" : "false") << ","
        << "\"audioPlayable\":" << (audioPlayable_.load() ? "true" : "false") << ","
        << "\"audioDecodeOpened\":" << (audioDecodeOpened_.load() ? "true" : "false") << ","
        << "\"audioCallbackSet\":" << (audioCallbackSet_.load() ? "true" : "false") << ","
        << "\"audioRecordingIndependentOfPlayback\":true,"
        << "\"audioPlayError\":\"" << escapeJson(audioPlayError_) << "\"}";
    return out.str();
}
std::string NativePlayer::prepare(const std::string &url, int timeoutMs) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (url.empty()) {
        return jsonError(-1, "url is empty");
    }

    stop();
    resetStats();
    clearLastFrame();

    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = PlayerState::Preparing;
        url_ = url;
        timeoutMs_ = std::max(timeoutMs, 1);
        errorMessage_.clear();
        lastReconnectError_.clear();
    }

    LOGI("prepare url=%s timeoutMs=%d", url.c_str(), timeoutMs);

    std::string error;
    const int result = openInput(url, timeoutMs_, true, error);
    if (result < 0) {
        setState(PlayerState::Error, error);
        return jsonError(result, error);
    }

    setState(PlayerState::Prepared);

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"player prepared\","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"videoWidth\":" << videoWidth_ << ","
        << "\"videoHeight\":" << videoHeight_ << ","
        << "\"fps\":" << fps_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnectMaxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"reconnectRetryDelayMs\":" << reconnectRetryDelayMs_.load() << "}";
    return out.str();
}

std::string NativePlayer::start() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (!renderer_.hasSurface()) {
        return jsonError(-1, "Surface is not set");
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == PlayerState::Playing) {
        return jsonSuccess("player already playing");
    }
    if (state_ == PlayerState::Paused && playbackThread_.joinable()) {
        pauseRequested_.store(false);
        state_ = PlayerState::Playing;
        LOGI("startPlayer resume player=%p", this);
        return jsonSuccess("player resumed");
    }
    if (state_ != PlayerState::Prepared) {
        return jsonError(-1, "player is not prepared");
    }
    if (playbackThread_.joinable()) {
        return jsonError(-1, "playback thread is already running");
    }

    stopRequested_.store(false);
    pauseRequested_.store(false);
    startPlayTimeMs_.store(nowMs());
    state_ = PlayerState::Playing;
    playbackThread_ = std::thread(&NativePlayer::playbackLoop, this);
    LOGI("startPlayer player=%p", this);
    return jsonSuccess("player started");
}

std::string NativePlayer::pause() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != PlayerState::Playing) {
        return jsonError(-1, "player is not playing");
    }
    pauseRequested_.store(true);
    state_ = PlayerState::Paused;
    LOGI("pausePlayer player=%p", this);
    return jsonSuccess("player paused");
}

std::string NativePlayer::stop() {
    if (isReleased()) {
        LOGI("stopPlayer ignored: player already released");
        return jsonError(-1, "player is released");
    }

    const bool shouldJoin = playbackThread_.joinable();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Idle && !shouldJoin && formatContext_ == nullptr) {
            state_ = PlayerState::Stopped;
            return jsonSuccess("player stopped");
        }
        if (state_ != PlayerState::Released) {
            state_ = PlayerState::Stopping;
        }
        stopRequested_.store(true);
        pauseRequested_.store(false);
    }

    if (shouldJoin) {
        playbackThread_.join();
    }

    if (remuxRecorder_.isRecording()) {
        LOGI("stopPlayer auto stop active recorder");
    }
    remuxRecorder_.stop();

    releaseFfmpegResources();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Released) {
            state_ = PlayerState::Stopped;
        }
    }
    LOGI("stopPlayer player=%p", this);
    return jsonSuccess("player stopped");
}

std::string NativePlayer::getState() {
    std::lock_guard<std::mutex> lock(mutex_);
    return buildStateJsonLocked();
}

std::string NativePlayer::getStats() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    PlayerState state;
    std::string url;
    std::string lastError;
    std::string reconnectError;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
        url = url_;
        lastError = errorMessage_;
        reconnectError = lastReconnectError_;
    }

    int frameWidth = 0;
    int frameHeight = 0;
    bool hasFrame = false;
    {
        std::lock_guard<std::mutex> lock(lastFrameMutex_);
        hasFrame = hasLastFrame_;
        frameWidth = lastFrameWidth_;
        frameHeight = lastFrameHeight_;
    }

    LOGI("getPlayerStats player=%p", this);
    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"state\":\"" << stateName(state) << "\","
        << "\"url\":\"" << escapeJson(url) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
        << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
        << "\"audioSampleRate\":" << audioSampleRate_ << ","
        << "\"audioChannels\":" << audioChannels_ << ","
        << "\"audioSampleFormat\":\"" << escapeJson(audioSampleFormatName_) << "\","
        << "\"readPacketCount\":" << readPacketCount_.load() << ","
        << "\"videoPacketCount\":" << videoPacketCount_.load() << ","
        << "\"audioPacketCount\":" << audioPacketCount_.load() << ","
        << "\"videoFrameCount\":" << videoFrameCount_.load() << ","
        << "\"audioFrameCount\":" << audioFrameCount_.load() << ","
        << "\"renderedFrameCount\":" << renderedFrameCount_.load() << ","
        << "\"droppedVideoFrameCount\":" << droppedVideoFrameCount_.load() << ","
        << "\"recording\":" << (remuxRecorder_.isRecording() ? "true" : "false") << ","
        << "\"recordVideoPacketCount\":" << remuxRecorder_.getVideoPacketCount() << ","
        << "\"recordAudioPacketCount\":" << remuxRecorder_.getAudioPacketCount() << ","
        << "\"recordCompletedSegmentCount\":" << remuxRecorder_.getCompletedSegmentCount() << ","
        << "\"surfaceAttached\":" << (renderer_.hasSurface() ? "true" : "false") << ","
        << "\"hasLastFrame\":" << (hasFrame ? "true" : "false") << ","
        << "\"lastFrameWidth\":" << frameWidth << ","
        << "\"lastFrameHeight\":" << frameHeight << ","
        << "\"audioEnabled\":" << (audioEnabled_.load() ? "true" : "false") << ","
        << "\"audioClockUs\":" << audioClockUs_.load() << ","
        << "\"videoClockUs\":" << videoClockUs_.load() << ","
        << "\"lastReadPacketTimeMs\":" << lastReadPacketTimeMs_.load() << ","
        << "\"lastVideoFrameTimeMs\":" << lastVideoFrameTimeMs_.load() << ","
        << "\"lastAudioFrameTimeMs\":" << lastAudioFrameTimeMs_.load() << ","
        << "\"lastRenderTimeMs\":" << lastRenderTimeMs_.load() << ","
        << "\"lastSnapshotTimeMs\":" << lastSnapshotTimeMs_.load() << ","
        << "\"startPlayTimeMs\":" << startPlayTimeMs_.load() << ","
        << "\"lastError\":\"" << escapeJson(lastError) << "\"," 
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"reconnectMaxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"reconnectRetryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"reconnectAttemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectSuccessCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectTimeMs\":" << lastReconnectTimeMs_.load() << ","
        << "\"lastReconnectError\":\"" << escapeJson(reconnectError) << "\"}";
    return out.str();
}


std::string NativePlayer::setReconnectOptions(bool enabled, int maxRetryCount, int retryDelayMs) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    const int safeMaxRetryCount = std::clamp(maxRetryCount, 0, 100);
    const int safeRetryDelayMs = std::clamp(retryDelayMs, 100, 60000);
    reconnectEnabled_.store(enabled);
    reconnectMaxRetryCount_.store(safeMaxRetryCount);
    reconnectRetryDelayMs_.store(safeRetryDelayMs);
    LOGI("setPlayerReconnectOptions enabled=%d maxRetry=%d retryDelayMs=%d", enabled ? 1 : 0, safeMaxRetryCount, safeRetryDelayMs);

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"reconnect options updated\","
        << "\"enabled\":" << (enabled ? "true" : "false") << ","
        << "\"maxRetryCount\":" << safeMaxRetryCount << ","
        << "\"retryDelayMs\":" << safeRetryDelayMs << "}";
    return out.str();
}

std::string NativePlayer::getReconnectState() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    return buildReconnectJson();
}

std::string NativePlayer::takeSnapshot(const std::string &outputPath) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    std::vector<uint8_t> frameCopy;
    int width = 0;
    int height = 0;
    int stride = 0;
    int64_t ptsUs = 0;
    {
        std::lock_guard<std::mutex> lock(lastFrameMutex_);
        if (!hasLastFrame_ || lastRgbaFrame_.empty()) {
            LOGE("takePlayerSnapshot failed: no video frame available");
            return jsonError(-1, "no video frame available");
        }
        frameCopy = lastRgbaFrame_;
        width = lastFrameWidth_;
        height = lastFrameHeight_;
        stride = lastFrameStride_;
        ptsUs = lastFramePtsUs_;
    }

    LOGI("takePlayerSnapshot outputPath=%s hasFrame=1 width=%d height=%d ptsUs=%lld",
         outputPath.c_str(), width, height, static_cast<long long>(ptsUs));
    const std::string result = SnapshotManager::saveRgba(outputPath, frameCopy, width, height, stride, ptsUs);
    if (result.find("\"success\":true") != std::string::npos) {
        lastSnapshotTimeMs_.store(nowMs());
    }
    return result;
}

std::string NativePlayer::startRecord(const std::string &outputPath) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    AVFormatContext *input = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared && state_ != PlayerState::Playing && state_ != PlayerState::Paused) {
            return jsonError(-1, "player is not prepared");
        }
        input = formatContext_;
    }

    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());
    if (sourceHasAudio_.load() && !audioEnabled_.load()) {
        LOGI("AudioTrack disabled but audio remux recording remains enabled by source audio stream");
    }
    LOGI("startPlayerRecord outputPath=%s sourceHasAudio=%d audioPlaybackEnabled=%d", outputPath.c_str(), sourceHasAudio_.load() ? 1 : 0, audioEnabled_.load() ? 1 : 0);
    return remuxRecorder_.start(input, outputPath);
}


std::string NativePlayer::startSegmentRecord(const std::string &outputPattern, int segmentDurationSec) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    AVFormatContext *input = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared && state_ != PlayerState::Playing && state_ != PlayerState::Paused) {
            return jsonError(-1, "player is not prepared");
        }
        input = formatContext_;
    }

    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());
    if (sourceHasAudio_.load() && !audioEnabled_.load()) {
        LOGI("AudioTrack disabled but segmented audio remux recording remains enabled by source audio stream");
    }
    LOGI("startPlayerSegmentRecord outputPattern=%s segmentDurationSec=%d sourceHasAudio=%d audioPlaybackEnabled=%d", outputPattern.c_str(), segmentDurationSec, sourceHasAudio_.load() ? 1 : 0, audioEnabled_.load() ? 1 : 0);
    return remuxRecorder_.startSegmented(input, outputPattern, segmentDurationSec);
}
std::string NativePlayer::stopRecord() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    LOGI("stopPlayerRecord requested");
    return remuxRecorder_.stop();
}

std::string NativePlayer::getRecordState() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    return remuxRecorder_.getState();
}

std::string NativePlayer::release() {
    if (released_.load()) {
        LOGI("releasePlayer ignored: already released player=%p", this);
        return jsonSuccess("player already released");
    }

    stop();
    remuxRecorder_.release();
    renderer_.release();
    clearLastFrame();
    releaseFfmpegResources();

    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = PlayerState::Released;
    }
    released_.store(true);
    LOGI("releasePlayer player=%p", this);
    return jsonSuccess("player released");
}

int NativePlayer::interruptCallback(void *opaque) {
    auto *player = static_cast<NativePlayer *>(opaque);
    if (player == nullptr) {
        return 0;
    }
    return player->stopRequested_.load() ? 1 : 0;
}


bool NativePlayer::waitForReconnectDelay(int delayMs) {
    int remainingMs = std::max(delayMs, 0);
    while (remainingMs > 0 && !stopRequested_.load()) {
        const int stepMs = std::min(remainingMs, 100);
        std::this_thread::sleep_for(std::chrono::milliseconds(stepMs));
        remainingMs -= stepMs;
    }
    return !stopRequested_.load();
}

bool NativePlayer::reconnectInput(int readErrorCode) {
    if (!reconnectEnabled_.load() || !isNetworkUrl(url_)) {
        return false;
    }

    const int maxRetryCount = reconnectMaxRetryCount_.load();
    if (maxRetryCount <= 0) {
        return false;
    }

    const std::string readError = ffmpegErrorToString(readErrorCode);
    LOGE("playback disconnected url=%s error=%s, start reconnect", url_.c_str(), readError.c_str());
    if (remuxRecorder_.isRecording()) {
        LOGI("reconnect while recorder active; remux recorder keeps output context and resumes when packets return");
    }

    for (int attempt = 1; attempt <= maxRetryCount && !stopRequested_.load(); ++attempt) {
        reconnecting_.store(true);
        lastReconnectTimeMs_.store(nowMs());
        reconnectAttemptCount_.fetch_add(1);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                state_ = PlayerState::Reconnecting;
                errorMessage_ = readError;
                lastReconnectError_ = readError;
            }
        }

        const int retryDelayMs = reconnectRetryDelayMs_.load();
        LOGI("reconnect attempt %d/%d delayMs=%d url=%s", attempt, maxRetryCount, retryDelayMs, url_.c_str());
        if (!waitForReconnectDelay(retryDelayMs)) {
            break;
        }

        releaseFfmpegResources();
        std::string error;
        const int result = openInput(url_, timeoutMs_, true, error);
        if (result >= 0) {
            reconnectSuccessCount_.fetch_add(1);
            reconnecting_.store(false);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                    state_ = pauseRequested_.load() ? PlayerState::Paused : PlayerState::Playing;
                    errorMessage_.clear();
                    lastReconnectError_.clear();
                }
            }
            LOGI("reconnect success attempt=%d url=%s", attempt, url_.c_str());
            return true;
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            lastReconnectError_ = error;
            errorMessage_ = error;
        }
        LOGE("reconnect failed attempt=%d/%d url=%s error=%s", attempt, maxRetryCount, url_.c_str(), error.c_str());
    }

    reconnecting_.store(false);
    std::string finalError;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        finalError = lastReconnectError_.empty() ? readError : lastReconnectError_;
    }
    if (stopRequested_.load()) {
        return false;
    }
    setState(PlayerState::Error, finalError);
    LOGE("reconnect exhausted url=%s error=%s", url_.c_str(), finalError.c_str());
    return false;
}

void NativePlayer::playbackLoop() {
    LOGI("playback thread started player=%p", this);
    const int frameDelayMs = static_cast<int>(std::clamp(1000.0 / std::max(fps_, 1.0), 5.0, 100.0));

    while (!stopRequested_.load()) {
        if (pauseRequested_.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        const int readResult = av_read_frame(formatContext_, packet_);
        lastReadPacketTimeMs_.store(nowMs());
        if (readResult < 0) {
            const bool shouldReconnectEof = readResult == AVERROR_EOF && reconnectEnabled_.load() && isNetworkUrl(url_);
            if (stopRequested_.load() || (readResult == AVERROR_EOF && !shouldReconnectEof)) {
                break;
            }
            const std::string error = ffmpegErrorToString(readResult);
            LOGE("av_read_frame error: %s", error.c_str());
            if (reconnectInput(readResult)) {
                continue;
            }
            if (!stopRequested_.load()) {
                setState(PlayerState::Error, error);
            }
            break;
        }

        readPacketCount_.fetch_add(1);
        if (packet_->stream_index == videoStreamIndex_) {
            videoPacketCount_.fetch_add(1);
        } else if (packet_->stream_index == audioStreamIndex_) {
            audioPacketCount_.fetch_add(1);
            lastAudioFrameTimeMs_.store(nowMs());
            if (formatContext_ != nullptr && audioStreamIndex_ >= 0 && packet_->pts != AV_NOPTS_VALUE) {
                audioClockUs_.store(av_rescale_q(packet_->pts, formatContext_->streams[audioStreamIndex_]->time_base, AV_TIME_BASE_Q));
            }
        }

        if (remuxRecorder_.isRecording()) {
            remuxRecorder_.onPacket(packet_, formatContext_);
        }

        if (packet_->stream_index == videoStreamIndex_) {
            int result = avcodec_send_packet(videoCodecContext_, packet_);
            if (result < 0) {
                const std::string error = ffmpegErrorToString(result);
                LOGE("avcodec_send_packet error: %s", error.c_str());
                av_packet_unref(packet_);
                continue;
            }

            while (!stopRequested_.load()) {
                result = avcodec_receive_frame(videoCodecContext_, decodedFrame_);
                if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                    break;
                }
                if (result < 0) {
                    const std::string error = ffmpegErrorToString(result);
                    LOGE("avcodec_receive_frame error: %s", error.c_str());
                    break;
                }

                if (renderFrame(decodedFrame_)) {
                    videoFrameCount_.fetch_add(1);
                }
                av_frame_unref(decodedFrame_);
                std::this_thread::sleep_for(std::chrono::milliseconds(frameDelayMs));
            }
        }

        av_packet_unref(packet_);
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != PlayerState::Error && state_ != PlayerState::Released) {
        state_ = PlayerState::Stopped;
    }
    LOGI("playback thread ended player=%p", this);
}

bool NativePlayer::renderFrame(AVFrame *frame) {
    if (frame == nullptr || videoCodecContext_ == nullptr) {
        return false;
    }

    const int frameWidth = frame->width > 0 ? frame->width : videoCodecContext_->width;
    const int frameHeight = frame->height > 0 ? frame->height : videoCodecContext_->height;
    const auto sourceFormat = static_cast<AVPixelFormat>(frame->format);

    if (frameWidth <= 0 || frameHeight <= 0 || sourceFormat == AV_PIX_FMT_NONE) {
        LOGE("invalid decoded frame width=%d height=%d format=%d", frameWidth, frameHeight, frame->format);
        return false;
    }

    if (swsContext_ == nullptr || swsSourceFormat_ != frame->format || videoWidth_ != frameWidth || videoHeight_ != frameHeight) {
        if (swsContext_ != nullptr) {
            sws_freeContext(swsContext_);
            swsContext_ = nullptr;
        }

        swsContext_ = sws_getContext(frameWidth, frameHeight, sourceFormat,
                                     frameWidth, frameHeight, AV_PIX_FMT_RGBA,
                                     SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (swsContext_ == nullptr) {
            LOGE("sws_getContext failed");
            return false;
        }

        const int bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, frameWidth, frameHeight, 1);
        if (bufferSize <= 0) {
            LOGE("av_image_get_buffer_size failed: %d", bufferSize);
            return false;
        }
        rgbaBuffer_.assign(static_cast<size_t>(bufferSize), 0);
        const int fillResult = av_image_fill_arrays(rgbaFrame_->data, rgbaFrame_->linesize,
                                                    rgbaBuffer_.data(), AV_PIX_FMT_RGBA,
                                                    frameWidth, frameHeight, 1);
        if (fillResult < 0) {
            LOGE("av_image_fill_arrays failed: %s", ffmpegErrorToString(fillResult).c_str());
            return false;
        }

        swsSourceFormat_ = frame->format;
        videoWidth_ = frameWidth;
        videoHeight_ = frameHeight;
    }

    sws_scale(swsContext_, frame->data, frame->linesize, 0, frameHeight,
              rgbaFrame_->data, rgbaFrame_->linesize);

    int64_t ptsUs = 0;
    if (formatContext_ != nullptr && videoStreamIndex_ >= 0 && frame->best_effort_timestamp != AV_NOPTS_VALUE) {
        ptsUs = av_rescale_q(frame->best_effort_timestamp, formatContext_->streams[videoStreamIndex_]->time_base, AV_TIME_BASE_Q);
    }
    videoClockUs_.store(ptsUs);
    lastVideoFrameTimeMs_.store(nowMs());
    saveLastFrame(rgbaFrame_->data[0], rgbaFrame_->linesize[0], frameWidth, frameHeight, ptsUs);

    if (!renderer_.hasSurface()) {
        return true;
    }

    const RenderResult result = renderer_.renderRgba(rgbaFrame_->data[0], rgbaFrame_->linesize[0], frameWidth, frameHeight);
    if (!result.success) {
        LOGE("renderRgba failed: %s (%d)", result.errorMessage.c_str(), result.errorCode);
        droppedVideoFrameCount_.fetch_add(1);
        return true;
    }
    renderedFrameCount_.fetch_add(1);
    lastRenderTimeMs_.store(nowMs());
    return true;
}

void NativePlayer::saveLastFrame(const uint8_t *rgbaData, int lineSize, int width, int height, int64_t ptsUs) {
    if (rgbaData == nullptr || lineSize <= 0 || width <= 0 || height <= 0) {
        return;
    }

    const int targetStride = width * 4;
    std::lock_guard<std::mutex> lock(lastFrameMutex_);
    lastRgbaFrame_.assign(static_cast<size_t>(targetStride) * static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        std::memcpy(lastRgbaFrame_.data() + static_cast<size_t>(y) * static_cast<size_t>(targetStride),
                    rgbaData + static_cast<size_t>(y) * static_cast<size_t>(lineSize),
                    static_cast<size_t>(targetStride));
    }
    lastFrameWidth_ = width;
    lastFrameHeight_ = height;
    lastFrameStride_ = targetStride;
    lastFramePtsUs_ = ptsUs;
    hasLastFrame_ = true;
}

void NativePlayer::clearLastFrame() {
    std::lock_guard<std::mutex> lock(lastFrameMutex_);
    lastRgbaFrame_.clear();
    lastFrameWidth_ = 0;
    lastFrameHeight_ = 0;
    lastFrameStride_ = 0;
    lastFramePtsUs_ = 0;
    hasLastFrame_ = false;
}

void NativePlayer::resetStats() {
    readPacketCount_.store(0);
    videoPacketCount_.store(0);
    audioPacketCount_.store(0);
    videoFrameCount_.store(0);
    audioFrameCount_.store(0);
    renderedFrameCount_.store(0);
    droppedVideoFrameCount_.store(0);
    lastReadPacketTimeMs_.store(0);
    lastVideoFrameTimeMs_.store(0);
    lastAudioFrameTimeMs_.store(0);
    lastRenderTimeMs_.store(0);
    lastSnapshotTimeMs_.store(0);
    startPlayTimeMs_.store(0);
    audioClockUs_.store(0);
    videoClockUs_.store(0);
    reconnecting_.store(false);
    reconnectAttemptCount_.store(0);
    reconnectSuccessCount_.store(0);
    lastReconnectTimeMs_.store(0);
    lastReconnectError_.clear();
}

void NativePlayer::releaseFfmpegResources() {
    if (swsContext_ != nullptr) {
        sws_freeContext(swsContext_);
        swsContext_ = nullptr;
    }
    if (packet_ != nullptr) {
        av_packet_free(&packet_);
    }
    if (decodedFrame_ != nullptr) {
        av_frame_free(&decodedFrame_);
    }
    if (rgbaFrame_ != nullptr) {
        av_frame_free(&rgbaFrame_);
    }
    rgbaBuffer_.clear();
    if (videoCodecContext_ != nullptr) {
        avcodec_free_context(&videoCodecContext_);
    }
    if (formatContext_ != nullptr) {
        avformat_close_input(&formatContext_);
    }
    videoStreamIndex_ = -1;
    audioStreamIndex_ = -1;
    audioSampleRate_ = 0;
    audioChannels_ = 0;
    audioSampleFormat_ = -1;
    audioSampleFormatName_.clear();
    audioDecodeOpened_.store(false);
    audioPlayable_.store(false);
    sourceHasVideo_.store(false);
    sourceHasAudio_.store(false);
    swsSourceFormat_ = -1;
}

void NativePlayer::setState(PlayerState state, const std::string &errorMessage) {
    std::lock_guard<std::mutex> lock(mutex_);
    state_ = state;
    if (!errorMessage.empty()) {
        errorMessage_ = errorMessage;
    }
}

std::string NativePlayer::buildStateJsonLocked() const {
    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"state\":\"" << stateName(state_) << "\","
        << "\"url\":\"" << escapeJson(url_) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"videoWidth\":" << videoWidth_ << ","
        << "\"videoHeight\":" << videoHeight_ << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
        << "\"videoFrameCount\":" << videoFrameCount_.load() << ","
        << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
        << "\"audioSampleRate\":" << audioSampleRate_ << ","
        << "\"audioChannels\":" << audioChannels_ << ","
        << "\"fps\":" << fps_ << ","
        << "\"errorMessage\":\"" << escapeJson(errorMessage_) << "\"," 
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"reconnectAttemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectSuccessCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectError\":\"" << escapeJson(lastReconnectError_) << "\"}";
    return out.str();
}


std::string NativePlayer::buildReconnectJson() const {
    PlayerState state;
    std::string lastError;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
        lastError = lastReconnectError_;
    }

    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"state\":\"" << stateName(state) << "\"," 
        << "\"enabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"maxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"retryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"attemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"successCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectTimeMs\":" << lastReconnectTimeMs_.load() << ","
        << "\"lastError\":\"" << escapeJson(lastError) << "\"}";
    return out.str();
}
bool NativePlayer::isReleased() const {
    return released_.load();
}