package com.example.motro.targetselection.api;

import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.vision.BlueColorThresholds;

/**
 * 目标选择的默认策略，不可变。用 {@link #toBuilder()} 做局部调整，避免 setter 带来的可变共享。
 *
 * <p>与 {@link FrameCaptureRequest} 的分工：本类是**长期默认策略**（控制器用它构造每次的取帧请求、
 * 决定是否汇报未知 id）；请求对象是**单次调用参数**。</p>
 *
 * <p><b>两类配置，动机不同</b>：</p>
 * <ul>
 *   <li><b>管线策略</b>（{@code enabled} / 取帧预算 / 帧龄 / 置信度 / id 汇报）：决定
 *       "要不要做、用多久做"。</li>
 *   <li><b>视觉调参</b>（{@code roiRadius*} / {@code blueHue*} / {@code minSaturation} /
 *       {@code minValue} / {@code minBluePixelCount} / {@code maskDenoiseMinNeighbors}）：
 *       决定"看多大范围、什么颜色算蓝"。这些值<b>必然</b>会随真实无人机画面/推流端调色而变，
 *       所以它们在这里集中暴露，而不是散落成算法里的魔数 —— 现场调参只改这一个对象。</li>
 * </ul>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetSelectionConfig {

    /** {@link #maxFrameLongSidePx} 的取值：不缩放。 */
    public static final int NO_SCALE_LIMIT = 0;

    /** {@link #maxFrameAgeNs} 的取值：不限帧龄。 */
    public static final long NO_FRAME_AGE_LIMIT_NS = 0L;

    /**
     * {@link #roiRadiusX}/{@link #roiRadiusY} 的默认值（视频像素）。
     *
     * <p>取 200 的理由：一个无人机目标框的边长通常在数百像素量级，点击点一般落在框内，
     * 半径 200 足以把整条边（含框线粗细与轻微抖动）包进 ROI；再用「只分析 ROI」把
     * 待处理像素从 1080p 的 200 万压到 16 万。范围 150~250 都能工作，越大越不容易漏掉
     * 框边、但越慢。</p>
     */
    public static final int DEFAULT_ROI_RADIUS = 200;

    /** {@link #minBluePixelCount} 的默认值：低于它视为没有蓝色。 */
    public static final int DEFAULT_MIN_BLUE_PIXEL_COUNT = 12;

    /** {@link #maskDenoiseMinNeighbors} 的默认值：删除 8 邻域内没有任何蓝像素的孤立点。 */
    public static final int DEFAULT_MASK_DENOISE_MIN_NEIGHBORS = 1;

    /** 是否启用。false 时控制器直接返回 {@code CANCELLED}，完全不触达帧来源与识别实现。 */
    public final boolean enabled;

    /** 每次取帧的时间预算（毫秒），会被写入 {@link FrameCaptureRequest#timeoutMs}。 */
    public final long captureTimeoutMs;

    /** 分析用图最长边上限（像素，0 = 不缩放），决定精度/耗时的取舍。 */
    public final int maxFrameLongSidePx;

    /** 可接受帧龄（纳秒，0 = 不限）；用于避免分析到过旧的帧。 */
    public final long maxFrameAgeNs;

    /** 最小置信度 [0,1]；低于该值的候选按 {@code MISS} 处理。 */
    public final float minConfidence;

    /**
     * 是否汇报“命中但 id 未知”。true → {@code HIT_ID_UNKNOWN}；
     * false → 降级为 {@code MISS}（某些上层只认 id，不希望收到半成品结果）。
     */
    public final boolean reportUnknownTargetId;

    // ---------------------------------------------------------------- 视觉调参（Slice 4）

    /** ROI 水平半径（<b>视频</b>像素，&gt; 0）。点击点两侧各取这么多像素。 */
    public final int roiRadiusX;

    /** ROI 垂直半径（<b>视频</b>像素，&gt; 0）。 */
    public final int roiRadiusY;

    /** 蓝色色调下界（角度，[0,360)）。见 {@link BlueColorThresholds}。 */
    public final float blueHueMinDegrees;

    /** 蓝色色调上界（角度，[0,360)）。小于下界时表示区间跨 0° 环绕。 */
    public final float blueHueMaxDegrees;

    /** 蓝色最低饱和度 [0,1]。 */
    public final float minSaturation;

    /** 蓝色最低明度 [0,1]。 */
    public final float minValue;

    /** 蓝色像素数低于该值即视为没有蓝色（&ge; 0）。 */
    public final int minBluePixelCount;

    /**
     * 掩码去噪阈值：8 邻域内蓝像素少于该值的像素会被删除（[0,8]，0 = 不去噪）。
     * 默认 1（只删除完全孤立的噪声点），刻意不做腐蚀 —— 见
     * {@link com.example.motro.targetselection.vision.BlueMaskDetector}。
     */
    public final int maskDenoiseMinNeighbors;

    /**
     * 是否在分析结果里保留 ROI 原始像素（仅供调试；默认 false）。
     * 打开后会多一块 ROI 大小的数组，因此<b>不要</b>在正式运行路径上长期开启。
     */
    public final boolean debugMaskEnabled;

    private TargetSelectionConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.captureTimeoutMs = builder.captureTimeoutMs;
        this.maxFrameLongSidePx = builder.maxFrameLongSidePx;
        this.maxFrameAgeNs = builder.maxFrameAgeNs;
        this.minConfidence = builder.minConfidence;
        this.reportUnknownTargetId = builder.reportUnknownTargetId;
        this.roiRadiusX = builder.roiRadiusX;
        this.roiRadiusY = builder.roiRadiusY;
        this.blueHueMinDegrees = builder.blueHueMinDegrees;
        this.blueHueMaxDegrees = builder.blueHueMaxDegrees;
        this.minSaturation = builder.minSaturation;
        this.minValue = builder.minValue;
        this.minBluePixelCount = builder.minBluePixelCount;
        this.maskDenoiseMinNeighbors = builder.maskDenoiseMinNeighbors;
        this.debugMaskEnabled = builder.debugMaskEnabled;
    }

    /**
     * 默认策略：启用、300ms 取帧预算、分析图最长边 640、不限帧龄、不设置信度门槛、汇报未知 id；
     * ROI 半径 200、蓝色 200°~270° / S≥0.30 / V≥0.20、去噪阈值 1、蓝像素下限 12、debug 关闭。
     */
    public static TargetSelectionConfig defaults() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .captureTimeoutMs(captureTimeoutMs)
                .maxFrameLongSidePx(maxFrameLongSidePx)
                .maxFrameAgeNs(maxFrameAgeNs)
                .minConfidence(minConfidence)
                .reportUnknownTargetId(reportUnknownTargetId)
                .roiRadiusX(roiRadiusX)
                .roiRadiusY(roiRadiusY)
                .blueHueMinDegrees(blueHueMinDegrees)
                .blueHueMaxDegrees(blueHueMaxDegrees)
                .minSaturation(minSaturation)
                .minValue(minValue)
                .minBluePixelCount(minBluePixelCount)
                .maskDenoiseMinNeighbors(maskDenoiseMinNeighbors)
                .debugMaskEnabled(debugMaskEnabled);
    }

    @Override
    public String toString() {
        return "TargetSelectionConfig{enabled=" + enabled
                + ", captureTimeoutMs=" + captureTimeoutMs
                + ", maxFrameLongSidePx=" + maxFrameLongSidePx
                + ", maxFrameAgeNs=" + maxFrameAgeNs
                + ", minConfidence=" + minConfidence
                + ", reportUnknownTargetId=" + reportUnknownTargetId
                + ", roiRadiusX=" + roiRadiusX
                + ", roiRadiusY=" + roiRadiusY
                + ", blueHueMinDegrees=" + blueHueMinDegrees
                + ", blueHueMaxDegrees=" + blueHueMaxDegrees
                + ", minSaturation=" + minSaturation
                + ", minValue=" + minValue
                + ", minBluePixelCount=" + minBluePixelCount
                + ", maskDenoiseMinNeighbors=" + maskDenoiseMinNeighbors
                + ", debugMaskEnabled=" + debugMaskEnabled + "}";
    }

    /** 构造器：非法组合在 {@link #build()} 处一次性失败，避免把坏配置带进点击路径。 */
    public static final class Builder {

        private boolean enabled = true;
        private long captureTimeoutMs = 300L;
        private int maxFrameLongSidePx = 640;
        private long maxFrameAgeNs = NO_FRAME_AGE_LIMIT_NS;
        private float minConfidence = 0f;
        private boolean reportUnknownTargetId = true;

        private int roiRadiusX = DEFAULT_ROI_RADIUS;
        private int roiRadiusY = DEFAULT_ROI_RADIUS;
        private float blueHueMinDegrees = BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES;
        private float blueHueMaxDegrees = BlueColorThresholds.DEFAULT_HUE_MAX_DEGREES;
        private float minSaturation = BlueColorThresholds.DEFAULT_MIN_SATURATION;
        private float minValue = BlueColorThresholds.DEFAULT_MIN_VALUE;
        private int minBluePixelCount = DEFAULT_MIN_BLUE_PIXEL_COUNT;
        private int maskDenoiseMinNeighbors = DEFAULT_MASK_DENOISE_MIN_NEIGHBORS;
        private boolean debugMaskEnabled = false;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder captureTimeoutMs(long captureTimeoutMs) {
            if (captureTimeoutMs < 0) {
                throw new IllegalArgumentException("captureTimeoutMs must be >= 0 but was " + captureTimeoutMs);
            }
            this.captureTimeoutMs = captureTimeoutMs;
            return this;
        }

        public Builder maxFrameLongSidePx(int maxFrameLongSidePx) {
            if (maxFrameLongSidePx < 0) {
                throw new IllegalArgumentException("maxFrameLongSidePx must be >= 0 but was " + maxFrameLongSidePx);
            }
            this.maxFrameLongSidePx = maxFrameLongSidePx;
            return this;
        }

        public Builder maxFrameAgeNs(long maxFrameAgeNs) {
            if (maxFrameAgeNs < 0) {
                throw new IllegalArgumentException("maxFrameAgeNs must be >= 0 but was " + maxFrameAgeNs);
            }
            this.maxFrameAgeNs = maxFrameAgeNs;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minConfidence(float minConfidence) {
            if (Float.isNaN(minConfidence) || minConfidence < 0f || minConfidence > 1f) {
                throw new IllegalArgumentException("minConfidence must be within [0,1] but was " + minConfidence);
            }
            this.minConfidence = minConfidence;
            return this;
        }

        public Builder reportUnknownTargetId(boolean reportUnknownTargetId) {
            this.reportUnknownTargetId = reportUnknownTargetId;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder roiRadiusX(int roiRadiusX) {
            if (roiRadiusX <= 0) {
                throw new IllegalArgumentException("roiRadiusX must be > 0 but was " + roiRadiusX);
            }
            this.roiRadiusX = roiRadiusX;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder roiRadiusY(int roiRadiusY) {
            if (roiRadiusY <= 0) {
                throw new IllegalArgumentException("roiRadiusY must be > 0 but was " + roiRadiusY);
            }
            this.roiRadiusY = roiRadiusY;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,360) 内或非有限值 */
        public Builder blueHueMinDegrees(float blueHueMinDegrees) {
            requireHue(blueHueMinDegrees, "blueHueMinDegrees");
            this.blueHueMinDegrees = blueHueMinDegrees;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,360) 内或非有限值 */
        public Builder blueHueMaxDegrees(float blueHueMaxDegrees) {
            requireHue(blueHueMaxDegrees, "blueHueMaxDegrees");
            this.blueHueMaxDegrees = blueHueMaxDegrees;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minSaturation(float minSaturation) {
            requireUnitRange(minSaturation, "minSaturation");
            this.minSaturation = minSaturation;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minValue(float minValue) {
            requireUnitRange(minValue, "minValue");
            this.minValue = minValue;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder minBluePixelCount(int minBluePixelCount) {
            if (minBluePixelCount < 0) {
                throw new IllegalArgumentException("minBluePixelCount must be >= 0 but was "
                        + minBluePixelCount);
            }
            this.minBluePixelCount = minBluePixelCount;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,8] 内 */
        public Builder maskDenoiseMinNeighbors(int maskDenoiseMinNeighbors) {
            if (maskDenoiseMinNeighbors < 0 || maskDenoiseMinNeighbors > 8) {
                throw new IllegalArgumentException("maskDenoiseMinNeighbors must be within [0,8] but was "
                        + maskDenoiseMinNeighbors);
            }
            this.maskDenoiseMinNeighbors = maskDenoiseMinNeighbors;
            return this;
        }

        public Builder debugMaskEnabled(boolean debugMaskEnabled) {
            this.debugMaskEnabled = debugMaskEnabled;
            return this;
        }

        /**
         * @throws IllegalArgumentException 任一视觉阈值非法（这里复用
         *         {@link BlueColorThresholds#of} 的校验，保证「配置里能过」等价于
         *         「检测器构造时能过」，不会出现"配置合法但分析器一构造就抛"）
         */
        public TargetSelectionConfig build() {
            BlueColorThresholds.of(blueHueMinDegrees, blueHueMaxDegrees, minSaturation, minValue);
            return new TargetSelectionConfig(this);
        }

        private static void requireHue(float value, String name) {
            if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value >= 360f) {
                throw new IllegalArgumentException(name + " must be within [0,360) but was " + value);
            }
        }

        private static void requireUnitRange(float value, String name) {
            if (Float.isNaN(value) || value < 0f || value > 1f) {
                throw new IllegalArgumentException(name + " must be within [0,1] but was " + value);
            }
        }
    }
}
