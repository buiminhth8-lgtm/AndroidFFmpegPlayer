#ifndef MOTRO_LATENCY_DISTRIBUTION_H
#define MOTRO_LATENCY_DISTRIBUTION_H

#include <algorithm>
#include <array>
#include <cstdint>
#include <mutex>
#include <vector>

// LAT3 bounded rolling-window latency distribution. Fixed-size ring (no
// unbounded growth); percentiles are nearest-rank over the most recent
// samples. The mutex critical sections are tiny (one slot write on add, one
// copy on snapshot). Extracted from NativePlayer.h so the timing helpers are
// directly host-testable; semantics are unchanged.
constexpr size_t kLatencyDistributionWindow = 1024;

class LatencyDistribution {
public:
    struct Snapshot {
        int64_t count = 0;
        int64_t avg = 0;
        int64_t p50 = 0;
        int64_t p95 = 0;
        int64_t p99 = 0;
        int64_t max = 0;
    };

    void addSample(int64_t us) {
        if (us < 0) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        samples_[static_cast<size_t>(head_)] = us;
        head_ = (head_ + 1) % static_cast<int>(kLatencyDistributionWindow);
        if (count_ < static_cast<int>(kLatencyDistributionWindow)) {
            ++count_;
        }
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        count_ = 0;
        head_ = 0;
    }

    Snapshot snapshot() const {
        Snapshot snap;
        std::vector<int64_t> sorted;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (count_ <= 0) {
                return snap;
            }
            sorted.reserve(static_cast<size_t>(count_));
            for (int i = 0; i < count_; ++i) {
                const int idx = (head_ - count_ + i + static_cast<int>(kLatencyDistributionWindow))
                                % static_cast<int>(kLatencyDistributionWindow);
                sorted.push_back(samples_[static_cast<size_t>(idx)]);
            }
        }
        std::sort(sorted.begin(), sorted.end());
        const int n = static_cast<int>(sorted.size());
        int64_t sum = 0;
        for (int64_t v : sorted) {
            sum += v;
        }
        snap.count = n;
        snap.avg = n > 0 ? sum / n : 0;
        snap.p50 = sorted[static_cast<size_t>(percentileIndex(50, n))];
        snap.p95 = sorted[static_cast<size_t>(percentileIndex(95, n))];
        snap.p99 = sorted[static_cast<size_t>(percentileIndex(99, n))];
        snap.max = sorted.back();
        return snap;
    }

private:
    static int64_t percentileIndex(int pct, int n) {
        if (n <= 0) {
            return 0;
        }
        int64_t rank = (static_cast<int64_t>(pct) * n + 99) / 100;
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return rank - 1;
    }

    mutable std::mutex mutex_;
    std::array<int64_t, kLatencyDistributionWindow> samples_{};
    int count_ = 0;
    int head_ = 0;
};

#endif  // MOTRO_LATENCY_DISTRIBUTION_H
