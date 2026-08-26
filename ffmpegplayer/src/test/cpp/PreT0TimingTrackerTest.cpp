// LAT5 Pre-T0 timing helper host tests (no external framework; plain asserts).
// Compile with a host C++17 compiler, e.g.:
//   cl /nologo /std:c++17 /EHsc PreT0TimingTrackerTest.cpp /I ..\..\main\cpp\native
// Exit code 0 = all passed.
#include "../../main/cpp/native/diagnostics/PreT0TimingTracker.h"

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
    // 1. read duration: R0=100, R1=140 -> duration=40 (same clock as LAT2).
    {
        PreT0TimingTracker t;
        t.recordReadCall(140 - 100, PreT0TimingTracker::ReadResultClass::ReadOk);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.readCallCount == 1);
        CHECK(s.lastReadDurationUs == 40);
        CHECK(s.avgReadDurationUs == 40);
        CHECK(s.maxReadDurationUs == 40);
        CHECK(s.readDurationP50Us == 40);
        CHECK(s.readDurationDistCount == 1);
    }

    // 2. video return gap: returns at 100/140/180 -> gaps 40,40.
    {
        PreT0TimingTracker t;
        t.recordVideoReturn(100, 100000);
        t.recordVideoReturn(140, 100040);
        t.recordVideoReturn(180, 100080);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.videoReadCallCount == 3);
        CHECK(s.lastVideoReturnGapUs == 40);
        CHECK(s.avgVideoReturnGapUs == 40);
        CHECK(s.maxVideoReturnGapUs == 40);
        CHECK(s.videoReturnGapP50Us == 40);
        CHECK(s.videoReturnGapDistCount == 2);
        CHECK(s.lastVideoPtsDeltaUs == 40);
        CHECK(s.avgVideoPtsDeltaUs == 40);
        CHECK(s.videoPtsDeltaSampleCount == 2);
    }

    // 3a. normal 40 ms cadence must NOT be classified as a fast burst.
    {
        PreT0TimingTracker t;
        t.recordVideoReturn(0, 0);
        t.recordVideoReturn(40000, 40000);
        t.recordVideoReturn(80000, 80000);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.fastReturnPacketCount == 0);
        CHECK(s.currentFastReturnBurstLength == 0);
        CHECK(s.maxFastReturnBurstLength == 0);
    }

    // 3b. consecutive very-short gaps must grow the burst counters.
    {
        PreT0TimingTracker t;
        t.recordVideoReturn(0, 0);
        t.recordVideoReturn(100, 100);
        t.recordVideoReturn(200, 200);
        t.recordVideoReturn(300, 300);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.fastReturnPacketCount == 3);
        CHECK(s.currentFastReturnBurstLength == 4);
        CHECK(s.maxFastReturnBurstLength == 4);
        t.recordVideoReturn(40000, 40000);  // slow gap breaks the burst
        const PreT0TimingTracker::Snapshot s2 = t.snapshot();
        CHECK(s2.currentFastReturnBurstLength == 0);
        CHECK(s2.maxFastReturnBurstLength == 4);
    }

    // 4. generation/reset: previous video return timestamp must be cleared.
    {
        PreT0TimingTracker t;
        t.recordVideoReturn(1000, 1000);
        t.reset();
        t.recordVideoReturn(5000, 5000);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.lastVideoReturnGapUs == -1);
        CHECK(s.avgVideoReturnGapUs == 0);
        CHECK(s.videoReturnGapDistCount == 0);
        CHECK(s.lastVideoPtsDeltaUs == -1);
    }

    // 5. invalid/error reads: no video return, no fake gap, classes counted.
    {
        PreT0TimingTracker t;
        t.recordReadCall(50000, PreT0TimingTracker::ReadResultClass::ReadEagain);
        t.recordReadCall(60000, PreT0TimingTracker::ReadResultClass::ReadTimeout);
        t.recordReadCall(70000, PreT0TimingTracker::ReadResultClass::ReadEof);
        t.recordReadCall(80000, PreT0TimingTracker::ReadResultClass::ReadError);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.readCallCount == 4);
        CHECK(s.readEagainCount == 1);
        CHECK(s.readTimeoutCount == 1);
        CHECK(s.readEofCount == 1);
        CHECK(s.readErrorCount == 1);
        CHECK(s.videoReadCallCount == 0);
        CHECK(s.lastVideoReturnGapUs == -1);
        CHECK(s.readDurationDistCount == 4);
    }

    // 5b. negative duration is not injected into the distribution.
    {
        PreT0TimingTracker t;
        t.recordReadCall(-5, PreT0TimingTracker::ReadResultClass::ReadOk);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.readCallCount == 1);
        CHECK(s.lastReadDurationUs == -5);
        CHECK(s.readDurationDistCount == 0);
    }

    // BASIC mode: outcome health remains available without timing samples or
    // percentile snapshot work.
    {
        PreT0TimingTracker t;
        t.recordReadOutcome(PreT0TimingTracker::ReadResultClass::ReadTimeout);
        t.recordReadOutcome(PreT0TimingTracker::ReadResultClass::ReadError);
        const PreT0TimingTracker::Snapshot s = t.snapshot(false);
        CHECK(s.readCallCount == 2);
        CHECK(s.readTimeoutCount == 1);
        CHECK(s.readErrorCount == 1);
        CHECK(s.lastReadDurationUs == -1);
        CHECK(s.readDurationDistCount == 0);
        CHECK(s.videoReturnGapDistCount == 0);
    }

    // 6. bounded distributions: feeding far more than the window never grows
    //    the distribution counts beyond kLatencyDistributionWindow.
    {
        PreT0TimingTracker t;
        for (int i = 0; i < 3000; ++i) {
            t.recordReadCall(40000, PreT0TimingTracker::ReadResultClass::ReadOk);
        }
        for (int i = 0; i < 3000; ++i) {
            t.recordVideoReturn(static_cast<int64_t>(i) * 1000,
                                static_cast<int64_t>(i) * 1000);
        }
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.readCallCount == 3000);
        CHECK(s.videoReadCallCount == 3000);
        CHECK(s.readDurationDistCount == static_cast<int64_t>(kLatencyDistributionWindow));
        CHECK(s.videoReturnGapDistCount == static_cast<int64_t>(kLatencyDistributionWindow));
    }

    // Stall buckets: 150/300/600/1200 ms -> >100=4, >250=3, >500=2, >1000=1.
    {
        PreT0TimingTracker t;
        t.recordReadCall(150000, PreT0TimingTracker::ReadResultClass::ReadOk);
        t.recordReadCall(300000, PreT0TimingTracker::ReadResultClass::ReadOk);
        t.recordReadCall(600000, PreT0TimingTracker::ReadResultClass::ReadOk);
        t.recordReadCall(1200000, PreT0TimingTracker::ReadResultClass::ReadOk);
        const PreT0TimingTracker::Snapshot s = t.snapshot();
        CHECK(s.readStallGt100MsCount == 4);
        CHECK(s.readStallGt250MsCount == 3);
        CHECK(s.readStallGt500MsCount == 2);
        CHECK(s.readStallGt1000MsCount == 1);
        CHECK(s.maxReadStallUs == 1200000);
    }

    if (g_failures == 0) {
        std::printf("ALL_PRE_T0_TRACKER_TESTS_PASSED\n");
        return 0;
    }
    std::printf("PRE_T0_TRACKER_TESTS_FAILED=%d\n", g_failures);
    return 1;
}
