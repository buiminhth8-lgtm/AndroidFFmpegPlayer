package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.api.TargetSelectionController;
import com.example.motro.targetselection.api.TargetSelectionRequest;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueColorThresholds;
import com.example.motro.targetselection.vision.BlueMaskDetector;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetBoxDetector;
import com.example.motro.targetselection.vision.TargetCandidateSelector;
import com.example.motro.targetselection.vision.TargetHitTester;
import com.example.motro.targetselection.vision.TargetIdRecognitionResult;
import com.example.motro.targetselection.vision.TargetIdRecognizer;
import com.example.motro.targetselection.vision.TouchRoiExtractor;

import org.junit.Test;

/**
 * 触摸接入骨架测试：{@code ACTION_DOWN → VideoCoordinateMapper → 视频坐标}。
 *
 * <p>核心断言是「只做映射」—— 取帧与识别必须一次都没被触碰（否则"点击即取帧"的策略
 * 就被悄悄塞进了一个本不该做决策的方法里）。</p>
 *
 * <p>Slice 9 起控制器用 13 参构造显式注入协作者；本测试仍然只关心"映射不触发下游"，
 * 所以协作者全部是可数的空实现 + 一个从不被调用的识别器。</p>
 */
public class TargetSelectionControllerTouchTest {

    private static final float EPS = 0.001f;

    // ---------------------------------------------------------------- fakes

    /** 只数次数：触摸映射路径不该触发任何取帧。 */
    private static final class CountingFrameSource implements VideoFrameSource {
        int captureCount;

        @Override
        public CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException {
            captureCount++;
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class CountingRecognizer implements TargetIdRecognizer {
        int recognizeCount;

        @Override
        public TargetIdRecognitionResult recognize(LabelExtractionResult label, TargetSelectionConfig config) {
            recognizeCount++;
            return TargetIdRecognitionResult.noId(null, null, 0L, 0L, 0L, 0L, "not used", name());
        }

        @Override
        public String name() {
            return "touch-test-recognizer";
        }
    }

    private static final class FixedGeometryProvider implements VideoGeometryProvider {
        VideoGeometry geometry;

        @Override
        public VideoGeometry currentGeometry() {
            return geometry;
        }
    }

    // ------------------------------------------------------------- helpers

    private static TargetSelectionController controller(CountingFrameSource source,
                                                        FixedGeometryProvider geometry,
                                                        CountingRecognizer recognizer) {
        return controller(source, geometry, recognizer, TargetSelectionConfig.defaults());
    }

    private static TargetSelectionController controller(CountingFrameSource source,
                                                        FixedGeometryProvider geometry,
                                                        CountingRecognizer recognizer,
                                                        TargetSelectionConfig config) {
        return new TargetSelectionController(source, geometry,
                new TouchRoiExtractor(),
                new BlueMaskDetector(
                        BlueColorThresholds.of(config.blueHueMinDegrees, config.blueHueMaxDegrees,
                                config.minSaturation, config.minValue),
                        config.maskDenoiseMinNeighbors, config.minBluePixelCount, false),
                new TargetBoxDetector(config),
                new TargetHitTester(config),
                new TargetCandidateSelector(config),
                new LabelRegionExtractor(config),
                recognizer, config, null, null, null);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    public void onTouchDownMapsOnlyAndNeverTouchesCaptureOrVision() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        geometry.geometry = VideoGeometry.createWithScale(1280, 720, 1920, 1080, VideoScaleType.FIT_CENTER);
        CountingRecognizer recognizer = new CountingRecognizer();

        VideoPointMappingResult result = controller(source, geometry, recognizer)
                .onTouchDown(TouchSelectionRequest.downNow(960f, 540f));

        assertSame(VideoPointMappingStatus.SUCCESS, result.status);
        assertEquals(640f, result.videoX, EPS);
        assertEquals(360f, result.videoY, EPS);
        assertEquals(0.5f, result.normalizedX, EPS);
        assertTrue(result.insideVideoContent);

        // 关键：触摸映射是纯几何操作，既不该回读帧，也不该跑识别
        assertEquals("onTouchDown 不得取帧", 0, source.captureCount);
        assertEquals("onTouchDown 不得触发识别", 0, recognizer.recognizeCount);
    }

    @Test
    public void onTouchDownOnLetterboxIsOutsideVideoWithoutCoordinates() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        geometry.geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);
        CountingRecognizer recognizer = new CountingRecognizer();

        VideoPointMappingResult result = controller(source, geometry, recognizer)
                .onTouchDown(50f, 5f, TouchSelectionRequest.NO_EVENT_TIME_NS);

        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, result.status);
        assertFalse(result.insideVideoContent);
        assertFalse(result.hasVideoCoordinates());
        assertTrue(Float.isNaN(result.videoX));
        assertTrue(Float.isNaN(result.normalizedY));
        // 点了黑边不该让上层去取帧
        assertEquals(0, source.captureCount);
        assertEquals(0, recognizer.recognizeCount);
    }

    @Test
    public void onTouchDownWithUnknownGeometryIsInvalidGeometry() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        CountingRecognizer recognizer = new CountingRecognizer();

        geometry.geometry = null;
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY,
                controller(source, geometry, recognizer).onTouchDown(1f, 1f, 0L).status);

        geometry.geometry = VideoGeometry.EMPTY;
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY,
                controller(source, geometry, recognizer).onTouchDown(1f, 1f, 0L).status);

        assertEquals(0, source.captureCount);
    }

    @Test
    public void onTouchDownIsNotGatedByConfigEnabled() {
        // 坐标映射无副作用：关闭目标选择时仍可用于调试/埋点，因此不受 enabled 影响
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        geometry.geometry = VideoGeometry.createWithScale(100, 100, 100, 100, VideoScaleType.FIT_CENTER);
        CountingRecognizer recognizer = new CountingRecognizer();

        TargetSelectionConfig disabled = TargetSelectionConfig.defaults().toBuilder().enabled(false).build();
        VideoPointMappingResult result = controller(source, geometry, recognizer, disabled)
                .onTouchDown(25f, 75f, 0L);

        assertSame(VideoPointMappingStatus.SUCCESS, result.status);
        assertEquals(25f, result.videoX, EPS);
        assertEquals(75f, result.videoY, EPS);
    }

    @Test
    public void convenienceOverloadMatchesTheRequestOverload() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        geometry.geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP);
        CountingRecognizer recognizer = new CountingRecognizer();
        TargetSelectionController controller = controller(source, geometry, recognizer);

        VideoPointMappingResult viaFloats = controller.onTouchDown(30f, 70f, 123L);
        VideoPointMappingResult viaRequest = controller.onTouchDown(TouchSelectionRequest.down(30f, 70f, 123L));

        assertSame(viaRequest.status, viaFloats.status);
        assertEquals(viaRequest.videoX, viaFloats.videoX, EPS);
        assertEquals(viaRequest.videoY, viaFloats.videoY, EPS);
        assertEquals(viaRequest.normalizedX, viaFloats.normalizedX, EPS);
    }

    @Test
    public void nullRequestIsRejected() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        geometry.geometry = VideoGeometry.createWithScale(100, 100, 100, 100, VideoScaleType.FIT_CENTER);
        CountingRecognizer recognizer = new CountingRecognizer();

        try {
            controller(source, geometry, recognizer).onTouchDown(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：缺参数要在入口处失败，而不是等到点击时才 NPE
        }
    }

    @Test
    public void mappingIsConsistentWithTheFullSelectionPipeline() {
        CountingFrameSource source = new CountingFrameSource();
        FixedGeometryProvider geometry = new FixedGeometryProvider();
        // 上下各 25px 黑边的几何：同一坐标下两个入口对"是否在画面内"的判断必须一致
        geometry.geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);
        CountingRecognizer recognizer = new CountingRecognizer();

        TargetSelectionController controller = controller(source, geometry, recognizer);

        // 黑边：映射层给 OUTSIDE_VIDEO，完整管线给 OUTSIDE_VIDEO（且都不取帧）
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, controller.onTouchDown(50f, 5f, 0L).status);
        assertEquals(TargetSelectionStatus.OUTSIDE_VIDEO,
                controller.select(TargetSelectionRequest.of(50f, 5f, 0L)).status);
        assertEquals(0, source.captureCount);

        // 画面内：映射层给出坐标，完整管线的 touchVideo 与之相同
        VideoPointMappingResult mapped = controller.onTouchDown(25f, 50f, 0L);
        assertSame(VideoPointMappingStatus.SUCCESS, mapped.status);
        assertEquals(50f, mapped.videoX, EPS);

        // 完整管线这次会走到取帧（fake 返回 null → NO_FRAME），但坐标语义已由映射层确定
        assertEquals(TargetSelectionStatus.NO_FRAME,
                controller.select(TargetSelectionRequest.of(25f, 50f, 0L)).status);
        assertEquals(1, source.captureCount);
    }
}
