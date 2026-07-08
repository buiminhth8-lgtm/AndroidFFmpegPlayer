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

struct PlayerOptions {
    RtspTransport rtspTransport = RtspTransport::TCP;
    LatencyMode latencyMode = LatencyMode::BALANCED;

    int64_t openTimeoutUs = 5000000;
    int64_t readTimeoutUs = 5000000;

    int64_t probesize = 131072;
    int64_t analyzeduration = 200000;
    int maxProbePackets = 128;

    int64_t maxDelayUs = 200000;
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
    int cacheLastFrameEveryN = 1;
    SyncMaster syncMaster = SyncMaster::AUDIO;
};

SourceType detectSourceType(const std::string &url);
bool isRtspSource(SourceType sourceType);

std::string rtspTransportName(RtspTransport transport);
std::string latencyModeName(LatencyMode mode);
std::string syncMasterName(SyncMaster syncMaster);
std::string sourceTypeName(SourceType sourceType);
std::string effectiveRtspTransportName(const PlayerOptions &options, bool preferUdpInAuto);

bool parseRtspTransport(const std::string &value, RtspTransport &transport);
bool parseLatencyMode(const std::string &value, LatencyMode &mode);
bool parseSyncMaster(const std::string &value, SyncMaster &syncMaster);

PlayerOptions makePlayerOptions(RtspTransport transport, LatencyMode mode);
void applyLatencyProfile(PlayerOptions &options);

bool setPlayerOptionValue(PlayerOptions &options, const std::string &key, const std::string &value, std::string &errorMessage);
std::string playerOptionsToJson(const PlayerOptions &options, SourceType sourceType, bool preferUdpInAuto, const std::string &effectiveSyncMaster);
std::string latencyProfilesJson();
std::string rtspLowLatencyHelpJson();
std::string ultraLowLatencyHelpJson();
std::string latencyReportHelpJson();
std::string sourceInfoJson(const std::string &url);

#endif // MOTRO_PLAYER_OPTIONS_H
