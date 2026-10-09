package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.vision.BlueColorThresholds;
import com.example.motro.targetselection.vision.BlueMaskDetector;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BlueMaskStatus;
import com.example.motro.targetselection.vision.TouchRoiExtractor;
import com.example.motro.targetselection.vision.TouchRoiResult;

import org.junit.Test;

import java.util.Arrays;

/**
 * 蓝色掩码提取的行为契约。
 *
 * <p>场景刻意做得「小而确定」：ROI 正对着一块 80×80 的区域，蓝像素数可以手算，
 * 于是断言能落到精确值，而不是"大概不为零"。真实编码样本走
 * {@link BlueMaskSampleSmokeTest}。</p>
 */
public class BlueMaskDetectorTest {

    private static final int IMAGE = 200;
    /** ROI 正中心 80×80：[60,140) × [60,140)。 */
    private static final int ROI_ORIGIN = 60;
    private static final int ROI_SIZE = 80;
    private static final int ROI_RADIUS = ROI_SIZE / 2;
    private static final int ROI_PIXELS = ROI_SIZE * ROI_SIZE;

    private final TouchRoiExtractor extractor = new TouchRoiExtractor();

    private static BlueMaskDetector detector(boolean keepDebugRoi) {
        return new BlueMaskDetector(BlueColorThresholds.defaults(), 1, 12, keepDebugRoi);
    }

    private static BlueMaskDetector detector(int denoiseMinNeighbors, int minBluePixelCount) {
        return new BlueMaskDetector(BlueColorThresholds.defaults(),
                denoiseMinNeighbors, minBluePixelCount, false);
    }

    private TouchRoiResult roiOf(int[] pixels) {
        ArgbFrameImage image = ArgbFrameImage.of(IMAGE, IMAGE, pixels);
        CapturedFrame frame = CapturedFrame.ofImage(image, 0L, 0L);
        return extractor.extract(frame, null, IMAGE / 2f, IMAGE / 2f, ROI_RADIUS, ROI_RADIUS);
    }

    private static int[] background() {
        return TestImages.solid(IMAGE, IMAGE, TestImages.GRAY_BACKGROUND);
    }

    // ---------------------------------------------------------------- 正向：蓝色被认出来

    @Test
    public void solidBlueBlockIsFullyDetected() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.PURE_BLUE);

        BlueMaskResult result = detector(false).detect(roiOf(pixels));

        assertEquals(result.debugString(), BlueMaskStatus.SUCCESS, result.status);
        assertEquals(ROI_PIXELS, result.bluePixelCount);
        assertEquals(1f, result.bluePixelRatio, 1e-6f);
        assertEquals(ROI_SIZE, result.mask.width());
        assertEquals(ROI_SIZE, result.mask.height());
        assertTrue(result.mask.get(0, 0));
        assertTrue(result.mask.get(ROI_SIZE - 1, ROI_SIZE - 1));
        assertEquals(ROI_ORIGIN, result.roiRectInVideo.left, 0.01f);
        assertEquals(ROI_ORIGIN + ROI_SIZE, result.roiRectInVideo.right, 0.01f);
        assertEquals(ROI_RADIUS, result.touchLocalX, 0.01f);
        assertEquals(ROI_RADIUS, result.touchLocalY, 0.01f);
        assertTrue(result.processingMs >= 0L);
    }

    /** 蓝框线：只保留边框，中间的背景必须干净 —— 这是"框能被保留、背景不泛滥"的核心断言。 */
    @Test
    public void blueFrameLineIsKeptButItsInsideStaysClean() {
        int[] pixels = background();
        TestImages.strokeRect(pixels, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                2, TestImages.PURE_BLUE);

        BlueMaskResult result = detector(false).detect(roiOf(pixels));

        assertEquals(BlueMaskStatus.SUCCESS, result.status);
        // 2px 粗的 80×80 空心框 ≈ 4*80*2 - 4*4 = 624 个像素
        assertEquals(624, result.bluePixelCount);
        assertTrue(result.mask.get(0, 0));
        assertTrue(result.mask.get(ROI_SIZE - 1, 0));
        assertTrue(result.mask.get(0, ROI_SIZE - 1));
        assertTrue(result.mask.get(ROI_SIZE - 1, ROI_SIZE - 1));
        // 框内必须干净：背景（含框内区域）不得大面积进掩码
        assertFalse(result.mask.get(ROI_RADIUS, ROI_RADIUS));
        assertTrue("蓝像素占比不该超过 15%", result.bluePixelRatio < 0.15f);
    }

    /** 1px 细线在去噪后必须完整保留 —— 腐蚀掉细线会让后续矩形恢复直接失败。 */
    @Test
    public void thinBlueLineSurvivesDenoise() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, ROI_ORIGIN, 100, ROI_SIZE, 2, TestImages.PURE_BLUE);

        BlueMaskResult result = detector(true).detect(roiOf(pixels));

        assertEquals(BlueMaskStatus.SUCCESS, result.status);
        assertEquals(ROI_SIZE * 2, result.bluePixelCount);
        for (int x = 0; x < ROI_SIZE; x++) {
            assertTrue("row 40 x=" + x, result.mask.get(x, 40));
            assertTrue("row 41 x=" + x, result.mask.get(x, 41));
        }
    }

    /** 蓝色 `id:xxxx` 文字（细笔画 + 镂空）必须被保留，后续 Slice 还要用它做 id 识别。 */
    @Test
    public void blueLabelGlyphsSurviveAsSeveralRunsPerRow() {
        int[] pixels = background();
        TestImages.drawLabelGlyphs(pixels, IMAGE, IMAGE, 70, 90, 6, 10, 5, TestImages.PURE_BLUE);

        BlueMaskResult result = detector(true).detect(roiOf(pixels));

        assertEquals(BlueMaskStatus.SUCCESS, result.status);
        assertEquals(5 * 28, result.bluePixelCount);
        // 笔画是"多段"的：一行里会出现若干个互不相连的段
        assertTrue("runCount=" + result.mask.runCount(), result.mask.runCount() >= 10);
        assertTrue(result.bluePixelRatio < 0.05f);
    }

    // ---------------------------------------------------------------- 反向：非蓝被排除

    @Test
    public void nonBlueBackgroundsProduceEmptyMask() {
        assertEquals(BlueMaskStatus.EMPTY_MASK,
                detector(false).detect(roiOf(TestImages.solid(IMAGE, IMAGE, TestImages.GRAY_BACKGROUND))).status);
        assertEquals(BlueMaskStatus.EMPTY_MASK,
                detector(false).detect(roiOf(TestImages.solid(IMAGE, IMAGE, TestImages.CYAN_BACKGROUND))).status);
        assertEquals(BlueMaskStatus.EMPTY_MASK,
                detector(false).detect(roiOf(TestImages.solid(IMAGE, IMAGE, TestImages.GREEN_BACKGROUND))).status);
        assertEquals(BlueMaskStatus.EMPTY_MASK,
                detector(false).detect(roiOf(TestImages.solid(IMAGE, IMAGE, TestImages.rgb(255, 255, 255)))).status);
        assertEquals(BlueMaskStatus.EMPTY_MASK,
                detector(false).detect(roiOf(TestImages.solid(IMAGE, IMAGE, TestImages.rgb(10, 10, 10)))).status);
    }

    @Test
    public void emptyMaskStillReportsRawCounts() {
        BlueMaskResult result = detector(false).detect(roiOf(background()));
        assertEquals(BlueMaskStatus.EMPTY_MASK, result.status);
        assertEquals(0, result.bluePixelCount);
        assertEquals(0f, result.bluePixelRatio, 1e-6f);
        assertTrue(result.hasRoi());
        assertFalse(result.hasBluePixels());
        assertTrue(result.mask.isEmpty());
    }

    // ---------------------------------------------------------------- 压缩偏色容错

    @Test
    public void hevcShiftedBluesAreStillDetected() {
        int[] shifted = background();
        TestImages.fillRect(shifted, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.SHIFTED_BLUE);
        assertEquals(ROI_PIXELS, detector(false).detect(roiOf(shifted)).bluePixelCount);

        int[] light = background();
        TestImages.fillRect(light, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.SHIFTED_BLUE_LIGHT);
        assertEquals(ROI_PIXELS, detector(false).detect(roiOf(light)).bluePixelCount);
    }

    @Test
    public void washedOutBlueIsRejectedBySaturationFloor() {
        int[] washed = background();
        TestImages.fillRect(washed, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.WASHED_BLUE);

        BlueMaskResult withDefaults = detector(false).detect(roiOf(washed));
        assertEquals(BlueMaskStatus.EMPTY_MASK, withDefaults.status);

        // 把饱和度门槛降到 0.2 就能认出来 —— 说明门槛真的在起作用（而不是颜色压根不沾边）
        BlueMaskDetector lenient = new BlueMaskDetector(
                BlueColorThresholds.of(200f, 270f, 0.2f, 0.2f), 1, 12, false);
        assertEquals(ROI_PIXELS, lenient.detect(roiOf(washed)).bluePixelCount);
    }

    // ---------------------------------------------------------------- 去噪

    @Test
    public void isolatedNoisePixelIsRemovedByDenoise() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, 100, 100, 1, 1, TestImages.PURE_BLUE);

        assertEquals(1, detector(0, 0).detect(roiOf(pixels)).bluePixelCount);
        assertEquals(0, detector(1, 0).detect(roiOf(pixels)).bluePixelCount);
    }

    @Test
    public void clumpWiderThanOnePixelIsStillNoiseAtHigherThreshold() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, 100, 100, 3, 1, TestImages.PURE_BLUE);

        // 1×3 的小段：两端各只有 1 个邻居、中间 2 个 ——
        // 阈值为 1 时保留（3 个点都有邻居），阈值为 3 时全部被删
        assertEquals(3, detector(1, 0).detect(roiOf(pixels)).bluePixelCount);
        assertEquals(0, detector(3, 0).detect(roiOf(pixels)).bluePixelCount);
    }

    @Test
    public void denoiseDisabledKeepsEverything() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, 100, 100, 1, 1, TestImages.PURE_BLUE);
        assertEquals(1, detector(0, 0).detect(roiOf(pixels)).bluePixelCount);
    }

    // ---------------------------------------------------------------- 阈值配置生效

    @Test
    public void hueAndCountThresholdsTakeEffect() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, 100, 100, 4, 4, TestImages.PURE_BLUE);

        // 纯蓝是 240°，把下界抬到 250° 就该全部落空
        BlueMaskDetector shiftedHue = new BlueMaskDetector(
                BlueColorThresholds.of(250f, 270f, 0.3f, 0.2f), 1, 0, false);
        assertEquals(BlueMaskStatus.EMPTY_MASK, shiftedHue.detect(roiOf(pixels)).status);

        // 16 个蓝像素，下限设 17 → EMPTY_MASK；设 16 → SUCCESS（边界包含）
        assertEquals(BlueMaskStatus.EMPTY_MASK, detector(1, 17).detect(roiOf(pixels)).status);
        assertEquals(BlueMaskStatus.SUCCESS, detector(1, 16).detect(roiOf(pixels)).status);
    }

    // ---------------------------------------------------------------- 不修改输入 / Debug

    @Test
    public void detectionNeverMutatesTheInputPixels() {
        int[] pixels = background();
        TestImages.strokeRect(pixels, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                2, TestImages.PURE_BLUE);
        int[] sourceSnapshot = Arrays.copyOf(pixels, pixels.length);
        TouchRoiResult roi = roiOf(pixels);
        int[] roiSnapshot = Arrays.copyOf(roi.argb, roi.argb.length);

        BlueMaskResult result = detector(false).detect(roi);

        // 掩码生成是纯读操作：既不能改 ROI 缓冲，也不能回溯改到源图像
        assertArrayEquals(roiSnapshot, roi.argb);
        assertArrayEquals(sourceSnapshot, pixels);
        assertNull("默认不保留 ROI 原始像素", result.debugRoiArgb);
        assertTrue(result.bluePixelCount > 0);
    }

    @Test
    public void debugModeKeepsRoiPixelsOnly() {
        int[] pixels = background();
        TestImages.fillRect(pixels, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.PURE_BLUE);

        BlueMaskResult result = detector(true).detect(roiOf(pixels));
        assertNotNull(result.debugRoiArgb);
        assertEquals(ROI_PIXELS, result.debugRoiArgb.length);
        assertEquals(TestImages.PURE_BLUE, result.debugRoiArgb[0]);
    }

    // ---------------------------------------------------------------- 非法输入

    @Test
    public void nullRoiIsInvalidFrame() {
        BlueMaskResult result = detector(false).detect(null);
        assertEquals(BlueMaskStatus.INVALID_FRAME, result.status);
        assertNotNull(result.debugReason);
        assertFalse(result.hasRoi());
        assertEquals(0, result.mask.width());
        assertEquals(0, result.mask.height());
    }

    @Test
    public void roiFailureStatusIsPassedThroughUnchanged() {
        CapturedFrame empty = CapturedFrame.empty(0L, 1L);
        TouchRoiResult failed = extractor.extract(empty, null, 10f, 10f, 20, 20);
        assertEquals(BlueMaskStatus.INVALID_FRAME, detector(false).detect(failed).status);

        CapturedFrame zeroRadius = CapturedFrame.ofImage(
                ArgbFrameImage.solid(IMAGE, IMAGE, TestImages.GRAY_BACKGROUND), 0L, 0L);
        TouchRoiResult badRoi = extractor.extract(zeroRadius, null, 10f, 10f, 0, 20);
        assertEquals(BlueMaskStatus.INVALID_ROI, detector(false).detect(badRoi).status);

        TouchRoiResult outside = extractor.extract(zeroRadius, null, -5f, 10f, 20, 20);
        assertEquals(BlueMaskStatus.OUTSIDE_VIDEO, detector(false).detect(outside).status);

        TouchRoiResult noPixels = extractor.extract(
                CapturedFrame.ofImage(new FakeFrameImage(IMAGE, IMAGE), 0L, 0L), null, 10f, 10f, 20, 20);
        assertEquals(BlueMaskStatus.PIXELS_UNAVAILABLE, detector(false).detect(noPixels).status);
    }

    @Test
    public void invalidDetectorArgumentsAreRejected() {
        assertRejected(() -> new BlueMaskDetector(null, 1, 0, false));
        assertRejected(() -> new BlueMaskDetector(BlueColorThresholds.defaults(), -1, 0, false));
        assertRejected(() -> new BlueMaskDetector(BlueColorThresholds.defaults(), 9, 0, false));
        assertRejected(() -> new BlueMaskDetector(BlueColorThresholds.defaults(), 1, -1, false));
    }

    /** 复用内部位缓冲意味着跨调用不能互相污染：连跑几次结果必须一致（且互换缓冲后仍正确）。 */
    @Test
    public void repeatedDetectionIsStableAcrossBufferReuse() {
        int[] blue = background();
        TestImages.fillRect(blue, IMAGE, IMAGE, ROI_ORIGIN, ROI_ORIGIN, ROI_SIZE, ROI_SIZE,
                TestImages.PURE_BLUE);
        BlueMaskDetector shared = detector(true);

        BlueMaskResult first = shared.detect(roiOf(blue));
        BlueMaskResult second = shared.detect(roiOf(background()));
        BlueMaskResult third = shared.detect(roiOf(blue));

        assertEquals(ROI_PIXELS, first.bluePixelCount);
        assertEquals(0, second.bluePixelCount);
        assertEquals(ROI_PIXELS, third.bluePixelCount);
        assertEquals(BlueMaskStatus.EMPTY_MASK, second.status);
        assertEquals(BlueMaskStatus.SUCCESS, third.status);
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void assertRejected(ThrowingRunnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
