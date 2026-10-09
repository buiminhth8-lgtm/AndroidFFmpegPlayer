package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.FrameSize;

import org.junit.Test;

/**
 * 尺寸解算。这段算术直接决定分析图的清晰度与内存占用，而且算错时不会报错、
 * 只会让后续坐标映射整体偏移，所以逐条钉住。
 */
public class FrameSizeTest {

    @Test
    public void zeroOrNegativeViewSizeIsUnknownNotOneByOne() {
        assertNull(FrameSize.forCapture(0, 720, 640));
        assertNull(FrameSize.forCapture(1280, 0, 640));
        assertNull(FrameSize.forCapture(-1, -1, 640));
        assertNull(FrameSize.forCapture(0, 0, 0));
    }

    @Test
    public void noLimitKeepsOriginalSize() {
        FrameSize size = FrameSize.forCapture(1920, 1080, FrameCaptureRequest.NO_SCALE_LIMIT);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
    }

    @Test
    public void sizeWithinLimitIsKeptVerbatim() {
        FrameSize size = FrameSize.forCapture(640, 360, 640);
        assertEquals(640, size.width);
        assertEquals(360, size.height);
        assertTrue(size.matches(640, 360));
        assertFalse(size.matches(641, 360));
    }

    @Test
    public void landscapeScalesByLongSide() {
        FrameSize size = FrameSize.forCapture(1920, 1080, 640);
        assertEquals(640, size.width);
        assertEquals(360, size.height);
        assertEquals(640, Math.max(size.width, size.height));
    }

    @Test
    public void portraitScalesByHeight() {
        FrameSize size = FrameSize.forCapture(1080, 1920, 640);
        assertEquals(360, size.width);
        assertEquals(640, size.height);
        assertEquals(640, Math.max(size.width, size.height));
    }

    @Test
    public void extremeAspectRatioNeverCollapsesToZero() {
        // 100x1 缩到长边 10 时次边会被舍入到 0 —— 必须兜到 1，否则 createBitmap 直接抛异常
        FrameSize size = FrameSize.forCapture(100, 1, 10);
        assertEquals(10, size.width);
        assertEquals(1, size.height);
        assertTrue(size.height >= 1);
    }

    @Test
    public void longSideNeverExceedsLimit() {
        for (int longSide = 641; longSide <= 4000; longSide += 137) {
            FrameSize size = FrameSize.forCapture(longSide, longSide / 2, 640);
            assertTrue("longSide=" + longSide + " → " + size,
                    Math.max(size.width, size.height) <= 640);
            assertTrue(size.width >= 1 && size.height >= 1);
        }
    }

    @Test
    public void pixelCountIsExact() {
        assertEquals(1920L * 1080L, FrameSize.forCapture(1920, 1080, 0).pixelCount());
    }

    @Test
    public void sameInputsProduceEqualSizedObjects() {
        FrameSize a = FrameSize.forCapture(1920, 1080, 640);
        FrameSize b = FrameSize.forCapture(1920, 1080, 640);
        assertTrue(a.matches(b.width, b.height));
        assertEquals(a.toString(), b.toString());
        assertSame(FrameSize.class, a.getClass());
    }
}
