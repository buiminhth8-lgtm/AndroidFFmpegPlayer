#ifndef MOTRO_THERMAL_CONFIG_H
#define MOTRO_THERMAL_CONFIG_H

#include <string>

enum class ThermalPaletteMode {
    ORIGINAL = 0,
    WHITE_HOT = 1,
    IRONBOW = 2
};

struct ThermalConfig {
    bool enabled = false;
    ThermalPaletteMode palette = ThermalPaletteMode::ORIGINAL;
    bool agcEnabled = false;
    float gamma = 1.0f;
    float blackPoint = 0.0f;
    float whitePoint = 1.0f;
};

std::string thermalPaletteName(ThermalPaletteMode palette);
bool parseThermalPalette(int value, ThermalPaletteMode &palette);
bool isValidThermalGamma(float gamma);
bool isValidThermalWindow(float blackPoint, float whitePoint);

#endif // MOTRO_THERMAL_CONFIG_H
