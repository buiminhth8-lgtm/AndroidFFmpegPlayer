#ifndef MOTRO_PLAYBACK_DIAGNOSTICS_H
#define MOTRO_PLAYBACK_DIAGNOSTICS_H

#include "DiagnosticsMode.h"
#include "E2ETimebase.h"
#include "LatencyDistribution.h"
#include "PreT0TimingTracker.h"

#include <atomic>
#include <cstdint>

// Central owner for optional playback diagnostics. Correctness/lifecycle state
// never depends on this object. Hot-path callers submit scalar hooks only;
// aggregation stays bounded and JSON/Logcat formatting stays outside the path.
class PlaybackDiagnostics {
public:
    struct LatencySnapshot {
        LatencyDistribution::Snapshot demuxBacklog;
        LatencyDistribution::Snapshot decoderBacklog;
        LatencyDistribution::Snapshot renderBacklog;
        LatencyDistribution::Snapshot clientMediaBacklog;
        LatencyDistribution::Snapshot demuxSubmit;
        LatencyDistribution::Snapshot decoderResidence;
        LatencyDistribution::Snapshot decodeRender;
        LatencyDistribution::Snapshot renderSubmit;
        LatencyDistribution::Snapshot packetRender;
    };

    struct E2ESnapshot {
        RtcpSrTracker::Snapshot senderReport;
        SendToT0Distribution::Snapshot sendToT0;
        int64_t sameFrameMappedCount = 0;
        int64_t sameFrameUnmatchedCount = 0;
    };

    DiagnosticsMode mode() const {
        return mode_.load(std::memory_order_relaxed);
    }

    void setMode(DiagnosticsMode mode) {
        mode_.store(mode, std::memory_order_relaxed);
    }

    bool basicEnabled() const {
        return mode() != DiagnosticsMode::Off;
    }

    bool latencyEnabled() const {
        return mode() == DiagnosticsMode::Latency;
    }

    void onRead(int64_t durationUs, PreT0TimingTracker::ReadResultClass resultClass) {
        const DiagnosticsMode currentMode = mode();
        if (currentMode == DiagnosticsMode::Latency) {
            preT0_.recordReadCall(durationUs, resultClass);
        } else if (currentMode == DiagnosticsMode::Basic) {
            preT0_.recordReadOutcome(resultClass);
        }
    }

    void onVideoPacketReturn(int64_t monoUs, int64_t ptsUs) {
        if (latencyEnabled()) {
            preT0_.recordVideoReturn(monoUs, ptsUs);
        }
    }

    PreT0TimingTracker::Snapshot preT0Snapshot() const {
        if (!basicEnabled()) {
            return {};
        }
        return preT0_.snapshot(latencyEnabled());
    }

    void resetPreT0() {
        preT0_.reset();
    }

    void onMediaBacklog(int64_t demuxUs, int64_t decoderUs,
                        int64_t renderUs, int64_t totalUs) {
        if (!latencyEnabled()) {
            return;
        }
        demuxBacklog_.addSample(demuxUs);
        decoderBacklog_.addSample(decoderUs);
        renderBacklog_.addSample(renderUs);
        clientMediaBacklog_.addSample(totalUs);
    }

    void onStageSample(int64_t demuxSubmitUs, int64_t decoderResidenceUs,
                       int64_t decodeRenderUs, int64_t renderSubmitUs,
                       int64_t packetRenderUs) {
        if (!latencyEnabled()) {
            return;
        }
        demuxSubmit_.addSample(demuxSubmitUs);
        decoderResidence_.addSample(decoderResidenceUs);
        decodeRender_.addSample(decodeRenderUs);
        renderSubmit_.addSample(renderSubmitUs);
        packetRender_.addSample(packetRenderUs);
    }

    LatencySnapshot latencySnapshot() const {
        if (!latencyEnabled()) {
            return {};
        }
        LatencySnapshot snap;
        snap.demuxBacklog = demuxBacklog_.snapshot();
        snap.decoderBacklog = decoderBacklog_.snapshot();
        snap.renderBacklog = renderBacklog_.snapshot();
        snap.clientMediaBacklog = clientMediaBacklog_.snapshot();
        snap.demuxSubmit = demuxSubmit_.snapshot();
        snap.decoderResidence = decoderResidence_.snapshot();
        snap.decodeRender = decodeRender_.snapshot();
        snap.renderSubmit = renderSubmit_.snapshot();
        snap.packetRender = packetRender_.snapshot();
        return snap;
    }

    void resetLatency() {
        demuxBacklog_.reset();
        decoderBacklog_.reset();
        renderBacklog_.reset();
        clientMediaBacklog_.reset();
        demuxSubmit_.reset();
        decoderResidence_.reset();
        decodeRender_.reset();
        renderSubmit_.reset();
        packetRender_.reset();
    }

    void setRtpClockRate(int64_t clockRate) {
        if (latencyEnabled()) {
            rtcpSr_.setClockRate(clockRate);
        }
    }

    void onSenderReport(uint32_t ssrc, uint64_t ntpTimestamp, uint32_t rtpTimestamp) {
        if (latencyEnabled()) {
            rtcpSr_.recordSenderReport(ssrc, ntpTimestamp, rtpTimestamp);
        }
    }

    RtcpSrTracker::Snapshot senderReportSnapshot() const {
        return latencyEnabled() ? rtcpSr_.snapshot() : RtcpSrTracker::Snapshot{};
    }

    void onE2ESameFrameUnmatched() {
        if (latencyEnabled()) {
            e2eSameFrameUnmatchedCount_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void onE2ESameFrameMapped() {
        if (latencyEnabled()) {
            e2eSameFrameMappedCount_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void onE2ESample(const E2ESampleResult &sample, bool clockSyncValid) {
        if (latencyEnabled()) {
            e2eSendToT0_.addSample(sample, clockSyncValid);
        }
    }

    E2ESnapshot e2eSnapshot() const {
        if (!latencyEnabled()) {
            return {};
        }
        E2ESnapshot snap;
        snap.senderReport = rtcpSr_.snapshot();
        snap.sendToT0 = e2eSendToT0_.snapshot();
        snap.sameFrameMappedCount = e2eSameFrameMappedCount_.load(std::memory_order_relaxed);
        snap.sameFrameUnmatchedCount = e2eSameFrameUnmatchedCount_.load(std::memory_order_relaxed);
        return snap;
    }

    void resetE2E() {
        rtcpSr_.reset();
        e2eSendToT0_.reset();
        e2eSameFrameMappedCount_.store(0, std::memory_order_relaxed);
        e2eSameFrameUnmatchedCount_.store(0, std::memory_order_relaxed);
    }

private:
    std::atomic<DiagnosticsMode> mode_{DiagnosticsMode::Basic};
    PreT0TimingTracker preT0_;
    LatencyDistribution demuxBacklog_;
    LatencyDistribution decoderBacklog_;
    LatencyDistribution renderBacklog_;
    LatencyDistribution clientMediaBacklog_;
    LatencyDistribution demuxSubmit_;
    LatencyDistribution decoderResidence_;
    LatencyDistribution decodeRender_;
    LatencyDistribution renderSubmit_;
    LatencyDistribution packetRender_;
    RtcpSrTracker rtcpSr_;
    SendToT0Distribution e2eSendToT0_;
    std::atomic<int64_t> e2eSameFrameMappedCount_{0};
    std::atomic<int64_t> e2eSameFrameUnmatchedCount_{0};
};

#endif  // MOTRO_PLAYBACK_DIAGNOSTICS_H
