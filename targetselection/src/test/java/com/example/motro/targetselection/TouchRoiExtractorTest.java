package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.TouchRoi;
import com.example.motro.targetselection.vision.TouchRoiExtractor;
import com.example.motro.targetselection.vision.TouchRoiResult;
import com.example.motro.targetselection.vision.TouchRoiStatus;

import org.junit.Test;

/**
 * ROI 裁剪的边界契约 —— 本 Slice 最容易出崩溃的地方。
 *
 * <p>覆盖：中心裁剪、四个边缘、四角、ROI 不越界、非法帧/半径/坐标、
 * 点在视频之外、载体不支持像素回读、以及「裁出来的像素是否真的就是那一块」。</p>
 */
public class TouchRoiExtractorTest {

    private static final int FRAME_W = 1280;
    private static final int FRAME_H = 720;
    private static final int RADIUS = 200;

    private final TouchRoiExtractor extractor = new TouchRoiExtractor();

    private static CapturedFrame frame(int width, int height, int argb) {
        return CapturedFrame.ofImage(ArgbFrameImage.solid(width, height, argb), 0L, 0L);
    }

    private TouchRoiResult extract(float videoX, float videoY) {
        return extractor.extract(frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND),
                null, videoX, videoY, RADIUS, RADIUS);
    }

    // ---------------------------------------------------------------- 中心与边缘

    @Test
    public void centerCropIsSymmetricAroundTheTouchPoint() {
        TouchRoiResult result = extract(640f, 360f);
        assertTrue(result.debugString(), result.status == TouchRoiStatus.SUCCESS);

        TouchRoi roi = result.roi;
        assertEquals(440, roi.frameLeft);
        assertEquals(160, roi.frameTop);
        assertEquals(400, roi.frameWidth);
        assertEquals(400, roi.frameHeight);
        assertEquals(200, roi.touchLocalX);
        assertEquals(200, roi.touchLocalY);
        assertEquals(400 * 400, result.argb.length);

        // 视频坐标下的 ROI 与帧坐标一致（无几何 → 1:1）
        assertEquals(440f, roi.videoRect.left, 0.01f);
        assertEquals(160f, roi.videoRect.top, 0.01f);
        assertEquals(840f, roi.videoRect.right, 0.01f);
        assertEquals(560f, roi.videoRect.bottom, 0.01f);
    }

    @Test
    public void leftEdgeShrinksInsteadOfGoingNegative() {
        TouchRoi roi = extract(10f, 360f).roi;
        assertEquals(0, roi.frameLeft);
        assertEquals(210, roi.frameWidth);
        assertEquals(10, roi.touchLocalX);
        assertEquals(200, roi.touchLocalY);
    }

    @Test
    public void rightEdgeShrinksInsteadOfOverflowing() {
        TouchRoi roi = extract(1270f, 360f).roi;
        assertEquals(1070, roi.frameLeft);
        assertEquals(FRAME_W, roi.frameRight());
        assertEquals(210, roi.frameWidth);
        assertEquals(200, roi.touchLocalX);
    }

    @Test
    public void topEdgeShrinksInsteadOfGoingNegative() {
        TouchRoi roi = extract(640f, 10f).roi;
        assertEquals(0, roi.frameTop);
        assertEquals(210, roi.frameHeight);
        assertEquals(10, roi.touchLocalY);
        assertEquals(200, roi.touchLocalX);
    }

    @Test
    public void bottomEdgeShrinksInsteadOfOverflowing() {
        TouchRoi roi = extract(640f, 710f).roi;
        assertEquals(510, roi.frameTop);
        assertEquals(FRAME_H, roi.frameBottom());
        assertEquals(210, roi.frameHeight);
        assertEquals(200, roi.touchLocalY);
    }

    @Test
    public void everyCornerStaysInsideTheFrame() {
        float[][] corners = {{5f, 5f}, {1275f, 5f}, {5f, 715f}, {1275f, 715f}};
        for (float[] corner : corners) {
            TouchRoiResult result = extract(corner[0], corner[1]);
            assertTrue("corner " + corner[0] + "," + corner[1] + " -> " + result.status,
                    result.status == TouchRoiStatus.SUCCESS);
            TouchRoi roi = result.roi;
            assertEquals(205, roi.frameWidth);
            assertEquals(205, roi.frameHeight);
            assertTrue(roi.containsLocal(roi.touchLocalX, roi.touchLocalY));
        }
    }

    @Test
    public void roiNeverLeavesTheFrameForAnyTouchPoint() {
        // 帧只建一次：这个用例要扫近 700 个点，逐次重建 1280×720 的帧会把时间浪费在分配上
        CapturedFrame frame = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        // 步长刻意整除边界，保证 0 与 1280 / 720 都被覆盖到
        for (int y = 0; y <= FRAME_H; y += 36) {
            for (int x = 0; x <= FRAME_W; x += 40) {
                TouchRoiResult result = extractor.extract(frame, null, x, y, RADIUS, RADIUS);
                assertTrue("(" + x + "," + y + ") -> " + result.status, result.isUsable());
                TouchRoi roi = result.roi;
                assertTrue("left >= 0", roi.frameLeft >= 0);
                assertTrue("top >= 0", roi.frameTop >= 0);
                assertTrue("right <= frameWidth", roi.frameRight() <= FRAME_W);
                assertTrue("bottom <= frameHeight", roi.frameBottom() <= FRAME_H);
                assertTrue("width > 0", roi.frameWidth > 0);
                assertTrue("height > 0", roi.frameHeight > 0);
                assertTrue("touch inside roi", roi.containsLocal(roi.touchLocalX, roi.touchLocalY));
                assertEquals(roi.pixelCount(), result.argb.length);
            }
        }
    }

    // ---------------------------------------------------------------- 裁出来的像素对不对

    @Test
    public void croppedPixelsMatchTheSourceRegionExactly() {
        // 每个像素编码自己的坐标，于是"裁对没裁对"可以被精确断言
        final int[] pixels = new int[FRAME_W * FRAME_H];
        for (int y = 0; y < FRAME_H; y++) {
            for (int x = 0; x < FRAME_W; x++) {
                pixels[y * FRAME_W + x] = 0xFF000000 | (y << 12) | x;
            }
        }
        ArgbFrameImage image = ArgbFrameImage.of(FRAME_W, FRAME_H, pixels);
        TouchRoiResult result = extractor.extract(
                CapturedFrame.ofImage(image, 0L, 0L), null, 640f, 360f, 200, 200);

        assertTrue(result.isUsable());
        assertEquals(440, image.lastReadX);
        assertEquals(160, image.lastReadY);
        assertEquals(400, image.lastReadWidth);
        assertEquals(400, image.lastReadHeight);

        for (int row = 0; row < 400; row++) {
            for (int column = 0; column < 400; column++) {
                int expected = 0xFF000000 | ((160 + row) << 12) | (440 + column);
                assertEquals("pixel at roi(" + column + "," + row + ")",
                        expected, result.argb[row * 400 + column]);
            }
        }
    }

    // ---------------------------------------------------------------- 视频 ↔ 帧 尺度换算

    @Test
    public void downscaledFrameShrinksRoiAndRadius() {
        // 视频/View 都是 1280x720，回读帧被缩到一半
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        TouchRoiResult result = extractor.extract(
                frame(640, 360, TestImages.GRAY_BACKGROUND), geometry, 640f, 360f, 200, 200);

        assertTrue(result.isUsable());
        TouchRoi roi = result.roi;
        // 帧坐标：中心 (320,180)，半径 100
        assertEquals(220, roi.frameLeft);
        assertEquals(80, roi.frameTop);
        assertEquals(200, roi.frameWidth);
        assertEquals(200, roi.frameHeight);
        assertEquals(100, roi.touchLocalX);
        // 视频坐标下的 ROI 仍是 200 半径（对外交流用视频坐标）
        assertEquals(440f, roi.videoRect.left, 0.01f);
        assertEquals(840f, roi.videoRect.right, 0.01f);
    }

    // ---------------------------------------------------------------- 非法输入

    @Test
    public void nullFrameIsInvalidFrame() {
        TouchRoiResult result = extractor.extract(null, null, 10f, 10f, RADIUS, RADIUS);
        assertEquals(TouchRoiStatus.INVALID_FRAME, result.status);
        assertNull(result.roi);
        assertNull(result.argb);
        assertFalse(result.isUsable());
    }

    @Test
    public void emptyFrameIsInvalidFrame() {
        CapturedFrame empty = CapturedFrame.empty(0L, 1L);
        TouchRoiResult result = extractor.extract(empty, null, 10f, 10f, RADIUS, RADIUS);
        assertEquals(TouchRoiStatus.INVALID_FRAME, result.status);
        assertNotNull(result.debugReason);
    }

    @Test
    public void zeroSizedFrameIsInvalidFrame() {
        CapturedFrame zero = CapturedFrame.ofImage(ArgbFrameImage.of(0, 0, new int[0]), 0L, 0L);
        TouchRoiResult result = extractor.extract(zero, null, 0f, 0f, RADIUS, RADIUS);
        assertEquals(TouchRoiStatus.INVALID_FRAME, result.status);
    }

    @Test
    public void releasedFrameIsInvalidFrame() {
        CapturedFrame captured = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        captured.release();
        TouchRoiResult result = extractor.extract(captured, null, 640f, 360f, RADIUS, RADIUS);
        assertEquals(TouchRoiStatus.INVALID_FRAME, result.status);
    }

    @Test
    public void nonPositiveRadiusIsInvalidRoi() {
        CapturedFrame captured = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        assertEquals(TouchRoiStatus.INVALID_ROI,
                extractor.extract(captured, null, 640f, 360f, 0, RADIUS).status);
        assertEquals(TouchRoiStatus.INVALID_ROI,
                extractor.extract(captured, null, 640f, 360f, RADIUS, -5).status);
    }

    @Test
    public void nonFiniteTouchPointIsInvalidRoi() {
        CapturedFrame captured = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        assertEquals(TouchRoiStatus.INVALID_ROI,
                extractor.extract(captured, null, Float.NaN, 360f, RADIUS, RADIUS).status);
        assertEquals(TouchRoiStatus.INVALID_ROI,
                extractor.extract(captured, null, 640f, Float.POSITIVE_INFINITY, RADIUS, RADIUS).status);
    }

    @Test
    public void touchPointOutsideVideoIsReportedSeparately() {
        CapturedFrame captured = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        assertEquals(TouchRoiStatus.OUTSIDE_VIDEO,
                extractor.extract(captured, null, -20f, 360f, RADIUS, RADIUS).status);
        assertEquals(TouchRoiStatus.OUTSIDE_VIDEO,
                extractor.extract(captured, null, FRAME_W + 20f, 360f, RADIUS, RADIUS).status);
        assertEquals(TouchRoiStatus.OUTSIDE_VIDEO,
                extractor.extract(captured, null, 640f, -1f, RADIUS, RADIUS).status);
    }

    @Test
    public void imageWithoutPixelReadoutIsReportedSeparately() {
        FakeFrameImage noPixels = new FakeFrameImage(FRAME_W, FRAME_H);
        CapturedFrame captured = CapturedFrame.ofImage(noPixels, 0L, 0L);
        TouchRoiResult result = extractor.extract(captured, null, 640f, 360f, RADIUS, RADIUS);
        assertEquals(TouchRoiStatus.PIXELS_UNAVAILABLE, result.status);
        // 帧本身没问题，所以几何已经算出来了；只是读不到像素
        assertNull(result.argb);
    }

    @Test
    public void extractorDoesNotReleaseTheFrame() {
        ArgbFrameImage image = ArgbFrameImage.solid(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        CapturedFrame captured = CapturedFrame.ofImage(image, 0L, 0L);
        extractor.extract(captured, null, 640f, 360f, RADIUS, RADIUS);
        assertEquals("所有权仍在调用方", 0, image.releaseCount);
        assertTrue(captured.hasImage());
    }

    @Test
    public void repeatedExtractionReturnsIndependentBuffers() {
        CapturedFrame captured = frame(FRAME_W, FRAME_H, TestImages.GRAY_BACKGROUND);
        TouchRoiResult first = extractor.extract(captured, null, 640f, 360f, RADIUS, RADIUS);
        TouchRoiResult second = extractor.extract(captured, null, 100f, 100f, RADIUS, RADIUS);
        assertTrue(second.isUsable());
        // 不共享缓冲：否则第二次分析会把第一次的结果改写掉（别名 bug）
        assertFalse(first.argb == second.argb);
        assertFalse(first.roi == second.roi);
        assertSame(TouchRoiStatus.SUCCESS, second.status);
    }
}
