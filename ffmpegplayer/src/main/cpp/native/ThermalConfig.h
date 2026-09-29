#ifndef MOTRO_THERMAL_CONFIG_H
#define MOTRO_THERMAL_CONFIG_H

#include <string>

enum class ThermalPaletteMode {
    ORIGINAL = 0,
    WHITE_HOT = 1,
    IRONBOW = 2
};

// 热成像显示参数；仅控制画面强度映射与伪彩色，不代表温度标定结果。
struct ThermalConfig {
    bool enabled = false;
    ThermalPaletteMode palette = ThermalPaletteMode::ORIGINAL;
    // 自动增益控制：根据图像亮度分布估计显示窗口。
    bool agcEnabled = false;
    float gamma = 1.0f;
    // 归一化强度窗口的下限与上限，用于拉伸暗部到亮部的显示范围。
    float blackPoint = 0.0f;
    float whitePoint = 1.0f;
};

std::string thermalPaletteName(ThermalPaletteMode palette);
bool parseThermalPalette(int value, ThermalPaletteMode &palette);
bool isValidThermalGamma(float gamma);
bool isValidThermalWindow(float blackPoint, float whitePoint);

#endif // MOTRO_THERMAL_CONFIG_H
