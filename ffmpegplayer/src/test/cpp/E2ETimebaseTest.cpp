// LAT6 end-to-end timebase helper host tests (no external framework; plain
// asserts). Compile with a host C++17 compiler, e.g.:
//   cl /nologo /std:c++17 /EHsc E2ETimebaseTest.cpp /I ..\..\main\cpp\native
// Exit code 0 = all passed.
#include "E2ETimebase.h"

#include <cstdio>
#include <cstdint>

static int g_failures = 0;

#define CHECK(cond)                                                                              \
    do {                                                                                         \
        if (!(cond)) {                                                                           \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                          \
            ++g_failures;                                                                        \
        }                                                                                        \
    } while (0)

int main() {
    // 1. NTP -> Unix ns conversion (no float; fraction scaled by 1e9/2^32).
    {
        // Unix 1755000000 s -> NTP seconds 1755000000 + 2208988800.
        const uint32_t ntpSecs =
                static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(ntpToUnixNs(ntpSecs, 0) == 1755000000LL * 1000000000LL);
        // fraction 0x80000000 == exactly one half second.
        CHECK(ntpToUnixNs(ntpSecs, 0x80000000u)
              == 1755000000LL * 1000000000LL + 500000000LL);
        // fraction 0x40000000 == exactly one quarter second.
        CHECK(ntpToUnixNs(ntpSecs, 0x40000000u)
              == 1755000000LL * 1000000000LL + 250000000LL);
    }

    // 2. RTP clock conversion via SR mapping: 90000 ticks == 1 s at 90 kHz.
    //    (Clock rate comes from the mapper config, never hardcoded in prod.)
    {
        RtpToNtpMapper mapper(90000);
        const int64_t srNtpNs = 1755000000LL * 1000000000LL;
        const uint32_t ntpSecs = static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(mapper.addSenderReport(0x1234abcd, 1000000, ntpSecs, 0));
        int64_t wallNs = -1;
        CHECK(mapper.mapRtpToWallNs(1000000 + 90000, wallNs));
        CHECK(wallNs == srNtpNs + 1000000000LL);
        CHECK(mapper.mapRtpToWallNs(1000000 + 45000, wallNs));
        CHECK(wallNs == srNtpNs + 500000000LL);
    }

    // 3. RTP wrap-around: 0xfffffff0 -> wrap -> 0x00000020 extends forward by
    //    0x30 ticks, never a huge negative jump.
    {
        const uint64_t extended = extendRtpTimestamp(0xfffffff0ULL, 0x00000020u);
        CHECK(extended == 0x100000020ULL);
        // Monotone continuation across the wrap boundary.
        const uint64_t next = extendRtpTimestamp(extended, 0x00000060u);
        CHECK(next == 0x100000060ULL);
        // No-wrap case stays in the same epoch.
        CHECK(extendRtpTimestamp(1000, 2000) == 2000);
    }

    // 4. SR mapping validity: without an anchor nothing maps (never a fake 0).
    {
        RtpToNtpMapper mapper(90000);
        int64_t wallNs = -1;
        CHECK(!mapper.mapRtpToWallNs(123456, wallNs));
        CHECK(wallNs == -1);
    }

    // 5. Generation reset / SSRC mismatch: a stale anchor must not map after
    //    reset, and an SR from a foreign SSRC is rejected and invalidates the
    //    mapping (old sender frames can never match a new receiver session).
    {
        RtpToNtpMapper mapper(90000);
        mapper.setExpectedSsrc(0x11111111);
        const uint32_t ntpSecs = static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(mapper.addSenderReport(0x11111111, 500000, ntpSecs, 0));
        CHECK(mapper.snapshot().srMappingValid);
        CHECK(!mapper.addSenderReport(0x22222222, 600000, ntpSecs, 0));
        CHECK(mapper.snapshot().ssrcMismatchCount == 1);
        CHECK(!mapper.snapshot().srMappingValid);
        int64_t wallNs = -1;
        CHECK(!mapper.mapRtpToWallNs(540000, wallNs));
        mapper.reset();
        CHECK(!mapper.snapshot().hasAnchor);
        CHECK(!mapper.mapRtpToWallNs(540000, wallNs));
    }

    // 6. Clock anomaly: receiver T0 wall BEFORE sender send wall is invalid
    //    and flagged (never clamped to 0).
    {
        const E2ESampleResult anomaly =
                measureSenderSendToReceiverT0Us(1000000000000LL, 999999999000LL);
        CHECK(!anomaly.valid);
        CHECK(anomaly.anomaly);
        CHECK(anomaly.latencyUs == -1);

        const E2ESampleResult ok =
                measureSenderSendToReceiverT0Us(1000000000000LL, 1000120000000LL);
        CHECK(ok.valid);
        CHECK(!ok.anomaly);
        CHECK(ok.latencyUs == 120000);  // 120 ms

        const E2ESampleResult missing =
                measureSenderSendToReceiverT0Us(-1, 100000120000LL);
        CHECK(!missing.valid);
        CHECK(!missing.anomaly);
    }

    // 7. Same-frame correlation: two distinct frames map to their own wall
    //    times (a frame's mapped time must never be its neighbor's).
    {
        RtpToNtpMapper mapper(90000);
        const uint32_t ntpSecs = static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(mapper.addSenderReport(0xfeedbeef, 360000, ntpSecs, 0x80000000u));
        int64_t frameA = -1;
        int64_t frameB = -1;
        CHECK(mapper.mapRtpToWallNs(360000, frameA));       // SR frame itself
        CHECK(mapper.mapRtpToWallNs(360000 + 2250, frameB)); // next 25 fps frame
        CHECK(frameA == 1755000000LL * 1000000000LL + 500000000LL);
        CHECK(frameB == frameA + 25000000LL);  // +25 ms
        CHECK(frameA != frameB);
    }

    // 8. Bounded E2E distribution: valid samples enter the bounded window,
    //    anomalies/invalids are counted separately and never enter it.
    {
        SendToT0Distribution dist;
        for (int i = 0; i < 3000; ++i) {
            E2ESampleResult sample;
            sample.valid = true;
            sample.latencyUs = 100000;
            dist.addSample(sample);
        }
        E2ESampleResult anomaly;
        anomaly.anomaly = true;
        dist.addSample(anomaly);
        E2ESampleResult invalid;
        dist.addSample(invalid);
        const SendToT0Distribution::Snapshot snap = dist.snapshot();
        CHECK(snap.validCount == 3000);
        CHECK(snap.anomalyCount == 1);
        CHECK(snap.invalidCount == 1);
        CHECK(snap.p50Us == 100000);
        CHECK(snap.p95Us == 100000);
        CHECK(snap.p99Us == 100000);
        dist.reset();
        const SendToT0Distribution::Snapshot cleared = dist.snapshot();
        CHECK(cleared.validCount == 0);
        CHECK(cleared.anomalyCount == 0);
        CHECK(cleared.invalidCount == 0);
        CHECK(cleared.p50Us == 0);
    }

    // 9. SR drift audit: NTP elapsed matching the RTP elapsed at nominal rate
    //    yields ~0 ppm; a stretched NTP delta shows up as positive drift.
    {
        RtpToNtpMapper mapper(90000);
        const uint32_t baseSecs = static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(mapper.addSenderReport(1, 0, baseSecs, 0));
        // Exactly 10 s of RTP ticks later, exactly 10 s of NTP later.
        CHECK(mapper.addSenderReport(1, 900000, static_cast<uint32_t>(baseSecs + 10), 0));
        CHECK(mapper.snapshot().driftPpm == 0);
        // Next SR arrives exactly 0.5 s late on the NTP side over another 10 s
        // of RTP (fraction 0x80000000 is exact): drift = 0.5 s / 10 s = 50000 ppm.
        CHECK(mapper.addSenderReport(1, 1800000,
                                     static_cast<uint32_t>(baseSecs + 20),
                                     0x80000000u));
        CHECK(mapper.snapshot().driftPpm == 50000);
    }

    // 10. RTCP SR tracker: new SRs recorded once, duplicates suppressed, NTP
    //     raw 64-bit conversion correct, srSendToT0 measured on wall clocks.
    {
        RtcpSrTracker tracker;
        tracker.setClockRate(90000);
        const uint64_t ntpRaw = (static_cast<uint64_t>(1755000000LL + 2208988800LL) << 32)
                                | 0x80000000ULL;
        CHECK(tracker.recordSenderReport(0x0a0b0c0d, ntpRaw, 1000000));
        // Same SR attached to many consecutive packets: counted once.
        CHECK(!tracker.recordSenderReport(0x0a0b0c0d, ntpRaw, 1000000));
        CHECK(!tracker.recordSenderReport(0x0a0b0c0d, ntpRaw, 1000000));
        const RtcpSrTracker::Snapshot snap = tracker.snapshot();
        CHECK(snap.srReceivedCount == 1);
        CHECK(snap.duplicateCount == 2);
        CHECK(snap.srMappingValid);
        CHECK(snap.ssrc == 0x0a0b0c0d);
        CHECK(snap.lastSrNtpNs == 1755000000LL * 1000000000LL + 500000000LL);
        CHECK(snap.lastSrRtpTimestamp == 1000000);
        // Cross-device segment: SR send wall -> receiver T0 wall (120 ms).
        const E2ESampleResult seg =
                measureSenderSendToReceiverT0Us(snap.lastSrNtpNs,
                                                snap.lastSrNtpNs + 120000000LL);
        CHECK(seg.valid);
        CHECK(seg.latencyUs == 120000);
    }

    // 11. RTCP SR tracker: SSRC mismatch invalidates the mapping and is
    //     counted; drift audit runs across two genuine reports.
    {
        RtcpSrTracker tracker;
        tracker.setClockRate(90000);
        const uint32_t baseSecs = static_cast<uint32_t>(1755000000LL + 2208988800LL);
        CHECK(tracker.recordSenderReport(1, static_cast<uint64_t>(baseSecs) << 32, 0));
        // Foreign SSRC: rejected mapping, still a "new" report.
        CHECK(tracker.recordSenderReport(2, (static_cast<uint64_t>(baseSecs) << 32) + 0x80000000ULL, 90000));
        const RtcpSrTracker::Snapshot bad = tracker.snapshot();
        CHECK(bad.ssrcMismatchCount == 1);
        CHECK(!bad.srMappingValid);
        // Back to the expected SSRC, exactly 10 s of RTP and NTP later.
        CHECK(tracker.recordSenderReport(1, static_cast<uint64_t>(baseSecs + 10) << 32, 900000));
        const RtcpSrTracker::Snapshot good = tracker.snapshot();
        CHECK(good.driftPpm == 0);
        CHECK(good.srMappingValid);
        // Next SR: exactly 0.5 s late over another 10 s of RTP -> 50000 ppm.
        CHECK(tracker.recordSenderReport(
                1, (static_cast<uint64_t>(baseSecs + 20) << 32) | 0x80000000ULL, 1800000));
        CHECK(tracker.snapshot().driftPpm == 50000);
        tracker.reset();
        const RtcpSrTracker::Snapshot cleared = tracker.snapshot();
        CHECK(cleared.srReceivedCount == 0);
        CHECK(!cleared.hasAnchor);
        CHECK(!cleared.srMappingValid);
    }

    if (g_failures == 0) {
        std::printf("ALL_E2E_TIMEBASE_TESTS_PASSED\n");
        return 0;
    }
    std::printf("E2E_TIMEBASE_TESTS_FAILED=%d\n", g_failures);
    return 1;
}
