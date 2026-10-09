package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetHitResult;
import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.api.TargetSelectionController;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.vision.TargetCandidate;
import com.example.motro.targetselection.vision.TargetProvider;

import org.junit.Test;

/**
 * 控制器最小调用链测试：FrameSource -> Geometry -> TargetProvider -> TargetHitResult。
 * 全部使用 fake 实现，不需要真机、不需要 Robolectric，也不依赖任何播放器。
 */
public class TargetSelectionControllerTest {

    private static final float EPS = 0.001f;

    // ---------------------------------------------------------------- fakes

    /** 假取帧实现：可配置“返回什么/是否抛异常”，并记录收到的请求。 */
    private static final class FakeFrameSource implements VideoFrameSource {
        CapturedFrame next;
        boolean throwOnCapture;
        String failureMessage = "capture failed";
        int captureCount;
        FrameCaptureRequest lastRequest;
        boolean closed;

        @Override
        public CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException {
            captureCount++;
            lastRequest = request;
            if (throwOnCapture) {
                throw new FrameCaptureException(failureMessage);
            }
            return next;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeGeometryProvider implements VideoGeometryProvider {
        VideoGeometry geometry;

        @Override
        public VideoGeometry currentGeometry() {
            return geometry;
        }
    }

    /** 假识别实现：记录是否被调用、是否拿到帧引用。 */
    private static final class FakeTargetProvider implements TargetProvider {
        TargetCandidate candidate;
        RuntimeException crash;
        int hitTestCount;
        CapturedFrame frameSeen;

        @Override
        public TargetCandidate hitTest(CapturedFrame frame, float videoX, float videoY) {
            hitTestCount++;
            frameSeen = frame;
            if (crash != null) {
                throw crash;
            }
            return candidate;
        }
    }

    // ------------------------------------------------------------- helpers

    private static CapturedFrame frameWithImage(FakeFrameImage imageHandle, long ptsUs) {
        return CapturedFrame.of(imageHandle, 0, 0, 1_000L, 1_200L, ptsUs, -1L);
    }

    private static TargetSelectionController controller(FakeFrameSource source,
                                                       FakeGeometryProvider geometry,
                                                       FakeTargetProvider provider) {
        return new TargetSelectionController(source, geometry, provider, TargetSelectionConfig.defaults());
    }

    private static VideoGeometry plainGeometry() {
        return VideoGeometry.create(100, 50, 100, 50);
    }

    // ------------------------------------------------------------ hit paths

    @Test
    public void fullChainHitWithKnownId() {
        FakeFrameImage image = new FakeFrameImage(100, 50);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(image, 42_000L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = TargetCandidate.of("T7", TargetBoundingBox.of(10f, 0f, 30f, 50f), 0.95f);

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals("T7", result.targetId);
        assertEquals(0.95f, result.confidence, EPS);
        assertEquals(20f, result.touchVideoX, EPS);
        assertEquals(25f, result.touchVideoY, EPS);
        assertEquals(42_000L, result.framePtsUs);
        assertTrue(result.processingMs >= 0L);
        assertNull(result.reason);

        assertEquals(1, source.captureCount);
        assertEquals(1, provider.hitTestCount);
        // 控制器必须把帧释放掉，否则每次点击都漏一个回读缓冲
        assertEquals(1, image.releaseCount);
        assertTrue(source.next.isReleased());
    }

    @Test
    public void hitWithoutIdIsReportedAsHitIdUnknown() {
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = TargetCandidate.withoutId(TargetBoundingBox.of(10f, 0f, 30f, 50f), 0.5f);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 1L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNull(result.targetId);
        assertNotNull(result.boundingBox);
        assertTrue(result.isHit());
        assertFalse(result.hasResolvedId());
    }

    @Test
    public void unknownIdDegradesToMissWhenConfigForbidsHalfResults() {
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = TargetCandidate.withoutId(TargetBoundingBox.of(10f, 0f, 30f, 50f), 0.5f);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 1L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .reportUnknownTargetId(false)
                .build();

        TargetHitResult result = new TargetSelectionController(source, geometry, provider, config)
                .selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertNull(result.boundingBox);
    }

    // ------------------------------------------------------------ miss paths

    @Test
    public void providerReturnsNullBecomesMiss() {
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = null;
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 8L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertTrue(result.hasVideoCoordinates());
        assertEquals(8L, result.framePtsUs);
    }

    @Test
    public void candidateBelowMinConfidenceBecomesMiss() {
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = TargetCandidate.of("T1", TargetBoundingBox.of(0f, 0f, 100f, 50f), 0.2f);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .minConfidence(0.8f)
                .build();

        TargetHitResult result = new TargetSelectionController(source, geometry, provider, config)
                .selectTargetAtViewPoint(50f, 25f);

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertNull(result.targetId);
    }

    // ------------------------------------------------- geometry / black bars

    @Test
    public void clickOnLetterboxBarIsOutsideVideoAndSkipsCapture() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(200, 100), 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        // 2:1 视频放进 100x100 View：上下各 25px 黑边
        geometry.geometry = VideoGeometry.create(200, 100, 100, 100, 0, 25, 100, 75, 0, false, false);
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(50f, 5f);

        assertEquals(TargetSelectionStatus.OUTSIDE_VIDEO, result.status);
        assertFalse(result.hasVideoCoordinates());
        // 关键：黑边点击不该浪费一次帧回读
        assertEquals(0, source.captureCount);
        assertEquals(0, provider.hitTestCount);
        // 也不该伪装成“分析过但没有目标”
        assertFalse(result.status == TargetSelectionStatus.MISS);
    }

    @Test
    public void unknownGeometryIsReportedSeparatelyFromOutsideVideo() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = null;
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetHitResult nullGeometry = controller(source, geometry, provider).selectTargetAtViewPoint(1f, 1f);
        assertEquals(TargetSelectionStatus.GEOMETRY_UNAVAILABLE, nullGeometry.status);
        assertNotNull(nullGeometry.reason);
        assertEquals(0, source.captureCount);

        geometry.geometry = VideoGeometry.EMPTY;
        TargetHitResult emptyGeometry = controller(source, geometry, provider).selectTargetAtViewPoint(1f, 1f);
        assertEquals(TargetSelectionStatus.GEOMETRY_UNAVAILABLE, emptyGeometry.status);
        assertEquals(0, source.captureCount);
    }

    // ----------------------------------------------------------- frame layer

    @Test
    public void captureExceptionBecomesCaptureFailedWithReason() {
        FakeFrameSource source = new FakeFrameSource();
        source.throwOnCapture = true;
        source.failureMessage = "SurfaceTexture timeout after 300ms";
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.CAPTURE_FAILED, result.status);
        assertEquals("SurfaceTexture timeout after 300ms", result.reason);
        assertEquals(1, source.captureCount);
        assertEquals(0, provider.hitTestCount);
    }

    @Test
    public void nullFrameBecomesNoFrame() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = null;
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.NO_FRAME, result.status);
        assertEquals(0, provider.hitTestCount);
    }

    @Test
    public void frameWithoutImageBecomesNoFrameAndIsReleased() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.empty(1L, 2L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetHitResult result = controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.NO_FRAME, result.status);
        assertTrue(source.next.isReleased());
        assertEquals(0, provider.hitTestCount);
    }

    @Test
    public void disabledConfigShortCircuitsBeforeTouchingAnything() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder().enabled(false).build();
        TargetHitResult result = new TargetSelectionController(source, geometry, provider, config)
                .selectTargetAtViewPoint(20f, 25f);

        assertEquals(TargetSelectionStatus.CANCELLED, result.status);
        assertEquals(0, source.captureCount);
        assertEquals(0, provider.hitTestCount);
    }

    // ------------------------------------------------------- wiring details

    @Test
    public void captureRequestIsBuiltFromConfig() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 3L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.candidate = TargetCandidate.of("T9", TargetBoundingBox.of(0f, 0f, 1f, 1f), 1f);

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .captureTimeoutMs(777L)
                .maxFrameLongSidePx(320)
                .maxFrameAgeNs(20_000_000L)
                .build();

        TargetSelectionController controller = new TargetSelectionController(source, geometry, provider, config);
        assertSame(config, controller.config());

        controller.selectTargetAtViewPoint(10f, 10f);

        assertNotNull(source.lastRequest);
        assertEquals(777L, source.lastRequest.timeoutMs);
        assertEquals(320, source.lastRequest.maxLongSidePx);
        assertEquals(20_000_000L, source.lastRequest.maxFrameAgeNs);
        // Slice 0 固定取“当前最新帧”，按 PTS 精确取帧留给后续 Slice
        assertFalse(source.lastRequest.hasTargetPts());
    }

    @Test
    public void frameIsReleasedEvenWhenProviderThrows() {
        FakeFrameImage image = new FakeFrameImage(100, 50);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(image, 1L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeTargetProvider provider = new FakeTargetProvider();
        provider.crash = new IllegalStateException("provider bug");

        try {
            controller(source, geometry, provider).selectTargetAtViewPoint(20f, 25f);
            fail("未检查异常应当向上传播，而不是被伪装成 MISS");
        } catch (IllegalStateException expected) {
            assertEquals("provider bug", expected.getMessage());
        }

        // finally 块必须已经把帧放掉，否则异常路径就是泄漏路径
        assertEquals(1, image.releaseCount);
        assertTrue(source.next.isReleased());
    }

    @Test
    public void constructorRejectsNullCollaborators() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        FakeTargetProvider provider = new FakeTargetProvider();
        TargetSelectionConfig config = TargetSelectionConfig.defaults();

        assertRejected(() -> new TargetSelectionController(null, geometry, provider, config));
        assertRejected(() -> new TargetSelectionController(source, null, provider, config));
        assertRejected(() -> new TargetSelectionController(source, geometry, null, config));
        assertRejected(() -> new TargetSelectionController(source, geometry, provider, null));
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void assertRejected(ThrowingRunnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：缺失协作者必须在构造处失败，而不是等到点击时才 NPE
        }
    }
}
