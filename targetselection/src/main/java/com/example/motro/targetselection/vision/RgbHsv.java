package com.example.motro.targetselection.vision;

/**
 * RGB → HSV 转换（纯函数，<b>无任何 Android 依赖</b>，可在纯 JVM 单测里穷举）。
 *
 * <p><b>为什么必须先转 HSV，而不能直接比较 RGB</b>：推流端画的蓝框经 HEVC 编码后，
 * 像素值会整体偏移（量化 + 色度子采样），纯蓝 {@code #0000FF} 常常解码成
 * {@code #3C4FD2} 之类。用 {@code rgb == 0xFF0000FF} 这种精确比较会大面积漏检。
 * HSV 把「是什么颜色（H）」与「多浓（S）」「多亮（V）」拆开，于是可以写
 * 「色调在蓝色区间内，且饱和度/亮度不过低」这类容错规则 —— 同一套阈值能同时容忍
 * 压缩偏移、光照变化与不同推流端的调色。</p>
 *
 * <p><b>量纲约定（重要，避免与 OpenCV 的 HSV 混淆）</b>：</p>
 * <ul>
 *   <li>{@link #hue}：<b>角度</b>，[0, 360)。纯蓝 {@code #0000FF} = 240°。
 *       （注意 OpenCV 的 {@code cv::cvtColor} 默认 H 是 [0,180]，是同值的二分之一；
 *       本模块用角度制，配置里的 {@code blueHueMin/Max} 也是角度制。）</li>
 *   <li>{@link #saturation}：[0, 1]。</li>
 *   <li>{@link #value}：[0, 1]（HSV 的 V，等于 max(R,G,B)/255，不是亮度 luma）。</li>
 * </ul>
 *
 * <p>Alpha 通道被<b>忽略</b>：推流叠加层与回读帧都是不透明合成结果，
 * 用 alpha 参与颜色判定只会引入无关变量。</p>
 *
 * <p>线程约定：本类无可变状态，{@link #fromArgb(int, float[])} 允许调用方传入
 * 复用数组以避免逐像素分配 —— 这是本模块热路径上唯一重要的优化。</p>
 */
public final class RgbHsv {

    /** {@link #fromArgb(int, float[])} 输出数组的长度：{hue, saturation, value}。 */
    public static final int COMPONENTS = 3;

    /** 色调（角度，[0,360)）。 */
    public final float hue;

    /** 饱和度 [0,1]。 */
    public final float saturation;

    /** 明度 [0,1]。 */
    public final float value;

    private RgbHsv(float hue, float saturation, float value) {
        this.hue = hue;
        this.saturation = saturation;
        this.value = value;
    }

    /**
     * 由 ARGB_8888 打包整数（{@code android.graphics.Bitmap.getPixels} 的格式）转换。
     *
     * @param argb 打包颜色，alpha 被忽略
     */
    public static RgbHsv fromArgb(int argb) {
        final float[] out = fromArgb(argb, new float[COMPONENTS]);
        return new RgbHsv(out[0], out[1], out[2]);
    }

    /**
     * 零分配重载：把结果写入调用方提供的数组。
     *
     * @param argb 打包颜色，alpha 被忽略
     * @param out  长度 &gt;= {@link #COMPONENTS} 的数组；不允许为 null
     * @return 传入的 {@code out}，便于链式使用
     * @throws IllegalArgumentException out 为 null 或长度不足
     */
    public static float[] fromArgb(int argb, float[] out) {
        if (out == null || out.length < COMPONENTS) {
            throw new IllegalArgumentException("out must have at least " + COMPONENTS + " elements");
        }
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;

        final int max = Math.max(r, Math.max(g, b));
        final int min = Math.min(r, Math.min(g, b));
        final int delta = max - min;

        // V = max/255；S = delta/max（max == 0 时颜色是纯黑，饱和度定义为 0）
        final float value = max / 255f;
        final float saturation = max == 0 ? 0f : delta / (float) max;

        // H：delta == 0（灰阶）时色调无定义，约定为 0 —— 但那时饱和度恒为 0，
        // 任何 minSaturation > 0 的规则都会把它排除，所以这个约定不会造成误判。
        float hue;
        if (delta == 0) {
            hue = 0f;
        } else if (max == r) {
            hue = 60f * (((g - b) / (float) delta) % 6f);
        } else if (max == g) {
            hue = 60f * ((b - r) / (float) delta + 2f);
        } else {
            hue = 60f * ((r - g) / (float) delta + 4f);
        }
        if (hue < 0f) {
            hue += 360f;
        }

        out[0] = hue;
        out[1] = saturation;
        out[2] = value;
        return out;
    }

    @Override
    public String toString() {
        return "RgbHsv{h=" + hue + ", s=" + saturation + ", v=" + value + "}";
    }
}
