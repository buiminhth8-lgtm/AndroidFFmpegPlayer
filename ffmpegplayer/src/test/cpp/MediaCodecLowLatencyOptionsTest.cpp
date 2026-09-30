#include "../../main/cpp/native/PlayerOptions.h"

#include <cstdio>
#include <string>

namespace {

bool expect(bool condition, const char *label) {
    if (condition) {
        return true;
    }
    std::printf("FAIL %s\n", label);
    return false;
}

}  // namespace

int main() {
    bool passed = true;
    PlayerOptions options;
    std::string error;

    passed &= expect(!options.enableMediaCodecLowLatency, "default OFF");
    passed &= expect(setPlayerOptionValue(options, "enable_mediacodec_low_latency",
                                          "true", error),
                     "ON option accepted");
    passed &= expect(options.enableMediaCodecLowLatency, "ON request stored");

    // 延迟 profile 会重建 PlayerOptions；MediaCodec 请求必须跨 profile/reconnect 配置保留。
    options.rtspTransport = RtspTransport::UDP;
    options.latencyMode = LatencyMode::LOW_LATENCY;
    applyLatencyProfile(options);
    passed &= expect(options.enableMediaCodecLowLatency, "request survives profile rebuild");

    const std::string json = playerOptionsToJson(
            options, SourceType::RTSP, false, "video");
    passed &= expect(json.find("\"enableMediaCodecLowLatency\":true") != std::string::npos,
                     "request appears in options JSON");

    error.clear();
    passed &= expect(!setPlayerOptionValue(options, "enable_mediacodec_low_latency",
                                           "invalid", error),
                     "invalid value rejected");
    passed &= expect(options.enableMediaCodecLowLatency,
                     "invalid value does not mutate request");

    error.clear();
    passed &= expect(setPlayerOptionValue(options, "mediacodec_low_latency",
                                          "off", error),
                     "OFF alias accepted");
    passed &= expect(!options.enableMediaCodecLowLatency, "OFF request stored");

    if (!passed) {
        return 1;
    }
    std::printf("ALL_MEDIACODEC_LOW_LATENCY_OPTIONS_TESTS_PASSED\n");
    return 0;
}
