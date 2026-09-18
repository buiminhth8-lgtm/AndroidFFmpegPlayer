#pragma once

#include <string_view>

#ifndef FFMPEGPLAYER_ENABLE_TEST_HOOKS
#error "FFMPEGPLAYER_ENABLE_TEST_HOOKS must be defined by the build"
#endif

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS != 0 && FFMPEGPLAYER_ENABLE_TEST_HOOKS != 1
#error "FFMPEGPLAYER_ENABLE_TEST_HOOKS must be 0 or 1"
#endif

namespace ffmpegplayer {

enum class TestHookCommandPolicy {
    NotTestHook,
    EnabledInDebug,
    UnsupportedInRelease,
};

inline constexpr std::string_view kTestHookUnsupportedJson =
        R"({"success":false,"errorCode":"unsupported_in_release","message":"test hook is unsupported in release builds"})";

constexpr bool isTestOnlyDebugCommand(std::string_view command) {
    return command == "-player-lifetime-stress"
           || command == "-audio-backpressure-test";
}

constexpr TestHookCommandPolicy testHookCommandPolicy(std::string_view command) {
    if (!isTestOnlyDebugCommand(command)) {
        return TestHookCommandPolicy::NotTestHook;
    }
#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
    return TestHookCommandPolicy::EnabledInDebug;
#else
    return TestHookCommandPolicy::UnsupportedInRelease;
#endif
}

}  // namespace ffmpegplayer
