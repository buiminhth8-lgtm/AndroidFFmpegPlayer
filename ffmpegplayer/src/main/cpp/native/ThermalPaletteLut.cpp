#include "ThermalPaletteLut.h"

namespace {

struct RgbPoint {
    float t;
    uint8_t r;
    uint8_t g;
    uint8_t b;
};

// Ironbow color control points, piecewise-linear interpolated to 256 entries.
// index is monotonically increasing with intensity/brightness.
// Byte-for-byte equivalent to the Phase 1 software YUV LUT.
const RgbPoint kIronbowPoints[] = {
        {0.00f, 10, 0, 30},     // near-black dark blue
        {0.15f, 0, 0, 120},     // dark blue
        {0.30f, 120, 0, 200},   // violet
        {0.45f, 200, 0, 200},   // magenta
        {0.60f, 230, 40, 60},   // red
        {0.75f, 250, 140, 20},  // orange
        {0.90f, 250, 220, 60},  // yellow
        {1.00f, 255, 245, 235}  // white
};

} // namespace

std::array<uint8_t, kIronbowLutSize> createIronbowLut() {
    constexpr int pointCount = static_cast<int>(sizeof(kIronbowPoints) / sizeof(kIronbowPoints[0]));
    std::array<uint8_t, kIronbowLutSize> lut{};
    for (int i = 0; i < 256; ++i) {
        const float t = i / 255.0f;
        int seg = 0;
        for (int s = 0; s < pointCount - 1; ++s) {
            if (t <= kIronbowPoints[s + 1].t) {
                seg = s;
                break;
            }
            seg = s;
        }
        const RgbPoint &a = kIronbowPoints[seg];
        const RgbPoint &b = kIronbowPoints[seg + 1];
        const float span = (b.t - a.t) > 0.0f ? (b.t - a.t) : 1.0f;
        const float f = (t - a.t) / span;
        lut[i * 3 + 0] = static_cast<uint8_t>(a.r + (b.r - a.r) * f);
        lut[i * 3 + 1] = static_cast<uint8_t>(a.g + (b.g - a.g) * f);
        lut[i * 3 + 2] = static_cast<uint8_t>(a.b + (b.b - a.b) * f);
    }
    return lut;
}
