package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.FrameCaptureStatus;

import org.junit.Test;

/**
 * 状态语义契约。这些断言的价值在于：把“哪些情况该重试、哪些该上报”固化成可执行定义，
 * 否则上层很容易把 {@code NO_FRAME}（还没起播）当成故障弹错误框。
 */
public class FrameCaptureStatusTest {

    @Test
    public void onlySuccessIsSuccess() {
        for (FrameCaptureStatus status : FrameCaptureStatus.values()) {
            assertEquals("status=" + status, status == FrameCaptureStatus.SUCCESS, status.isSuccess());
        }
    }

    @Test
    public void everyNonSuccessIsFailure() {
        for (FrameCaptureStatus status : FrameCaptureStatus.values()) {
            assertEquals("status=" + status, status != FrameCaptureStatus.SUCCESS, status.isFailure());
        }
    }

    @Test
    public void retryableCoversTimingProblemsOnly() {
        assertTrue(FrameCaptureStatus.NO_FRAME.isRetryable());
        assertTrue(FrameCaptureStatus.SOURCE_NOT_READY.isRetryable());
        assertTrue(FrameCaptureStatus.TIMEOUT.isRetryable());

        // 这些重试无用：要么调用方式不对，要么到了生命周期终点，要么是真实故障
        assertFalse(FrameCaptureStatus.SUCCESS.isRetryable());
        assertFalse(FrameCaptureStatus.INVALID_SIZE.isRetryable());
        assertFalse(FrameCaptureStatus.UNSUPPORTED.isRetryable());
        assertFalse(FrameCaptureStatus.CANCELLED.isRetryable());
        assertFalse(FrameCaptureStatus.CAPTURE_FAILED.isRetryable());
    }

    /** 平台错误码不得出现在枚举命名里 —— 这是“不被某一种实现绑死”的可见证据。 */
    @Test
    public void statusNamesArePlatformAgnostic() {
        for (FrameCaptureStatus status : FrameCaptureStatus.values()) {
            String name = status.name().toUpperCase(java.util.Locale.ROOT);
            assertFalse(name, name.contains("PIXELCOPY"));
            assertFalse(name, name.contains("SURFACE"));
            assertFalse(name, name.contains("MEDIACODEC"));
        }
    }

    @Test
    public void expectedStatusesExist() {
        // 规格要求的最小集合，少一个都说明接口被削了
        FrameCaptureStatus[] required = {
                FrameCaptureStatus.SUCCESS,
                FrameCaptureStatus.SOURCE_NOT_READY,
                FrameCaptureStatus.INVALID_SIZE,
                FrameCaptureStatus.CAPTURE_FAILED,
                FrameCaptureStatus.UNSUPPORTED,
                FrameCaptureStatus.CANCELLED,
        };
        assertEquals(6, required.length);
        for (FrameCaptureStatus status : required) {
            assertTrue(status.name(), status != null);
        }
        // 额外保留的两个：NO_FRAME 承接 Slice 0 语义，TIMEOUT 让上层能调超时参数
        assertTrue(FrameCaptureStatus.NO_FRAME.isFailure());
        assertTrue(FrameCaptureStatus.TIMEOUT.isFailure());
    }
}
