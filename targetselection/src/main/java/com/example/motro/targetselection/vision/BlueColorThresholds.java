package com.example.motro.targetselection.vision;

/**
 * 「什么算蓝色」的完整判定规则，<b>不可变</b>。把散落的阈值收在一处，
 * 于是「调参」只需要改这一份；顺便让 {@link BlueMaskDetector} 不必认识
 * {@code TargetSelectionConfig} 这种上层策略对象。
 *
 * <p><b>判定式</b>（{@link #matches(float, float, float)}）：</p>
 * <pre>
 *   hue ∈ [hueMinDegrees, hueMaxDegrees]   ← 饱和度/明度不过关时色调无意义，所以三项都要
 *   saturation &gt;= minSaturation
 *   value      &gt;= minValue
 * </pre>
 *
 * <p><b>默认值为什么是这个区间</b>：纯蓝 {@code #0000FF} 是 240°；推流端叠加层常用
 * 接近纯蓝的 {@code #0066FF}（≈217°）或 {@code #1E90FF}（≈210°）；HEVC 压缩偏冷时
 * 也可能落到 250°~265°。取 <b>200°~270°</b> 覆盖这几类，同时把青色（≈180°，画面里
 * 常见于天空/水面）排除在外 —— 蓝色区间定得过宽是本案最典型的误检来源。
 * 饱和度 0.30 / 明度 0.20 用来滤掉「近乎灰的压缩噪声」，它们往往是蓝色区间的
 * 假阳性主要来源。</p>
 *
 * <p><b>色调环绕（wrap-around）</b>：若 {@code hueMinDegrees &gt; hueMaxDegrees}，
 * 区间按跨越 0° 解释（例如 350°~10°）—— 蓝色虽然用不上，但把这条语义定死，
 * 以后换成红色（0° 附近）时不必再改判定逻辑。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class BlueColorThresholds {

    /** 默认蓝色色调下界（角度）。 */
    public static final float DEFAULT_HUE_MIN_DEGREES = 200f;

    /** 默认蓝色色调上界（角度）。 */
    public static final float DEFAULT_HUE_MAX_DEGREES = 270f;

    /** 默认最低饱和度。 */
    public static final float DEFAULT_MIN_SATURATION = 0.30f;

    /** 默认最低明度。 */
    public static final float DEFAULT_MIN_VALUE = 0.20f;

    /** 色调下界（角度，[0,360)）。 */
    public final float hueMinDegrees;

    /** 色调上界（角度，[0,360)）。小于下界时表示区间跨 0° 环绕。 */
    public final float hueMaxDegrees;

    /** 最低饱和度 [0,1]。 */
    public final float minSaturation;

    /** 最低明度 [0,1]。 */
    public final float minValue;

    private BlueColorThresholds(float hueMinDegrees, float hueMaxDegrees,
                                float minSaturation, float minValue) {
        this.hueMinDegrees = hueMinDegrees;
        this.hueMaxDegrees = hueMaxDegrees;
        this.minSaturation = minSaturation;
        this.minValue = minValue;
    }

    /**
     * @param hueMinDegrees 色调下界（角度，[0,360)）
     * @param hueMaxDegrees 色调上界（角度，[0,360)）
     * @param minSaturation 最低饱和度 [0,1]
     * @param minValue      最低明度 [0,1]
     * @throws IllegalArgumentException 任一值非有限、色调不在 [0,360)、饱和度或明度不在 [0,1]
     */
    public static BlueColorThresholds of(float hueMinDegrees, float hueMaxDegrees,
                                         float minSaturation, float minValue) {
        requireFinite(hueMinDegrees, "hueMinDegrees");
        requireFinite(hueMaxDegrees, "hueMaxDegrees");
        if (hueMinDegrees < 0f || hueMinDegrees >= 360f) {
            throw new IllegalArgumentException("hueMinDegrees must be within [0,360) but was " + hueMinDegrees);
        }
        if (hueMaxDegrees < 0f || hueMaxDegrees >= 360f) {
            throw new IllegalArgumentException("hueMaxDegrees must be within [0,360) but was " + hueMaxDegrees);
        }
        requireUnitRange(minSaturation, "minSaturation");
        requireUnitRange(minValue, "minValue");
        return new BlueColorThresholds(hueMinDegrees, hueMaxDegrees, minSaturation, minValue);
    }

    /** 默认规则：200°~270°、S≥0.30、V≥0.20。 */
    public static BlueColorThresholds defaults() {
        return new BlueColorThresholds(DEFAULT_HUE_MIN_DEGREES, DEFAULT_HUE_MAX_DEGREES,
                DEFAULT_MIN_SATURATION, DEFAULT_MIN_VALUE);
    }

    /** 色调区间是否跨越 0°。 */
    public boolean wrapsAroundHue() {
        return hueMinDegrees > hueMaxDegrees;
    }

    /**
     * 单个像素是否判定为蓝色。色调比较已处理环绕，调用方不必关心。
     *
     * @param hue        色调（角度，[0,360)）
     * @param saturation 饱和度 [0,1]
     * @param value      明度 [0,1]
     */
    public boolean matches(float hue, float saturation, float value) {
        if (saturation < minSaturation || value < minValue) {
            return false;
        }
        return hueWithinRange(hue);
    }

    private boolean hueWithinRange(float hue) {
        if (wrapsAroundHue()) {
            return hue >= hueMinDegrees || hue <= hueMaxDegrees;
        }
        return hue >= hueMinDegrees && hue <= hueMaxDegrees;
    }

    /** 稳定的一行摘要，便于日志抓取（调参时按这行记录现场）。 */
    public String describe() {
        return "hue=[" + hueMinDegrees + "," + hueMaxDegrees + "]deg"
                + " minSat=" + minSaturation + " minVal=" + minValue;
    }

    private static void requireUnitRange(float value, String name) {
        if (Float.isNaN(value) || value < 0f || value > 1f) {
            throw new IllegalArgumentException(name + " must be within [0,1] but was " + value);
        }
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    @Override
    public String toString() {
        return "BlueColorThresholds{" + describe() + "}";
    }
}
