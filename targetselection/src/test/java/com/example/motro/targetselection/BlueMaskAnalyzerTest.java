package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueMaskAnalyzer;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BlueMaskStatus;
import com.example.motro.targetselection.vision.TouchRoiRequest;

import org.junit.Test;

/** 单入口 {@link BlueMaskAnalyzer} 的端到端契约：配置注入、几何换算、debug 开关、错误透传。 */
public class BlueMaskAnalyzerTest {

    private static CapturedFrame grayFrame(int width, int height) {
        return CapturedFrame.ofImage(
                ArgbFrameImage.solid(width, height, TestImages.GRAY_BACKGROUND), 0L, 0L);
    }

    /** 整帧铺蓝的帧（保证 ROI 无论落在哪都能看到蓝）。 */
    private static CapturedFrame allBlueFrame(int width, int height) {
        return CapturedFrame.ofImage(ArgbFrameImage.solid(width, height, TestImages.PURE_BLUE), 0L, 0L);
    }

    @Test
    public void defaultRadiusProducesTheConfiguredRoiSize() {
        BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(TargetSelectionConfig.defaults());
        BlueMaskResult result = analyzer.analyze(allBlueFrame(1280, 720), null, 640f, 360f);

        assertEquals(BlueMaskStatus.SUCCESS, result.status);
        assertEquals(TargetSelectionConfig.DEFAULT_ROI_RADIUS * 2, result.roiWidth);
        assertEquals(TargetSelectionConfig.DEFAULT_ROI_RADIUS * 2, result.roiHeight);
        assertEquals(400 * 400, result.bluePixelCount);
        assertEquals(1f, result.bluePixelRatio, 1e-6f);
        assertEquals(200f, result.touchLocalX, 0.01f);
        assertEquals(200f, result.touchLocalY, 0.01f);
        assertTrue(result.processingMs >= 0L);
    }

    @Test
    public void configuredRadiiAreRespected() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .roiRadiusX(50)
                .roiRadiusY(30)
                .build();
        BlueMaskResult result = new BlueMaskAnalyzer(config).analyze(allBlueFrame(1280, 720), null, 640f, 360f);

        assertEquals(100, result.roiWidth);
        assertEquals(60, result.roiHeight);
        assertEquals(6000, result.bluePixelCount);
    }

    @Test
    public void debugSwitchControlsRetentionOfRoiPixels() {
        BlueMaskResult off = new BlueMaskAnalyzer(TargetSelectionConfig.defaults())
                .analyze(allBlueFrame(1280, 720), null, 640f, 360f);
        assertNull("生产路径不该多留一份大数组", off.debugRoiArgb);

        TargetSelectionConfig debug = TargetSelectionConfig.defaults().toBuilder()
                .debugMaskEnabled(true)
                .build();
        BlueMaskResult on = new BlueMaskAnalyzer(debug).analyze(allBlueFrame(1280, 720), null, 640f, 360f);
        assertNotNull(on.debugRoiArgb);
        assertEquals(on.roiWidth * on.roiHeight, on.debugRoiArgb.length);
    }

    /**
     * 几何参与换算：视频 1000×500 放进 1000×1000 的 View（上下各 250px 黑边），
     * 回读帧是 500×500。<b>点击的是 View 点</b>，ROI 必须围绕映射出来的<b>视频点</b>，
     * 而不是围绕 View 坐标 —— 这两者差了整整 250px 的黑边。
     */
    @Test
    public void roiIsCenteredOnTheMappedVideoPointNotTheViewPoint() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1000, 500, 1000, 1000, VideoScaleType.FIT_CENTER);
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(geometry, 500f, 500f);
        assertTrue(mapping.debugString(), mapping.hasVideoCoordinates());
        assertEquals(500f, mapping.videoX, 0.01f);
        assertEquals(250f, mapping.videoY, 0.01f);

        CapturedFrame frame = CapturedFrame.ofImage(
                ArgbFrameImage.solid(500, 500, TestImages.PURE_BLUE), 0L, 0L);
        TouchRoiRequest request = TouchRoiRequest.forMapping(frame, geometry, mapping);
        assertNotNull(request);

        BlueMaskResult result = new BlueMaskAnalyzer(TargetSelectionConfig.defaults()).analyze(request);

        assertEquals(BlueMaskStatus.SUCCESS, result.status);
        // ROI 在视频坐标下围绕 (500,250)，半径 200：纵向被夹在视频高度 500 内
        assertEquals(300f, result.roiRectInVideo.left, 0.01f);
        assertEquals(50f, result.roiRectInVideo.top, 0.01f);
        assertEquals(700f, result.roiRectInVideo.right, 0.01f);
        assertEquals(450f, result.roiRectInVideo.bottom, 0.01f);
        // 帧空间里半径被缩到 100 → 200×200
        assertEquals(200, result.roiWidth);
        assertEquals(200, result.roiHeight);
    }

    @Test
    public void roiFollowsTheTouchPointAroundTheFrame() {
        BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(TargetSelectionConfig.defaults());
        CapturedFrame frame = allBlueFrame(1280, 720);

        BlueMaskResult topLeft = analyzer.analyze(frame, null, 0f, 0f);
        assertEquals(200, topLeft.roiWidth);
        assertEquals(200, topLeft.roiHeight);
        assertEquals(0f, topLeft.roiRectInVideo.left, 0.01f);
        assertEquals(0f, topLeft.roiRectInVideo.top, 0.01f);
        assertTrue(topLeft.bluePixelCount > 0);

        BlueMaskResult bottomRight = analyzer.analyze(frame, null, 1280f, 720f);
        assertEquals(200, bottomRight.roiWidth);
        assertEquals(200, bottomRight.roiHeight);
        assertEquals(1280f, bottomRight.roiRectInVideo.right, 0.01f);
        assertEquals(720f, bottomRight.roiRectInVideo.bottom, 0.01f);
    }

    @Test
    public void nonBlueFrameYieldsEmptyMaskNotFailure() {
        BlueMaskResult result = new BlueMaskAnalyzer(TargetSelectionConfig.defaults())
                .analyze(grayFrame(1280, 720), null, 640f, 360f);
        assertEquals(BlueMaskStatus.EMPTY_MASK, result.status);
        assertTrue(result.hasRoi());
        assertEquals(0, result.bluePixelCount);
    }

    @Test
    public void failuresPropagateWithDistinctStatuses() {
        BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(TargetSelectionConfig.defaults());

        assertEquals(BlueMaskStatus.OUTSIDE_VIDEO,
                analyzer.analyze(allBlueFrame(1280, 720), null, -50f, 360f).status);
        assertEquals(BlueMaskStatus.INVALID_ROI,
                analyzer.analyze(allBlueFrame(1280, 720), null, Float.NaN, 360f).status);
        assertEquals(BlueMaskStatus.INVALID_FRAME,
                analyzer.analyze(CapturedFrame.empty(0L, 1L), null, 100f, 100f).status);
        assertEquals(BlueMaskStatus.PIXELS_UNAVAILABLE,
                analyzer.analyze(CapturedFrame.ofImage(new FakeFrameImage(640, 360), 0L, 0L),
                        null, 100f, 100f).status);
    }

    @Test
    public void analyzerKeepsTheConfigItWasBuiltWith() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(config);
        assertSame(config, analyzer.config());
    }

    @Test
    public void nullArgumentsAreRejected() {
        assertRejected(() -> new BlueMaskAnalyzer(null));
        BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(TargetSelectionConfig.defaults());
        assertRejected(() -> analyzer.analyze((TouchRoiRequest) null));
        assertRejected(() -> TouchRoiRequest.of(null, null, 1f, 1f));
    }

    @Test
    public void requestFactoryRefusesMappingsWithoutVideoCoordinates() {
        CapturedFrame frame = allBlueFrame(100, 100);
        assertNull(TouchRoiRequest.forMapping(frame, null, null));

        VideoGeometry geometry = VideoGeometry.createWithScale(
                1000, 1000, 1000, 1000, VideoScaleType.FIT_CENTER);
        // 点在 View 之外 → OUTSIDE_VIDEO，没有视频坐标
        VideoPointMappingResult outside = VideoCoordinateMapper.map(geometry, -5f, 500f);
        assertNull(TouchRoiRequest.forMapping(frame, geometry, outside));
        assertNull(TouchRoiRequest.forMapping(null, geometry, outside));
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
