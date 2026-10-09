package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.example.motro.targetselection.frame.FrameCaptureStatus;
import com.example.motro.targetselection.frame.PixelCopyStatusMapper;

import org.junit.Test;

/**
 * 平台返回码 → 模块状态的映射表。
 *
 * <p>两条独立的验证：</p>
 * <ol>
 *   <li>映射结果符合业务语义（“没数据”该被当成时序问题而不是故障）；</li>
 *   <li>本类镜像的常量与平台真值一致 —— 这条断言存在的原因很直白：
 *       常量是手抄的，平台若改值、或我抄错，映射会静默失效，
 *       而静默失效的后果是“Surface 有数据却永远报没帧”。</li>
 * </ol>
 */
public class PixelCopyStatusMapperTest {

    @Test
    public void successMapsToSuccess() {
        assertEquals(FrameCaptureStatus.SUCCESS, PixelCopyStatusMapper.map(PixelCopyStatusMapper.SUCCESS));
    }

    /** Surface 有效但还没出图 —— 正常时序，不是故障，否则上层会弹错误框。 */
    @Test
    public void sourceNoDataIsNoFrameNotFailure() {
        assertEquals(FrameCaptureStatus.NO_FRAME,
                PixelCopyStatusMapper.map(PixelCopyStatusMapper.ERROR_SOURCE_NO_DATA));
    }

    @Test
    public void invalidSourceIsSourceNotReady() {
        assertEquals(FrameCaptureStatus.SOURCE_NOT_READY,
                PixelCopyStatusMapper.map(PixelCopyStatusMapper.ERROR_SOURCE_INVALID));
    }

    @Test
    public void timeoutKeepsItsOwnStatus() {
        assertEquals(FrameCaptureStatus.TIMEOUT,
                PixelCopyStatusMapper.map(PixelCopyStatusMapper.ERROR_TIMEOUT));
    }

    /** 目标位图非法是实现方的编程错误，必须暴露，不能被当成“没帧”让上层无限重试。 */
    @Test
    public void invalidDestinationAndUnknownAreRealFailures() {
        assertEquals(FrameCaptureStatus.CAPTURE_FAILED,
                PixelCopyStatusMapper.map(PixelCopyStatusMapper.ERROR_DESTINATION_INVALID));
        assertEquals(FrameCaptureStatus.CAPTURE_FAILED,
                PixelCopyStatusMapper.map(PixelCopyStatusMapper.ERROR_UNKNOWN));
        assertEquals(FrameCaptureStatus.CAPTURE_FAILED, PixelCopyStatusMapper.map(-999));
        assertEquals(FrameCaptureStatus.CAPTURE_FAILED, PixelCopyStatusMapper.map(Integer.MAX_VALUE));
    }

    @Test
    public void describeIsHumanReadableAndCoversUnknownCodes() {
        assertEquals("SUCCESS", PixelCopyStatusMapper.describe(PixelCopyStatusMapper.SUCCESS));
        assertEquals("ERROR_SOURCE_NO_DATA",
                PixelCopyStatusMapper.describe(PixelCopyStatusMapper.ERROR_SOURCE_NO_DATA));
        assertNotEquals("", PixelCopyStatusMapper.describe(1234));
    }

    @Test
    public void mirrorConstantsMatchThePlatformValues() {
        assertEquals(android.view.PixelCopy.SUCCESS, PixelCopyStatusMapper.SUCCESS);
        assertEquals(android.view.PixelCopy.ERROR_UNKNOWN, PixelCopyStatusMapper.ERROR_UNKNOWN);
        assertEquals(android.view.PixelCopy.ERROR_TIMEOUT, PixelCopyStatusMapper.ERROR_TIMEOUT);
        assertEquals(android.view.PixelCopy.ERROR_SOURCE_NO_DATA,
                PixelCopyStatusMapper.ERROR_SOURCE_NO_DATA);
        assertEquals(android.view.PixelCopy.ERROR_SOURCE_INVALID,
                PixelCopyStatusMapper.ERROR_SOURCE_INVALID);
        assertEquals(android.view.PixelCopy.ERROR_DESTINATION_INVALID,
                PixelCopyStatusMapper.ERROR_DESTINATION_INVALID);
    }
}
