#ifndef MOTRO_NATIVE_PLAYER_H
#define MOTRO_NATIVE_PLAYER_H

#include "PlayerRemuxRecorder.h"
#include "PlayerOptions.h"
#include "NativeYuvGlRenderer.h"
#include "VideoRenderer.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AVCodecContext;
struct AVFormatContext;
struct AVFrame;
struct AVPacket;
struct SwsContext;

enum class PlayerState {
    Idle,
    Preparing,
    Prepared,
    Playing,
    Paused,
    Reconnecting,
    Stopping,
    Stopped,
    Error,
    Released
};

class NativePlayer {
public:
    static void setJavaVm(JavaVM *javaVm);

    NativePlayer();
    ~NativePlayer();

    NativePlayer(const NativePlayer &) = delete;
    NativePlayer &operator=(const NativePlayer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface);
    std::string clearSurface();
    std::string setAudioCallback(JNIEnv *env, jobject callback);
    std::string enableAudio(bool enabled);
    std::string prepare(const std::string &url, int timeoutMs);
    std::string start();
    std::string pause();
    std::string stop();
    std::string getState();
    std::string getStats();
    std::string setReconnectOptions(bool enabled, int maxRetryCount, int retryDelayMs);
    std::string getReconnectState();
    std::string setRtspTransport(const std::string &transport);
    std::string getRtspTransportState();
    std::string setLatencyMode(const std::string &mode);
    std::string setOption(const std::string &key, const std::string &value);
    std::string setHardwareDecode(bool enabled);
    std::string setHardwareRenderMode(const std::string &mode);
    std::string getLatencyConfig();
    std::string takeSnapshot(const std::string &outputPath);
    std::string startRecord(const std::string &outputPath);
    std::string startSegmentRecord(const std::string &outputPattern, int segmentDurationSec);
    std::string startRecordWithConfig(const std::string &outputPathOrPattern, const std::string &formatName, int segmentDurationSec);
    std::string stopRecord();
    std::string getRecordState();
    std::string release();

private:
    static int interruptCallback(void *opaque);

    void playbackLoop();
    int openInput(const std::string &url, int timeoutMs, bool resetStreamMetadata, std::string &errorMessage);
    bool refreshRealtimeInputForStart();
    bool reconnectInput(int readErrorCode);
    bool switchTransportInput();
    bool waitForReconnectDelay(int delayMs);
    void beginStartupKeyFrameWait(const char *reason);
    void finishStartupKeyFrameWait(const char *reason);
    bool renderFrame(AVFrame *frame);
    bool renderMediaCodecFrame(AVFrame *frame, int64_t ptsUs);
    bool renderSoftwareYuvGlFrame(AVFrame *frame, int frameWidth, int frameHeight, int64_t ptsUs);
    bool isSoftwareYuvGlFrameSupported(int frameFormat) const;
    bool shouldDropRealtimePacket(const AVPacket *packet);
    bool shouldDropRealtimeFrame(int64_t ptsUs);
    bool resolveMasterClockUs(const PlayerOptions &options, int64_t videoPtsUs, int64_t &masterClockUs, SyncMaster &effectiveMaster);
    SyncMaster effectiveSyncMaster(const PlayerOptions &options) const;
    std::string effectiveSyncMasterName(const PlayerOptions &options) const;
    void updateVideoDelayStats(int64_t delayUs);
    void resetRealtimeClock();
    void saveLastFrame(const uint8_t *rgbaData, int lineSize, int width, int height, int64_t ptsUs);
    void clearLastFrame();
    void deleteSurfaceGlobalRefLocked(JNIEnv *env);
    void resetStats();
    void releaseFfmpegResources();
    void setState(PlayerState state, const std::string &errorMessage = "");
    std::string buildStateJsonLocked() const;
    std::string buildReconnectJson() const;
    bool isReleased() const;

    mutable std::mutex mutex_;
    mutable std::mutex surfaceMutex_;
    VideoRenderer renderer_;
    NativeYuvGlRenderer yuvGlRenderer_;
    PlayerRemuxRecorder remuxRecorder_;
    std::thread playbackThread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> pauseRequested_{false};
    std::atomic<bool> released_{false};

    PlayerState state_ = PlayerState::Idle;
    std::string url_;
    std::string errorMessage_;
    std::string lastReconnectError_;
    std::string rtspTransportMode_ = "tcp";
    PlayerOptions playerOptions_;
    SourceType sourceType_ = SourceType::OTHER;
    int timeoutMs_ = 5000;
    bool isRealtimeInput_ = false;
    bool realtimeClockInitialized_ = false;
    int64_t realtimeFirstPtsUs_ = 0;
    int64_t realtimeStartWallUs_ = 0;
    int64_t lastRealtimeDropLogMs_ = 0;
    bool dropUntilKeyFrame_ = false;
    bool startupKeyFrameWait_ = false;
    int64_t startupKeyFrameWaitStartMs_ = 0;
    int64_t maxRealtimeLatencyUs_ = 250000;
    int64_t keyFrameCatchupLatencyUs_ = 2000000;
    std::atomic<bool> preferUdpTransport_{false};
    std::atomic<bool> transportSwitchRequested_{false};
    std::atomic<int64_t> lastVideoDelayUs_{0};
    std::atomic<int64_t> totalVideoDelayUs_{0};
    std::atomic<int64_t> videoDelaySampleCount_{0};
    std::atomic<int64_t> maxVideoDelayUs_{0};
    std::atomic<int64_t> wallClockUs_{0};

    AVFormatContext *formatContext_ = nullptr;
    AVCodecContext *videoCodecContext_ = nullptr;
    AVCodecContext *audioCodecContext_ = nullptr;
    SwsContext *swsContext_ = nullptr;
    AVPacket *packet_ = nullptr;
    AVFrame *decodedFrame_ = nullptr;
    AVFrame *latestFrame_ = nullptr;
    AVFrame *rgbaFrame_ = nullptr;
    std::vector<uint8_t> rgbaBuffer_;
    jobject surfaceGlobalRef_ = nullptr;
    bool mediaCodecContextInitialized_ = false;

    int videoStreamIndex_ = -1;
    int audioStreamIndex_ = -1;
    int videoWidth_ = 0;
    int videoHeight_ = 0;
    int audioSampleRate_ = 0;
    int audioChannels_ = 0;
    int audioSampleFormat_ = -1;
    std::string audioSampleFormatName_;
    std::string audioDecodeError_;
    std::string audioPlayError_;
    int swsSourceFormat_ = -1;
    double fps_ = 25.0;
    std::string videoCodec_;
    std::string audioCodec_;
    std::string lastFrameFormatName_;

    mutable std::mutex lastFrameMutex_;
    std::vector<uint8_t> lastRgbaFrame_;
    int lastFrameWidth_ = 0;
    int lastFrameHeight_ = 0;
    int lastFrameStride_ = 0;
    int64_t lastFramePtsUs_ = 0;
    bool hasLastFrame_ = false;

    std::atomic<int64_t> readPacketCount_{0};
    std::atomic<int64_t> videoPacketCount_{0};
    std::atomic<int64_t> audioPacketCount_{0};
    std::atomic<int64_t> inputPacketBytes_{0};
    std::atomic<int64_t> videoPacketBytes_{0};
    std::atomic<int64_t> audioPacketBytes_{0};
    std::atomic<int64_t> streamBitRate_{0};
    std::atomic<int64_t> videoBitRate_{0};
    std::atomic<int64_t> audioBitRate_{0};
    std::atomic<int64_t> videoFrameCount_{0};
    std::atomic<int64_t> audioFrameCount_{0};
    std::atomic<int64_t> renderedFrameCount_{0};
    std::atomic<int64_t> droppedVideoFrameCount_{0};
    std::atomic<int64_t> hardwareDecodedFrameCount_{0};
    std::atomic<int64_t> hardwareRenderedFrameCount_{0};
    std::atomic<int64_t> hardwareDroppedFrameCount_{0};
    std::atomic<int64_t> softwareDecodedFrameCount_{0};
    std::atomic<int64_t> softwareRenderedFrameCount_{0};
    std::atomic<int64_t> yuvGlRenderedFrameCount_{0};
    std::atomic<int64_t> yuvGlFallbackFrameCount_{0};
    std::atomic<int64_t> droppedVideoPacketCount_{0};
    std::atomic<int64_t> packetDropBeforeDecodeCount_{0};
    std::atomic<int64_t> frameDropBeforeRenderCount_{0};
    std::atomic<bool> startupKeyFrameWaitActive_{false};
    std::atomic<int64_t> startupKeyFrameDroppedPacketCount_{0};
    std::atomic<int64_t> lastFrameCacheUpdateCount_{0};
    std::atomic<int64_t> lastFrameCacheSkippedCount_{0};
    std::atomic<int64_t> lastFrameCacheCandidateCount_{0};
    std::atomic<int64_t> lastReadPacketTimeMs_{0};
    std::atomic<int64_t> lastVideoFrameTimeMs_{0};
    std::atomic<int64_t> lastAudioFrameTimeMs_{0};
    std::atomic<int64_t> lastRenderTimeMs_{0};
    std::atomic<int64_t> lastSnapshotTimeMs_{0};
    std::atomic<int64_t> lastReadFrameCostUs_{0};
    std::atomic<int64_t> totalReadFrameCostUs_{0};
    std::atomic<int64_t> readFrameCostSampleCount_{0};
    std::atomic<int64_t> maxReadFrameCostUs_{0};
    std::atomic<int64_t> lastSendPacketCostUs_{0};
    std::atomic<int64_t> lastReceiveFrameCostUs_{0};
    std::atomic<int64_t> totalDecodeCostUs_{0};
    std::atomic<int64_t> decodeCostSampleCount_{0};
    std::atomic<int64_t> maxDecodeCostUs_{0};
    std::atomic<int64_t> lastSwsScaleCostUs_{0};
    std::atomic<int64_t> totalSwsScaleCostUs_{0};
    std::atomic<int64_t> swsScaleCostSampleCount_{0};
    std::atomic<int64_t> maxSwsScaleCostUs_{0};
    std::atomic<int64_t> lastRenderCostUs_{0};
    std::atomic<int64_t> lastRenderLockCostUs_{0};
    std::atomic<int64_t> lastRenderCopyCostUs_{0};
    std::atomic<int64_t> lastRenderPostCostUs_{0};
    std::atomic<int64_t> totalRenderCostUs_{0};
    std::atomic<int64_t> renderCostSampleCount_{0};
    std::atomic<int64_t> maxRenderCostUs_{0};
    std::atomic<int64_t> lastFrameProcessCostUs_{0};
    std::atomic<int64_t> totalFrameProcessCostUs_{0};
    std::atomic<int64_t> frameProcessCostSampleCount_{0};
    std::atomic<int64_t> maxFrameProcessCostUs_{0};
    std::atomic<int64_t> startPlayTimeMs_{0};
    std::atomic<int64_t> audioClockUs_{0};
    std::atomic<int64_t> videoClockUs_{0};
    std::atomic<bool> sourceHasVideo_{false};
    std::atomic<bool> sourceHasAudio_{false};
    std::atomic<bool> audioEnabled_{false};
    std::atomic<bool> audioPlayable_{false};
    std::atomic<bool> audioDecodeOpened_{false};
    std::atomic<bool> audioCallbackSet_{false};
    std::atomic<bool> reconnectEnabled_{true};
    std::atomic<bool> reconnecting_{false};
    std::atomic<int> reconnectMaxRetryCount_{3};
    std::atomic<int> reconnectRetryDelayMs_{1000};
    std::atomic<int64_t> reconnectAttemptCount_{0};
    std::atomic<int64_t> reconnectSuccessCount_{0};
    std::atomic<int64_t> lastReconnectTimeMs_{0};
};

#endif // MOTRO_NATIVE_PLAYER_H
