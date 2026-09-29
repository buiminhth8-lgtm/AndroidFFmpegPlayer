#include "PlayerRemuxRecorder.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <system_error>
#include <iomanip>
#include <sstream>
#include <sys/stat.h>

extern "C" {
#include "libavcodec/bsf.h"
#include "libavcodec/codec_par.h"
#include "libavcodec/packet.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/buffer.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
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

std::string trimLower(std::string value) {
    value.erase(std::remove_if(value.begin(), value.end(), [](unsigned char c) {
        return std::isspace(c) != 0;
    }), value.end());
    return lowerCopy(value);
}

std::string normalizeFormatName(const std::string &formatName) {
    const std::string format = trimLower(formatName);
    if (format.empty() || format == "auto") {
        return std::string();
    }
    if (format == "ts" || format == "mpegts") {
        return "mpegts";
    }
    if (format == "mp4" || format == "m4v") {
        return "mp4";
    }
    if (format == "mov" || format == "quicktime") {
        return "mov";
    }
    if (format == "mkv" || format == "matroska") {
        return "matroska";
    }
    if (format == "webm") {
        return "webm";
    }
    if (format == "flv") {
        return "flv";
    }
    return format;
}

std::string formatNameForPath(const std::string &path) {
    if (endsWith(path, ".ts")) {
        return "mpegts";
    }
    if (endsWith(path, ".mp4") || endsWith(path, ".m4v")) {
        return "mp4";
    }
    if (endsWith(path, ".mov")) {
        return "mov";
    }
    if (endsWith(path, ".mkv")) {
        return "matroska";
    }
    if (endsWith(path, ".webm")) {
        return "webm";
    }
    if (endsWith(path, ".flv")) {
        return "flv";
    }
    return std::string();
}

std::string resolveFormatName(const std::string &path, const std::string &requestedFormatName) {
    const std::string requested = normalizeFormatName(requestedFormatName);
    if (!requested.empty()) {
        return requested;
    }
    return formatNameForPath(path);
}

bool isMp4LikeFormat(const std::string &formatName) {
    const std::string format = normalizeFormatName(formatName);
    return format == "mp4" || format == "mov";
}

std::string muxerOptionsJson(bool fragmentedMp4) {
    std::ostringstream out;
    out << "{\"fragmentedMp4\":" << (fragmentedMp4 ? "true" : "false") << "}";
    return out.str();
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

PlayerRemuxRecorder::PlayerRemuxRecorder() {
    publishSnapshot();
}

PlayerRemuxRecorder::~PlayerRemuxRecorder() {
    release();
}

void PlayerRemuxRecorder::PacketDeleter::operator()(AVPacket *packet) const {
    av_packet_free(&packet);
}

void PlayerRemuxRecorder::setInput(AVFormatContext *inputFmtCtx) {
    InputSnapshot snapshot(avformat_alloc_context(), [](AVFormatContext *ctx) {
        avformat_free_context(ctx);
    });
    bool valid = snapshot != nullptr && inputFmtCtx != nullptr;
    if (valid) {
        for (unsigned i = 0; i < inputFmtCtx->nb_streams; ++i) {
            AVStream *source = inputFmtCtx->streams[i];
            AVStream *copy = avformat_new_stream(snapshot.get(), nullptr);
            if (copy == nullptr || source == nullptr || source->codecpar == nullptr
                || avcodec_parameters_copy(copy->codecpar, source->codecpar) < 0) {
                valid = false;
                break;
            }
            copy->time_base = source->time_base;
        }
    }
    std::lock_guard<std::mutex> lock(mutex_);
    input_ = valid ? std::move(snapshot) : InputSnapshot{};
    if (!valid && accepting_.exchange(false)) {
        queueFailure_ = QueueFailure::Allocation;
        publishedState_ = RecorderState::Error;
        queueCv_.notify_one();
    }
}

void PlayerRemuxRecorder::clearInput() {
    std::lock_guard<std::mutex> lock(mutex_);
    input_.reset();
}

std::string PlayerRemuxRecorder::start(const std::string &outputPath) {
    RemuxRecordConfig config;
    config.outputPathOrPattern = outputPath;
    return startWithConfig(config);
}

std::string PlayerRemuxRecorder::startSegmented(const std::string &outputPattern, int segmentDurationSec) {
    RemuxRecordConfig config;
    config.outputPathOrPattern = outputPattern;
    config.segmentMode = true;
    config.segmentDurationSec = segmentDurationSec;
    return startWithConfig(config);
}

std::string PlayerRemuxRecorder::startWithConfig(const RemuxRecordConfig &config) {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    if (released_) return jsonError(-1, "recorder is released");
    if (accepting_.load()) return jsonError(-1, "recorder is already recording");
    // 上一次错误退出也可能留下 joinable 线程；禁止跨会话复用旧队列。
    stopAndJoin();
    std::unique_lock<std::mutex> lock(mutex_);
    if (!input_) return jsonError(-1, "input format context is null; player is not prepared");
    queue_.clear();
    queueBytes_ = 0;
    queueDrops_ = 0;
    queueHighWatermark_ = 0;
    queueBytesHighWatermark_ = 0;
    queueFailure_ = QueueFailure::None;
    stopRequested_ = false;
    startCompleted_ = false;
    packetsWritten_.store(0);
    writeErrors_.store(0);
    publishedState_ = RecorderState::Starting;
    try {
        worker_ = std::thread(&PlayerRemuxRecorder::workerLoop, this, input_, config);
    } catch (const std::system_error &error) {
        publishedState_ = RecorderState::Error;
        return jsonError(-1, error.what());
    }
    // 维持同步 start API：只有调用者等待文件打开；播放线程不获取 lifecycleMutex_。
    startCv_.wait(lock, [this] { return startCompleted_; });
    return startResult_;
}

void PlayerRemuxRecorder::onPacket(const AVPacket *packet) {
    if (packet == nullptr || !accepting_.load()) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!accepting_.load() || !input_) return;
    if (packet->stream_index < 0 || packet->stream_index >= static_cast<int>(input_->nb_streams)) return;
    const auto type = input_->streams[packet->stream_index]->codecpar->codec_type;
    if (type != AVMEDIA_TYPE_VIDEO && type != AVMEDIA_TYPE_AUDIO) return;

    // 计算实际持有的缓冲区和 side data；单个超大包也不得突破队列预算。
    size_t bytes = packet->buf != nullptr ? packet->buf->size : static_cast<size_t>(std::max(packet->size, 0));
    for (int i = 0; i < packet->side_data_elems; ++i) {
        const size_t sideBytes = packet->side_data[i].size;
        if (sideBytes > kMaxQueueBytes || bytes > kMaxQueueBytes - sideBytes) {
            bytes = kMaxQueueBytes + 1;
            break;
        }
        bytes += sideBytes;
    }
    if (queue_.size() >= kMaxQueuePackets || bytes > kMaxQueueBytes
        || queueBytes_ > kMaxQueueBytes - bytes) {
        ++queueDrops_;
        queueFailure_ = QueueFailure::Overflow;
        accepting_.store(false);
        publishedState_ = RecorderState::Error;
        LOGE("recorder queue overflow packets=%zu bytes=%zu; stop recording, keep playback running",
             queue_.size(), queueBytes_);
        queueCv_.notify_one();
        return;
    }
    std::unique_ptr<AVPacket, PacketDeleter> copy(av_packet_clone(packet));
    if (copy) {
        try {
            queue_.push_back({std::move(copy), input_, bytes});
            queueBytes_ += bytes;
            queueHighWatermark_ = std::max(queueHighWatermark_, queue_.size());
            queueBytesHighWatermark_ = std::max(queueBytesHighWatermark_, queueBytes_);
            queueCv_.notify_one();
            return;
        } catch (const std::bad_alloc &) {
            // RAII 释放尚未进入队列的引用。
        }
    }
    ++queueDrops_;
    queueFailure_ = QueueFailure::Allocation;
    accepting_.store(false);
    publishedState_ = RecorderState::Error;
    queueCv_.notify_one();
}

bool PlayerRemuxRecorder::adoptInput(const InputSnapshot &input) {
    if (input == workerInput_) return true;
    // 不可将不同编码参数的包写入旧容器；变化仅终止录制，不影响重连播放。
    if (input->nb_streams != workerInput_->nb_streams) {
        setErrorLocked("record input stream layout changed after reconnect");
        return false;
    }
    for (unsigned i = 0; i < input->nb_streams; ++i) {
        const AVCodecParameters *a = workerInput_->streams[i]->codecpar;
        const AVCodecParameters *b = input->streams[i]->codecpar;
        if (a->codec_type != b->codec_type || a->codec_id != b->codec_id
            || a->width != b->width || a->height != b->height || a->sample_rate != b->sample_rate
            || (a->codec_type == AVMEDIA_TYPE_AUDIO
                && (a->ch_layout.nb_channels != b->ch_layout.nb_channels
                    || (a->ch_layout.nb_channels > 0
                        && av_channel_layout_compare(&a->ch_layout, &b->ch_layout) != 0)))
            || a->extradata_size != b->extradata_size
            || (a->extradata_size > 0 && std::memcmp(a->extradata, b->extradata, a->extradata_size) != 0)) {
            setErrorLocked("record codec parameters changed after reconnect");
            return false;
        }
        if (i < streamMapping_.size() && streamMapping_[i] >= 0) {
            const int outputIndex = streamMapping_[i];
            timestampOffset_[outputIndex] = lastDts_[outputIndex] == AV_NOPTS_VALUE ? 0 : lastDts_[outputIndex] + 1;
        }
    }
    workerInput_ = input;
    std::fill(firstPts_.begin(), firstPts_.end(), AV_NOPTS_VALUE);
    std::fill(firstDts_.begin(), firstDts_.end(), AV_NOPTS_VALUE);
    if (audioBitstreamFilter_ != nullptr) {
        av_bsf_flush(audioBitstreamFilter_);
        audioBitstreamFilter_->time_base_in = input->streams[audioInputStreamIndex_]->time_base;
        audioBitstreamFilter_->time_base_out = audioBitstreamFilter_->time_base_in;
    }
    waitingForKeyFrame_ = hasVideo_;
    state_ = hasVideo_ ? RecorderState::WaitingKeyFrame : RecorderState::Recording;
    segmentStartPtsUs_ = AV_NOPTS_VALUE;
    LOGI("recorder adopted reconnect input snapshot; waitKeyFrame=%d", waitingForKeyFrame_ ? 1 : 0);
    return true;
}

void PlayerRemuxRecorder::workerLoop(InputSnapshot input, RemuxRecordConfig config) {
    workerInput_ = std::move(input);
    const std::string result = startLocked(workerInput_.get(), config);
    const bool started = result.find("\"success\":true") != std::string::npos;
    publishSnapshot();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        startResult_ = result;
        startCompleted_ = true;
        accepting_.store(started && queueFailure_ == QueueFailure::None);
    }
    startCv_.notify_all();
    if (started) {
        for (;;) {
            QueuedPacket next;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                queueCv_.wait(lock, [this] {
                    return stopRequested_ || queueFailure_ != QueueFailure::None || !queue_.empty();
                });
                if (queueFailure_ != QueueFailure::None) {
                    const QueueFailure failure = queueFailure_;
                    lock.unlock();
                    setErrorLocked(failure == QueueFailure::Overflow
                                   ? "record packet queue overflow; recording stopped"
                                   : "record packet/input allocation failed; recording stopped");
                    break;
                }
                if (queue_.empty()) break; // 正常 stop 在所有已接受包排空后退出。
                next = std::move(queue_.front());
                queueBytes_ -= next.bytes;
                queue_.pop_front();
            }
            // 此区间无队列锁/生命周期锁，写盘阻塞不会传递给生产者或诊断查询。
            if (!adoptInput(next.input)) break;
            if (shouldWritePacketLocked(next.packet.get(), workerInput_.get())
                && rotateSegmentIfNeededLocked(next.packet.get(), workerInput_.get())) {
                writePacketLocked(next.packet.get(), workerInput_.get());
            }
            publishSnapshot();
            if (state_ == RecorderState::Error) break;
        }
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        accepting_.store(false);
        queueDrops_ += queue_.size();
        queue_.clear();
        queueBytes_ = 0;
    }
    finishWorker();
    workerInput_.reset();
    publishSnapshot();
}

void PlayerRemuxRecorder::finishWorker() {
    stopTimeUs_ = av_gettime_relative();
    const bool sourceVideo = sourceHasVideo_;
    const bool sourceAudio = sourceHasAudio_;
    const bool recordedVideo = videoStreamRecorded_;
    const bool recordedAudio = audioStreamRecorded_;
    const int result = closeOutputLocked(true);
    sourceHasVideo_ = sourceVideo;
    sourceHasAudio_ = sourceAudio;
    videoStreamRecorded_ = recordedVideo;
    audioStreamRecorded_ = recordedAudio;
    if (result < 0) setErrorLocked(ffmpegErrorToString(result), result);
    if (state_ != RecorderState::Error) state_ = RecorderState::Stopped;
}

void PlayerRemuxRecorder::stopAndJoin() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        accepting_.store(false);
        stopRequested_ = true;
        if (isRecorderActive(publishedState_)) publishedState_ = RecorderState::Stopping;
    }
    queueCv_.notify_one();
    if (worker_.joinable()) worker_.join();
}

std::string PlayerRemuxRecorder::stop() {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    stopAndJoin();
    std::string result = getState();
    result.insert(1, "\"message\":\"player remux recording stopped\",");
    return result;
}

void PlayerRemuxRecorder::release() {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    if (released_) return;
    stopAndJoin();
    released_ = true;
    std::lock_guard<std::mutex> lock(mutex_);
    input_.reset();
    publishedState_ = RecorderState::Released;
}

void PlayerRemuxRecorder::publishSnapshot() {
    const std::string details = buildStateDetails();
    publishedVideoPackets_.store(videoPacketCount_);
    publishedAudioPackets_.store(audioPacketCount_);
    publishedSegments_.store(completedSegmentCount_);
    std::lock_guard<std::mutex> lock(mutex_);
    publishedDetails_ = details;
    publishedState_ = queueFailure_ != QueueFailure::None ? RecorderState::Error
                      : stopRequested_ && isRecorderActive(state_) ? RecorderState::Stopping : state_;
}

std::string PlayerRemuxRecorder::getState() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << "{\"success\":" << (publishedState_ == RecorderState::Error ? "false" : "true") << ","
        << "\"recording\":" << (accepting_.load() ? "true" : "false") << ","
        << "\"state\":\"" << stateName(publishedState_) << "\","
        << publishedDetails_ << ","
        << "\"audioPlaybackEnabled\":" << (audioPlaybackEnabled_.load() ? "true" : "false") << ","
        << "\"queuePackets\":" << queue_.size() << ","
        << "\"queueBytes\":" << queueBytes_ << ","
        << "\"queueDrops\":" << queueDrops_ << ","
        << "\"queueHighWatermark\":" << queueHighWatermark_ << ","
        << "\"queueBytesHighWatermark\":" << queueBytesHighWatermark_ << ","
        << "\"queueMaxPackets\":" << kMaxQueuePackets << ","
        << "\"queueMaxBytes\":" << kMaxQueueBytes << ","
        << "\"queueOverflowPolicy\":\"stop_recording\","
        << "\"packetsWritten\":" << packetsWritten_.load() << ","
        << "\"writeErrors\":" << writeErrors_.load() << "}";
    return out.str();
}

void PlayerRemuxRecorder::setAudioPlaybackState(bool enabled) {
    audioPlaybackEnabled_.store(enabled);
}

bool PlayerRemuxRecorder::isRecording() const { return accepting_.load(); }
int64_t PlayerRemuxRecorder::getVideoPacketCount() const { return publishedVideoPackets_.load(); }
int64_t PlayerRemuxRecorder::getAudioPacketCount() const { return publishedAudioPackets_.load(); }
int64_t PlayerRemuxRecorder::getCompletedSegmentCount() const { return publishedSegments_.load(); }

std::string PlayerRemuxRecorder::startLocked(AVFormatContext *inputFmtCtx,
                                             const RemuxRecordConfig &config) {
    if (state_ == RecorderState::Released) {
        return jsonError(-1, "recorder is released");
    }
    if (isRecorderActive(state_)) {
        return jsonError(-1, "recorder is already recording");
    }
    if (inputFmtCtx == nullptr) {
        return jsonError(-1, "input format context is null; player is not prepared");
    }
    if (config.outputPathOrPattern.empty()) {
        return jsonError(-1, config.segmentMode ? "outputPattern is empty" : "outputPath is empty");
    }
    if (config.segmentMode && config.segmentDurationSec <= 0) {
        return jsonError(-1, "segmentDurationSec must be greater than 0");
    }

    segmentMode_ = config.segmentMode;
    outputPattern_ = config.outputPathOrPattern;
    currentSegmentIndex_ = 0;
    const std::string firstOutputPath = segmentMode_ ? makeSegmentPathLocked(currentSegmentIndex_) : config.outputPathOrPattern;
    const std::string parent = parentDirectory(firstOutputPath);
    if (!directoryExists(parent)) {
        return jsonError(-1, "output parent directory does not exist: " + parent);
    }

    resetLocked(false);
    segmentMode_ = config.segmentMode;
    outputPattern_ = config.outputPathOrPattern;
    requestedFormatName_ = normalizeFormatName(config.formatName);
    fragmentedMp4_ = config.fragmentedMp4;
    outputPath_ = firstOutputPath;
    currentSegmentPath_ = firstOutputPath;
    currentSegmentIndex_ = 0;
    segmentDurationUs_ = segmentMode_ ? static_cast<int64_t>(config.segmentDurationSec) * AV_TIME_BASE : 0;
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
        << "\"requestedFormat\":\"" << escapeJson(requestedFormatName_) << "\","
        << "\"muxerOptions\":" << muxerOptionsJson(isMp4LikeFormat(formatName_) && fragmentedMp4_) << ","
        << "\"fragmentedMp4\":" << (isMp4LikeFormat(formatName_) && fragmentedMp4_ ? "true" : "false") << ","
        << "\"abnormalExitReadable\":" << (isMp4LikeFormat(formatName_) && fragmentedMp4_ ? "true" : "false") << ","
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

    const std::string resolvedFormatName = resolveFormatName(outputPath, requestedFormatName_);
    const char *requestedFormatName = resolvedFormatName.empty() ? nullptr : resolvedFormatName.c_str();
    int result = avformat_alloc_output_context2(&outputFmtCtx_, nullptr, requestedFormatName, outputPath.c_str());
    if (result < 0 || outputFmtCtx_ == nullptr) {
        const std::string error = result < 0 ? ffmpegErrorToString(result) : "avformat_alloc_output_context2 failed";
        closeOutputLocked(false);
        setErrorLocked(error, result < 0 ? result : -1);
        return lastErrorCode_;
    }

    formatName_ = outputFmtCtx_->oformat && outputFmtCtx_->oformat->name ? outputFmtCtx_->oformat->name : "unknown";
    LOGI("record output format requested=%s resolved=%s actual=%s fragmentedMp4=%d path=%s",
         requestedFormatName_.c_str(), resolvedFormatName.c_str(), formatName_.c_str(),
         fragmentedMp4_ ? 1 : 0, outputPath.c_str());
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

        if (isAudio && codecpar->codec_id == AV_CODEC_ID_AAC && isMp4LikeFormat(formatName_)) {
            const AVBitStreamFilter *filter = av_bsf_get_by_name("aac_adtstoasc");
            if (filter == nullptr) {
                closeOutputLocked(false);
                setErrorLocked("aac_adtstoasc bitstream filter unavailable", AVERROR_BSF_NOT_FOUND);
                return lastErrorCode_;
            }
            result = av_bsf_alloc(filter, &audioBitstreamFilter_);
            if (result >= 0) {
                result = avcodec_parameters_copy(audioBitstreamFilter_->par_in, codecpar);
            }
            if (result >= 0) {
                audioBitstreamFilter_->time_base_in = inputStream->time_base;
                result = av_bsf_init(audioBitstreamFilter_);
            }
            if (result >= 0) {
                result = avcodec_parameters_copy(outputStream->codecpar, audioBitstreamFilter_->par_out);
            }
            if (result < 0) {
                const std::string error = ffmpegErrorToString(result);
                closeOutputLocked(false);
                setErrorLocked(error, result);
                return result;
            }
            LOGI("record AAC bitstream filter enabled name=aac_adtstoasc input=%u output=%d", i,
                 outputStream->index);
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
    timestampOffset_.assign(outputFmtCtx_->nb_streams, 0);

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

    AVDictionary *muxerOptions = nullptr;
    const bool usingFragmentedMp4 = isMp4LikeFormat(formatName_) && fragmentedMp4_;
    if (usingFragmentedMp4) {
        av_dict_set(&muxerOptions, "movflags", "frag_keyframe+empty_moov+default_base_moof", 0);
        av_dict_set(&muxerOptions, "flush_packets", "1", 0);
        LOGI("mp4/mov recorder uses fragmented MP4 movflags=frag_keyframe+empty_moov+default_base_moof path=%s", outputPath.c_str());
    }

    result = avformat_write_header(outputFmtCtx_, &muxerOptions);
    AVDictionaryEntry *unusedOption = nullptr;
    while ((unusedOption = av_dict_get(muxerOptions, "", unusedOption, AV_DICT_IGNORE_SUFFIX)) != nullptr) {
        LOGI("unused muxer option %s=%s", unusedOption->key, unusedOption->value);
    }
    av_dict_free(&muxerOptions);
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

    av_bsf_free(&audioBitstreamFilter_);

    if (outputFmtCtx_ != nullptr) {
        if (!(outputFmtCtx_->oformat->flags & AVFMT_NOFILE) && outputFmtCtx_->pb != nullptr) {
            const int closeResult = avio_closep(&outputFmtCtx_->pb);
            if (trailerResult >= 0 && closeResult < 0) {
                trailerResult = closeResult;
            }
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
    requestedFormatName_.clear();
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
    fragmentedMp4_ = true;
}

std::string PlayerRemuxRecorder::buildStateDetails() const {
    std::ostringstream out;
    out << "\"outputPath\":\"" << escapeJson(outputPath_) << "\","
        << "\"outputPattern\":\"" << escapeJson(outputPattern_) << "\","
        << "\"format\":\"" << escapeJson(formatName_) << "\","
        << "\"requestedFormat\":\"" << escapeJson(requestedFormatName_) << "\","
        << "\"segmentMode\":" << (segmentMode_ ? "true" : "false") << ","
        << "\"segmentDurationUs\":" << segmentDurationUs_ << ","
        << "\"fragmentedMp4\":" << (isMp4LikeFormat(formatName_) && fragmentedMp4_ ? "true" : "false") << ","
        << "\"abnormalExitReadable\":" << (isMp4LikeFormat(formatName_) && fragmentedMp4_ ? "true" : "false") << ","
        << "\"sourceHasVideo\":" << (sourceHasVideo_ ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_ ? "true" : "false") << ","
        << "\"videoStreamRecorded\":" << (videoStreamRecorded_ ? "true" : "false") << ","
        << "\"audioStreamRecorded\":" << (audioStreamRecorded_ ? "true" : "false") << ","
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
        << "\"durationUs\":" << elapsedUs(startTimeUs_, stopTimeUs_);
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
    const int trailerResult = closeOutputLocked(true);
    if (trailerResult < 0) {
        setErrorLocked(ffmpegErrorToString(trailerResult), trailerResult);
        return false;
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
        setErrorLocked("packet reference: " + error, result);
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

    auto writeOutputPacket = [&](AVPacket *outputPacket, AVRational sourceTimeBase) -> bool {
        av_packet_rescale_ts(outputPacket, sourceTimeBase, outputStream->time_base);
        // 重连后以输出时间基衔接已写 DTS，输入 PTS 可从零重新开始。
        if (outputPacket->pts != AV_NOPTS_VALUE) outputPacket->pts += timestampOffset_[outputIndex];
        if (outputPacket->dts != AV_NOPTS_VALUE) outputPacket->dts += timestampOffset_[outputIndex];
        outputPacket->stream_index = outputIndex;
        outputPacket->pos = -1;

        // RTSP 重连首包可能没有 PTS/DTS。不能让 muxer 隐式补值，否则本地
        // lastDts 与容器时间线分离，随后恢复有效时间戳的包会被判为倒退。
        const int64_t step = std::max<int64_t>(1, outputPacket->duration);
        const int64_t previousDts = lastDts_[outputIndex];
        if (outputPacket->dts == AV_NOPTS_VALUE) {
            outputPacket->dts = outputPacket->pts != AV_NOPTS_VALUE ? outputPacket->pts
                               : previousDts == AV_NOPTS_VALUE ? timestampOffset_[outputIndex]
                               : previousDts + step;
        }
        if (previousDts != AV_NOPTS_VALUE && outputPacket->dts <= previousDts) {
            const int64_t correction = previousDts + step - outputPacket->dts;
            outputPacket->dts += correction;
            if (outputPacket->pts != AV_NOPTS_VALUE) outputPacket->pts += correction;
            // 把同一修正应用于后续包，保留输入时间间隔，而非逐包丢弃。
            timestampOffset_[outputIndex] += correction;
            LOGI("record timestamp discontinuity stream=%d correction=%lld",
                 outputIndex, static_cast<long long>(correction));
        }
        if (outputPacket->pts == AV_NOPTS_VALUE || outputPacket->pts < outputPacket->dts) {
            outputPacket->pts = outputPacket->dts;
        }
        const int64_t writtenDts = outputPacket->dts;

        result = av_interleaved_write_frame(outputFmtCtx_, outputPacket);
        if (result < 0) {
            const std::string error = "mux write: " + ffmpegErrorToString(result);
            LOGE("av_interleaved_write_frame failed stream=%d error=%s", outputIndex, error.c_str());
            setErrorLocked(error, result);
            return false;
        }
        if (isMp4LikeFormat(formatName_) && fragmentedMp4_ && outputFmtCtx_ != nullptr
            && outputFmtCtx_->pb != nullptr) {
            avio_flush(outputFmtCtx_->pb);
            if (outputFmtCtx_->pb->error < 0) {
                setErrorLocked("mux flush: " + ffmpegErrorToString(outputFmtCtx_->pb->error),
                               outputFmtCtx_->pb->error);
                return false;
            }
        }
        lastDts_[outputIndex] = writtenDts;
        packetsWritten_.fetch_add(1);

        if (packet->stream_index == videoInputStreamIndex_) {
            ++videoPacketCount_;
            ++currentSegmentVideoPacketCount_;
        } else if (packet->stream_index == audioInputStreamIndex_) {
            ++audioPacketCount_;
            ++currentSegmentAudioPacketCount_;
        }
        return true;
    };

    if (packet->stream_index == audioInputStreamIndex_ && audioBitstreamFilter_ != nullptr) {
        result = av_bsf_send_packet(audioBitstreamFilter_, recordPacket);
        av_packet_free(&recordPacket);
        if (result < 0) {
            const std::string error = ffmpegErrorToString(result);
            setErrorLocked(error, result);
            return false;
        }

        AVPacket *filteredPacket = av_packet_alloc();
        if (filteredPacket == nullptr) {
            setErrorLocked("av_packet_alloc failed", -1);
            return false;
        }
        bool success = true;
        while (true) {
            result = av_bsf_receive_packet(audioBitstreamFilter_, filteredPacket);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                break;
            }
            if (result < 0) {
                setErrorLocked(ffmpegErrorToString(result), result);
                success = false;
                break;
            }
            if (!writeOutputPacket(filteredPacket, audioBitstreamFilter_->time_base_out)) {
                success = false;
                break;
            }
            av_packet_unref(filteredPacket);
        }
        av_packet_free(&filteredPacket);
        return success;
    }

    const bool success = writeOutputPacket(recordPacket, inputStream->time_base);
    av_packet_free(&recordPacket);
    return success;
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
    writeErrors_.fetch_add(1);
    lastError_ = message;
    lastErrorCode_ = errorCode;
    state_ = RecorderState::Error;
    waitingForKeyFrame_ = false;
    LOGE("recorder error code=%d message=%s", errorCode, message.c_str());
}
