#ifndef MOTRO_PLAYER_OPTIONS_H
#define MOTRO_PLAYER_OPTIONS_H

#include <cstdint>
#include <string>

enum class RtspTransport {
    TCP,
    UDP,
    UDP_MULTICAST,
    AUTO
};

enum class LatencyMode {
    LOW_LATENCY,
    ULTRA_LOW_LATENCY,
    BALANCED,
    STABLE
};

enum class SyncMaster {
    AUDIO,
    VIDEO,
    WALL_CLOCK
};

enum class SourceType {
    RTSP,
    HLS,
    RTMP,
    HTTP,
    LOCAL,
    OTHER
};

enum class RenderMode {
    SOFTWARE_RGBA,
    SOFTWARE_YUV_GL,
    MEDIACODEC_SURFACE,
    MEDIACODEC_OES,
    MEDIACODEC_NV12_GL
};

// 播放器配置与实际解码结果；带 Us 的时长为微秒，带 Ms 的重连间隔为毫秒。
struct PlayerOptions {
    RtspTransport rtspTransport = RtspTransport::TCP;
    LatencyMode latencyMode = LatencyMode::BALANCED;
    bool enableHardwareDecode = false;
    RenderMode renderMode = RenderMode::SOFTWARE_RGBA;
    // 硬件解码初始化失败时是否允许改用软件解码。
    bool hardwareDecodeAllowFallback = true;

    bool infiniteReconnect = true;
    bool reconnectOnEof = true;
    bool reconnectOn404 = true;
    bool keepWaitingWhenSourceMissing = true;
    int reconnectInitialDelayMs = 1000;
    int reconnectMaxDelayMs = 5000;
    // 重试次数上限；负值配合无限重连策略表示持续等待源恢复。
    int reconnectMaxRetry = -1;

    std::string requestedDecoderName;
    std::string actualDecoderName;
    bool usingHardwareDecoder = false;
    bool hardwareDecodeFallbackUsed = false;
    std::string hardwareDecodeError;

    int64_t openTimeoutUs = 5000000;
    int64_t readTimeoutUs = 5000000;

    // 探测输入格式使用的字节预算，减小可缩短探测但可能影响流信息识别。
    int64_t probesize = 131072;
    // 流分析时长预算，单位微秒。
    int64_t analyzeduration = 200000;
    int maxProbePackets = 128;

    int64_t maxDelayUs = 200000;
    // RTP 重排序队列大小；负值时不显式传递该选项，沿用 FFmpeg 默认行为。
    int reorderQueueSize = -1;
    int socketBufferSize = 262144;

    bool fflagsNoBuffer = false;
    bool avioDirect = false;
    bool lowDelayDecode = false;
    bool tcpNoDelay = true;
    bool enableFrameDrop = true;
    bool enablePacketDrop = false;
    bool enableLatestFrameOnly = false;
    bool skipNonRef = false;

    int decoderThreadCount = 1;
    int64_t dropLateFrameThresholdUs = 500000;
    int64_t dropLatePacketThresholdUs = 500000;
    // RGBA 截图缓存的更新采样间隔，用于平衡缓存新鲜度和复制开销。
    int cacheLastFrameEveryN = 1;
    SyncMaster syncMaster = SyncMaster::AUDIO;
};

SourceType detectSourceType(const std::string &url);
bool isRtspSource(SourceType sourceType);

std::string rtspTransportName(RtspTransport transport);
std::string latencyModeName(LatencyMode mode);
std::string syncMasterName(SyncMaster syncMaster);
std::string sourceTypeName(SourceType sourceType);
std::string renderModeName(RenderMode renderMode);
std::string effectiveRtspTransportName(const PlayerOptions &options, bool preferUdpInAuto);

bool parseRtspTransport(const std::string &value, RtspTransport &transport);
bool parseLatencyMode(const std::string &value, LatencyMode &mode);
bool parseSyncMaster(const std::string &value, SyncMaster &syncMaster);
bool parseRenderMode(const std::string &value, RenderMode &renderMode);

PlayerOptions makePlayerOptions(RtspTransport transport, LatencyMode mode);
// 把延迟档位展开为探测、缓存和丢帧等具体参数。
void applyLatencyProfile(PlayerOptions &options);

// 解析并校验单个配置项；失败时通过 errorMessage 返回原因。
bool setPlayerOptionValue(PlayerOptions &options, const std::string &key, const std::string &value, std::string &errorMessage);
std::string playerOptionsToJson(const PlayerOptions &options, SourceType sourceType, bool preferUdpInAuto, const std::string &effectiveSyncMaster);
std::string latencyProfilesJson();
std::string rtspLowLatencyHelpJson();
std::string ultraLowLatencyHelpJson();
std::string latencyReportHelpJson();
std::string hardwareDecodeHelpJson();
std::string sourceInfoJson(const std::string &url);

#endif // MOTRO_PLAYER_OPTIONS_H
