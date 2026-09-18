// Production diagnostics policy host tests (no external framework).
#include "../../main/cpp/native/diagnostics/PlaybackDiagnostics.h"

#include <cstdio>

int main() {
    PlaybackDiagnostics diagnostics;
    if (diagnostics.mode() != DiagnosticsMode::Basic
            || !diagnostics.basicEnabled() || diagnostics.latencyEnabled()) {
        return 1;
    }
    diagnostics.onRead(50000, PreT0TimingTracker::ReadResultClass::ReadTimeout);
    const PreT0TimingTracker::Snapshot basic = diagnostics.preT0Snapshot();
    if (basic.readCallCount != 1 || basic.readTimeoutCount != 1
            || basic.readDurationDistCount != 0) {
        return 7;
    }

    DiagnosticsMode parsed = DiagnosticsMode::Basic;
    if (!parseDiagnosticsMode(" OFF ", parsed) || parsed != DiagnosticsMode::Off) {
        return 2;
    }
    diagnostics.setMode(parsed);
    if (diagnostics.basicEnabled() || diagnostics.latencyEnabled()) {
        return 3;
    }

    if (!parseDiagnosticsMode("LaTeNcY", parsed) || parsed != DiagnosticsMode::Latency) {
        return 4;
    }
    diagnostics.setMode(parsed);
    if (!diagnostics.basicEnabled() || !diagnostics.latencyEnabled()) {
        return 5;
    }
    diagnostics.onRead(40000, PreT0TimingTracker::ReadResultClass::ReadOk);
    diagnostics.onMediaBacklog(10, 20, 30, 60);
    diagnostics.onStageSample(1, 2, 3, 4, 10);
    const PreT0TimingTracker::Snapshot latency = diagnostics.preT0Snapshot();
    const PlaybackDiagnostics::LatencySnapshot distributions = diagnostics.latencySnapshot();
    if (latency.readDurationDistCount != 1
            || distributions.clientMediaBacklog.count != 1
            || distributions.packetRender.count != 1) {
        return 8;
    }
    if (parseDiagnosticsMode("verbose", parsed)) {
        return 6;
    }

    std::printf("ALL_DIAGNOSTICS_MODE_TESTS_PASSED\n");
    return 0;
}
