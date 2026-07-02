#include "PlayerOptions.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <limits>
#include <sstream>

namespace {

std::string lowerTrim(std::string value) {
    value.erase(std::remove_if(value.begin(), value.end(), [](unsigned char c) {
        return std::isspace(c) != 0;
    }), value.end());
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool startsWith(const std::string &value, const char *prefix) {
    return value.rfind(prefix, 0) == 0;
}

std::string escapeJson(const std::string &value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default: out << c; break;
        }
    }
    return out.str();
}

bool parseInt64(const std::string &value, int64_t &out) {
    char *end = nullptr;
    const long long parsed = std::strtoll(value.c_str(), &end, 10);
    if (end == value.c_str() || *end != '\0') {
        return false;
    }
    out = static_cast<int64_t>(parsed);
    return true;
}

bool parseInt(const std::string &value, int &out) {
    int64_t parsed = 0;
    if (!parseInt64(value, parsed)
        || parsed < std::numeric_limits<int>::min()
        || parsed > std::numeric_limits<int>::max()) {
        return false;
    }
    out = static_cast<int>(parsed);
    return true;
}

bool parseBool(const std::string &value, bool &out) {
    const std::string normalized = lowerTrim(value);
    if (normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on") {
        out = true;
        return true;
    }
    if (normalized == "0" || normalized == "false" || normalized == "no" || normalized == "off") {
        out = false;
        return true;
    }
    return false;
}

void appendOptionsJson(std::ostringstream &out, const PlayerOptions &options) {
    out << "\"rtspTransport\":\"" << rtspTransportName(options.rtspTransport) << "\","
        << "\"latencyMode\":\"" << latencyModeName(options.latencyMode) << "\","
        << "\"openTimeoutUs\":" << options.openTimeoutUs << ","
        << "\"readTimeoutUs\":" << options.readTimeoutUs << ","
        << "\"probesize\":" << options.probesize << ","
        << "\"analyzeduration\":" << options.analyzeduration << ","
        << "\"maxProbePackets\":" << options.maxProbePackets << ","
        << "\"maxDelayUs\":" << options.maxDelayUs << ","
        << "\"reorderQueueSize\":" << options.reorderQueueSize << ","
        << "\"socketBufferSize\":" << options.socketBufferSize << ","
        << "\"fflagsNoBuffer\":" << (options.fflagsNoBuffer ? "true" : "false") << ","
        << "\"avioDirect\":" << (options.avioDirect ? "true" : "false") << ","
        << "\"lowDelayDecode\":" << (options.lowDelayDecode ? "true" : "false") << ","
        << "\"tcpNoDelay\":" << (options.tcpNoDelay ? "true" : "false") << ","
        << "\"decoderThreadCount\":" << options.decoderThreadCount << ","
        << "\"enableFrameDrop\":" << (options.enableFrameDrop ? "true" : "false") << ","
        << "\"dropLateFrameThresholdUs\":" << options.dropLateFrameThresholdUs << ","
        << "\"skipNonRef\":" << (options.skipNonRef ? "true" : "false");
}

} // namespace

SourceType detectSourceType(const std::string &url) {
    std::string lower = url;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (startsWith(lower, "rtsp://") || startsWith(lower, "rtsps://")) {
        return SourceType::RTSP;
    }
    if (lower.find(".m3u8") != std::string::npos) {
        return SourceType::HLS;
    }
    if (startsWith(lower, "rtmp://") || startsWith(lower, "rtmps://")) {
        return SourceType::RTMP;
    }
    if (startsWith(lower, "http://") || startsWith(lower, "https://")) {
        return SourceType::HTTP;
    }
    if (startsWith(lower, "file://") || lower.find("://") == std::string::npos) {
        return SourceType::LOCAL;
    }
    return SourceType::OTHER;
}

bool isRtspSource(SourceType sourceType) {
    return sourceType == SourceType::RTSP;
}

std::string rtspTransportName(RtspTransport transport) {
    switch (transport) {
        case RtspTransport::TCP: return "tcp";
        case RtspTransport::UDP: return "udp";
        case RtspTransport::UDP_MULTICAST: return "udp_multicast";
        case RtspTransport::AUTO: return "auto";
    }
    return "tcp";
}

std::string latencyModeName(LatencyMode mode) {
    switch (mode) {
        case LatencyMode::LOW_LATENCY: return "low_latency";
        case LatencyMode::BALANCED: return "balanced";
        case LatencyMode::STABLE: return "stable";
    }
    return "balanced";
}

std::string sourceTypeName(SourceType sourceType) {
    switch (sourceType) {
        case SourceType::RTSP: return "RTSP";
        case SourceType::HLS: return "HLS";
        case SourceType::RTMP: return "RTMP";
        case SourceType::HTTP: return "HTTP";
        case SourceType::LOCAL: return "LOCAL";
        case SourceType::OTHER: return "OTHER";
    }
    return "OTHER";
}

std::string effectiveRtspTransportName(const PlayerOptions &options, bool preferUdpInAuto) {
    if (options.rtspTransport == RtspTransport::AUTO) {
        return preferUdpInAuto ? "udp" : "tcp";
    }
    return rtspTransportName(options.rtspTransport);
}

bool parseRtspTransport(const std::string &value, RtspTransport &transport) {
    const std::string normalized = lowerTrim(value);
    if (normalized == "tcp") {
        transport = RtspTransport::TCP;
        return true;
    }
    if (normalized == "udp") {
        transport = RtspTransport::UDP;
        return true;
    }
    if (normalized == "udp_multicast" || normalized == "multicast") {
        transport = RtspTransport::UDP_MULTICAST;
        return true;
    }
    if (normalized == "auto") {
        transport = RtspTransport::AUTO;
        return true;
    }
    return false;
}

bool parseLatencyMode(const std::string &value, LatencyMode &mode) {
    const std::string normalized = lowerTrim(value);
    if (normalized == "low_latency" || normalized == "low" || normalized == "realtime") {
        mode = LatencyMode::LOW_LATENCY;
        return true;
    }
    if (normalized == "balanced" || normalized == "balance") {
        mode = LatencyMode::BALANCED;
        return true;
    }
    if (normalized == "stable" || normalized == "stability") {
        mode = LatencyMode::STABLE;
        return true;
    }
    return false;
}

PlayerOptions makePlayerOptions(RtspTransport transport, LatencyMode mode) {
    PlayerOptions options;
    options.rtspTransport = transport;
    options.latencyMode = mode;
    applyLatencyProfile(options);
    return options;
}

void applyLatencyProfile(PlayerOptions &options) {
    const RtspTransport transport = options.rtspTransport;
    const LatencyMode mode = options.latencyMode;
    options = PlayerOptions{};
    options.rtspTransport = transport;
    options.latencyMode = mode;

    const bool udp = transport == RtspTransport::UDP || transport == RtspTransport::UDP_MULTICAST;

    if (mode == LatencyMode::LOW_LATENCY) {
        options.openTimeoutUs = 3000000;
        options.readTimeoutUs = 3000000;
        options.probesize = 32768;
        options.analyzeduration = 0;
        options.maxProbePackets = 32;
        options.maxDelayUs = 0;
        options.reorderQueueSize = udp ? 0 : -1;
        options.socketBufferSize = 102400;
        options.fflagsNoBuffer = true;
        options.avioDirect = true;
        options.lowDelayDecode = true;
        options.tcpNoDelay = true;
        options.enableFrameDrop = true;
        options.decoderThreadCount = 1;
        options.dropLateFrameThresholdUs = udp ? 150000 : 200000;
        return;
    }

    if (mode == LatencyMode::BALANCED) {
        options.openTimeoutUs = 5000000;
        options.readTimeoutUs = 5000000;
        options.probesize = 131072;
        options.analyzeduration = udp ? 100000 : 200000;
        options.maxProbePackets = 128;
        options.maxDelayUs = udp ? 100000 : 200000;
        options.reorderQueueSize = udp ? 4 : -1;
        options.socketBufferSize = 262144;
        options.fflagsNoBuffer = udp;
        options.avioDirect = false;
        options.lowDelayDecode = true;
        options.tcpNoDelay = true;
        options.enableFrameDrop = true;
        options.decoderThreadCount = 1;
        options.dropLateFrameThresholdUs = udp ? 300000 : 500000;
        return;
    }

    options.openTimeoutUs = 8000000;
    options.readTimeoutUs = 8000000;
    options.probesize = 500000;
    options.analyzeduration = 500000;
    options.maxProbePackets = 512;
    options.maxDelayUs = 500000;
    options.reorderQueueSize = udp ? 16 : -1;
    options.socketBufferSize = 1048576;
    options.fflagsNoBuffer = false;
    options.avioDirect = false;
    options.lowDelayDecode = false;
    options.tcpNoDelay = false;
    options.enableFrameDrop = false;
    options.decoderThreadCount = 0;
    options.dropLateFrameThresholdUs = 1000000;
}

bool setPlayerOptionValue(PlayerOptions &options, const std::string &key, const std::string &value, std::string &errorMessage) {
    const std::string normalizedKey = lowerTrim(key);
    if (normalizedKey == "rtsp_transport") {
        RtspTransport transport;
        if (!parseRtspTransport(value, transport)) {
            errorMessage = "invalid rtsp_transport: " + value;
            return false;
        }
        options.rtspTransport = transport;
        applyLatencyProfile(options);
        return true;
    }
    if (normalizedKey == "latency_mode") {
        LatencyMode mode;
        if (!parseLatencyMode(value, mode)) {
            errorMessage = "invalid latency_mode: " + value;
            return false;
        }
        options.latencyMode = mode;
        applyLatencyProfile(options);
        return true;
    }

    int64_t parsedLong = 0;
    int parsedInt = 0;
    bool parsedBool = false;

    if (normalizedKey == "probesize") {
        if (!parseInt64(value, parsedLong) || parsedLong < 0) {
            errorMessage = "probesize must be a non-negative integer";
            return false;
        }
        options.probesize = parsedLong;
        return true;
    }
    if (normalizedKey == "analyzeduration") {
        if (!parseInt64(value, parsedLong) || parsedLong < 0) {
            errorMessage = "analyzeduration must be a non-negative integer";
            return false;
        }
        options.analyzeduration = parsedLong;
        return true;
    }
    if (normalizedKey == "max_probe_packets") {
        if (!parseInt(value, parsedInt) || parsedInt < 0) {
            errorMessage = "max_probe_packets must be a non-negative integer";
            return false;
        }
        options.maxProbePackets = parsedInt;
        return true;
    }
    if (normalizedKey == "max_delay") {
        if (!parseInt64(value, parsedLong) || parsedLong < 0) {
            errorMessage = "max_delay must be a non-negative integer";
            return false;
        }
        options.maxDelayUs = parsedLong;
        return true;
    }
    if (normalizedKey == "reorder_queue_size") {
        if (!parseInt(value, parsedInt)) {
            errorMessage = "reorder_queue_size must be an integer";
            return false;
        }
        options.reorderQueueSize = parsedInt;
        return true;
    }
    if (normalizedKey == "buffer_size") {
        if (!parseInt(value, parsedInt) || parsedInt < 0) {
            errorMessage = "buffer_size must be a non-negative integer";
            return false;
        }
        options.socketBufferSize = parsedInt;
        return true;
    }
    if (normalizedKey == "stimeout" || normalizedKey == "timeout" || normalizedKey == "rw_timeout") {
        if (!parseInt64(value, parsedLong) || parsedLong < 0) {
            errorMessage = normalizedKey + " must be a non-negative integer";
            return false;
        }
        if (normalizedKey == "stimeout" || normalizedKey == "timeout") {
            options.openTimeoutUs = parsedLong;
        } else {
            options.readTimeoutUs = parsedLong;
        }
        return true;
    }
    if (normalizedKey == "decoder_thread_count") {
        if (!parseInt(value, parsedInt) || parsedInt < 0) {
            errorMessage = "decoder_thread_count must be a non-negative integer";
            return false;
        }
        options.decoderThreadCount = parsedInt;
        return true;
    }
    if (normalizedKey == "enable_frame_drop") {
        if (!parseBool(value, parsedBool)) {
            errorMessage = "enable_frame_drop must be boolean";
            return false;
        }
        options.enableFrameDrop = parsedBool;
        return true;
    }
    if (normalizedKey == "drop_late_frame_threshold_us") {
        if (!parseInt64(value, parsedLong) || parsedLong < 0) {
            errorMessage = "drop_late_frame_threshold_us must be a non-negative integer";
            return false;
        }
        options.dropLateFrameThresholdUs = parsedLong;
        return true;
    }
    if (normalizedKey == "fflags_nobuffer") {
        if (!parseBool(value, parsedBool)) {
            errorMessage = "fflags_nobuffer must be boolean";
            return false;
        }
        options.fflagsNoBuffer = parsedBool;
        return true;
    }
    if (normalizedKey == "avio_direct") {
        if (!parseBool(value, parsedBool)) {
            errorMessage = "avio_direct must be boolean";
            return false;
        }
        options.avioDirect = parsedBool;
        return true;
    }
    if (normalizedKey == "skip_non_ref") {
        if (!parseBool(value, parsedBool)) {
            errorMessage = "skip_non_ref must be boolean";
            return false;
        }
        options.skipNonRef = parsedBool;
        return true;
    }

    errorMessage = "unknown player option: " + key;
    return false;
}

std::string playerOptionsToJson(const PlayerOptions &options, SourceType sourceType, bool preferUdpInAuto) {
    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"sourceType\":\"" << sourceTypeName(sourceType) << "\","
        << "\"effectiveRtspTransport\":\"" << effectiveRtspTransportName(options, preferUdpInAuto) << "\",";
    appendOptionsJson(out, options);
    out << "}";
    return out.str();
}

std::string latencyProfilesJson() {
    std::ostringstream out;
    out << "{\"success\":true,\"profiles\":{"
        << "\"low_latency\":{\"description\":\"minimum latency for LAN preview; UDP may lose packets or show artifacts\"},"
        << "\"balanced\":{\"description\":\"default compromise between latency and stability\"},"
        << "\"stable\":{\"description\":\"larger buffers for WAN/Wi-Fi jitter and recording-first workflows\"}"
        << "}}";
    return out.str();
}

std::string rtspLowLatencyHelpJson() {
    return "{\"success\":true,"
           "\"tcp\":\"tcp is stable and ordered but retransmission can add latency; low_latency uses tcp_nodelay and smaller buffers\","
           "\"udp\":\"udp has lower latency and avoids TCP head-of-line blocking, but packet loss can cause artifacts\","
           "\"modes\":\"use udp+low_latency for LAN control preview, tcp+stable for WAN or recording-first sessions\"}";
}

std::string sourceInfoJson(const std::string &url) {
    const SourceType sourceType = detectSourceType(url);
    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"url\":\"" << escapeJson(url) << "\","
        << "\"sourceType\":\"" << sourceTypeName(sourceType) << "\","
        << "\"isRtsp\":" << (isRtspSource(sourceType) ? "true" : "false") << ",";
    if (isRtspSource(sourceType)) {
        out << "\"recommendedTransport\":\"udp\","
            << "\"recommendedLatencyMode\":\"balanced\","
            << "\"message\":\"try udp low_latency on LAN; use tcp stable if packet loss or artifacts are visible\"";
    } else {
        out << "\"recommendedTransport\":\"none\","
            << "\"recommendedLatencyMode\":\"balanced\","
            << "\"message\":\"RTSP transport options are not applied to this source type\"";
    }
    out << "}";
    return out.str();
}
