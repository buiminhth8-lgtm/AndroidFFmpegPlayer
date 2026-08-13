#include "ThermalConfig.h"

#include <cmath>

std::string thermalPaletteName(ThermalPaletteMode palette) {
    switch (palette) {
        case ThermalPaletteMode::ORIGINAL: return "original";
        case ThermalPaletteMode::WHITE_HOT: return "white_hot";
        case ThermalPaletteMode::IRONBOW: return "ironbow";
    }
    return "original";
}

bool parseThermalPalette(int value, ThermalPaletteMode &palette) {
    switch (value) {
        case 0: palette = ThermalPaletteMode::ORIGINAL; return true;
        case 1: palette = ThermalPaletteMode::WHITE_HOT; return true;
        case 2: palette = ThermalPaletteMode::IRONBOW; return true;
        default: return false;
    }
}

bool isValidThermalGamma(float gamma) {
    return std::isfinite(gamma) && gamma >= 0.5f && gamma <= 2.0f;
}
