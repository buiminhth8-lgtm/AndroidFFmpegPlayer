package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameSourceType;

import org.junit.Test;

/** CapturedFrame 生命周期：释放幂等、释放后状态、尺寸回退、空帧。纯 JVM 测试（用 {@link FakeFrameImage}）。 */
public class CapturedFrameTest {

    @Test
    public void sizeFallsBackToImageWhenNotProvided() {
        FakeFrameImage image = new FakeFrameImage(1920, 1080);
        CapturedFrame frame = CapturedFrame.of(image, 0, 0, 100L, 140L, 5L, 6L);
        assertEquals(1920, frame.width);
        assertEquals(1080, frame.height);
        assertEquals(100L, frame.captureRequestNs);
        assertEquals(140L, frame.captureCompletedNs);
        assertEquals(5L, frame.ptsUs);
        assertEquals(6L, frame.frameTimestampNs);
        assertTrue(frame.hasPts());
        assertTrue(frame.hasFrameTimestamp());
        assertSame(image, frame.image());
        assertTrue(frame.hasImage());
    }

    @Test
    public void explicitSizeOverridesImage() {
        CapturedFrame frame = CapturedFrame.of(new FakeFrameImage(100, 50), 40, 20, 0L, 0L, -1L, -1L);
        assertEquals(40, frame.width);
        assertEquals(20, frame.height);
    }

    @Test
    public void releaseIsIdempotentAndDelegatesExactlyOnce() {
        FakeFrameImage image = new FakeFrameImage(10, 10);
        CapturedFrame frame = CapturedFrame.ofImage(image, 0L, 1L);

        frame.release();
        frame.release();
        frame.release();

        assertEquals(1, image.releaseCount);
        assertTrue(frame.isReleased());
        assertNull(frame.image());
        assertFalse(frame.hasImage());
        // 释放后尺寸/时间戳仍可读：历史记录不该随资源释放一起消失
        assertEquals(10, frame.width);
        assertEquals(10, frame.height);
    }

    @Test
    public void emptyFrameHasNoImageAndIsSafeToRelease() {
        CapturedFrame frame = CapturedFrame.empty(7L, 9L);
        assertFalse(frame.hasImage());
        assertNull(frame.image());
        assertEquals(0, frame.width);
        assertEquals(0, frame.height);
        assertFalse(frame.hasPts());
        assertFalse(frame.hasFrameTimestamp());
        assertEquals(CapturedFrame.NO_PTS_US, frame.ptsUs);
        assertEquals(CapturedFrame.NO_TIMESTAMP_NS, frame.frameTimestampNs);

        frame.release();
        assertTrue(frame.isReleased());
    }

    @Test
    public void nullImageWithExplicitSizeKeepsSizeReported() {
        CapturedFrame frame = CapturedFrame.of(null, 64, 48, 1L, 2L, -1L, -1L);
        assertFalse(frame.hasImage());
        assertEquals(64, frame.width);
        assertEquals(48, frame.height);
    }

    // ------------------------------------------------- Slice 1：来源类型与耗时可观测

    @Test
    public void sourceTypeDefaultsToUnknownWhenNotStated() {
        CapturedFrame frame = CapturedFrame.ofImage(new FakeFrameImage(8, 8), 0L, 1L);
        assertEquals(FrameSourceType.UNKNOWN, frame.sourceType);
        assertEquals(FrameSourceType.UNKNOWN, CapturedFrame.empty(0L, 1L).sourceType);
    }

    @Test
    public void sourceTypeIsCarriedThroughAndNullBecomesUnknown() {
        CapturedFrame fromSurface = CapturedFrame.of(
                new FakeFrameImage(8, 8), 8, 8, 0L, 1L, -1L, -1L, FrameSourceType.SURFACE_VIEW);
        assertEquals(FrameSourceType.SURFACE_VIEW, fromSurface.sourceType);
        assertTrue(fromSurface.toString(), fromSurface.toString().contains("SURFACE_VIEW"));

        CapturedFrame explicitNull = CapturedFrame.of(
                new FakeFrameImage(8, 8), 8, 8, 0L, 1L, -1L, -1L, null);
        assertEquals(FrameSourceType.UNKNOWN, explicitNull.sourceType);

        CapturedFrame texture = CapturedFrame.ofImage(
                new FakeFrameImage(8, 8), 0L, 1L, FrameSourceType.TEXTURE_VIEW);
        assertEquals(FrameSourceType.TEXTURE_VIEW, texture.sourceType);
        assertEquals(8, texture.width);
        assertEquals(8, texture.height);
    }

    @Test
    public void captureDurationIsDerivedAndNeverNegative() {
        CapturedFrame frame = CapturedFrame.of(new FakeFrameImage(4, 4), 4, 4,
                1_000_000L, 13_000_000L, -1L, -1L);
        assertEquals(12L, frame.captureDurationMs());

        // 时钟异常（完成时刻早于发起时刻）时收敛为 0，不要出现负耗时污染统计
        CapturedFrame skewed = CapturedFrame.of(new FakeFrameImage(4, 4), 4, 4, 9L, 1L, -1L, -1L);
        assertEquals(0L, skewed.captureDurationMs());
    }
}
