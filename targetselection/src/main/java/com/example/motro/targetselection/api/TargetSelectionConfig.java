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

    // ---------------------------------------------------------------- 目标框候选恢复（Slice 5）

    /** {@link #maxBoxWidth}/{@link #maxBoxHeight} 的取值：不设上限（交给长宽比与边覆盖率约束）。 */
    public static final int NO_BOX_SIZE_LIMIT = 0;

    /**
     * {@link #minBoxWidth} 的默认值（<b>视频</b>像素）。
     *
     * <p>取 40 的理由有两层：一是它必须小于真实目标框（无人机画面里目标框通常上百像素），
     * 二是它和 {@link #minBoxHeight} 一起承担<b>排除 {@code id:xxxx} 文字</b>的职责 ——
     * 推流端画在框边的编号文字只有几十像素高，只要最小边长大于字号，
     * 「一行文字」就永远凑不出合法矩形（没有足够长的垂直边，也没有第二组水平边）。</p>
     */
    public static final int DEFAULT_MIN_BOX_WIDTH = 40;

    /**
     * {@link #minBoxHeight} 的默认值（<b>视频</b>像素）。
     *
     * <p>这是排除文字块的<b>主要</b>判据：编号文字的字高一般 &lt; 30px，
     * 而框高通常远大于它。注意它按「视频像素」计，因此与分辨率无关；
     * 换更大/更小的推流分辨率时不需要改动。</p>
     */
    public static final int DEFAULT_MIN_BOX_HEIGHT = 40;

    /**
     * {@link #maxBoxAspectRatio} 的默认值：长边/短边不超过 5。
     *
     * <p>目标框（尤其是人物/车辆）接近方形到 3:1；出现 10:1 的"矩形"几乎一定是
     * 两条平行线被误配成框（例如上下两条无关的横线，或文字行被当成一条边）。</p>
     */
    public static final float DEFAULT_MAX_BOX_ASPECT_RATIO = 5f;

    /**
     * {@link #minEdgeCoverage} 的默认值：一条边至少 50% 的像素是蓝的才算"这条边存在"。
     *
     * <p>取 0.5 而不是 0.9 是<b>刻意的</b>：推流端细蓝线经 HEVC + 4:2:0 之后会断裂、
     * 掉色，要求 90% 覆盖等于要求"完整的闭合轮廓"，那正是本 Slice 明确不要的严格条件。
     * 一半覆盖既能容忍断裂，又足以把"偶发几个蓝点"排除在"边"之外。</p>
     */
    public static final float DEFAULT_MIN_EDGE_COVERAGE = 0.5f;

    /** {@link #minPresentEdges} 的默认值：至少两条边达标（允许另外两条缺失或断裂）。 */
    public static final int DEFAULT_MIN_PRESENT_EDGES = 2;

    /**
     * {@link #maxEdgeThicknessPx} 的默认值（<b>视频</b>像素）：一条边线最多 16px 厚。
     *
     * <p>这是区分「细线框」与「一整块蓝色区域」的关键判据，也是本 Slice 适配
     * 推流端叠加层的核心：目标框是几像素宽的<b>空心</b>细线，而画面里的蓝色物体
     * （蓝色车身、蓝色屏幕、蓝色天空）是一整块<b>实心</b>区域。实心区域在投影上表现为
     * "连续很多行都有很高的覆盖率"，于是它形成的"边带"会厚达几十上百像素 ——
     * 直接被这条上限挡掉。</p>
     */
    public static final int DEFAULT_MAX_EDGE_THICKNESS_PX = 16;

    /**
     * {@link #minEdgeSolidity} 的默认值：一条边带的外接盒里至少 50% 是蓝的，才算一段"线"。
     *
     * <p>这条判据专门用来切开两类<b>看着一样细</b>的东西：真线，与"借来像素凑数的假线"。
     * 真实框线的实心度≈1；而贴在框沿上的 {@code id:xxxx} 文字，其每一列会因为同时穿过
     * 框的上、下边线而把像素数补到阈值以上，并集跨度又被拉到整框高度 —— 于是一列文字
     * 冒充成一条贯穿全高的竖边（实测实心度只有 0.09）。</p>
     *
     * <p>为什么厚度上限挡不住：那种列只有 4~14px 宽，比真正的细线还细。
     * 而 0.5 这个值对真实情况很宽松 —— 一条被 HEVC 吃掉近一半像素的框线仍能通过
     * （实测断裂样本的上边线实心度 0.87）。</p>
     */
    public static final float DEFAULT_MIN_EDGE_SOLIDITY = 0.5f;

    /**
     * {@link #minBoxConfidence} 的默认值。
     *
     * <p>置信度 = 边覆盖率均值 × (达标边数/4)，所以 0.3 大致等价于
     * "四条边里<b>至少三条基本达标</b>"。为什么不取 0.25（正好两条边）：
     * 两条平行线（比如两段文字下划线、两条无关横线）配出的"框"覆盖率均值是 0.5、
     * 达标边数 2，刚好拿满 0.25 —— 那是个稳定的误检源。抬到 0.3 以后，
     * 「三边齐全、一边缺失/断裂」仍然通过（0.75 × 3/4 = 0.5625），
     * 而"只有两条边"的形状被挡住。</p>
     */
    public static final float DEFAULT_MIN_BOX_CONFIDENCE = 0.3f;

    /**
     * {@link #touchTolerancePx} 的默认值（<b>视频</b>像素）。
     *
     * <p>手指点击有 ±8~10px 的抖动，而目标框往往只有几十像素高 —— 不设容差
     * 会让"明明点在框上"变成 MISS。12px 大致是半个指头的误差量级。
     * 注意容差只影响<b>是否命中</b>，返回给上层的永远是<b>原始</b>框，
     * 否则下游会拿到一个比实际大一圈的坐标。</p>
     */
    public static final int DEFAULT_TOUCH_TOLERANCE_PX = 12;

    /**
     * {@link #maxCandidateCount} 的默认值：单次检测最多保留 16 个候选。
     *
     * <p>这是对<b>异常掩码</b>的兜底：噪点密集时投影会切出很多"边带"，
     * 两两配对是组合数增长。限制候选数既保证单次耗时可控，
     * 也避免把一堆垃圾候选丢给选择逻辑。</p>
     */
    public static final int DEFAULT_MAX_CANDIDATE_COUNT = 16;

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

    // ---------------------------------------------------------------- 目标框候选恢复（Slice 5）

    /** 候选框最小宽度（<b>视频</b>像素，&gt; 0）。见 {@link #DEFAULT_MIN_BOX_WIDTH}。 */
    public final int minBoxWidth;

    /** 候选框最小高度（<b>视频</b>像素，&gt; 0）。见 {@link #DEFAULT_MIN_BOX_HEIGHT}。 */
    public final int minBoxHeight;

    /** 候选框最大宽度（视频像素，{@link #NO_BOX_SIZE_LIMIT} = 不限）。 */
    public final int maxBoxWidth;

    /** 候选框最大高度（视频像素，{@link #NO_BOX_SIZE_LIMIT} = 不限）。 */
    public final int maxBoxHeight;

    /** 长边/短边上限（&ge; 1）。 */
    public final float maxBoxAspectRatio;

    /** 一条边至少多大比例的像素是蓝的，才算「这条边存在」（(0,1]）。 */
    public final float minEdgeCoverage;

    /** 达标边数下限（[1,4]）。 */
    public final int minPresentEdges;

    /** 边线最大厚度（视频像素，&ge; 1）：超过它就不是细线框，而是实心色块。 */
    public final int maxEdgeThicknessPx;

    /**
     * 一条边带的<b>实心度</b>下限（(0,1]）：外接盒里蓝像素占比低于它就不算"线"。
     * 用来挡掉"借别的边的像素凑出来的假边"（典型是贴在框沿上的 id 文字列）。
     */
    public final float minEdgeSolidity;

    /** 候选框最低置信度 [0,1]；低于该值的候选被丢弃。 */
    public final float minBoxConfidence;

    /** 触摸命中容差（<b>视频</b>像素，&ge; 0）：判定时把框向外扩这么多，但返回的仍是原始框。 */
    public final int touchTolerancePx;

    /** 单次检测保留的候选上限（&ge; 1），防异常掩码产生组合爆炸。 */
    public final int maxCandidateCount;

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
        this.minBoxWidth = builder.minBoxWidth;
        this.minBoxHeight = builder.minBoxHeight;
        this.maxBoxWidth = builder.maxBoxWidth;
        this.maxBoxHeight = builder.maxBoxHeight;
        this.maxBoxAspectRatio = builder.maxBoxAspectRatio;
        this.minEdgeCoverage = builder.minEdgeCoverage;
        this.minPresentEdges = builder.minPresentEdges;
        this.maxEdgeThicknessPx = builder.maxEdgeThicknessPx;
        this.minEdgeSolidity = builder.minEdgeSolidity;
        this.minBoxConfidence = builder.minBoxConfidence;
        this.touchTolerancePx = builder.touchTolerancePx;
        this.maxCandidateCount = builder.maxCandidateCount;
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
                .debugMaskEnabled(debugMaskEnabled)
                .minBoxWidth(minBoxWidth)
                .minBoxHeight(minBoxHeight)
                .maxBoxWidth(maxBoxWidth)
                .maxBoxHeight(maxBoxHeight)
                .maxBoxAspectRatio(maxBoxAspectRatio)
                .minEdgeCoverage(minEdgeCoverage)
                .minPresentEdges(minPresentEdges)
                .maxEdgeThicknessPx(maxEdgeThicknessPx)
                .minEdgeSolidity(minEdgeSolidity)
                .minBoxConfidence(minBoxConfidence)
                .touchTolerancePx(touchTolerancePx)
                .maxCandidateCount(maxCandidateCount);
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
                + ", debugMaskEnabled=" + debugMaskEnabled
                + ", minBoxWidth=" + minBoxWidth
                + ", minBoxHeight=" + minBoxHeight
                + ", maxBoxWidth=" + maxBoxWidth
                + ", maxBoxHeight=" + maxBoxHeight
                + ", maxBoxAspectRatio=" + maxBoxAspectRatio
                + ", minEdgeCoverage=" + minEdgeCoverage
                + ", minPresentEdges=" + minPresentEdges
                + ", maxEdgeThicknessPx=" + maxEdgeThicknessPx
                + ", minEdgeSolidity=" + minEdgeSolidity
                + ", minBoxConfidence=" + minBoxConfidence
                + ", touchTolerancePx=" + touchTolerancePx
                + ", maxCandidateCount=" + maxCandidateCount + "}";
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

        private int minBoxWidth = DEFAULT_MIN_BOX_WIDTH;
        private int minBoxHeight = DEFAULT_MIN_BOX_HEIGHT;
        private int maxBoxWidth = NO_BOX_SIZE_LIMIT;
        private int maxBoxHeight = NO_BOX_SIZE_LIMIT;
        private float maxBoxAspectRatio = DEFAULT_MAX_BOX_ASPECT_RATIO;
        private float minEdgeCoverage = DEFAULT_MIN_EDGE_COVERAGE;
        private int minPresentEdges = DEFAULT_MIN_PRESENT_EDGES;
        private int maxEdgeThicknessPx = DEFAULT_MAX_EDGE_THICKNESS_PX;
        private float minEdgeSolidity = DEFAULT_MIN_EDGE_SOLIDITY;
        private float minBoxConfidence = DEFAULT_MIN_BOX_CONFIDENCE;
        private int touchTolerancePx = DEFAULT_TOUCH_TOLERANCE_PX;
        private int maxCandidateCount = DEFAULT_MAX_CANDIDATE_COUNT;

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

        /** @throws IllegalArgumentException 不为正 */
        public Builder minBoxWidth(int minBoxWidth) {
            if (minBoxWidth <= 0) {
                throw new IllegalArgumentException("minBoxWidth must be > 0 but was " + minBoxWidth);
            }
            this.minBoxWidth = minBoxWidth;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder minBoxHeight(int minBoxHeight) {
            if (minBoxHeight <= 0) {
                throw new IllegalArgumentException("minBoxHeight must be > 0 but was " + minBoxHeight);
            }
            this.minBoxHeight = minBoxHeight;
            return this;
        }

        /**
         * @param maxBoxWidth 最大宽度（视频像素）；{@link #NO_BOX_SIZE_LIMIT} 表示不限
         * @throws IllegalArgumentException 为负，或小于 {@link #minBoxWidth}（且不为 0）
         */
        public Builder maxBoxWidth(int maxBoxWidth) {
            if (maxBoxWidth < 0) {
                throw new IllegalArgumentException("maxBoxWidth must be >= 0 but was " + maxBoxWidth);
            }
            if (maxBoxWidth != NO_BOX_SIZE_LIMIT && maxBoxWidth < minBoxWidth) {
                throw new IllegalArgumentException("maxBoxWidth must be >= minBoxWidth ("
                        + minBoxWidth + ") or " + NO_BOX_SIZE_LIMIT + " but was " + maxBoxWidth);
            }
            this.maxBoxWidth = maxBoxWidth;
            return this;
        }

        /**
         * @param maxBoxHeight 最大高度（视频像素）；{@link #NO_BOX_SIZE_LIMIT} 表示不限
         * @throws IllegalArgumentException 为负，或小于 {@link #minBoxHeight}（且不为 0）
         */
        public Builder maxBoxHeight(int maxBoxHeight) {
            if (maxBoxHeight < 0) {
                throw new IllegalArgumentException("maxBoxHeight must be >= 0 but was " + maxBoxHeight);
            }
            if (maxBoxHeight != NO_BOX_SIZE_LIMIT && maxBoxHeight < minBoxHeight) {
                throw new IllegalArgumentException("maxBoxHeight must be >= minBoxHeight ("
                        + minBoxHeight + ") or " + NO_BOX_SIZE_LIMIT + " but was " + maxBoxHeight);
            }
            this.maxBoxHeight = maxBoxHeight;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 或非有限值 */
        public Builder maxBoxAspectRatio(float maxBoxAspectRatio) {
            if (!Float.isFinite(maxBoxAspectRatio) || maxBoxAspectRatio < 1f) {
                throw new IllegalArgumentException("maxBoxAspectRatio must be >= 1 but was " + maxBoxAspectRatio);
            }
            this.maxBoxAspectRatio = maxBoxAspectRatio;
            return this;
        }

        /** @throws IllegalArgumentException 不在 (0,1] 内 */
        public Builder minEdgeCoverage(float minEdgeCoverage) {
            if (Float.isNaN(minEdgeCoverage) || minEdgeCoverage <= 0f || minEdgeCoverage > 1f) {
                throw new IllegalArgumentException("minEdgeCoverage must be within (0,1] but was "
                        + minEdgeCoverage);
            }
            this.minEdgeCoverage = minEdgeCoverage;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [1,4] 内 */
        public Builder minPresentEdges(int minPresentEdges) {
            if (minPresentEdges < 1 || minPresentEdges > 4) {
                throw new IllegalArgumentException("minPresentEdges must be within [1,4] but was "
                        + minPresentEdges);
            }
            this.minPresentEdges = minPresentEdges;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder maxEdgeThicknessPx(int maxEdgeThicknessPx) {
            if (maxEdgeThicknessPx <= 0) {
                throw new IllegalArgumentException("maxEdgeThicknessPx must be > 0 but was "
                        + maxEdgeThicknessPx);
            }
            this.maxEdgeThicknessPx = maxEdgeThicknessPx;
            return this;
        }

        /**
         * 边带实心度下限。
         *
         * @throws IllegalArgumentException 不在 (0,1] 内
         */
        public Builder minEdgeSolidity(float minEdgeSolidity) {
            if (Float.isNaN(minEdgeSolidity) || minEdgeSolidity <= 0f || minEdgeSolidity > 1f) {
                throw new IllegalArgumentException("minEdgeSolidity must be within (0,1] but was "
                        + minEdgeSolidity);
            }
            this.minEdgeSolidity = minEdgeSolidity;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minBoxConfidence(float minBoxConfidence) {
            requireUnitRange(minBoxConfidence, "minBoxConfidence");
            this.minBoxConfidence = minBoxConfidence;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder touchTolerancePx(int touchTolerancePx) {
            if (touchTolerancePx < 0) {
                throw new IllegalArgumentException("touchTolerancePx must be >= 0 but was "
                        + touchTolerancePx);
            }
            this.touchTolerancePx = touchTolerancePx;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 */
        public Builder maxCandidateCount(int maxCandidateCount) {
            if (maxCandidateCount < 1) {
                throw new IllegalArgumentException("maxCandidateCount must be >= 1 but was "
                        + maxCandidateCount);
            }
            this.maxCandidateCount = maxCandidateCount;
            return this;
        }

        /**
         * @throws IllegalArgumentException 任一视觉阈值非法（这里复用
         *         {@link BlueColorThresholds#of} 的校验，保证「配置里能过」等价于
         *         「检测器构造时能过」，不会出现"配置合法但分析器一构造就抛"）
         */
        public TargetSelectionConfig build() {
            BlueColorThresholds.of(blueHueMinDegrees, blueHueMaxDegrees, minSaturation, minValue);
            // 上限与下限的关系在这里再查一次：setter 是按调用顺序逐个校验的，
            // 若先设 max 后设 min，单次调用时无法发现矛盾，必须在这里兜住。
            requireSizeLimitsConsistent(maxBoxWidth, minBoxWidth, "maxBoxWidth", "minBoxWidth");
            requireSizeLimitsConsistent(maxBoxHeight, minBoxHeight, "maxBoxHeight", "minBoxHeight");
            return new TargetSelectionConfig(this);
        }

        private static void requireSizeLimitsConsistent(int max, int min, String maxName, String minName) {
            if (max != NO_BOX_SIZE_LIMIT && max < min) {
                throw new IllegalArgumentException(maxName + " must be >= " + minName + " ("
                        + min + ") or " + NO_BOX_SIZE_LIMIT + " but was " + max);
            }
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
