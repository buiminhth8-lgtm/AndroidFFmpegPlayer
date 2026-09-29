#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <limits>

// 单调时钟截止时间。由输入所属线程 arm/clear，FFmpeg 回调只读取原子值。
class NetworkIoDeadline {
public:
    void arm(int64_t nowUs, int64_t timeoutUs) {
        const int64_t duration = std::max<int64_t>(1, timeoutUs);
        deadlineUs_.store(nowUs > std::numeric_limits<int64_t>::max() - duration
                          ? std::numeric_limits<int64_t>::max() : nowUs + duration);
    }
    void clear() { deadlineUs_.store(0); }
    bool expired(int64_t nowUs) const {
        const int64_t deadline = deadlineUs_.load();
        return deadline > 0 && nowUs >= deadline;
    }
private:
    std::atomic<int64_t> deadlineUs_{0};
};

inline int reconnectBackoffMs(int attempt, int initialMs, int maximumMs) {
    const int64_t initial = std::max(100, initialMs);
    const int64_t maximum = std::max<int64_t>(initial, maximumMs);
    int64_t delay = initial;
    for (int i = 1; i < attempt && delay < maximum; ++i) delay = std::min(delay * 2, maximum);
    return static_cast<int>(delay);
}

inline bool waitForInitialRtsp404(bool rtsp, bool notFound, bool enabled,
                                 bool reconnect404, bool keepWaiting, bool retryAllowed) {
    return rtsp && notFound && enabled && reconnect404 && keepWaiting && retryAllowed;
}
