#ifndef MOTRO_PLAYER_REMUX_RECORDER_H
#define MOTRO_PLAYER_REMUX_RECORDER_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct AVFormatContext;
struct AVPacket;
struct AVBSFContext;

struct RemuxRecordConfig {
    std::string outputPathOrPattern;
    std::string formatName;
    bool segmentMode = false;
    int segmentDurationSec = 0;
    bool fragmentedMp4 = true;
};

enum class RecorderState {
    Idle,
    Starting,
    WaitingKeyFrame,
    Recording,
    Stopping,
    Stopped,
    Error,
    Released
};

// 直接重封装输入压缩包，不重新编码；录制音频不依赖用户是否开启音频监听。
class PlayerRemuxRecorder {
public:
    PlayerRemuxRecorder();
    ~PlayerRemuxRecorder();

    PlayerRemuxRecorder(const PlayerRemuxRecorder &) = delete;
    PlayerRemuxRecorder &operator=(const PlayerRemuxRecorder &) = delete;

    std::string start(AVFormatContext *inputFmtCtx, const std::string &outputPath);
    std::string startSegmented(AVFormatContext *inputFmtCtx, const std::string &outputPattern, int segmentDurationSec);
    std::string startWithConfig(AVFormatContext *inputFmtCtx, const RemuxRecordConfig &config);
    // 接收解复用后的压缩包，依次执行关键帧筛选、分段判断和封装写入。
    void onPacket(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    std::string stop();
    std::string getState();
    void setAudioPlaybackState(bool enabled);
    bool isRecording() const;
    int64_t getVideoPacketCount() const;
    int64_t getAudioPacketCount() const;
    int64_t getCompletedSegmentCount() const;
    void release();

private:
    // 以下 Locked 方法要求调用方持有 mutex_，用于串行化输出文件与录制状态变化。
    std::string startLocked(AVFormatContext *inputFmtCtx,
                            const RemuxRecordConfig &config);
    int openOutputLocked(AVFormatContext *inputFmtCtx, const std::string &outputPath);
    int closeOutputLocked(bool writeTrailer);
    void resetLocked(bool keepReleasedState);
    std::string buildStateJsonLocked(bool success) const;
    bool shouldWritePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    bool rotateSegmentIfNeededLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    bool writePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    std::string makeSegmentPathLocked(int segmentIndex) const;
    void setErrorLocked(const std::string &message, int errorCode = -1);

    mutable std::mutex mutex_;
    AVFormatContext *outputFmtCtx_ = nullptr;
    AVBSFContext *audioBitstreamFilter_ = nullptr;
    // 输入流索引到输出流索引的映射，未录制的流不写入输出文件。
    std::vector<int> streamMapping_;
    // 各流的初始时间戳与最近 DTS，用于输出时间戳归一化及单调性处理。
    std::vector<int64_t> firstPts_;
    std::vector<int64_t> firstDts_;
    std::vector<int64_t> lastDts_;

    RecorderState state_ = RecorderState::Idle;
    std::string outputPath_;
    std::string outputPattern_;
    std::string currentSegmentPath_;
    std::string lastSegmentPath_;
    std::string formatName_;
    std::string requestedFormatName_;
    std::string lastError_;
    int lastErrorCode_ = 0;
    int videoInputStreamIndex_ = -1;
    int audioInputStreamIndex_ = -1;
    int64_t videoPacketCount_ = 0;
    int64_t audioPacketCount_ = 0;
    int64_t currentSegmentVideoPacketCount_ = 0;
    int64_t currentSegmentAudioPacketCount_ = 0;
    int64_t completedSegmentCount_ = 0;
    int64_t startTimeUs_ = 0;
    int64_t stopTimeUs_ = 0;
    int64_t segmentDurationUs_ = 0;
    int64_t segmentStartPtsUs_ = 0;
    int64_t currentSegmentStartTimeUs_ = 0;
    int currentSegmentIndex_ = 0;
    bool hasVideo_ = false;
    bool sourceHasVideo_ = false;
    bool sourceHasAudio_ = false;
    bool videoStreamRecorded_ = false;
    bool audioStreamRecorded_ = false;
    bool audioPlaybackEnabled_ = false;
    // 有视频时等待可独立解码的关键帧再开始写包。
    bool waitingForKeyFrame_ = false;
    bool headerWritten_ = false;
    bool segmentMode_ = false;
    bool fragmentedMp4_ = true;
};

#endif // MOTRO_PLAYER_REMUX_RECORDER_H
