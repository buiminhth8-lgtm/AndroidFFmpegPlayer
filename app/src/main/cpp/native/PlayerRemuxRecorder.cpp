#include "PlayerRemuxRecorder.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <iomanip>
#include <sstream>
#include <sys/stat.h>

extern "C" {
#include "libavcodec/codec_par.h"
#include "libavcodec/packet.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/error.h"
#include "libavutil/mathematics.h"
#include "libavutil/time.h"
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

const char *stateName(RecorderState state) {
    switch (state) {
        case RecorderState::Idle: return "idle";
        case RecorderState::Starting: return "starting";
        case RecorderState::WaitingKeyFrame: return "waiting_keyframe";
        case RecorderState::Recording: return "recording";
        case RecorderState::Stopping: return "stopping";
        case RecorderState::Stopped: return "stopped";
        case RecorderState::Error: return "error";
        case RecorderState::Released: return "released";
    }
    return "unknown";
}

std::string lowerCopy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool endsWith(const std::string &value, const std::string &suffix) {
    if (value.size() < suffix.size()) {
        return false;
    }
    return lowerCopy(value.substr(value.size() - suffix.size())) == suffix;
}

const char *formatNameForPath(const std::string &path) {
    if (endsWith(path, ".ts")) {
        return "mpegts";
    }
    if (endsWith(path, ".mp4")) {
        return "mp4";
    }
    return nullptr;
}

std::string parentDirectory(const std::string &path) {
    const size_t slash = path.find_last_of("/\\");
    if (slash == std::string::npos) {
        return std::string();
    }
    if (slash == 0) {
        return path.substr(0, 1);
    }
    return path.substr(0, slash);
}

bool directoryExists(const std::string &path) {
    struct stat info = {};
    return !path.empty() && stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

bool isRecorderActive(RecorderState state) {
    return state == RecorderState::Starting
           || state == RecorderState::WaitingKeyFrame
           || state == RecorderState::Recording;
}

int64_t elapsedUs(int64_t startTimeUs, int64_t stopTimeUs) {
    if (startTimeUs <= 0) {
        return 0;
    }
    const int64_t end = stopTimeUs > 0 ? stopTimeUs : av_gettime_relative();
    return std::max<int64_t>(0, end - startTimeUs);
}

bool hasPrintfIntegerPlaceholder(const std::string &pattern) {
    for (size_t pos = pattern.find('%'); pos != std::string::npos; pos = pattern.find('%', pos + 1)) {
        if (pos + 1 < pattern.size() && pattern[pos + 1] == '%') {
            ++pos;
            continue;
        }
        size_t i = pos + 1;
        while (i < pattern.size() && std::string("-+ #0").find(pattern[i]) != std::string::npos) {
            ++i;
        }
        while (i < pattern.size() && std::isdigit(static_cast<unsigned char>(pattern[i]))) {
            ++i;
        }
        if (i < pattern.size() && (pattern[i] == 'd' || pattern[i] == 'i' || pattern[i] == 'u')) {
            return true;
        }
    }
    return false;
}

std::string insertSegmentIndex(const std::string &path, int displayIndex) {
    const size_t slash = path.find_last_of("/\\");
    const size_t dot = path.find_last_of('.');
    const bool hasExtension = dot != std::string::npos && (slash == std::string::npos || dot > slash);
    const std::string prefix = hasExtension ? path.substr(0, dot) : path;
    const std::string suffix = hasExtension ? path.substr(dot) : std::string();

    std::ostringstream out;
    out << prefix << "_" << std::setw(6) << std::setfill('0') << displayIndex << suffix;
    return out.str();
}

int64_t packetTimestampUs(const AVPacket *packet, AVStream *stream) {
    if (packet == nullptr || stream == nullptr) {
        return AV_NOPTS_VALUE;
    }
    int64_t timestamp = packet->pts;
    if (timestamp == AV_NOPTS_VALUE) {
        timestamp = packet->dts;
    }
    if (timestamp == AV_NOPTS_VALUE) {
        return AV_NOPTS_VALUE;
    }
    return av_rescale_q(timestamp, stream->time_base, AV_TIME_BASE_Q);
}

} // namespace

PlayerRemuxRecorder::PlayerRemuxRecorder() = default;

PlayerRemuxRecorder::~PlayerRemuxRecorder() {
    release();
}

std::string PlayerRemuxRecorder::start(AVFormatContext *inputFmtCtx, const std::string &outputPath) {
    std::lock_guard<std::mutex> lock(mutex_);
    return startLocked(inputFmtCtx, outputPath, false, 0);
}

std::string PlayerRemuxRecorder::startSegmented(AVFormatContext *inputFmtCtx,
                                                const std::string &outputPattern,
                                                int segmentDurationSec) {
    std::lock_guard<std::mutex> lock(mutex_);
    return startLocked(inputFmtCtx, outputPattern, true, segmentDurationSec);
}

std::string PlayerRemuxRecorder::startLocked(AVFormatContext *inputFmtCtx,
                                             const std::string &outputPathOrPattern,
                                             bool segmentMode,
                                             int segmentDurationSec) {
    if (state_ == RecorderState::Released) {
        return jsonError(-1, "recorder is released");
    }
    if (isRecorderActive(state_)) {
        return jsonError(-1, "recorder is already recording");
    }
    if (inputFmtCtx == nullptr) {
        return jsonError(-1, "input format context is null; player is not prepared");
    }
    if (outputPathOrPattern.empty()) {
        return jsonError(-1, segmentMode ? "outputPattern is empty" : "outputPath is empty");
    }
    if (segmentMode && segmentDurationSec <= 0) {
        return jsonError(-1, "segmentDurationSec must be greater than 0");
    }

    segmentMode_ = segmentMode;
    outputPattern_ = outputPathOrPattern;
    currentSegmentIndex_ = 0;
    const std::string firstOutputPath = segmentMode_ ? makeSegmentPathLocked(currentSegmentIndex_) : outputPathOrPattern;
    const std::string parent = parentDirectory(firstOutputPath);
    if (!directoryExists(parent)) {
        return jsonError(-1, "output parent directory does not exist: " + parent);
    }

    resetLocked(false);
    segmentMode_ = segmentMode;
    outputPattern_ = outputPathOrPattern;
    outputPath_ = firstOutputPath;
    currentSegmentPath_ = firstOutputPath;
    currentSegmentIndex_ = 0;
    segmentDurationUs_ = segmentMode_ ? static_cast<int64_t>(segmentDurationSec) * AV_TIME_BASE : 0;
    startTimeUs_ = av_gettime_relative();
    stopTimeUs_ = 0;
    state_ = RecorderState::Starting;

    const int result = openOutputLocked(inputFmtCtx, firstOutputPath);
    if (result < 0) {
        return jsonError(lastErrorCode_, lastError_);
    }

    LOGI("startPlayerRecord outputPath=%s segmentMode=%d segmentDurationUs=%lld format=%s inputStreamCount=%u",
         firstOutputPath.c_str(), segmentMode_ ? 1 : 0, static_cast<long long>(segmentDurationUs_),
         formatName_.c_str(), inputFmtCtx->nb_streams);
    if (formatName_ == "mp4") {
        LOGI("mp4 recording requires normal stopPlayerRecord/av_write_trailer to finalize file");
    }

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\""
        << (segmentMode_ ? "player segmented remux recording started" : "player remux recording started") << "\","
        << "\"outputPath\":\"" << escapeJson(outputPath_) << "\","
        << "\"outputPattern\":\"" << escapeJson(outputPattern_) << "\","
        << "\"currentSegmentPath\":\"" << escapeJson(currentSegmentPath_) << "\","
        << "\"segmentMode\":" << (segmentMode_ ? "true" : "false") << ","
        << "\"segmentDurationUs\":" << segmentDurationUs_ << ","
        << "\"format\":\"" << escapeJson(formatName_) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_ ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_ ? "true" : "false") << ","
        << "\"videoStreamRecorded\":" << (videoStreamRecorded_ ? "true" : "false") << ","
        << "\"audioStreamRecorded\":" << (audioStreamRecorded_ ? "true" : "false") << ","
        << "\"audioPlaybackEnabled\":" << (audioPlaybackEnabled_ ? "true" : "false") << ","
        << "\"audioRecordingIndependentOfPlayback\":true}";
    return out.str();
}

int PlayerRemuxRecorder::openOutputLocked(AVFormatContext *inputFmtCtx, const std::string &outputPath) {
    outputPath_ = outputPath;
    currentSegmentPath_ = outputPath;
    videoInputStreamIndex_ = -1;
    audioInputStreamIndex_ = -1;
    hasVideo_ = false;
    sourceHasVideo_ = false;
    sourceHasAudio_ = false;
    videoStreamRecorded_ = false;
    audioStreamRecorded_ = false;
    waitingForKeyFrame_ = false;
    headerWritten_ = false;
    segmentStartPtsUs_ = AV_NOPTS_VALUE;
    currentSegmentStartTimeUs_ = av_gettime_relative();
    currentSegmentVideoPacketCount_ = 0;
    currentSegmentAudioPacketCount_ = 0;

    const char *requestedFormatName = formatNameForPath(outputPath);
    int result = avformat_alloc_output_context2(&outputFmtCtx_, nullptr, requestedFormatName, outputPath.c_str());
    if (result < 0 || outputFmtCtx_ == nullptr) {
        const std::string error = result < 0 ? ffmpegErrorToString(result) : "avformat_alloc_output_context2 failed";
        closeOutputLocked(false);
        setErrorLocked(error, result < 0 ? result : -1);
        return lastErrorCode_;
    }

    formatName_ = outputFmtCtx_->oformat && outputFmtCtx_->oformat->name ? outputFmtCtx_->oformat->name : "unknown";
    streamMapping_.assign(inputFmtCtx->nb_streams, -1);
    firstPts_.assign(inputFmtCtx->nb_streams, AV_NOPTS_VALUE);
    firstDts_.assign(inputFmtCtx->nb_streams, AV_NOPTS_VALUE);

    for (unsigned int i = 0; i < inputFmtCtx->nb_streams; ++i) {
        AVStream *inputStream = inputFmtCtx->streams[i];
        if (inputStream == nullptr || inputStream->codecpar == nullptr) {
            continue;
        }

        AVCodecParameters *codecpar = inputStream->codecpar;
        const AVMediaType type = codecpar->codec_type;
        if (type != AVMEDIA_TYPE_VIDEO && type != AVMEDIA_TYPE_AUDIO) {
            continue;
        }

        const bool isAudio = type == AVMEDIA_TYPE_AUDIO;
        const bool isVideo = type == AVMEDIA_TYPE_VIDEO;
        if (isVideo) {
            sourceHasVideo_ = true;
        } else if (isAudio) {
            sourceHasAudio_ = true;
            if (codecpar->codec_id == AV_CODEC_ID_NONE || codecpar->sample_rate <= 0 || codecpar->ch_layout.nb_channels <= 0) {
                LOGE("skip invalid audio stream input=%u codec=%s sampleRate=%d channels=%d",
                     i, avcodec_get_name(codecpar->codec_id), codecpar->sample_rate, codecpar->ch_layout.nb_channels);
                lastError_ = "audio stream codec parameters are incomplete; audio stream skipped";
                continue;
            }
        }

        AVStream *outputStream = avformat_new_stream(outputFmtCtx_, nullptr);
        if (outputStream == nullptr) {
            if (isAudio) {
                LOGE("avformat_new_stream failed for audio stream input=%u; continue video recording", i);
                lastError_ = "avformat_new_stream failed for audio stream; audio stream skipped";
                continue;
            }
            closeOutputLocked(false);
            setErrorLocked("avformat_new_stream failed", -1);
            return -1;
        }

        const int copyResult = avcodec_parameters_copy(outputStream->codecpar, codecpar);
        if (copyResult < 0) {
            const std::string error = ffmpegErrorToString(copyResult);
            if (isAudio) {
                LOGE("avcodec_parameters_copy failed for audio input=%u error=%s; continue video recording", i, error.c_str());
                lastError_ = error;
                continue;
            }
            closeOutputLocked(false);
            setErrorLocked(error, copyResult);
            return copyResult;
        }
        outputStream->codecpar->codec_tag = 0;
        outputStream->time_base = inputStream->time_base;
        streamMapping_[i] = outputStream->index;

        if (isVideo) {
            hasVideo_ = true;
            videoStreamRecorded_ = true;
            videoInputStreamIndex_ = static_cast<int>(i);
            LOGI("record stream map video input=%u output=%d codec=%s", i, outputStream->index,
                 avcodec_get_name(codecpar->codec_id));
        } else if (isAudio) {
            audioStreamRecorded_ = true;
            audioInputStreamIndex_ = static_cast<int>(i);
            LOGI("record stream map audio input=%u output=%d codec=%s sampleRate=%d channels=%d playbackEnabled=%d recordingIndependent=1",
                 i, outputStream->index, avcodec_get_name(codecpar->codec_id), codecpar->sample_rate,
                 codecpar->ch_layout.nb_channels, audioPlaybackEnabled_ ? 1 : 0);
        }
    }

    if (sourceHasAudio_ && !audioStreamRecorded_) {
        LOGI("source has audio, but no audio output stream was created; record continues as video-only");
    }
    if (!sourceHasAudio_) {
        LOGI("source has no audio stream; start video-only remux record");
    }

    if (outputFmtCtx_->nb_streams == 0) {
        closeOutputLocked(false);
        setErrorLocked("no video/audio stream to record", -1);
        return -1;
    }

    lastDts_.assign(outputFmtCtx_->nb_streams, AV_NOPTS_VALUE);

    if (!(outputFmtCtx_->oformat->flags & AVFMT_NOFILE)) {
        result = avio_open(&outputFmtCtx_->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
        if (result < 0) {
            const std::string error = ffmpegErrorToString(result);
            LOGE("avio_open failed path=%s error=%s", outputPath.c_str(), error.c_str());
            closeOutputLocked(false);
            setErrorLocked(error, result);
            return result;
        }
    }

    result = avformat_write_header(outputFmtCtx_, nullptr);
    if (result < 0) {
        const std::string error = ffmpegErrorToString(result);
        LOGE("avformat_write_header failed path=%s error=%s", outputPath.c_str(), error.c_str());
        closeOutputLocked(false);
        setErrorLocked(error, result);
        return result;
    }

    headerWritten_ = true;
    waitingForKeyFrame_ = hasVideo_;
    state_ = hasVideo_ ? RecorderState::WaitingKeyFrame : RecorderState::Recording;
    LOGI("write header success outputPath=%s waitingKeyFrame=%d segmentIndex=%d",
         outputPath.c_str(), waitingForKeyFrame_ ? 1 : 0, currentSegmentIndex_ + 1);
    return 0;
}

int PlayerRemuxRecorder::closeOutputLocked(bool writeTrailer) {
    int trailerResult = 0;
    const bool hadPackets = currentSegmentVideoPacketCount_ > 0 || currentSegmentAudioPacketCount_ > 0;
    const std::string closedPath = currentSegmentPath_;

    if (outputFmtCtx_ != nullptr && writeTrailer && headerWritten_) {
        trailerResult = av_write_trailer(outputFmtCtx_);
        if (trailerResult < 0) {
            LOGE("av_write_trailer failed path=%s error=%s", closedPath.c_str(), ffmpegErrorToString(trailerResult).c_str());
        } else {
            LOGI("write trailer success outputPath=%s", closedPath.c_str());
        }
    }

    if (outputFmtCtx_ != nullptr) {
        if (!(outputFmtCtx_->oformat->flags & AVFMT_NOFILE) && outputFmtCtx_->pb != nullptr) {
            avio_closep(&outputFmtCtx_->pb);
        }
        avformat_free_context(outputFmtCtx_);
        outputFmtCtx_ = nullptr;
    }

    if (hadPackets) {
        ++completedSegmentCount_;
        lastSegmentPath_ = closedPath;
        LOGI("record segment closed path=%s completedSegmentCount=%lld videoPackets=%lld audioPackets=%lld",
             closedPath.c_str(), static_cast<long long>(completedSegmentCount_),
             static_cast<long long>(currentSegmentVideoPacketCount_),
             static_cast<long long>(currentSegmentAudioPacketCount_));
    }

    streamMapping_.clear();
    firstPts_.clear();
    firstDts_.clear();
    lastDts_.clear();
    videoInputStreamIndex_ = -1;
    audioInputStreamIndex_ = -1;
    hasVideo_ = false;
    sourceHasVideo_ = false;
    sourceHasAudio_ = false;
    videoStreamRecorded_ = false;
    audioStreamRecorded_ = false;
    waitingForKeyFrame_ = false;
    headerWritten_ = false;
    currentSegmentVideoPacketCount_ = 0;
    currentSegmentAudioPacketCount_ = 0;
    segmentStartPtsUs_ = AV_NOPTS_VALUE;
    currentSegmentStartTimeUs_ = 0;
    return trailerResult;
}

void PlayerRemuxRecorder::onPacket(const AVPacket *packet, AVFormatContext *inputFmtCtx) {
    if (packet == nullptr || inputFmtCtx == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (!isRecorderActive(state_) || outputFmtCtx_ == nullptr || !headerWritten_) {
        return;
    }
    if (!shouldWritePacketLocked(packet, inputFmtCtx)) {
        return;
    }
    if (!rotateSegmentIfNeededLocked(packet, inputFmtCtx)) {
        return;
    }
    writePacketLocked(packet, inputFmtCtx);
}

std::string PlayerRemuxRecorder::stop() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (state_ == RecorderState::Released) {
        return buildStateJsonLocked(true);
    }
    if (!isRecorderActive(state_) && outputFmtCtx_ == nullptr) {
        return buildStateJsonLocked(true);
    }

    state_ = RecorderState::Stopping;
    stopTimeUs_ = av_gettime_relative();
    LOGI("stopPlayerRecord outputPath=%s segmentMode=%d videoPackets=%lld audioPackets=%lld completedSegments=%lld",
         outputPath_.c_str(), segmentMode_ ? 1 : 0,
         static_cast<long long>(videoPacketCount_), static_cast<long long>(audioPacketCount_),
         static_cast<long long>(completedSegmentCount_));

    const bool stoppedSourceHasVideo = sourceHasVideo_;
    const bool stoppedSourceHasAudio = sourceHasAudio_;
    const bool stoppedVideoStreamRecorded = videoStreamRecorded_;
    const bool stoppedAudioStreamRecorded = audioStreamRecorded_;
    const bool stoppedAudioPlaybackEnabled = audioPlaybackEnabled_;
    const int trailerResult = closeOutputLocked(true);
    const std::string stoppedPath = outputPath_;
    const std::string stoppedPattern = outputPattern_;
    const std::string stoppedSegmentPath = currentSegmentPath_;
    const std::string stoppedLastSegmentPath = lastSegmentPath_;
    const std::string stoppedFormat = formatName_;
    const int64_t stoppedVideoPackets = videoPacketCount_;
    const int64_t stoppedAudioPackets = audioPacketCount_;
    const int64_t stoppedDurationUs = elapsedUs(startTimeUs_, stopTimeUs_);
    const int64_t stoppedCompletedSegments = completedSegmentCount_;
    const bool stoppedSegmentMode = segmentMode_;
    const int64_t stoppedSegmentDurationUs = segmentDurationUs_;

    resetLocked(false);
    state_ = RecorderState::Stopped;
    outputPath_ = stoppedPath;
    outputPattern_ = stoppedPattern;
    currentSegmentPath_ = stoppedSegmentPath;
    lastSegmentPath_ = stoppedLastSegmentPath;
    formatName_ = stoppedFormat;
    videoPacketCount_ = stoppedVideoPackets;
    audioPacketCount_ = stoppedAudioPackets;
    completedSegmentCount_ = stoppedCompletedSegments;
    segmentMode_ = stoppedSegmentMode;
    segmentDurationUs_ = stoppedSegmentDurationUs;
    sourceHasVideo_ = stoppedSourceHasVideo;
    sourceHasAudio_ = stoppedSourceHasAudio;
    videoStreamRecorded_ = stoppedVideoStreamRecorded;
    audioStreamRecorded_ = stoppedAudioStreamRecorded;
    audioPlaybackEnabled_ = stoppedAudioPlaybackEnabled;
    startTimeUs_ = stopTimeUs_ > 0 ? stopTimeUs_ - stoppedDurationUs : 0;
    stopTimeUs_ = startTimeUs_ + stoppedDurationUs;
    if (trailerResult < 0) {
        lastError_ = ffmpegErrorToString(trailerResult);
        lastErrorCode_ = trailerResult;
    }

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"player remux recording stopped\","
        << "\"outputPath\":\"" << escapeJson(outputPath_) << "\","
        << "\"outputPattern\":\"" << escapeJson(outputPattern_) << "\","
        << "\"lastSegmentPath\":\"" << escapeJson(lastSegmentPath_) << "\","
        << "\"segmentMode\":" << (segmentMode_ ? "true" : "false") << ","
        << "\"completedSegmentCount\":" << completedSegmentCount_ << ","
        << "\"sourceHasVideo\":" << (sourceHasVideo_ ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_ ? "true" : "false") << ","
        << "\"videoStreamRecorded\":" << (videoStreamRecorded_ ? "true" : "false") << ","
        << "\"audioStreamRecorded\":" << (audioStreamRecorded_ ? "true" : "false") << ","
        << "\"videoPacketCount\":" << videoPacketCount_ << ","
        << "\"audioPacketCount\":" << audioPacketCount_ << ","
        << "\"durationUs\":" << stoppedDurationUs << "}";
    return out.str();
}

std::string PlayerRemuxRecorder::getState() {
    std::lock_guard<std::mutex> lock(mutex_);
    return buildStateJsonLocked(true);
}


void PlayerRemuxRecorder::setAudioPlaybackState(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    audioPlaybackEnabled_ = enabled;
}
bool PlayerRemuxRecorder::isRecording() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return isRecorderActive(state_);
}

int64_t PlayerRemuxRecorder::getVideoPacketCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return videoPacketCount_;
}

int64_t PlayerRemuxRecorder::getAudioPacketCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return audioPacketCount_;
}

int64_t PlayerRemuxRecorder::getCompletedSegmentCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return completedSegmentCount_;
}

void PlayerRemuxRecorder::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == RecorderState::Released) {
        return;
    }
    if (isRecorderActive(state_) && outputFmtCtx_ != nullptr && headerWritten_) {
        LOGI("release recorder: active recording will be stopped first outputPath=%s", outputPath_.c_str());
        closeOutputLocked(true);
    }
    resetLocked(true);
    state_ = RecorderState::Released;
    LOGI("release recorder");
}

void PlayerRemuxRecorder::resetLocked(bool keepReleasedState) {
    closeOutputLocked(false);
    streamMapping_.clear();
    firstPts_.clear();
    firstDts_.clear();
    lastDts_.clear();
    if (!keepReleasedState) {
        state_ = RecorderState::Idle;
        lastError_.clear();
        lastErrorCode_ = 0;
    }
    outputPath_.clear();
    outputPattern_.clear();
    currentSegmentPath_.clear();
    lastSegmentPath_.clear();
    formatName_.clear();
    videoInputStreamIndex_ = -1;
    audioInputStreamIndex_ = -1;
    videoPacketCount_ = 0;
    audioPacketCount_ = 0;
    currentSegmentVideoPacketCount_ = 0;
    currentSegmentAudioPacketCount_ = 0;
    completedSegmentCount_ = 0;
    startTimeUs_ = 0;
    stopTimeUs_ = 0;
    segmentDurationUs_ = 0;
    segmentStartPtsUs_ = AV_NOPTS_VALUE;
    currentSegmentStartTimeUs_ = 0;
    currentSegmentIndex_ = 0;
    hasVideo_ = false;
    sourceHasVideo_ = false;
    sourceHasAudio_ = false;
    videoStreamRecorded_ = false;
    audioStreamRecorded_ = false;
    waitingForKeyFrame_ = false;
    headerWritten_ = false;
    segmentMode_ = false;
}

std::string PlayerRemuxRecorder::buildStateJsonLocked(bool success) const {
    const bool active = isRecorderActive(state_);
    std::ostringstream out;
    out << "{\"success\":" << (success ? "true" : "false") << ","
        << "\"recording\":" << (active ? "true" : "false") << ","
        << "\"state\":\"" << stateName(state_) << "\","
        << "\"outputPath\":\"" << escapeJson(outputPath_) << "\","
        << "\"outputPattern\":\"" << escapeJson(outputPattern_) << "\","
        << "\"format\":\"" << escapeJson(formatName_) << "\","
        << "\"segmentMode\":" << (segmentMode_ ? "true" : "false") << ","
        << "\"segmentDurationUs\":" << segmentDurationUs_ << ","
        << "\"sourceHasVideo\":" << (sourceHasVideo_ ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_ ? "true" : "false") << ","
        << "\"videoStreamRecorded\":" << (videoStreamRecorded_ ? "true" : "false") << ","
        << "\"audioStreamRecorded\":" << (audioStreamRecorded_ ? "true" : "false") << ","
        << "\"audioPlaybackEnabled\":" << (audioPlaybackEnabled_ ? "true" : "false") << ","
        << "\"audioRecordingIndependentOfPlayback\":true,"
        << "\"currentSegmentIndex\":" << (currentSegmentIndex_ + 1) << ","
        << "\"currentSegmentPath\":\"" << escapeJson(currentSegmentPath_) << "\","
        << "\"lastSegmentPath\":\"" << escapeJson(lastSegmentPath_) << "\","
        << "\"completedSegmentCount\":" << completedSegmentCount_ << ","
        << "\"videoPacketCount\":" << videoPacketCount_ << ","
        << "\"audioPacketCount\":" << audioPacketCount_ << ","
        << "\"currentSegmentVideoPacketCount\":" << currentSegmentVideoPacketCount_ << ","
        << "\"currentSegmentAudioPacketCount\":" << currentSegmentAudioPacketCount_ << ","
        << "\"waitingForKeyFrame\":" << (waitingForKeyFrame_ ? "true" : "false") << ","
        << "\"lastError\":\"" << escapeJson(lastError_) << "\","
        << "\"durationUs\":" << elapsedUs(startTimeUs_, stopTimeUs_) << "}";
    return out.str();
}

bool PlayerRemuxRecorder::shouldWritePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx) {
    if (packet->stream_index < 0 || static_cast<size_t>(packet->stream_index) >= streamMapping_.size()) {
        return false;
    }
    const int outputIndex = streamMapping_[packet->stream_index];
    if (outputIndex < 0) {
        return false;
    }
    if (packet->stream_index >= static_cast<int>(inputFmtCtx->nb_streams)) {
        return false;
    }

    AVStream *inputStream = inputFmtCtx->streams[packet->stream_index];
    if (inputStream == nullptr || inputStream->codecpar == nullptr) {
        return false;
    }

    if (waitingForKeyFrame_) {
        if (packet->stream_index != videoInputStreamIndex_) {
            return false;
        }
        if ((packet->flags & AV_PKT_FLAG_KEY) == 0) {
            return false;
        }
        waitingForKeyFrame_ = false;
        state_ = RecorderState::Recording;
        segmentStartPtsUs_ = packetTimestampUs(packet, inputStream);
        if (segmentStartPtsUs_ == AV_NOPTS_VALUE) {
            segmentStartPtsUs_ = 0;
        }
        currentSegmentStartTimeUs_ = av_gettime_relative();
        LOGI("first keyframe received, start writing packets outputPath=%s segmentIndex=%d",
             outputPath_.c_str(), currentSegmentIndex_ + 1);
    }
    return true;
}

bool PlayerRemuxRecorder::rotateSegmentIfNeededLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx) {
    if (!segmentMode_ || segmentDurationUs_ <= 0) {
        return true;
    }
    if (packet->stream_index >= static_cast<int>(inputFmtCtx->nb_streams)) {
        return true;
    }

    AVStream *inputStream = inputFmtCtx->streams[packet->stream_index];
    if (inputStream == nullptr) {
        return true;
    }

    const bool packetIsVideoKey = packet->stream_index == videoInputStreamIndex_ && (packet->flags & AV_PKT_FLAG_KEY) != 0;
    if (hasVideo_ && !packetIsVideoKey) {
        return true;
    }

    int64_t packetUs = packetTimestampUs(packet, inputStream);
    if (segmentStartPtsUs_ == AV_NOPTS_VALUE) {
        segmentStartPtsUs_ = packetUs == AV_NOPTS_VALUE ? 0 : packetUs;
        currentSegmentStartTimeUs_ = av_gettime_relative();
        return true;
    }

    const int64_t elapsedByPts = packetUs == AV_NOPTS_VALUE ? AV_NOPTS_VALUE : packetUs - segmentStartPtsUs_;
    const int64_t elapsedByWall = currentSegmentStartTimeUs_ > 0 ? av_gettime_relative() - currentSegmentStartTimeUs_ : 0;
    const int64_t segmentElapsedUs = elapsedByPts == AV_NOPTS_VALUE ? elapsedByWall : elapsedByPts;
    if (segmentElapsedUs < segmentDurationUs_) {
        return true;
    }
    if (currentSegmentVideoPacketCount_ + currentSegmentAudioPacketCount_ <= 0) {
        return true;
    }

    LOGI("rotate record segment currentPath=%s elapsedUs=%lld targetUs=%lld nextIndex=%d",
         currentSegmentPath_.c_str(), static_cast<long long>(segmentElapsedUs),
         static_cast<long long>(segmentDurationUs_), currentSegmentIndex_ + 2);
    const bool stoppedSourceHasVideo = sourceHasVideo_;
    const bool stoppedSourceHasAudio = sourceHasAudio_;
    const bool stoppedVideoStreamRecorded = videoStreamRecorded_;
    const bool stoppedAudioStreamRecorded = audioStreamRecorded_;
    const bool stoppedAudioPlaybackEnabled = audioPlaybackEnabled_;
    const int trailerResult = closeOutputLocked(true);
    if (trailerResult < 0) {
        lastError_ = ffmpegErrorToString(trailerResult);
        lastErrorCode_ = trailerResult;
    }

    ++currentSegmentIndex_;
    const std::string nextPath = makeSegmentPathLocked(currentSegmentIndex_);
    const int result = openOutputLocked(inputFmtCtx, nextPath);
    if (result < 0) {
        return false;
    }

    waitingForKeyFrame_ = false;
    state_ = RecorderState::Recording;
    segmentStartPtsUs_ = packetUs == AV_NOPTS_VALUE ? 0 : packetUs;
    currentSegmentStartTimeUs_ = av_gettime_relative();
    LOGI("record segment opened path=%s segmentIndex=%d", nextPath.c_str(), currentSegmentIndex_ + 1);
    return true;
}

bool PlayerRemuxRecorder::writePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx) {
    AVStream *inputStream = inputFmtCtx->streams[packet->stream_index];
    const int outputIndex = streamMapping_[packet->stream_index];
    AVStream *outputStream = outputFmtCtx_->streams[outputIndex];

    AVPacket *recordPacket = av_packet_alloc();
    if (recordPacket == nullptr) {
        setErrorLocked("av_packet_alloc failed", -1);
        return false;
    }

    int result = av_packet_ref(recordPacket, packet);
    if (result < 0) {
        const std::string error = ffmpegErrorToString(result);
        av_packet_free(&recordPacket);
        setErrorLocked(error, result);
        return false;
    }

    if (firstPts_[packet->stream_index] == AV_NOPTS_VALUE && recordPacket->pts != AV_NOPTS_VALUE) {
        firstPts_[packet->stream_index] = recordPacket->pts;
    }
    if (firstDts_[packet->stream_index] == AV_NOPTS_VALUE && recordPacket->dts != AV_NOPTS_VALUE) {
        firstDts_[packet->stream_index] = recordPacket->dts;
    }

    if (recordPacket->pts != AV_NOPTS_VALUE && firstPts_[packet->stream_index] != AV_NOPTS_VALUE) {
        recordPacket->pts -= firstPts_[packet->stream_index];
    }
    if (recordPacket->dts != AV_NOPTS_VALUE && firstDts_[packet->stream_index] != AV_NOPTS_VALUE) {
        recordPacket->dts -= firstDts_[packet->stream_index];
    }
    if (recordPacket->pts != AV_NOPTS_VALUE && recordPacket->pts < 0) {
        recordPacket->pts = 0;
    }
    if (recordPacket->dts != AV_NOPTS_VALUE && recordPacket->dts < 0) {
        recordPacket->dts = 0;
    }
    if (recordPacket->pts != AV_NOPTS_VALUE && recordPacket->dts != AV_NOPTS_VALUE && recordPacket->pts < recordPacket->dts) {
        LOGE("record packet pts < dts, adjust pts stream=%d pts=%lld dts=%lld",
             packet->stream_index, static_cast<long long>(recordPacket->pts), static_cast<long long>(recordPacket->dts));
        recordPacket->pts = recordPacket->dts;
    }

    av_packet_rescale_ts(recordPacket, inputStream->time_base, outputStream->time_base);
    recordPacket->stream_index = outputIndex;
    recordPacket->pos = -1;

    if (recordPacket->dts != AV_NOPTS_VALUE && outputIndex >= 0 && static_cast<size_t>(outputIndex) < lastDts_.size()) {
        if (lastDts_[outputIndex] != AV_NOPTS_VALUE && recordPacket->dts <= lastDts_[outputIndex]) {
            LOGE("skip non-monotonic dts stream=%d dts=%lld lastDts=%lld",
                 outputIndex, static_cast<long long>(recordPacket->dts), static_cast<long long>(lastDts_[outputIndex]));
            av_packet_free(&recordPacket);
            return false;
        }
        lastDts_[outputIndex] = recordPacket->dts;
    }

    result = av_interleaved_write_frame(outputFmtCtx_, recordPacket);
    if (result < 0) {
        const std::string error = ffmpegErrorToString(result);
        LOGE("av_interleaved_write_frame failed stream=%d error=%s", outputIndex, error.c_str());
        av_packet_free(&recordPacket);
        setErrorLocked(error, result);
        return false;
    }

    if (packet->stream_index == videoInputStreamIndex_) {
        ++videoPacketCount_;
        ++currentSegmentVideoPacketCount_;
    } else if (packet->stream_index == audioInputStreamIndex_) {
        ++audioPacketCount_;
        ++currentSegmentAudioPacketCount_;
    }

    av_packet_free(&recordPacket);
    return true;
}

std::string PlayerRemuxRecorder::makeSegmentPathLocked(int segmentIndex) const {
    const int displayIndex = segmentIndex + 1;
    if (!segmentMode_) {
        return outputPattern_;
    }
    if (hasPrintfIntegerPlaceholder(outputPattern_)) {
        char buffer[4096] = {0};
        const int written = std::snprintf(buffer, sizeof(buffer), outputPattern_.c_str(), displayIndex);
        if (written > 0 && written < static_cast<int>(sizeof(buffer))) {
            return std::string(buffer);
        }
        LOGE("segment output pattern is too long, fallback to indexed suffix pattern=%s", outputPattern_.c_str());
    }
    return insertSegmentIndex(outputPattern_, displayIndex);
}

void PlayerRemuxRecorder::setErrorLocked(const std::string &message, int errorCode) {
    lastError_ = message;
    lastErrorCode_ = errorCode;
    state_ = RecorderState::Error;
    waitingForKeyFrame_ = false;
    LOGE("recorder error code=%d message=%s", errorCode, message.c_str());
}