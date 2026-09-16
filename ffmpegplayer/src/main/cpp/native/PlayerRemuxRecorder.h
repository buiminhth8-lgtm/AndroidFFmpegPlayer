#ifndef MOTRO_PLAYER_REMUX_RECORDER_H
#define MOTRO_PLAYER_REMUX_RECORDER_H

#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <deque>
#include <memory>
#include <thread>
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

    std::string start(const std::string &outputPath);
    std::string startSegmented(const std::string &outputPattern, int segmentDurationSec);
    std::string startWithConfig(const RemuxRecordConfig &config);
    // 在输入所属线程复制流参数；快照不含 AVIO，也不借用输入上下文的内存。
    void setInput(AVFormatContext *inputFmtCtx);
    void clearInput();
    // 只引用压缩包并入队；不得获取生命周期锁或执行任何输出文件操作。
    void onPacket(const AVPacket *packet);
    static constexpr size_t kMaxQueuePackets = 512;
    static constexpr size_t kMaxQueueBytes = 16 * 1024 * 1024;
    std::string stop();
    std::string getState();
    void setAudioPlaybackState(bool enabled);
    bool isRecording() const;
    int64_t getVideoPacketCount() const;
    int64_t getAudioPacketCount() const;
    int64_t getCompletedSegmentCount() const;
    void release();

private:
    struct PacketDeleter { void operator()(AVPacket *packet) const; };
    using InputSnapshot = std::shared_ptr<AVFormatContext>;
    struct QueuedPacket {
        std::unique_ptr<AVPacket, PacketDeleter> packet;
        InputSnapshot input;
        size_t bytes;
    };
    enum class QueueFailure { None, Overflow, Allocation };
    void workerLoop(InputSnapshot input, RemuxRecordConfig config);
    void finishWorker();
    void stopAndJoin();
    void publishSnapshot();
    bool adoptInput(const InputSnapshot &input);
    // 保留原 Locked 命名；这些封装方法现在只由唯一的 Remux Worker 调用。
    // mutex_ 只保护队列和已发布快照，绝不能跨磁盘 I/O 持有。
    std::string startLocked(AVFormatContext *inputFmtCtx,
                            const RemuxRecordConfig &config);
    int openOutputLocked(AVFormatContext *inputFmtCtx, const std::string &outputPath);
    int closeOutputLocked(bool writeTrailer);
    void resetLocked(bool keepReleasedState);
    std::string buildStateDetails() const;
    bool shouldWritePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    bool rotateSegmentIfNeededLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    bool writePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx);
    std::string makeSegmentPathLocked(int segmentIndex) const;
    void setErrorLocked(const std::string &message, int errorCode = -1);

    mutable std::mutex mutex_;
    // 仅 start/stop/release 使用，允许等待 worker；播放线程永不获取此锁。
    std::mutex lifecycleMutex_;
    std::condition_variable queueCv_;
    std::condition_variable startCv_;
    std::thread worker_;
    std::deque<QueuedPacket> queue_;
    InputSnapshot input_;
    InputSnapshot workerInput_;
    std::atomic<bool> accepting_{false};
    bool released_ = false; // lifecycleMutex_
    bool stopRequested_ = false; // 以下队列/发布字段均由 mutex_ 保护
    bool startCompleted_ = false;
    QueueFailure queueFailure_ = QueueFailure::None;
    size_t queueBytes_ = 0;
    size_t queueHighWatermark_ = 0;
    size_t queueBytesHighWatermark_ = 0;
    uint64_t queueDrops_ = 0;
    std::string startResult_;
    std::string publishedDetails_;
    RecorderState publishedState_ = RecorderState::Idle;
    std::atomic<int64_t> packetsWritten_{0};
    std::atomic<int64_t> writeErrors_{0};
    std::atomic<int64_t> publishedVideoPackets_{0};
    std::atomic<int64_t> publishedAudioPackets_{0};
    std::atomic<int64_t> publishedSegments_{0};
    // 以下资源和非原子状态只由 worker 访问，队列中的输入快照延长流参数寿命。
    AVFormatContext *outputFmtCtx_ = nullptr;
    AVBSFContext *audioBitstreamFilter_ = nullptr;
    // 输入流索引到输出流索引的映射，未录制的流不写入输出文件。
    std::vector<int> streamMapping_;
    // 各流的初始时间戳与最近 DTS，用于输出时间戳归一化及单调性处理。
    std::vector<int64_t> firstPts_;
    std::vector<int64_t> firstDts_;
    std::vector<int64_t> lastDts_;
    std::vector<int64_t> timestampOffset_;

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
    std::atomic<bool> audioPlaybackEnabled_{false};
    // 有视频时等待可独立解码的关键帧再开始写包。
    bool waitingForKeyFrame_ = false;
    bool headerWritten_ = false;
    bool segmentMode_ = false;
    bool fragmentedMp4_ = true;
};

#endif // MOTRO_PLAYER_REMUX_RECORDER_H
