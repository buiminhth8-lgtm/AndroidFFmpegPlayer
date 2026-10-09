package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.FrameCaptureRequest;

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
                .build();

        assertNotSame(original, changed);
        // 原对象不受影响 —— 这是“不可变”的实际含义
        assertTrue(original.enabled);
        assertEquals(300L, original.captureTimeoutMs);
        assertEquals(640, original.maxFrameLongSidePx);
        assertTrue(original.reportUnknownTargetId);

        assertFalse(changed.enabled);
        assertEquals(1200L, changed.captureTimeoutMs);
        assertEquals(1280, changed.maxFrameLongSidePx);
        assertEquals(50_000_000L, changed.maxFrameAgeNs);
        assertEquals(0.6f, changed.minConfidence, 0.0001f);
        assertFalse(changed.reportUnknownTargetId);
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
