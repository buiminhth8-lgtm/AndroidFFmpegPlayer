#ifndef MOTRO_E2E_TIMEBASE_H
#define MOTRO_E2E_TIMEBASE_H

// LAT6: Sender / Server / Network end-to-end timebase mapping helpers.
//
// Clock domains (frozen; never subtract across domains without an explicit
// mapping):
//   A. Sender monotonic      - sender-internal durations only.
//   B. Sender wall clock     - NTP/PTP synchronized Unix time.
//   C. RTP media clock       - e.g. 90 kHz video RTP timestamps (wrap at 2^32).
//   D. Android monotonic     - LAT2/LAT3 T0..T4 (steady_clock).
//   E. Android wall clock    - receiver Unix wall time (this header's bridge).
//
// This header is self-contained (no FFmpeg / android dependencies) so the math
// is host-testable. All absolute timestamps are int64 nanoseconds; float is
// never used for absolute time. Diagnostics only: SIDE_CHANNEL_DIAGNOSTICS.

#include "LatencyDistribution.h"

#include <chrono>
#include <cstdint>
#include <mutex>

// NTP era offset: seconds between 1900-01-01 and 1970-01-01.
constexpr uint64_t kNtpUnixEpochOffsetSeconds = 2208988800ULL;

// Receiver WALL clock now, in Unix nanoseconds (clock domain E). Used ONLY as
// the T0 bridge value for cross-device comparison against an independently
// synchronized sender/server wall clock; never for local stage durations.
inline int64_t wallClockNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::system_clock::now().time_since_epoch())
            .count();
}

// NTP 64-bit timestamp (seconds + fraction of a second) -> Unix ns.
// The fraction is scaled by 1e9 / 2^32 in integer math (no float, no overflow:
// max intermediate = (2^32-1) * 1e9 < 2^63).
inline int64_t ntpToUnixNs(uint32_t seconds, uint32_t fraction) {
    const uint64_t unixSecs = static_cast<uint64_t>(seconds) - kNtpUnixEpochOffsetSeconds;
    const int64_t fracNs = static_cast<int64_t>(
            (static_cast<uint64_t>(fraction) * 1000000000ULL) >> 32);
    return static_cast<int64_t>(unixSecs * 1000000000ULL) + fracNs;
}

// Extend a wrapping 32-bit RTP timestamp into a 64-bit continuous timeline,
// choosing the 32-bit epoch closest to lastExtended. lastExtended == 0 means
// "uninitialized" and returns newRtp verbatim (first sample initializes).
inline uint64_t extendRtpTimestamp(uint64_t lastExtended, uint32_t newRtp) {
    constexpr uint64_t kRtpRange = 1ULL << 32;
    if (lastExtended == 0) {
        return static_cast<uint64_t>(newRtp);
    }
    const uint64_t base = (lastExtended / kRtpRange) * kRtpRange;
    const auto diffFromLast = [lastExtended](uint64_t value) -> uint64_t {
        return value > lastExtended ? value - lastExtended : lastExtended - value;
    };
    uint64_t best = base | newRtp;
    uint64_t bestDiff = diffFromLast(best);
    const uint64_t nextEpoch = (base + kRtpRange) | newRtp;
    const uint64_t nextDiff = diffFromLast(nextEpoch);
    if (nextDiff < bestDiff) {
        bestDiff = nextDiff;
        best = nextEpoch;
    }
    if (base >= kRtpRange) {
        const uint64_t prevEpoch = (base - kRtpRange) | newRtp;
        const uint64_t prevDiff = diffFromLast(prevEpoch);
        if (prevDiff < bestDiff) {
            best = prevEpoch;
        }
    }
    return best;
}

// Maps sender RTP media-clock timestamps to sender wall clock using RTCP
// Sender Report anchors (RTP timestamp <-> NTP timestamp). LEVEL 2 mapping.
//
// Semantics: the mapped value is the SENDER MEDIA TIMELINE expressed on the
// sender wall clock. It does NOT represent camera capture time unless the
// capture->RTP timestamp semantics are independently proven.
class RtpToNtpMapper {
public:
    explicit RtpToNtpMapper(int64_t rtpClockRateHz)
        : rtpClockRateHz_(rtpClockRateHz) {}

    // Generation reset: clears the anchor so stale sender media timestamps can
    // never map across a reconnect / SSRC change / new sender session.
    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        hasAnchor_ = false;
        srMappingValid_ = false;
        srReceivedCount_ = 0;
        ssrcMismatchCount_ = 0;
        driftPpm_ = 0;
        lastExtRtp_ = 0;
        lastSrNtpNs_ = 0;
    }

    void setExpectedSsrc(uint32_t ssrc) {
        std::lock_guard<std::mutex> lock(mutex_);
        expectedSsrc_ = ssrc;
        hasExpectedSsrc_ = true;
    }

    // Feed one RTCP SR anchor. Returns false when the report is rejected
    // (SSRC mismatch or non-positive clock rate); anchors are never partially
    // applied.
    bool addSenderReport(uint32_t ssrc, uint32_t rtpTimestamp,
                         uint32_t ntpSeconds, uint32_t ntpFraction) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (rtpClockRateHz_ <= 0) {
            return false;
        }
        if (hasExpectedSsrc_ && ssrc != expectedSsrc_) {
            ++ssrcMismatchCount_;
            srMappingValid_ = false;
            return false;
        }
        const int64_t ntpNs = ntpToUnixNs(ntpSeconds, ntpFraction);
        const uint64_t extRtp = hasAnchor_
                ? extendRtpTimestamp(lastExtRtp_, rtpTimestamp)
                : static_cast<uint64_t>(rtpTimestamp);
        if (hasAnchor_) {
            const uint64_t rtpDeltaTicks = extRtp - lastExtRtp_;
            const int64_t ntpDeltaNs = ntpNs - lastSrNtpNs_;
            if (rtpDeltaTicks > 0 && ntpDeltaNs > 0) {
                // Drift audit: NTP elapsed vs RTP elapsed at the nominal rate.
                const int64_t expectedNs =
                        static_cast<int64_t>((rtpDeltaTicks * 1000000000ULL)
                                             / static_cast<uint64_t>(rtpClockRateHz_));
                driftPpm_ = ((ntpDeltaNs - expectedNs) * 1000000) / expectedNs;
            }
        }
        lastExtRtp_ = extRtp;
        lastSrNtpNs_ = ntpNs;
        hasAnchor_ = true;
        ++srReceivedCount_;
        srMappingValid_ = true;
        return true;
    }

    // Map an extended RTP timestamp to sender wall clock ns:
    //   mediaWallNs = srNtpNs + (extRtp - srExtRtp) * 1e9 / clockRate.
    // Returns false while no valid anchor exists (never fabricates a value).
    bool mapRtpToWallNs(uint64_t extendedRtp, int64_t &outWallNs) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!hasAnchor_ || !srMappingValid_ || rtpClockRateHz_ <= 0) {
            return false;
        }
        const int64_t deltaTicks = static_cast<int64_t>(extendedRtp - lastExtRtp_);
        outWallNs = lastSrNtpNs_
                    + deltaTicks * 1000000000LL / rtpClockRateHz_;
        return true;
    }

    struct Snapshot {
        bool hasAnchor = false;
        bool srMappingValid = false;
        int64_t srReceivedCount = 0;
        int64_t ssrcMismatchCount = 0;
        int64_t driftPpm = 0;
        uint64_t lastExtRtp = 0;
        int64_t lastSrNtpNs = 0;
    };

    Snapshot snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        Snapshot snap;
        snap.hasAnchor = hasAnchor_;
        snap.srMappingValid = srMappingValid_;
        snap.srReceivedCount = srReceivedCount_;
        snap.ssrcMismatchCount = ssrcMismatchCount_;
        snap.driftPpm = driftPpm_;
        snap.lastExtRtp = lastExtRtp_;
        snap.lastSrNtpNs = lastSrNtpNs_;
        return snap;
    }

private:
    mutable std::mutex mutex_;
    int64_t rtpClockRateHz_;
    bool hasExpectedSsrc_ = false;
    uint32_t expectedSsrc_ = 0;
    bool hasAnchor_ = false;
    bool srMappingValid_ = false;
    int64_t srReceivedCount_ = 0;
    int64_t ssrcMismatchCount_ = 0;
    int64_t driftPpm_ = 0;
    uint64_t lastExtRtp_ = 0;
    int64_t lastSrNtpNs_ = 0;
};

struct E2ESampleResult {
    bool valid = false;    // usable latency sample
    bool anomaly = false;  // CLOCK_MAPPING_ANOMALY: receiver wall before sender wall
    int64_t latencyUs = -1;
};

// Cross-device segment: sender send wall -> receiver T0 wall. Both values must
// come from independently synchronized wall clocks (domains B and E). A
// negative result is NEVER clamped to zero; it invalidates the sample and
// flags a clock mapping anomaly.
inline E2ESampleResult measureSenderSendToReceiverT0Us(int64_t senderSendWallNs,
                                                       int64_t receiverT0WallNs) {
    E2ESampleResult result;
    if (senderSendWallNs < 0 || receiverT0WallNs < 0) {
        return result;
    }
    const int64_t diffNs = receiverT0WallNs - senderSendWallNs;
    if (diffNs < 0) {
        result.anomaly = true;
        return result;
    }
    result.valid = true;
    result.latencyUs = diffNs / 1000;
    return result;
}

// Bounded distribution for valid cross-device E2E samples. Anomalies and
// invalid samples are counted separately and never enter the percentile
// window; real outliers are kept (no outlier deletion).
class SendToT0Distribution {
public:
    void addSample(const E2ESampleResult &sample) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (sample.anomaly) {
            ++anomalyCount_;
            return;
        }
        if (!sample.valid) {
            ++invalidCount_;
            return;
        }
        dist_.addSample(sample.latencyUs);
        ++validCount_;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        dist_.reset();
        validCount_ = 0;
        anomalyCount_ = 0;
        invalidCount_ = 0;
    }

    struct Snapshot {
        int64_t validCount = 0;
        int64_t anomalyCount = 0;
        int64_t invalidCount = 0;
        int64_t p50Us = 0;
        int64_t p95Us = 0;
        int64_t p99Us = 0;
    };

    Snapshot snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        Snapshot snap;
        snap.validCount = validCount_;
        snap.anomalyCount = anomalyCount_;
        snap.invalidCount = invalidCount_;
        const LatencyDistribution::Snapshot dist = dist_.snapshot();
        snap.p50Us = dist.p50;
        snap.p95Us = dist.p95;
        snap.p99Us = dist.p99;
        return snap;
    }

private:
    mutable std::mutex mutex_;
    LatencyDistribution dist_;
    int64_t validCount_ = 0;
    int64_t anomalyCount_ = 0;
    int64_t invalidCount_ = 0;
};

#endif  // MOTRO_E2E_TIMEBASE_H
