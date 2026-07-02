#ifndef MOTRO_PLAYER_REMUX_RECORDER_H
#define MOTRO_PLAYER_REMUX_RECORDER_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct AVFormatContext;
struct AVPacket;

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

class PlayerRemuxRecorder {
public:
    PlayerRemuxRecorder();
    ~PlayerRemuxRecorder();

    PlayerRemuxRecorder(const PlayerRemuxRecorder &) = delete;
    PlayerRemuxRecorder &operator=(const PlayerRemuxRecorder &) = delete;

    std::string start(AVFormatContext *inputFmtCtx, const std::string &outputPath);
    std::string startSegmented(AVFormatContext *inputFmtCtx, const std::string &outputPattern, int segmentDurationSec);
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
    std::string startLocked(AVFormatContext *inputFmtCtx,
                            const std::string &outputPathOrPattern,
                            bool segmentMode,
                            int segmentDurationSec);
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
    std::vector<int> streamMapping_;
    std::vector<int64_t> firstPts_;
    std::vector<int64_t> firstDts_;
    std::vector<int64_t> lastDts_;

    RecorderState state_ = RecorderState::Idle;
    std::string outputPath_;
    std::string outputPattern_;
    std::string currentSegmentPath_;
    std::string lastSegmentPath_;
    std::string formatName_;
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
    bool waitingForKeyFrame_ = false;
    bool headerWritten_ = false;
    bool segmentMode_ = false;
};

#endif // MOTRO_PLAYER_REMUX_RECORDER_H