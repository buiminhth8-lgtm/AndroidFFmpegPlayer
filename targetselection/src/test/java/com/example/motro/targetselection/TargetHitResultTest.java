package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetHitResult;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.geometry.TargetBoundingBox;

import org.junit.Test;

/** TargetHitResult 基础行为：工厂方法、字段归一化、坐标哨兵值。 */
public class TargetHitResultTest {

    private static final float EPS = 0.0001f;

    @Test
    public void hitCarriesIdBoxAndCoordinates() {
        TargetBoundingBox box = TargetBoundingBox.of(10f, 20f, 30f, 40f);
        TargetHitResult result = TargetHitResult.hit("T7", box, 0.9f, 15f, 25f, 123_456L, 7L);

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals("T7", result.targetId);
        assertSame(box, result.boundingBox);
        assertEquals(15f, result.touchVideoX, EPS);
        assertEquals(25f, result.touchVideoY, EPS);
        assertEquals(0.9f, result.confidence, EPS);
        assertEquals(123_456L, result.framePtsUs);
        assertEquals(7L, result.processingMs);
        assertTrue(result.isHit());
        assertTrue(result.hasResolvedId());
        assertTrue(result.hasVideoCoordinates());
        assertTrue(result.hasFramePts());
    }

    @Test
    public void hitIdUnknownKeepsBoxButNeverInventsId() {
        TargetBoundingBox box = TargetBoundingBox.of(1f, 2f, 3f, 4f);
        TargetHitResult result = TargetHitResult.hitIdUnknown(box, 0.4f, 2f, 3f, 9L, 5L);

        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNull(result.targetId);
        assertSame(box, result.boundingBox);
        assertTrue(result.isHit());
        assertFalse(result.hasResolvedId());
    }

    @Test
    public void missKeepsCoordinatesButDropsTargetPayload() {
        TargetHitResult result = TargetHitResult.miss(4f, 5f, 100L, 3L);

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertNull(result.targetId);
        assertNull(result.boundingBox);
        assertEquals(0f, result.confidence, EPS);
        assertEquals(4f, result.touchVideoX, EPS);
        assertTrue(result.hasVideoCoordinates());
        assertFalse(result.isHit());
    }

    @Test
    public void unmappedStatusesUseNaNCoordinatesNotZero() {
        // 0 是合法坐标，未映射必须用 NaN 表达，否则上层会把左上角当成真实点击点
        TargetHitResult outside = TargetHitResult.outsideVideo(2L);
        assertFalse(outside.hasVideoCoordinates());
        assertTrue(Float.isNaN(outside.touchVideoX));
        assertTrue(Float.isNaN(outside.touchVideoY));
        assertFalse(outside.hasFramePts());
        assertEquals(TargetHitResult.NO_PTS_US, outside.framePtsUs);

        assertFalse(TargetHitResult.geometryUnavailable(1L, "no geometry").hasVideoCoordinates());
        assertFalse(TargetHitResult.captureFailed(1L, "timeout").hasVideoCoordinates());
        assertFalse(TargetHitResult.noFrame(1L, "no frame yet").hasVideoCoordinates());
        assertFalse(TargetHitResult.cancelled(null).hasVideoCoordinates());
    }

    @Test
    public void failureReasonsArePreserved() {
        assertEquals("timeout after 300ms", TargetHitResult.captureFailed(5L, "timeout after 300ms").reason);
        assertEquals("no geometry", TargetHitResult.geometryUnavailable(5L, "no geometry").reason);
        assertNull(TargetHitResult.hit("A", TargetBoundingBox.of(0f, 0f, 1f, 1f), 1f, 0f, 0f, 0L, 0L).reason);
    }

    @Test
    public void nonHitStatusesAreNormalizedEvenIfPayloadPassed() {
        // 防止实现方误传载荷，让非命中结果“看起来像命中”
        TargetHitResult result = TargetHitResult.of(
                TargetSelectionStatus.CAPTURE_FAILED,
                "should-be-dropped",
                TargetBoundingBox.of(0f, 0f, 10f, 10f),
                1f, 2f, 0.99f, 5L, 2L, "boom");

        assertNull(result.targetId);
        assertNull(result.boundingBox);
        assertEquals(0f, result.confidence, EPS);
        assertEquals(1f, result.touchVideoX, EPS);
    }

    @Test
    public void blankIdOnHitDegradesToHitIdUnknown() {
        // 不变量：status=HIT 必然带 id。传入空 id 的 HIT 必须降级，而不是留下矛盾结果
        TargetBoundingBox box = TargetBoundingBox.of(0f, 0f, 5f, 5f);
        TargetHitResult result = TargetHitResult.hit("", box, 1f, 0f, 0f, 0L, 0L);

        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNull(result.targetId);
        assertFalse(result.hasResolvedId());
        assertTrue(result.isHit());
        // 框是有效的，只有 id 读不出来 —— 框必须保留
        assertSame(box, result.boundingBox);
    }

    @Test
    public void nullIdOnHitAlsoDegrades() {
        TargetHitResult result = TargetHitResult.of(TargetSelectionStatus.HIT, null,
                TargetBoundingBox.of(0f, 0f, 5f, 5f), 1f, 1f, 0.8f, 3L, 1L, null);
        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNull(result.targetId);
        assertEquals(0.8f, result.confidence, EPS);
    }

    @Test
    public void confidenceAndProcessingTimeAreClamped() {
        TargetHitResult high = TargetHitResult.hit("A", TargetBoundingBox.of(0f, 0f, 1f, 1f), 7f, 0f, 0f, 0L, -3L);
        assertEquals(1f, high.confidence, EPS);
        assertEquals(0L, high.processingMs);

        TargetHitResult low = TargetHitResult.hit("A", TargetBoundingBox.of(0f, 0f, 1f, 1f), -2f, 0f, 0f, 0L, 0L);
        assertEquals(0f, low.confidence, EPS);

        TargetHitResult nan = TargetHitResult.hit("A", TargetBoundingBox.of(0f, 0f, 1f, 1f), Float.NaN, 0f, 0f, 0L, 0L);
        assertEquals(0f, nan.confidence, EPS);
    }

    @Test
    public void nullStatusIsRejected() {
        try {
            TargetHitResult.of(null, null, null, 0f, 0f, 0f, 0L, 0L, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("status"));
        }
    }
}
