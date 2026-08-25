#ifndef MOTRO_PRE_T0_TIMING_TRACKER_H
#define MOTRO_PRE_T0_TIMING_TRACKER_H

#include "LatencyDistribution.h"

#include <cstdint>
#include <mutex>

// LAT5: RTSP / RTP Pre-T0 isolation diagnostics.
//
// Measures, on the SAME steady monotonic clock as LAT2:
//   - av_read_frame() call duration (R0 = just before the call, R1 = just
//     after; readCallDurationUs = R1 - R0). This is NOT network latency: it
//     may include waiting for socket data, protocol/RTP depacketization,
//     reorder/jitter handling and internal buffered-packet availability.
//   - video packet return gap (monotonic time between consecutive video
//     packets returned by av_read_frame; VIDEO_PACKET_DEMUX_RETURN_GAP, not
//     socket arrival gap).
//   - video packet PTS delta (media timeline) for cadence cross-check.
//   - fast-return burst detection (consecutive video returns with gaps below
//     kFastVideoReturnThresholdUs).
//   - read stall buckets, max stall, and read error/timeout classification.
//
// All packet-level values are aggregated here; no per-packet logging.
class PreT0TimingTracker {
public:
    enum class ReadResultClass {
        ReadOk,
        ReadEagain,
        ReadTimeout,
        ReadEof,
        ReadError
    };

    // A 25 fps stream returns a video packet about every 40 ms. 5 ms is
    // clearly below one frame (<1/8 cadence) and above scheduling noise, so a
    // gap below this threshold marks a burst-style return.
    static constexpr int64_t kFastVideoReturnThresholdUs = 5000;

    struct Snapshot {
        int64_t readCallCount = 0;
        int64_t videoReadCallCount = 0;
        int64_t lastReadDurationUs = -1;
        int64_t avgReadDurationUs = 0;
        int64_t maxReadDurationUs = 0;
        int64_t readDurationP50Us = 0;
        int64_t readDurationP95Us = 0;
        int64_t readDurationP99Us = 0;
        int64_t readDurationDistCount = 0;
        int64_t lastVideoReturnGapUs = -1;
        int64_t avgVideoReturnGapUs = 0;
        int64_t maxVideoReturnGapUs = 0;
        int64_t videoReturnGapP50Us = 0;
        int64_t videoReturnGapP95Us = 0;
        int64_t videoReturnGapP99Us = 0;
        int64_t videoReturnGapDistCount = 0;
        int64_t lastVideoPtsDeltaUs = -1;
        int64_t avgVideoPtsDeltaUs = 0;
        int64_t maxVideoPtsDeltaUs = 0;
        int64_t videoPtsDeltaSampleCount = 0;
        int64_t fastReturnPacketCount = 0;
        int64_t currentFastReturnBurstLength = 0;
        int64_t maxFastReturnBurstLength = 0;
        int64_t readStallGt100MsCount = 0;
        int64_t readStallGt250MsCount = 0;
        int64_t readStallGt500MsCount = 0;
        int64_t readStallGt1000MsCount = 0;
        int64_t maxReadStallUs = 0;
        int64_t readEagainCount = 0;
        int64_t readTimeoutCount = 0;
        int64_t readEofCount = 0;
        int64_t readErrorCount = 0;
    };

    // BASIC mode uses this outcome-only path: it keeps online read health
    // counters without maintaining latency distributions.
    void recordReadOutcome(ReadResultClass resultClass) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++readCallCount_;
        recordReadOutcomeLocked(resultClass);
    }

    // LATENCY mode records both basic outcome counters and detailed timing.
    void recordReadCall(int64_t durationUs, ReadResultClass resultClass) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++readCallCount_;
        lastReadDurationUs_ = durationUs;
        if (durationUs >= 0) {
            totalReadDurationUs_ += durationUs;
            ++readDurationSampleCount_;
            if (durationUs > maxReadDurationUs_) {
                maxReadDurationUs_ = durationUs;
            }
            readDurationDist_.addSample(durationUs);
        }
        if (durationUs > 100000) {
            ++readStallGt100MsCount_;
        }
        if (durationUs > 250000) {
            ++readStallGt250MsCount_;
        }
        if (durationUs > 500000) {
            ++readStallGt500MsCount_;
        }
        if (durationUs > 1000000) {
            ++readStallGt1000MsCount_;
        }
        if (durationUs > 100000 && durationUs > maxReadStallUs_) {
            maxReadStallUs_ = durationUs;
        }
        recordReadOutcomeLocked(resultClass);
    }

    // includeLatencyDistributions=false avoids percentile copies/sorts for
    // production BASIC stats while retaining the same public counter fields.
    Snapshot snapshot(bool includeLatencyDistributions = true) const {
        Snapshot snap;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            snap.readCallCount = readCallCount_;
            snap.videoReadCallCount = videoReadCallCount_;
            snap.lastReadDurationUs = lastReadDurationUs_;
            snap.avgReadDurationUs = readDurationSampleCount_ <= 0
                                             ? 0 : totalReadDurationUs_ / readDurationSampleCount_;
            snap.maxReadDurationUs = maxReadDurationUs_;
            snap.lastVideoReturnGapUs = lastVideoReturnGapUs_;
            snap.avgVideoReturnGapUs = videoReturnGapSampleCount_ <= 0
                                               ? 0 : totalVideoReturnGapUs_ / videoReturnGapSampleCount_;
            snap.maxVideoReturnGapUs = maxVideoReturnGapUs_;
            snap.lastVideoPtsDeltaUs = lastVideoPtsDeltaUs_;
            snap.avgVideoPtsDeltaUs = videoPtsDeltaSampleCount_ <= 0
                                              ? 0 : totalVideoPtsDeltaUs_ / videoPtsDeltaSampleCount_;
            snap.maxVideoPtsDeltaUs = maxVideoPtsDeltaUs_;
            snap.videoPtsDeltaSampleCount = videoPtsDeltaSampleCount_;
            snap.fastReturnPacketCount = fastReturnPacketCount_;
            snap.currentFastReturnBurstLength = currentFastReturnBurstLength_;
            snap.maxFastReturnBurstLength = maxFastReturnBurstLength_;
            snap.readStallGt100MsCount = readStallGt100MsCount_;
            snap.readStallGt250MsCount = readStallGt250MsCount_;
            snap.readStallGt500MsCount = readStallGt500MsCount_;
            snap.readStallGt1000MsCount = readStallGt1000MsCount_;
            snap.maxReadStallUs = maxReadStallUs_;
            snap.readEagainCount = readEagainCount_;
            snap.readTimeoutCount = readTimeoutCount_;
            snap.readEofCount = readEofCount_;
            snap.readErrorCount = readErrorCount_;
        }
        if (!includeLatencyDistributions) {
            return snap;
        }
        const LatencyDistribution::Snapshot readDist = readDurationDist_.snapshot();
        snap.readDurationP50Us = readDist.p50;
        snap.readDurationP95Us = readDist.p95;
        snap.readDurationP99Us = readDist.p99;
        snap.readDurationDistCount = readDist.count;
        const LatencyDistribution::Snapshot gapDist = videoReturnGapDist_.snapshot();
        snap.videoReturnGapP50Us = gapDist.p50;
        snap.videoReturnGapP95Us = gapDist.p95;
        snap.videoReturnGapP99Us = gapDist.p99;
        snap.videoReturnGapDistCount = gapDist.count;
        return snap;
    }

private:
    void recordReadOutcomeLocked(ReadResultClass resultClass) {
        switch (resultClass) {
            case ReadResultClass::ReadOk:
                break;
            case ReadResultClass::ReadEagain:
                ++readEagainCount_;
                break;
            case ReadResultClass::ReadTimeout:
                ++readTimeoutCount_;
                break;
            case ReadResultClass::ReadEof:
                ++readEofCount_;
                break;
            case ReadResultClass::ReadError:
                ++readErrorCount_;
                break;
        }
    }

public:
    // Called only when av_read_frame actually returned a video packet.
    // monoUs is the packet-ready monotonic timestamp (T0 / R1); ptsUs is the
    // media-timeline PTS in us, or -1 when invalid. An error/EOF read never
    // reaches this method, so it can never fabricate a fake return gap.
    void recordVideoReturn(int64_t monoUs, int64_t ptsUs) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++videoReadCallCount_;
        const int64_t prevReturn = previousVideoReturnMonoUs_;
        previousVideoReturnMonoUs_ = monoUs;
        if (prevReturn >= 0) {
            const int64_t gapUs = monoUs - prevReturn;
            if (gapUs >= 0) {
                lastVideoReturnGapUs_ = gapUs;
                totalVideoReturnGapUs_ += gapUs;
                ++videoReturnGapSampleCount_;
                if (gapUs > maxVideoReturnGapUs_) {
                    maxVideoReturnGapUs_ = gapUs;
                }
                videoReturnGapDist_.addSample(gapUs);
                if (gapUs <= kFastVideoReturnThresholdUs) {
                    ++fastReturnPacketCount_;
                    currentFastReturnBurstLength_ = currentFastReturnBurstLength_ > 0
                                                            ? currentFastReturnBurstLength_ + 1
                                                            : 2;
                    if (currentFastReturnBurstLength_ > maxFastReturnBurstLength_) {
                        maxFastReturnBurstLength_ = currentFastReturnBurstLength_;
                    }
                } else {
                    currentFastReturnBurstLength_ = 0;
                }
            } else {
                // Negative monotonic gap: clock anomaly, never a burst.
                currentFastReturnBurstLength_ = 0;
            }
        }
        if (ptsUs >= 0) {
            const int64_t prevPts = previousVideoPacketPtsUs_;
            previousVideoPacketPtsUs_ = ptsUs;
            if (prevPts >= 0 && ptsUs > prevPts) {
                const int64_t deltaUs = ptsUs - prevPts;
                lastVideoPtsDeltaUs_ = deltaUs;
                totalVideoPtsDeltaUs_ += deltaUs;
                ++videoPtsDeltaSampleCount_;
                if (deltaUs > maxVideoPtsDeltaUs_) {
                    maxVideoPtsDeltaUs_ = deltaUs;
                }
            }
        }
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        readCallCount_ = 0;
        videoReadCallCount_ = 0;
        lastReadDurationUs_ = -1;
        totalReadDurationUs_ = 0;
        readDurationSampleCount_ = 0;
        maxReadDurationUs_ = 0;
        lastVideoReturnGapUs_ = -1;
        totalVideoReturnGapUs_ = 0;
        videoReturnGapSampleCount_ = 0;
        maxVideoReturnGapUs_ = 0;
        previousVideoReturnMonoUs_ = -1;
        lastVideoPtsDeltaUs_ = -1;
        totalVideoPtsDeltaUs_ = 0;
        videoPtsDeltaSampleCount_ = 0;
        maxVideoPtsDeltaUs_ = 0;
        previousVideoPacketPtsUs_ = -1;
        fastReturnPacketCount_ = 0;
        currentFastReturnBurstLength_ = 0;
        maxFastReturnBurstLength_ = 0;
        readStallGt100MsCount_ = 0;
        readStallGt250MsCount_ = 0;
        readStallGt500MsCount_ = 0;
        readStallGt1000MsCount_ = 0;
        maxReadStallUs_ = 0;
        readEagainCount_ = 0;
        readTimeoutCount_ = 0;
        readEofCount_ = 0;
        readErrorCount_ = 0;
        readDurationDist_.reset();
        videoReturnGapDist_.reset();
    }

private:
    mutable std::mutex mutex_;
    int64_t readCallCount_ = 0;
    int64_t videoReadCallCount_ = 0;
    int64_t lastReadDurationUs_ = -1;
    int64_t totalReadDurationUs_ = 0;
    int64_t readDurationSampleCount_ = 0;
    int64_t maxReadDurationUs_ = 0;
    LatencyDistribution readDurationDist_;
    int64_t lastVideoReturnGapUs_ = -1;
    int64_t totalVideoReturnGapUs_ = 0;
    int64_t videoReturnGapSampleCount_ = 0;
    int64_t maxVideoReturnGapUs_ = 0;
    LatencyDistribution videoReturnGapDist_;
    int64_t previousVideoReturnMonoUs_ = -1;
    int64_t lastVideoPtsDeltaUs_ = -1;
    int64_t totalVideoPtsDeltaUs_ = 0;
    int64_t videoPtsDeltaSampleCount_ = 0;
    int64_t maxVideoPtsDeltaUs_ = 0;
    int64_t previousVideoPacketPtsUs_ = -1;
    int64_t fastReturnPacketCount_ = 0;
    int64_t currentFastReturnBurstLength_ = 0;
    int64_t maxFastReturnBurstLength_ = 0;
    int64_t readStallGt100MsCount_ = 0;
    int64_t readStallGt250MsCount_ = 0;
    int64_t readStallGt500MsCount_ = 0;
    int64_t readStallGt1000MsCount_ = 0;
    int64_t maxReadStallUs_ = 0;
    int64_t readEagainCount_ = 0;
    int64_t readTimeoutCount_ = 0;
    int64_t readEofCount_ = 0;
    int64_t readErrorCount_ = 0;
};

#endif  // MOTRO_PRE_T0_TIMING_TRACKER_H
