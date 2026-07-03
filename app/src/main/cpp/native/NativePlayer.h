#ifndef MOTRO_NATIVE_PLAYER_H
#define MOTRO_NATIVE_PLAYER_H

#include "PlayerRemuxRecorder.h"
#include "PlayerOptions.h"
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
    bool renderFrame(AVFrame *frame);
    bool shouldDropRealtimeFrame(int64_t ptsUs);
    void resetRealtimeClock();
    void saveLastFrame(const uint8_t *rgbaData, int lineSize, int width, int height, int64_t ptsUs);
    void clearLastFrame();
    void resetStats();
    void releaseFfmpegResources();
    void setState(PlayerState state, const std::string &errorMessage = "");
    std::string buildStateJsonLocked() const;
    std::string buildReconnectJson() const;
    bool isReleased() const;

    mutable std::mutex mutex_;
    VideoRenderer renderer_;
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
    int64_t maxRealtimeLatencyUs_ = 250000;
    int64_t keyFrameCatchupLatencyUs_ = 2000000;
    std::atomic<bool> preferUdpTransport_{false};
    std::atomic<bool> transportSwitchRequested_{false};
    std::atomic<int64_t> lastVideoDelayUs_{0};

    AVFormatContext *formatContext_ = nullptr;
    AVCodecContext *videoCodecContext_ = nullptr;
    AVCodecContext *audioCodecContext_ = nullptr;
    SwsContext *swsContext_ = nullptr;
    AVPacket *packet_ = nullptr;
    AVFrame *decodedFrame_ = nullptr;
    AVFrame *rgbaFrame_ = nullptr;
    std::vector<uint8_t> rgbaBuffer_;

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
    std::atomic<int64_t> videoFrameCount_{0};
    std::atomic<int64_t> audioFrameCount_{0};
    std::atomic<int64_t> renderedFrameCount_{0};
    std::atomic<int64_t> droppedVideoFrameCount_{0};
    std::atomic<int64_t> lastReadPacketTimeMs_{0};
    std::atomic<int64_t> lastVideoFrameTimeMs_{0};
    std::atomic<int64_t> lastAudioFrameTimeMs_{0};
    std::atomic<int64_t> lastRenderTimeMs_{0};
    std::atomic<int64_t> lastSnapshotTimeMs_{0};
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
