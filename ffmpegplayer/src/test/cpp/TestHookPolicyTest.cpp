// Production test-hook policy host tests (no external framework).
#include "../../main/cpp/native/TestHookPolicy.h"

#include <cstdio>
#include <string_view>

#ifndef FFMPEGPLAYER_EXPECT_TEST_HOOKS
#error "FFMPEGPLAYER_EXPECT_TEST_HOOKS must be defined by the test command"
#endif

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS != FFMPEGPLAYER_EXPECT_TEST_HOOKS
#error "test-hook policy macro does not match the expected build variant"
#endif

namespace {

bool expectPolicy(std::string_view command,
                  ffmpegplayer::TestHookCommandPolicy expected,
                  const char *label) {
    if (ffmpegplayer::testHookCommandPolicy(command) == expected) {
        return true;
    }
    std::printf("FAIL policy: %s\n", label);
    return false;
}

}  // namespace

int main() {
#if FFMPEGPLAYER_EXPECT_TEST_HOOKS
    constexpr auto expectedTestPolicy =
            ffmpegplayer::TestHookCommandPolicy::EnabledInDebug;
#else
    constexpr auto expectedTestPolicy =
            ffmpegplayer::TestHookCommandPolicy::UnsupportedInRelease;
#endif

    bool passed = true;
    passed &= expectPolicy("-player-lifetime-stress", expectedTestPolicy,
                           "player lifetime stress");
    passed &= expectPolicy("-audio-backpressure-test", expectedTestPolicy,
                           "audio backpressure");

    // Production diagnostics and unknown commands must bypass the test-hook
    // gate so the existing runDebugCommand dispatcher handles them unchanged.
    passed &= expectPolicy("-version", ffmpegplayer::TestHookCommandPolicy::NotTestHook,
                           "normal diagnostic command");
    passed &= expectPolicy("-latency-config",
                           ffmpegplayer::TestHookCommandPolicy::NotTestHook,
                           "latency diagnostic command");
    passed &= expectPolicy("-unknown-command",
                           ffmpegplayer::TestHookCommandPolicy::NotTestHook,
                           "unknown command");

    constexpr std::string_view expectedReleaseRejection =
            R"({"success":false,"errorCode":"unsupported_in_release","message":"test hook is unsupported in release builds"})";
    if (ffmpegplayer::kTestHookUnsupportedJson != expectedReleaseRejection) {
        std::printf("FAIL stable release rejection\n");
        passed = false;
    }

    if (!passed) {
        return 1;
    }
#if FFMPEGPLAYER_EXPECT_TEST_HOOKS
    std::printf("ALL_DEBUG_TEST_HOOK_POLICY_TESTS_PASSED\n");
#else
    std::printf("ALL_RELEASE_TEST_HOOK_POLICY_TESTS_PASSED\n");
#endif
    return 0;
}
