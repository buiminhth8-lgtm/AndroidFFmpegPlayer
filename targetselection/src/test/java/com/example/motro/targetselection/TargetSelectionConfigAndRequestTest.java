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
