#ifndef MOTRO_NATIVE_PLAYER_H
#define MOTRO_NATIVE_PLAYER_H

#include "PlayerRemuxRecorder.h"
#include "PlayerOptions.h"
#include "NativeYuvGlRenderer.h"
#include "NativeOesRenderer.h"
#include "NativeNv12GlRenderer.h"
#include "ThermalConfig.h"
#include "VideoRenderer.h"

#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AVCodecContext;
struct AVFormatContext;
struct AVFrame;
struct AVPacket;
struct SwsContext;
struct SwrContext;

// A3: bounded low-latency PCM queue decoupling the playback thread (producer)
// from the audio output worker (consumer). PCM contract is fixed:
// S16 / 48000 Hz / stereo / interleaved. The producer never blocks; overflow
// drops the oldest blocks to keep the live edge.
class AudioPcmQueue {
public:
    struct Block {
        std::vector<uint8_t> data;   // owned PCM bytes (S16/48k/stereo interleaved)
        int64_t startPtsUs = 0;      // media start PTS of the block
        int64_t sampleCount = 0;     // samples per channel in this block
        int64_t generation = 0;      // discontinuity identity (reconnect/source change)
    };

    void configure(int64_t targetDurationUs, int64_t maxDurationUs);
    void enqueue(Block block);
    bool waitAndDequeue(Block &out);
    void flush();
    void requestStop();
    void resetForRestart();
    void clearStats();

    int64_t durationUs() const;
    int64_t blockCount() const;
    int64_t byteCount() const;
    int64_t dropCount() const;
    int64_t droppedSampleCount() const;
    int64_t flushCount() const;
    int64_t highWatermarkUs() const;

private:
    static int64_t blockDurationUs(const Block &block);

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<Block> blocks_;
    int64_t bufferedDurationUs_ = 0;
    int64_t targetDurationUs_ = 150000;
    int64_t maxDurationUs_ = 250000;
    bool stopRequested_ = false;
    int64_t dropCount_ = 0;
    int64_t droppedSampleCount_ = 0;
    int64_t flushCount_ = 0;
    int64_t highWatermarkUs_ = 0;
};

// Test-only hook: makes the audio output worker's null sink sleep before each
// consumed block, to validate that a slow consumer never blocks the playback
// thread. Default 0 = no delay (production behavior unchanged).
void setAudioWorkerBackpressureTestDelayMs(int delayMs);

enum class PlayerState {
    Idle,
    Preparing,
    Prepared,
    Playing,
    Paused,
    Disconnected,
    WaitingSource,
    Reconnecting,
    Reconnected,
    Stopping,
    Stopped,
    Error,
    Released
};

class NativePlayer {
public:
    static void setJavaVm(JavaVM *javaVm);

    explicit NativePlayer(int64_t logicalHandle);
    ~NativePlayer();

    NativePlayer(const NativePlayer &) = delete;
    NativePlayer &operator=(const NativePlayer &) = delete;

    std::string setSurface(JNIEnv *env, jobject surface);
    std::string clearSurface();
    std::string setAudioCallback(JNIEnv *env, jobject callback);
    std::string setPlayerEventListener(JNIEnv *env, jobject listener);
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
    std::string setThermalEnabled(bool enabled);
    std::string setThermalPalette(int palette);
    std::string setThermalAgcEnabled(bool enabled);
    std::string setThermalGamma(float gamma);
    std::string setThermalWindow(float blackPoint, float whitePoint);
    ThermalConfig getThermalConfig() const;
    void notifyOesFrameAvailable();
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
    bool prepareRealtimeInputForStart();
    bool reconnectInput(int readErrorCode);
    bool switchTransportInput();
    bool waitForReconnectDelay(int delayMs);
    int reconnectDelayForAttempt(int attempt) const;
    bool shouldTreatOpenErrorAsSourceMissing(const std::string &errorMessage) const;
    void syncReconnectPolicyFromOptionsLocked();
    void notifyPlayerEvent(const std::string &eventName,
                           PlayerState state,
                           int64_t attempt,
                           int maxRetry,
                           int delayMs,
                           int errorCode,
                           const std::string &errorMessage);
    void beginStartupKeyFrameWait(const char *reason);
    void finishStartupKeyFrameWait(const char *reason);
    bool commitDecodedVideoFormatIfChanged(int frameWidth, int frameHeight, int frameFormat,
                                           int yStride, int colorRange);
    void resetRealtimeClockForFormatDiscontinuity();
    bool renderFrame(AVFrame *frame);
    bool renderMediaCodecFrame(AVFrame *frame, int64_t ptsUs);
    bool renderNv12GlFrame(AVFrame *frame, int frameWidth, int frameHeight, int64_t ptsUs);
    bool renderSoftwareYuvGlFrame(AVFrame *frame, int frameWidth, int frameHeight);
    void renderOesPendingFrameIfReady();
    bool isSoftwareYuvGlFrameSupported(int frameFormat) const;
    void updateAgcState(AVFrame *frame, const ThermalConfig &thermal);
    bool shouldDropRealtimePacket(const AVPacket *packet);
    bool shouldDropRealtimeFrame(int64_t ptsUs);
    bool resolveMasterClockUs(const PlayerOptions &options, int64_t videoPtsUs, int64_t &masterClockUs, SyncMaster &effectiveMaster);
    SyncMaster effectiveSyncMaster(const PlayerOptions &options) const;
    std::string effectiveSyncMasterName(const PlayerOptions &options) const;
    void updateVideoDelayStats(int64_t delayUs);
    void decodeAudioPacket(const AVPacket *packet);
    void drainAudioDecodedFrames();
    void logRateLimitedAudioDecodeError(int errorCode);
    bool convertAudioFrameToPcm(AVFrame *frame);
    bool configureAudioSwrContext(int inputFormat, int inputSampleRate,
                                  uint64_t inputLayoutMask, int inputChannels);
    void logRateLimitedAudioResampleError(const char *message);
    void audioOutputWorkerLoop();
    void startAudioOutputWorker();
    void stopAudioOutputWorker();
    void flushAudioPcmForDiscontinuity();
    void resetRealtimeClock();
    void saveLastFrame(const uint8_t *rgbaData, int lineSize, int width, int height, int64_t ptsUs);
    void clearLastFrame();
    void markFrameRendered();
    void deleteSurfaceGlobalRefLocked(JNIEnv *env);
    void resetStats();
    void releaseFfmpegResources();
    void setState(PlayerState state, const std::string &errorMessage = "");
    std::string buildStateJsonLocked() const;
    std::string buildReconnectJson() const;
    bool isReleased() const;

    mutable std::mutex mutex_;
    mutable std::mutex surfaceMutex_;
    mutable std::mutex eventListenerMutex_;
    mutable std::mutex thermalConfigMutex_;
    ThermalConfig thermalConfig_;
    VideoRenderer renderer_;
    NativeYuvGlRenderer yuvGlRenderer_;
    NativeOesRenderer oesRenderer_;
    NativeNv12GlRenderer nv12GlRenderer_;
    std::atomic<bool> oesFramePending_{false};
    PlayerRemuxRecorder remuxRecorder_;
    std::thread playbackThread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> pauseRequested_{false};
    std::atomic<bool> released_{false};
    const int64_t logicalHandle_;

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
    int64_t keyFrameCatchupLatencyUs_ = 2000000;
    std::atomic<bool> preferUdpTransport_{false};
    std::atomic<bool> transportSwitchRequested_{false};
    std::atomic<int64_t> lastVideoDelayUs_{0};
    std::atomic<int64_t> totalVideoDelayUs_{0};
    std::atomic<int64_t> videoDelaySampleCount_{0};
    std::atomic<int64_t> maxVideoDelayUs_{0};
    std::atomic<int64_t> wallClockUs_{0};
    std::atomic<int64_t> decodedFormatChangeCount_{0};
    std::atomic<int64_t> realtimeClockFormatResetCount_{0};
    std::atomic<int64_t> inputOpenCount_{0};
    std::atomic<int64_t> videoDecoderOpenCount_{0};
    std::atomic<int64_t> hardwareDecoderOpenCount_{0};
    std::atomic<int64_t> realtimeStartInputReuseCount_{0};
    std::atomic<int64_t> startupFreshnessFlushCount_{0};
    std::atomic<int64_t> startupFreshnessFlushErrorCount_{0};
    std::atomic<int64_t> preparedAtTimeMs_{0};
    std::atomic<int64_t> lastPrepareCostUs_{-1};
    std::atomic<int64_t> lastPrepareToStartDelayMs_{-1};
    std::atomic<int64_t> startToFirstFrameMs_{-1};

    AVFormatContext *formatContext_ = nullptr;
    AVCodecContext *videoCodecContext_ = nullptr;
    AVCodecContext *audioCodecContext_ = nullptr;
    AVFrame *audioDecodedFrame_ = nullptr;
    struct SwrContext *audioSwrContext_ = nullptr;
    std::vector<uint8_t> audioPcmBuffer_;
    int audioSwrInputSampleFormat_ = -1;
    int audioSwrInputSampleRate_ = 0;
    int audioSwrInputChannels_ = 0;
    uint64_t audioSwrInputLayoutMask_ = 0;
    bool audioPcmFirstConvertLogged_ = false;
    SwsContext *swsContext_ = nullptr;
    AVPacket *packet_ = nullptr;
    AVFrame *decodedFrame_ = nullptr;
    AVFrame *latestFrame_ = nullptr;
    AVFrame *rgbaFrame_ = nullptr;
    std::vector<uint8_t> rgbaBuffer_;
    jobject surfaceGlobalRef_ = nullptr;
    jobject playerEventListenerGlobalRef_ = nullptr;
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
    int swsSourceWidth_ = 0;
    int swsSourceHeight_ = 0;
    int decodedFrameFormat_ = -1;
    uint64_t decodedFormatGeneration_ = 0;
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
    std::atomic<int64_t> yuvGlNoSurfaceFrameCount_{0};
    std::atomic<int64_t> nv12GlRenderedFrameCount_{0};
    std::atomic<int64_t> nv12GlFallbackFrameCount_{0};
    std::atomic<int64_t> nv12GlNoSurfaceFrameCount_{0};
    std::atomic<int64_t> nv12ThermalRenderedCount_{0};
    std::atomic<int> lastNv12ThermalRenderMode_{0};
    std::atomic<bool> nv12AgcValid_{false};
    std::atomic<float> nv12AgcBlackPoint_{0.0f};
    std::atomic<float> nv12AgcWhitePoint_{1.0f};
    std::atomic<int64_t> nv12AgcUpdateCount_{0};
    std::atomic<int64_t> nv12AgcInvalidCount_{0};
    std::atomic<int> nv12AgcFrameCounter_{0};
    std::atomic<int> nv12AgcLastFrameWidth_{0};
    std::atomic<int> nv12AgcLastFrameHeight_{0};
    std::atomic<int64_t> nv12GlLastRenderCostUs_{-1};
    std::atomic<int64_t> nv12GlTotalRenderCostUs_{0};
    std::atomic<int64_t> nv12GlRenderCostSampleCount_{0};
    std::atomic<int64_t> nv12GlMaxRenderCostUs_{0};
    std::atomic<int64_t> nv12GlLastUploadCostUs_{-1};
    std::atomic<int64_t> nv12GlTotalUploadCostUs_{0};
    std::atomic<int64_t> nv12GlUploadCostSampleCount_{0};
    std::atomic<int64_t> nv12GlMaxUploadCostUs_{0};
    std::atomic<int64_t> oesFrameAvailableCount_{0};
    std::atomic<int64_t> oesFrameRenderedCount_{0};
    std::atomic<int64_t> oesRenderFailCount_{0};
    std::atomic<int64_t> oesThermalRenderedCount_{0};
    std::atomic<int> lastOesThermalRenderMode_{0};
    std::atomic<int> oesAgcFrameCounter_{0};
    std::atomic<int64_t> whiteHotRenderedFrameCount_{0};
    std::atomic<int64_t> ironbowRenderedFrameCount_{0};
    std::atomic<int> lastThermalRenderMode_{0};
    std::atomic<bool> agcValid_{false};
    std::atomic<float> agcBlackPoint_{0.0f};
    std::atomic<float> agcWhitePoint_{1.0f};
    std::atomic<int64_t> agcUpdateCount_{0};
    std::atomic<int> agcFrameCounter_{0};
    std::atomic<int64_t> droppedVideoPacketCount_{0};
    std::atomic<int64_t> packetDropBeforeDecodeCount_{0};
    std::atomic<int64_t> frameDropBeforeRenderCount_{0};
    std::atomic<bool> startupKeyFrameWaitActive_{false};
    std::atomic<int64_t> startupKeyFrameDroppedPacketCount_{0};
    std::atomic<int64_t> lastFrameCacheUpdateCount_{0};
    std::atomic<int64_t> lastFrameCacheSkippedCount_{0};
    std::atomic<int64_t> lastFrameCacheCandidateCount_{0};
    std::atomic<int> lastFrameYStride_{0};
    std::atomic<int> lastFrameColorRange_{0};  // AVCOL_RANGE_UNSPECIFIED == 0
    std::atomic<int> lastFrameOutputType_{0};  // 1 yuv420p_cpu, 2 nv12_cpu, 3 direct_surface, 4 external_oes
    // Packed so Stats observes actual renderer and fallback reason from one
    // coherent runtime commit. Low byte: renderer (1 RGBA, 2 YUV GL, 3 NV12 GL,
    // 4 OES GL, 5 direct Surface); next byte: fallback reason (1 NV12, 2 YUV).
    std::atomic<uint32_t> rendererState_{0};
    std::atomic<int64_t> lastReadPacketTimeMs_{0};
    std::atomic<int64_t> lastVideoFrameTimeMs_{0};
    std::atomic<int64_t> lastAudioFrameTimeMs_{0};
    std::atomic<int64_t> lastRenderTimeMs_{0};
    std::atomic<int64_t> lastSnapshotTimeMs_{0};
    std::atomic<int64_t> lastReadFrameCostUs_{-1};
    std::atomic<int64_t> totalReadFrameCostUs_{0};
    std::atomic<int64_t> readFrameCostSampleCount_{0};
    std::atomic<int64_t> maxReadFrameCostUs_{0};
    std::atomic<int64_t> lastSendPacketCostUs_{-1};
    std::atomic<int64_t> lastReceiveFrameCostUs_{-1};
    std::atomic<int64_t> totalDecodeCostUs_{0};
    std::atomic<int64_t> decodeCostSampleCount_{0};
    std::atomic<int64_t> maxDecodeCostUs_{0};
    std::atomic<int64_t> lastSwsScaleCostUs_{-1};
    std::atomic<int64_t> totalSwsScaleCostUs_{0};
    std::atomic<int64_t> swsScaleCostSampleCount_{0};
    std::atomic<int64_t> maxSwsScaleCostUs_{0};
    std::atomic<int64_t> lastRenderCostUs_{-1};
    std::atomic<int64_t> lastRenderLockCostUs_{-1};
    std::atomic<int64_t> lastRenderCopyCostUs_{-1};
    std::atomic<int64_t> lastRenderPostCostUs_{-1};
    std::atomic<int64_t> totalRenderCostUs_{0};
    std::atomic<int64_t> renderCostSampleCount_{0};
    std::atomic<int64_t> maxRenderCostUs_{0};
    std::atomic<int64_t> lastFrameProcessCostUs_{-1};
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
    // A1: decoded audio frame pipeline (no PCM yet; decode -> count -> discard).
    std::atomic<bool> audioFlushRequested_{false};
    std::atomic<int64_t> audioDecodedSampleCount_{0};
    std::atomic<int64_t> audioDecodeErrorCount_{0};
    std::atomic<int64_t> lastDecodedAudioPtsUs_{0};
    std::atomic<int> lastDecodedAudioNbSamples_{0};
    std::atomic<int> lastDecodedAudioSampleRate_{0};
    std::atomic<int> lastDecodedAudioChannels_{0};
    std::atomic<int> lastDecodedAudioSampleFormat_{-1};
    std::atomic<int64_t> lastAudioDecodeCostUs_{-1};
    std::atomic<int64_t> totalAudioDecodeCostUs_{0};
    std::atomic<int64_t> audioDecodeCostSampleCount_{0};
    std::atomic<int64_t> maxAudioDecodeCostUs_{0};
    std::atomic<int64_t> lastAudioDecodeErrorLogMs_{0};
    bool audioDecodeFirstFrameLogged_ = false;
    // A2: decoded AVFrame -> PCM S16/48k/stereo interleaved via libswresample.
    std::atomic<int64_t> audioSwrReconfigureCount_{0};
    std::atomic<int64_t> audioPcmBlockCount_{0};
    std::atomic<int64_t> audioPcmSampleCount_{0};
    std::atomic<int64_t> audioPcmByteCount_{0};
    std::atomic<int64_t> audioResampleErrorCount_{0};
    std::atomic<int64_t> lastPcmPtsUs_{0};
    std::atomic<int64_t> lastAudioResampleCostUs_{-1};
    std::atomic<int64_t> totalAudioResampleCostUs_{0};
    std::atomic<int64_t> audioResampleCostSampleCount_{0};
    std::atomic<int64_t> maxAudioResampleCostUs_{0};
    std::atomic<int64_t> lastAudioResampleErrorLogMs_{0};
    // A3: bounded PCM queue + audio output worker (null/discard sink).
    AudioPcmQueue audioPcmQueue_;
    std::thread audioOutputWorkerThread_;
    mutable std::mutex audioWorkerMutex_;
    std::atomic<bool> audioWorkerRunning_{false};
    std::atomic<int64_t> audioQueueGeneration_{0};
    std::atomic<int64_t> audioWorkerConsumedBlockCount_{0};
    std::atomic<int64_t> audioWorkerConsumedSampleCount_{0};
    std::atomic<int64_t> audioWorkerConsumedByteCount_{0};
    std::atomic<int64_t> lastConsumedPcmPtsUs_{0};
    std::atomic<bool> reconnectEnabled_{true};
    std::atomic<bool> reconnecting_{false};
    std::atomic<bool> infiniteReconnect_{true};
    std::atomic<bool> reconnectOnEof_{true};
    std::atomic<bool> reconnectOn404_{true};
    std::atomic<bool> keepWaitingWhenSourceMissing_{true};
    std::atomic<int> reconnectMaxRetryCount_{-1};
    std::atomic<int> reconnectRetryDelayMs_{1000};
    std::atomic<int> reconnectMaxDelayMs_{5000};
    std::atomic<int64_t> reconnectAttemptCount_{0};
    std::atomic<int64_t> reconnectSuccessCount_{0};
    std::atomic<int64_t> lastReconnectTimeMs_{0};
    std::atomic<int> lastReconnectErrorCode_{0};
    std::atomic<bool> reconnectExhausted_{false};
    std::atomic<bool> waitingSource_{false};
    std::atomic<int64_t> lastDisconnectTimeMs_{0};
    std::atomic<int64_t> lastReconnectSuccessTimeMs_{0};
};

#endif // MOTRO_NATIVE_PLAYER_H
