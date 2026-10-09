package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TouchSelectionRequest;

import org.junit.Test;

/**
 * 触摸请求的时间语义测试。重点是「单调时钟」这件事：它一旦用错，
 * 坐标是对的、程序也不崩，只是帧对齐会系统性偏掉 —— 属于最难发现的一类问题，
 * 所以这里把时间换算钉成断言。
 */
public class TouchSelectionRequestTest {

    private static final float EPS = 0.001f;

    @Test
    public void explicitTimeIsKeptAndAgeIsComputed() {
        TouchSelectionRequest request = TouchSelectionRequest.down(10.5f, 20.25f, 1_000_000L);

        assertEquals(10.5f, request.viewX, EPS);
        assertEquals(20.25f, request.viewY, EPS);
        assertEquals(1_000_000L, request.eventTimeNs);
        assertTrue(request.hasEventTime());
        assertEquals(500L, request.ageNs(1_000_500L));
    }

    @Test
    public void downNowUsesTheSameMonotonicClockAsFrameCapture() {
        long before = System.nanoTime();
        TouchSelectionRequest request = TouchSelectionRequest.downNow(1f, 2f);
        long after = System.nanoTime();

        assertTrue("必须记录时刻，否则无法与帧对齐", request.hasEventTime());
        assertTrue("eventTimeNs 应落在两次 nanoTime 之间，实际=" + request.eventTimeNs,
                request.eventTimeNs >= before && request.eventTimeNs <= after);
    }

    @Test
    public void uptimeMillisIsConvertedIntoNanoTimeBase() {
        // 事件比现在早 8ms；now = uptime 1000ms / nanoTime 9s
        TouchSelectionRequest request = TouchSelectionRequest.downFromUptimeMillis(
                3f, 4f, 992L, 1000L, 9_000_000_000L);

        assertEquals(3f, request.viewX, EPS);
        assertEquals(4f, request.viewY, EPS);
        assertEquals(9_000_000_000L - 8_000_000L, request.eventTimeNs);
        // 换算后与 nanoTime 基准对齐：用同一个“现在”求年龄应正好是 8ms
        assertEquals(8_000_000L, request.ageNs(9_000_000_000L));
    }

    @Test
    public void uptimeConversionOfAZeroAgeEventMatchesNow() {
        TouchSelectionRequest request = TouchSelectionRequest.downFromUptimeMillis(
                0f, 0f, 12345L, 12345L, 7_777_000_000L);
        assertEquals(7_777_000_000L, request.eventTimeNs);
        assertEquals(0L, request.ageNs(7_777_000_000L));
    }

    @Test
    public void futureEventTimeIsRejectedInsteadOfProducingANegativeAge() {
        // 事件时刻晚于“现在”说明两个时钟用反了：宁可失败，也不要算出一个未来时间
        try {
            TouchSelectionRequest.downFromUptimeMillis(0f, 0f, 2000L, 1000L, 1L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void unknownTimeIsAllowedButMarkedAndAgeIsUnknown() {
        TouchSelectionRequest request = TouchSelectionRequest.down(1f, 1f, TouchSelectionRequest.NO_EVENT_TIME_NS);
        assertFalse(request.hasEventTime());
        assertEquals(TouchSelectionRequest.NO_EVENT_TIME_NS, request.ageNs(999L));
    }

    @Test
    public void negativeEventTimeOtherThanSentinelIsRejected() {
        try {
            TouchSelectionRequest.down(1f, 1f, -5L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue("失败信息应指向单调时钟，而不是让人去猜",
                    expected.getMessage().contains("monotonic"));
        }
    }

    @Test
    public void backwardsClockYieldsZeroAgeInsteadOfNegative() {
        assertEquals(0L, TouchSelectionRequest.down(0f, 0f, 5_000L).ageNs(1_000L));
    }

    @Test
    public void nonFiniteCoordinatesAreRejected() {
        assertRejected(Float.NaN, 0f);
        assertRejected(0f, Float.NaN);
        assertRejected(Float.POSITIVE_INFINITY, 0f);
        assertRejected(0f, Float.NEGATIVE_INFINITY);
    }

    private static void assertRejected(float viewX, float viewY) {
        try {
            TouchSelectionRequest.down(viewX, viewY, 1L);
            fail("expected IllegalArgumentException for (" + viewX + "," + viewY + ")");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
