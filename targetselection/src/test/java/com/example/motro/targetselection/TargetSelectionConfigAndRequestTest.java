package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.vision.BlueColorThresholds;

import org.junit.Test;

/** 配置与取帧请求：默认值契约、不可变性、非法值拦截。 */
public class TargetSelectionConfigAndRequestTest {

    @Test
    public void defaultsAreEnabledAndBounded() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        assertTrue(config.enabled);
        assertEquals(300L, config.captureTimeoutMs);
        assertEquals(640, config.maxFrameLongSidePx);
        assertEquals(TargetSelectionConfig.NO_FRAME_AGE_LIMIT_NS, config.maxFrameAgeNs);
        assertEquals(0f, config.minConfidence, 0.0001f);
        assertTrue(config.reportUnknownTargetId);
    }

    /** Slice 4 的视觉调参必须有确定默认值 —— 它们直接决定"什么算蓝"，不能留空。 */
    @Test
    public void visionDefaultsAreTunedAndExplicit() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        assertEquals(TargetSelectionConfig.DEFAULT_ROI_RADIUS, config.roiRadiusX);
        assertEquals(TargetSelectionConfig.DEFAULT_ROI_RADIUS, config.roiRadiusY);
        assertEquals(BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES, config.blueHueMinDegrees, 0.0001f);
        assertEquals(BlueColorThresholds.DEFAULT_HUE_MAX_DEGREES, config.blueHueMaxDegrees, 0.0001f);
        assertEquals(BlueColorThresholds.DEFAULT_MIN_SATURATION, config.minSaturation, 0.0001f);
        assertEquals(BlueColorThresholds.DEFAULT_MIN_VALUE, config.minValue, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BLUE_PIXEL_COUNT, config.minBluePixelCount);
        assertEquals(TargetSelectionConfig.DEFAULT_MASK_DENOISE_MIN_NEIGHBORS, config.maskDenoiseMinNeighbors);
        assertFalse("调试开关默认关闭，生产路径不留额外大数组", config.debugMaskEnabled);

        // 默认值本身必须能通过 BlueColorThresholds 的校验（配置与检测器共用一套校验）
        BlueColorThresholds.of(config.blueHueMinDegrees, config.blueHueMaxDegrees,
                config.minSaturation, config.minValue);
    }

    /**
     * Slice 5 的矩形恢复默认值同样要有确定取值 —— 它们决定"什么算一个目标框"。
     *
     * <p>这里额外钉住两条真实样本推出来的<b>取舍</b>，而不是把常量抄一遍：</p>
     * <ul>
     *   <li>{@code minBoxConfidence} 必须严格大于「两条平行线」的置信度 0.25
     *       （2 边达标 → 0.5 覆盖率 × 2/4），否则窗棂、文字下划线会稳定误检；</li>
     *   <li>{@code maxEdgeThicknessPx} 必须远小于常见蓝框边长，才能把"实心蓝色物体"
     *       与"蓝色细框"分开。</li>
     * </ul>
     */
    @Test
    public void targetBoxDefaultsAreTunedAndExplicit() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();

        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_WIDTH, config.minBoxWidth);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_HEIGHT, config.minBoxHeight);
        assertEquals(TargetSelectionConfig.NO_BOX_SIZE_LIMIT, config.maxBoxWidth);
        assertEquals(TargetSelectionConfig.NO_BOX_SIZE_LIMIT, config.maxBoxHeight);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_BOX_ASPECT_RATIO, config.maxBoxAspectRatio, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_EDGE_COVERAGE, config.minEdgeCoverage, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_PRESENT_EDGES, config.minPresentEdges);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_EDGE_THICKNESS_PX, config.maxEdgeThicknessPx);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_EDGE_SOLIDITY, config.minEdgeSolidity, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_CONFIDENCE, config.minBoxConfidence, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_TOUCH_TOLERANCE_PX, config.touchTolerancePx);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_CANDIDATE_COUNT, config.maxCandidateCount);

        assertTrue("两条平行线的置信度是 0.5×(2/4)=0.25，门槛必须高于它，否则稳定误检",
                config.minBoxConfidence > 0.25f);
        assertTrue("边线厚度上限必须远小于蓝框边长，才能区分'蓝色细框'与'蓝色实心物体'",
                config.maxEdgeThicknessPx < config.minBoxWidth);
        assertTrue("候选上限必须 >= 1，否则异常掩码会造出大量候选拖垮点击路径",
                config.maxCandidateCount >= 1);
    }

    /** Slice 5 的矩形恢复阈值非法必须在 <b>build()</b> 处失败。 */
    @Test
    public void invalidTargetBoxValuesAreRejectedAtBuildTime() {
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxWidth(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxWidth(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxHeight(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxHeight(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxWidth(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxHeight(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxAspectRatio(0.5f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxAspectRatio(Float.NaN));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeCoverage(0f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeCoverage(1.01f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeCoverage(Float.NaN));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minPresentEdges(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minPresentEdges(5));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxEdgeThicknessPx(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxEdgeThicknessPx(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeSolidity(0f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeSolidity(1.01f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minEdgeSolidity(Float.NaN));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxConfidence(-0.1f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxConfidence(1.5f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBoxConfidence(Float.NaN));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().touchTolerancePx(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxCandidateCount(0));
    }

    /**
     * 上/下限矛盾必须在 {@code build()} 兜住，而<b>不是</b>只在 setter 里查。
     *
     * <p>setter 是按调用顺序逐个校验的：先设 max=120 再设 min=200 时，两次调用各自都看不出矛盾，
     * 只有 build 时把两者放在一起才发现。若少了 build 这一步，配置对象就会带着
     * "min &gt; max" 进入检测器，结果是任何框都过不了尺寸过滤 —— 表现为"永远 MISS"，
     * 现场极难归因。</p>
     */
    @Test
    public void boxSizeLimitsAreCrossCheckedAtBuildTime() {
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxWidth(30).build());
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxBoxHeight(30).build());
        // 顺序反过来：先设合法 max，再把 min 抬到它之上
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder()
                .maxBoxWidth(120).minBoxWidth(200).build());
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder()
                .maxBoxHeight(120).minBoxHeight(200).build());
    }

    /** 最大尺寸的「不限」哨兵必须被接受，而且不与下限矛盾。 */
    @Test
    public void boxSizeLimitsAcceptTheUnlimitedSentinel() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .maxBoxWidth(TargetSelectionConfig.NO_BOX_SIZE_LIMIT)
                .maxBoxHeight(TargetSelectionConfig.NO_BOX_SIZE_LIMIT)
                .build();
        assertEquals(0, config.maxBoxWidth);
        assertEquals(0, config.maxBoxHeight);
    }

    @Test
    public void toBuilderProducesIndependentCopy() {
        TargetSelectionConfig original = TargetSelectionConfig.defaults();
        TargetSelectionConfig changed = original.toBuilder()
                .enabled(false)
                .captureTimeoutMs(1200L)
                .maxFrameLongSidePx(1280)
                .maxFrameAgeNs(50_000_000L)
                .minConfidence(0.6f)
                .reportUnknownTargetId(false)
                .roiRadiusX(150)
                .roiRadiusY(250)
                .blueHueMinDegrees(210f)
                .blueHueMaxDegrees(260f)
                .minSaturation(0.4f)
                .minValue(0.25f)
                .minBluePixelCount(40)
                .maskDenoiseMinNeighbors(2)
                .debugMaskEnabled(true)
                .minBoxWidth(64)
                .minBoxHeight(48)
                .maxBoxWidth(1200)
                .maxBoxHeight(900)
                .maxBoxAspectRatio(4f)
                .minEdgeCoverage(0.7f)
                .minPresentEdges(3)
                .maxEdgeThicknessPx(10)
                .minEdgeSolidity(0.65f)
                .minBoxConfidence(0.55f)
                .touchTolerancePx(24)
                .maxCandidateCount(8)
                .build();

        assertNotSame(original, changed);
        // 原对象不受影响 —— 这是“不可变”的实际含义
        assertTrue(original.enabled);
        assertEquals(300L, original.captureTimeoutMs);
        assertEquals(640, original.maxFrameLongSidePx);
        assertTrue(original.reportUnknownTargetId);
        assertEquals(TargetSelectionConfig.DEFAULT_ROI_RADIUS, original.roiRadiusX);
        assertEquals(BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES, original.blueHueMinDegrees, 0.0001f);
        assertFalse(original.debugMaskEnabled);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_WIDTH, original.minBoxWidth);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_HEIGHT, original.minBoxHeight);
        assertEquals(TargetSelectionConfig.NO_BOX_SIZE_LIMIT, original.maxBoxWidth);
        assertEquals(TargetSelectionConfig.NO_BOX_SIZE_LIMIT, original.maxBoxHeight);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_BOX_ASPECT_RATIO, original.maxBoxAspectRatio, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_EDGE_COVERAGE, original.minEdgeCoverage, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_PRESENT_EDGES, original.minPresentEdges);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_EDGE_THICKNESS_PX, original.maxEdgeThicknessPx);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_EDGE_SOLIDITY, original.minEdgeSolidity, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_MIN_BOX_CONFIDENCE, original.minBoxConfidence, 0.0001f);
        assertEquals(TargetSelectionConfig.DEFAULT_TOUCH_TOLERANCE_PX, original.touchTolerancePx);
        assertEquals(TargetSelectionConfig.DEFAULT_MAX_CANDIDATE_COUNT, original.maxCandidateCount);

        assertFalse(changed.enabled);
        assertEquals(1200L, changed.captureTimeoutMs);
        assertEquals(1280, changed.maxFrameLongSidePx);
        assertEquals(50_000_000L, changed.maxFrameAgeNs);
        assertEquals(0.6f, changed.minConfidence, 0.0001f);
        assertFalse(changed.reportUnknownTargetId);
        assertEquals(150, changed.roiRadiusX);
        assertEquals(250, changed.roiRadiusY);
        assertEquals(210f, changed.blueHueMinDegrees, 0.0001f);
        assertEquals(260f, changed.blueHueMaxDegrees, 0.0001f);
        assertEquals(0.4f, changed.minSaturation, 0.0001f);
        assertEquals(0.25f, changed.minValue, 0.0001f);
        assertEquals(40, changed.minBluePixelCount);
        assertEquals(2, changed.maskDenoiseMinNeighbors);
        assertTrue(changed.debugMaskEnabled);
        assertEquals(64, changed.minBoxWidth);
        assertEquals(48, changed.minBoxHeight);
        assertEquals(1200, changed.maxBoxWidth);
        assertEquals(900, changed.maxBoxHeight);
        assertEquals(4f, changed.maxBoxAspectRatio, 0.0001f);
        assertEquals(0.7f, changed.minEdgeCoverage, 0.0001f);
        assertEquals(3, changed.minPresentEdges);
        assertEquals(10, changed.maxEdgeThicknessPx);
        assertEquals(0.65f, changed.minEdgeSolidity, 0.0001f);
        assertEquals(0.55f, changed.minBoxConfidence, 0.0001f);
        assertEquals(24, changed.touchTolerancePx);
        assertEquals(8, changed.maxCandidateCount);
    }

    @Test
    public void invalidConfigValuesAreRejectedAtBuildTime() {
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().captureTimeoutMs(-1L));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxFrameLongSidePx(-8));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maxFrameAgeNs(-1L));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minConfidence(1.5f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minConfidence(-0.1f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minConfidence(Float.NaN));
    }

    /** 视觉参数非法必须在 <b>build()</b> 处失败，而不是等到点击时分析器才抛。 */
    @Test
    public void invalidVisionValuesAreRejectedAtBuildTime() {
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().roiRadiusX(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().roiRadiusX(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().roiRadiusY(0));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().blueHueMinDegrees(-1f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().blueHueMinDegrees(360f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().blueHueMaxDegrees(Float.NaN));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minSaturation(1.5f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minValue(-0.01f));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().minBluePixelCount(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maskDenoiseMinNeighbors(-1));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder().maskDenoiseMinNeighbors(9));
        assertRejected(() -> TargetSelectionConfig.defaults().toBuilder()
                .blueHueMinDegrees(200f).blueHueMaxDegrees(400f).build());
    }

    @Test
    public void zeroMeansUnlimitedForOptionalKnobs() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .maxFrameLongSidePx(TargetSelectionConfig.NO_SCALE_LIMIT)
                .maxFrameAgeNs(TargetSelectionConfig.NO_FRAME_AGE_LIMIT_NS)
                .build();
        assertEquals(0, config.maxFrameLongSidePx);
        assertEquals(0L, config.maxFrameAgeNs);
    }

    @Test
    public void captureRequestFlagsFollowSentinels() {
        FrameCaptureRequest plain = FrameCaptureRequest.latestFrame(200L);
        assertEquals(FrameCaptureRequest.NO_TARGET_PTS_US, plain.targetPtsUs);
        assertEquals(200L, plain.timeoutMs);
        assertFalse(plain.hasTargetPts());
        assertFalse(plain.isScaleLimited());
        assertFalse(plain.isFrameAgeLimited());

        FrameCaptureRequest precise = new FrameCaptureRequest(1_500_000L, 500L, 960, 30_000_000L);
        assertTrue(precise.hasTargetPts());
        assertTrue(precise.isScaleLimited());
        assertTrue(precise.isFrameAgeLimited());
    }

    @Test
    public void invalidCaptureRequestValuesAreRejected() {
        assertRejected(() -> new FrameCaptureRequest(0L, -1L, 0, 0L));
        assertRejected(() -> new FrameCaptureRequest(0L, 10L, -1, 0L));
        assertRejected(() -> new FrameCaptureRequest(0L, 10L, 0, -1L));
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void assertRejected(ThrowingRunnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：非法参数在构造处失败，而不是带进点击路径
        }
    }
}
