package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureResult;
import com.example.motro.targetselection.frame.FrameCaptureStatus;

import org.junit.Test;

/**
 * 结果对象的两件事：不变量（成功必然带图）与所有权（谁负责释放）。
 * 后者出问题的表现是内存缓慢上涨，属于最难事后定位的一类缺陷，所以每个分支都测。
 */
public class FrameCaptureResultTest {

    private static CapturedFrame frameWithImage(FakeFrameImage image) {
        return CapturedFrame.ofImage(image, 1L, 2L);
    }

    // ---------------------------------------------------------------- 不变量

    @Test
    public void successRequiresAFrameWithImage() {
        assertIllegalArgument(() -> FrameCaptureResult.success(null));
        assertIllegalArgument(() -> FrameCaptureResult.success(
                CapturedFrame.empty(0L, 0L)));
    }

    @Test
    public void failureRejectsNullAndSuccessStatus() {
        assertIllegalArgument(() -> FrameCaptureResult.failure(null, "reason"));
        assertIllegalArgument(() -> FrameCaptureResult.failure(FrameCaptureStatus.SUCCESS, "reason"));
    }

    @Test
    public void successNeverCarriesFailureStatusAndViceVersa() {
        FrameCaptureResult ok = FrameCaptureResult.success(frameWithImage(new FakeFrameImage(4, 4)));
        assertEquals(FrameCaptureStatus.SUCCESS, ok.status);
        assertTrue(ok.isSuccess());
        assertTrue(ok.hasFrame());

        FrameCaptureResult bad = FrameCaptureResult.captureFailed("boom", 5);
        assertFalse(bad.isSuccess());
        assertFalse(bad.hasFrame());
        assertNull(bad.frame());
        assertNull(bad.image());
    }

    // ---------------------------------------------------------------- 工厂语义

    @Test
    public void factoriesCarryStatusRawCodeAndReason() {
        assertResult(FrameCaptureResult.noFrame("no frame"), FrameCaptureStatus.NO_FRAME,
                FrameCaptureResult.NO_RAW_CODE, "no frame");
        assertResult(FrameCaptureResult.sourceNotReady("not ready"), FrameCaptureStatus.SOURCE_NOT_READY,
                FrameCaptureResult.NO_RAW_CODE, "not ready");
        assertResult(FrameCaptureResult.invalidSize("0x0"), FrameCaptureStatus.INVALID_SIZE,
                FrameCaptureResult.NO_RAW_CODE, "0x0");
        assertResult(FrameCaptureResult.unsupported("main thread"), FrameCaptureStatus.UNSUPPORTED,
                FrameCaptureResult.NO_RAW_CODE, "main thread");
        assertResult(FrameCaptureResult.timeout("too slow", 2), FrameCaptureStatus.TIMEOUT, 2, "too slow");
        assertResult(FrameCaptureResult.cancelled("cancelled"), FrameCaptureStatus.CANCELLED,
                FrameCaptureResult.NO_RAW_CODE, "cancelled");
        assertResult(FrameCaptureResult.captureFailed("boom"), FrameCaptureStatus.CAPTURE_FAILED,
                FrameCaptureResult.NO_RAW_CODE, "boom");
        assertResult(FrameCaptureResult.captureFailed("boom", 9), FrameCaptureStatus.CAPTURE_FAILED,
                9, "boom");
        assertResult(FrameCaptureResult.failure(FrameCaptureStatus.NO_FRAME, "x", 3),
                FrameCaptureStatus.NO_FRAME, 3, "x");
    }

    @Test
    public void successCarriesPlatformRawCodeWhenGiven() {
        FrameCaptureResult ok = FrameCaptureResult.success(
                frameWithImage(new FakeFrameImage(2, 2)), 0);
        assertEquals(0, ok.rawCode);
    }

    // ---------------------------------------------------------------- 所有权

    @Test
    public void releaseDelegatesExactlyOnceEvenIfCalledRepeatedly() {
        FakeFrameImage image = new FakeFrameImage(16, 9);
        FrameCaptureResult result = FrameCaptureResult.success(frameWithImage(image));

        result.release();
        result.release();
        result.release();

        assertEquals(1, image.releaseCount);
        assertFalse(result.hasFrame());
        assertNull(result.frame());
    }

    @Test
    public void takeFrameTransfersOwnershipOutOfTheResult() {
        FakeFrameImage image = new FakeFrameImage(16, 9);
        FrameCaptureResult result = FrameCaptureResult.success(frameWithImage(image));

        CapturedFrame taken = result.takeFrame();
        assertSame(image, taken.image());
        assertFalse("取走后结果不再持有帧", result.hasFrame());
        assertNull("重复取走应返回 null", result.takeFrame());

        // 取走后对结果调用 release 不应误伤已移交的帧
        result.release();
        assertEquals("帧已移交，结果不得代它释放", 0, image.releaseCount);

        // 真正的释放责任在取走的一方
        taken.release();
        assertEquals(1, image.releaseCount);
    }

    @Test
    public void releasingFailureResultIsANoOpAndDoesNotThrow() {
        FrameCaptureResult result = FrameCaptureResult.timeout("slow", 2);
        result.release();
        result.release();
        assertFalse(result.hasFrame());
        assertNull(result.frame());
    }

    @Test
    public void imageAccessorFollowsOwnership() {
        FakeFrameImage image = new FakeFrameImage(8, 8);
        FrameCaptureResult result = FrameCaptureResult.success(frameWithImage(image));
        assertSame(image, result.image());
        CapturedFrame taken = result.takeFrame();
        assertNull("取走后 image() 也应为 null", result.image());
        taken.release();
    }

    @Test
    public void toStringMentionsStatusAndFramePresence() {
        FrameCaptureResult ok = FrameCaptureResult.success(frameWithImage(new FakeFrameImage(1, 1)));
        assertTrue(ok.toString(), ok.toString().contains("SUCCESS"));
        assertTrue(ok.toString(), ok.toString().contains("hasFrame=true"));

        FrameCaptureResult bad = FrameCaptureResult.sourceNotReady("nope");
        assertTrue(bad.toString(), bad.toString().contains("SOURCE_NOT_READY"));
        assertTrue(bad.toString(), bad.toString().contains("nope"));
    }

    // ---------------------------------------------------------------- utils

    private static void assertResult(FrameCaptureResult result, FrameCaptureStatus status,
                                     int rawCode, String reason) {
        assertEquals(status, result.status);
        assertEquals(rawCode, result.rawCode);
        assertEquals(reason, result.debugReason);
        assertFalse(result.hasFrame());
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：不变量在构造处强制，而不是留到使用处才发现
        }
    }
}
