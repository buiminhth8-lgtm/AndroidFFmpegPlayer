#include "../../main/cpp/native/NetworkIoDeadline.h"
#include <climits>
#include <cstdio>
#include <cstdlib>
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "line %d: %s\n", __LINE__, #x); std::abort(); } } while (0)
int main() {
    NetworkIoDeadline deadline;
    CHECK(!deadline.expired(100));
    deadline.arm(100, 5000000);
    CHECK(!deadline.expired(5000099)); CHECK(deadline.expired(5000100));
    deadline.clear(); CHECK(!deadline.expired(9000000));
    deadline.arm(10000000, 5000000); CHECK(!deadline.expired(10000000));
    CHECK(deadline.expired(15000000));
    deadline.arm(100, 0); CHECK(!deadline.expired(100)); CHECK(deadline.expired(101));
    CHECK(reconnectBackoffMs(1, 1000, 5000) == 1000);
    CHECK(reconnectBackoffMs(2, 1000, 5000) == 2000);
    CHECK(reconnectBackoffMs(3, 1000, 5000) == 4000);
    CHECK(reconnectBackoffMs(INT_MAX, 1000, 5000) == 5000);
    CHECK(reconnectBackoffMs(2, INT_MAX, INT_MAX) == INT_MAX);
    CHECK(waitForInitialRtsp404(true, true, true, true, true, true));
    for (int i = 0; i < 6; ++i) {
        bool flags[6] = {true, true, true, true, true, true}; flags[i] = false;
        CHECK(!waitForInitialRtsp404(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]));
    }
    std::puts("ALL_NETWORK_IO_DEADLINE_TESTS_PASSED");
}
