#ifndef MOTRO_THERMAL_PALETTE_LUT_H
#define MOTRO_THERMAL_PALETTE_LUT_H

#include <array>
#include <cstdint>

constexpr size_t kIronbowLutSize = 256 * 3;

// Shared Ironbow 256x1 RGB LUT definition (identical for Phase 1 software
// YUV path and Phase 2 MediaCodec OES path). Index increases with intensity.
std::array<uint8_t, kIronbowLutSize> createIronbowLut();

#endif // MOTRO_THERMAL_PALETTE_LUT_H
