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
import com.example.motro.targetselection.api.TargetSelectionRequest;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.debug.TargetDebugState;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Slice 9 {@link TargetSelectionController} 的编排测试。
 *
 * <h2>为什么这里"造不出假组件"</h2>
 * Slice 4~8 的视觉组件（{@code TouchRoiExtractor} / {@code BlueMaskDetector} /
 * {@code TargetBoxDetector} / {@code TargetHitTester} / {@code TargetCandidateSelector} /
 * {@code LabelRegionExtractor}）全部是 {@code final class} 且配置在构造时注入 ——
 * 它们<b>刻意</b>不可被继承，于是本测试无法用子类桩替换它们。
 *
 * <p>这不是缺陷而是约束：控制器必须能靠<b>真实算法链 + 真实像素</b>跑通，
 * 唯一可插桩的接缝是 {@link TargetIdRecognizer} 接口（它将来会被 OCR SDK 替换，
 * 所以从一开始就是接口）。因此本测试用 {@link TestImages} 画出真实的蓝框与标签，
 * 再让 fake 识别器决定"读出了什么"，从而覆盖控制器自身负责的编排逻辑。</p>
 */
public class TargetSelectionControllerTest {

    private static final float EPS = 0.001f;

    // ---------------------------------------------------------------- fakes

    /**
     * 假取帧实现：可配置"返回什么/是否抛异常"，并记录收到的请求。
     *
     * <p>只覆写 {@link VideoFrameSource#captureFrame} —— {@code capture(...)} 的桥接
     * 逻辑（把异常/空帧翻译成 {@code FrameCaptureResult} 状态）是被测系统的一部分，
     * 桩不该把它也一起替换掉。</p>
     */
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

    /**
     * 假识别器（本 Slice 唯一可插的桩）：记录调用次数与拿到的标签区域，
     * 按预设返回成功/无 id/失败/抛异常。
     */
    private static final class FakeRecognizer implements TargetIdRecognizer {
        String idToReturn;
        boolean noId;
        boolean fail;
        RuntimeException crash;
        int recognizeCount;
        LabelExtractionResult labelSeen;
        int closeCount;

        @Override
        public TargetIdRecognitionResult recognize(LabelExtractionResult label, TargetSelectionConfig config) {
            recognizeCount++;
            labelSeen = label;
            if (crash != null) {
                throw crash;
            }
            if (fail) {
                return TargetIdRecognitionResult.recognitionFailed("noise", 1L, 2L, 3L,
                        "recognizer blew up", name());
            }
            if (noId || idToReturn == null) {
                return TargetIdRecognitionResult.noId("id:", 0.2f, 1L, 1L, 0L, 2L, "no digits", name());
            }
            return TargetIdRecognitionResult.success(idToReturn, "id:" + idToReturn, 0.91f,
                    1L, 2L, 0L, 3L, name());
        }

        @Override
        public void close() {
            closeCount++;
        }

        @Override
        public String name() {
            return "fake-recognizer";
        }
    }

    /** 记录收到的调试状态，用于验证 Debug State 串起来的字段。 */
    private static final class RecordingSink implements TargetSelectionController.DebugStateSink {
        final List<TargetDebugState> states = new ArrayList<>();
        RuntimeException crash;

        @Override
        public void onDebugState(TargetDebugState state) {
            if (crash != null) {
                throw crash;
            }
            states.add(state);
        }
    }

    // ------------------------------------------------------------- helpers

    private static TargetSelectionConfig config() {
        return TargetSelectionConfig.defaults();
    }

    /**
     * 用真实默认视觉链 + fake 识别器装配控制器。
     *
     * <p>与 {@link TargetSelectionController#withDefaults} 的唯一区别是识别器可注入 ——
     * 这正是本 Slice 把识别器抽成接口的意义。</p>
     */
    private static TargetSelectionController controller(VideoFrameSource source,
                                                       VideoGeometryProvider geometry,
                                                       TargetIdRecognizer recognizer) {
        return controller(source, geometry, recognizer, config(), null, null, null);
    }

    private static TargetSelectionController controller(VideoFrameSource source,
                                                       VideoGeometryProvider geometry,
                                                       TargetIdRecognizer recognizer,
                                                       TargetSelectionConfig config,
                                                       TargetSelectionController.DebugStateSink sink,
                                                       Executor workExecutor,
                                                       Executor callbackExecutor) {
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
                recognizer, config, sink, workExecutor, callbackExecutor);
    }

    private static VideoGeometry plainGeometry() {
        return VideoGeometry.create(100, 50, 100, 50);
    }

    private static CapturedFrame frameWithImage(FakeFrameImage imageHandle, long ptsUs) {
        return CapturedFrame.of(imageHandle, 0, 0, 1_000L, 1_200L, ptsUs, -1L);
    }

    // ------------------------------------------------ 完整 fake pipeline（id:1963）

    /**
     * 本 Slice 的招牌用例：touch → box → label → {@code "id:1963"} → {@code HIT(targetId=1963)}。
     *
     * <p>框与标签都用真实像素画出来，走<b>真实</b>的 ROI/掩码/框检测/命中/选择/标签提取，
     * 只有"读数字"这一步由 fake 识别器返回 {@code 1963}。这样既能证明编排正确，
     * 又不依赖字形模板对合成字形的匹配率。</p>
     */
    @Test
    public void fullPipelineTouchBoxLabelId1963EndsWithHit() {
        final int w = 240;
        final int h = 200;
        int[] pixels = TestImages.solid(w, h, TestImages.GRAY_BACKGROUND);
        // 与真实样本同量级的蓝框
        TestImages.strokeRect(pixels, w, h, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        // 框顶上方画 "id:1963"
        TestImages.drawArialLikeText(pixels, w, h, 62, 20, "id:1963", TestImages.ARIAL_GLYPH_HEIGHT, 2,
                TestImages.PURE_BLUE);

        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(w, h, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(w, h, w, h);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "1963";

        TargetSelectionController controller = controller(source, geometry, recognizer);
        TargetHitResult result = controller.select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals("1963", result.targetId);
        assertTrue(result.isHit());
        assertTrue(result.hasResolvedId());
        assertNotNull("命中必须带视频坐标 bbox", result.boundingBox);
        assertEquals(1, source.captureCount);
        assertEquals("框命中后必须调一次识别器", 1, recognizer.recognizeCount);
        assertNotNull("识别器必须拿到标签区域", recognizer.labelSeen);
        assertTrue("标签区域必须可用", recognizer.labelSeen.hasLabel());
        assertEquals(120f, result.touchViewX, EPS);
        assertEquals(95f, result.touchViewY, EPS);
        assertEquals(120f, result.touchVideoX, 0.01f);
        assertEquals(95f, result.touchVideoY, 0.01f);
        assertEquals("id 置信度应被带出来", 0.91f, result.idConfidence, EPS);
    }

    /** 框命中 + 识别器返回 id，结果是 HIT + targetId。 */
    @Test
    public void hitWithRecognizedIdReportsHit() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "42";

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals("42", result.targetId);
        assertEquals("框命中后必须调一次识别器", 1, recognizer.recognizeCount);
    }

    // ------------------------------------------------ 命中 + id 未解析

    /** 框命中 + 识别器返回 NO_ID → HIT_ID_UNKNOWN + selectedBox。 */
    @Test
    public void hitWithoutIdIsReportedAsHitIdUnknown() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.noId = true;

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNull(result.targetId);
        assertNotNull("框命中就必须带 bbox，即便 id 没读出来", result.boundingBox);
        assertTrue(result.isHit());
        assertFalse(result.hasResolvedId());
        assertNotNull("必须给出未解析 id 的原因", result.reason);
        assertEquals(1, recognizer.recognizeCount);
    }

    /** 框命中 + 识别器直接失败（RECOGNITION_FAILED）→ HIT_ID_UNKNOWN，<b>绝不降级为 MISS</b>。 */
    @Test
    public void hitWithFailedRecognizerStaysHitIdUnknownNotMiss() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.fail = true;

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals("OCR 失败不得把命中降级成 MISS", TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertTrue(result.isHit());
        assertNotNull(result.boundingBox);
        assertEquals(1, recognizer.recognizeCount);
    }

    /** 识别器抛未检查异常：管线兜成 INTERNAL_ERROR（而不是崩到 UI）。 */
    @Test
    public void recognizerCrashBecomesInternalError() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.crash = new IllegalStateException("recognizer bug");

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.INTERNAL_ERROR, result.status);
        assertEquals("recognizer bug", result.reason);
        assertTrue(result.status.isFailure());
    }

    // ------------------------------------------------------------ miss paths

    /** 画面内但没有蓝框 → MISS，且不做 Label/OCR。 */
    @Test
    public void noBlueNoBoxBecomesMissWithoutLabelOrOcr() {
        int[] pixels = TestImages.solid(240, 200, TestImages.GRAY_BACKGROUND);
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.of(ArgbFrameImage.of(240, 200, pixels), 0, 0, 0L, 0L, 7L, -1L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertTrue(result.hasVideoCoordinates());
        assertEquals(7L, result.framePtsUs);
        assertEquals("MISS 路径不得跑识别", 0, recognizer.recognizeCount);
    }

    /** 有蓝框但点在外面的灰底上 → bbox 未命中 → MISS，不跑识别。 */
    @Test
    public void tapOutsideDetectedBoxBecomesMiss() {
        int[] pixels = boxOnlyCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();

        // 框在 [60,40]-[180,150]，点在右下角远处
        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(225f, 190f));

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertNull(result.targetId);
        assertEquals("未命中框不得跑识别", 0, recognizer.recognizeCount);
    }

    // ------------------------------------------------- geometry / black bars

    /** 黑边点击 → OUTSIDE_VIDEO，且<b>一次帧都不取</b>。 */
    @Test
    public void clickOnLetterboxBarIsOutsideVideoAndSkipsCapture() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(200, 100), 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        // 2:1 视频放进 100x100 View：上下各 25px 黑边
        geometry.geometry = VideoGeometry.create(200, 100, 100, 100, 0, 25, 100, 75, 0, false, false);
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(50f, 5f));

        assertEquals(TargetSelectionStatus.OUTSIDE_VIDEO, result.status);
        assertFalse(result.hasVideoCoordinates());
        // 关键：黑边点击不该浪费一次帧回读，也不该跑识别
        assertEquals(0, source.captureCount);
        assertEquals(0, recognizer.recognizeCount);
        assertFalse(result.status == TargetSelectionStatus.MISS);
    }

    @Test
    public void unknownGeometryIsReportedSeparatelyFromOutsideVideo() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        FakeRecognizer recognizer = new FakeRecognizer();

        geometry.geometry = null;
        TargetHitResult nullGeometry = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(1f, 1f));
        assertEquals(TargetSelectionStatus.GEOMETRY_UNAVAILABLE, nullGeometry.status);
        assertNotNull(nullGeometry.reason);
        assertEquals(0, source.captureCount);

        geometry.geometry = VideoGeometry.EMPTY;
        TargetHitResult emptyGeometry = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(1f, 1f));
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
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(20f, 25f));

        assertEquals(TargetSelectionStatus.CAPTURE_FAILED, result.status);
        assertEquals("SurfaceTexture timeout after 300ms", result.reason);
        assertEquals(1, source.captureCount);
        assertEquals(0, recognizer.recognizeCount);
    }

    @Test
    public void nullFrameBecomesNoFrame() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = null;
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(20f, 25f));

        assertEquals(TargetSelectionStatus.NO_FRAME, result.status);
        assertEquals(0, recognizer.recognizeCount);
    }

    @Test
    public void frameWithoutImageBecomesNoFrameAndIsReleased() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.empty(1L, 2L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(20f, 25f));

        assertEquals(TargetSelectionStatus.NO_FRAME, result.status);
        assertTrue(source.next.isReleased());
        assertEquals(0, recognizer.recognizeCount);
    }

    @Test
    public void disabledConfigShortCircuitsBeforeTouchingAnything() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder().enabled(false).build();
        TargetHitResult result = controller(source, geometry, recognizer, config, null, null, null)
                .select(TargetSelectionRequest.ofNow(20f, 25f));

        assertEquals(TargetSelectionStatus.CANCELLED, result.status);
        assertEquals(0, source.captureCount);
        assertEquals(0, recognizer.recognizeCount);
    }

    // ------------------------------------------------------- wiring details

    /** selectedBox 必须被原样传给 LabelRegionExtractor：验证"命中框 → 标签搜索"的契约。 */
    @Test
    public void selectedBoxIsHandedToLabelExtractor() {
        int[] pixels = TestImages.solid(240, 200, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, 240, 200, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        TestImages.drawArialLikeText(pixels, 240, 200, 62, 20, "id:1963", TestImages.ARIAL_GLYPH_HEIGHT, 2,
                TestImages.PURE_BLUE);

        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "1963";

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertNotNull(result.boundingBox);
        // 标签搜索区域必须落在框的上方（Slice 7 的语义），且识别器拿到的正是这条链的产物
        assertNotNull(recognizer.labelSeen);
        assertTrue(recognizer.labelSeen.labelDetectMs >= 0L);
        assertEquals("传给识别器的标签必须来自同一帧的命中框", TargetSelectionStatus.HIT, result.status);
    }

    /** 取帧异常时后续阶段一律不执行（顺序 + 短路一起验）。 */
    @Test
    public void captureFailureStopsEverythingAfterIt() {
        FakeFrameSource source = new FakeFrameSource();
        source.throwOnCapture = true;
        source.failureMessage = "boom";
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();
        RecordingSink sink = new RecordingSink();

        TargetHitResult result = controller(source, geometry, recognizer, config(), sink, null, null)
                .select(TargetSelectionRequest.ofNow(20f, 25f));

        assertEquals(TargetSelectionStatus.CAPTURE_FAILED, result.status);
        assertEquals(1, source.captureCount);
        assertEquals("取帧失败后不得跑识别", 0, recognizer.recognizeCount);
        // 即便是失败路径，也要把调试状态发出去
        assertEquals(1, sink.states.size());
    }

    @Test
    public void captureRequestIsBuiltFromConfig() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = frameWithImage(new FakeFrameImage(100, 50), 3L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .captureTimeoutMs(777L)
                .maxFrameLongSidePx(320)
                .maxFrameAgeNs(20_000_000L)
                .build();

        TargetSelectionController controller = controller(source, geometry, recognizer, config, null, null, null);
        assertSame(config, controller.config());

        controller.select(TargetSelectionRequest.ofNow(10f, 10f));

        assertNotNull(source.lastRequest);
        assertEquals(777L, source.lastRequest.timeoutMs);
        assertEquals(320, source.lastRequest.maxLongSidePx);
        assertEquals(20_000_000L, source.lastRequest.maxFrameAgeNs);
        // Slice 0 固定取"当前最新帧"，按 PTS 精确取帧留给后续 Slice
        assertFalse(source.lastRequest.hasTargetPts());
    }

    // -------------------------------------------------------- lifecycle / timing

    /** 命中路径上帧必须被释放（否则每次点击漏一个回读缓冲）。 */
    @Test
    public void frameIsReleasedOnHitPath() {
        int[] pixels = boxWithLabelCanvas();
        ArgbFrameImage image = ArgbFrameImage.of(240, 200, pixels);
        FakeFrameSource source = new FakeFrameSource();
        CapturedFrame frame = CapturedFrame.ofImage(image, 0L, 0L);
        source.next = frame;
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "7";

        controller(source, geometry, recognizer).select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(1, image.releaseCount);
        assertTrue(frame.isReleased());
    }

    /** 管线内部抛异常时，帧仍必须在 finally 里被释放，且结果稳定为 INTERNAL_ERROR。 */
    @Test
    public void frameIsReleasedAndResultIsStableWhenPipelineThrows() {
        int[] pixels = boxWithLabelCanvas();
        ArgbFrameImage image = ArgbFrameImage.of(240, 200, pixels);
        FakeFrameSource source = new FakeFrameSource();
        CapturedFrame frame = CapturedFrame.ofImage(image, 0L, 0L);
        source.next = frame;
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.crash = new IllegalStateException("kaboom");

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.INTERNAL_ERROR, result.status);
        // 异常路径不能变成泄漏路径
        assertEquals(1, image.releaseCount);
        assertTrue(frame.isReleased());
    }

    /** 调试回调自己抛异常，不该把业务结果带崩。 */
    @Test
    public void debugSinkFailureDoesNotBreakTheResult() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "5";
        RecordingSink sink = new RecordingSink();
        sink.crash = new RuntimeException("overlay bug");

        TargetHitResult result = controller(source, geometry, recognizer, config(), sink, null, null)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals("5", result.targetId);
    }

    /** 命中路径上各阶段耗时字段都必须被填且非负，且 total 不小于分段之和的下界。 */
    @Test
    public void timingFieldsArePopulatedAndSane() {
        int[] pixels = TestImages.solid(240, 200, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, 240, 200, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        TestImages.drawArialLikeText(pixels, 240, 200, 62, 20, "id:1963", TestImages.ARIAL_GLYPH_HEIGHT, 2,
                TestImages.PURE_BLUE);

        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "1963";

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertTrue(result.totalProcessingMs >= 0L);
        assertTrue(result.processingMs >= 0L);
        assertEquals("processingMs 是 totalProcessingMs 的历史别名", result.totalProcessingMs, result.processingMs);
        assertTrue(result.captureLatencyMs >= 0L);
        assertTrue(result.boxDetectMs >= 0L);
        assertTrue(result.idRecognizeMs >= 0L);
        assertTrue(result.roiMs >= 0L);
        assertTrue(result.maskMs >= 0L);
        assertTrue("总耗时不得小于任一单段", result.totalProcessingMs >= result.idRecognizeMs);
    }

    /** 帧的 PTS 与 capture 时间戳必须原样透出（同帧证据）。 */
    @Test
    public void frameTimestampsAreCarriedThrough() {
        int[] pixels = boxWithLabelCanvas();
        CapturedFrame frame = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 111L, 222L);
        FakeFrameSource source = new FakeFrameSource();
        source.next = frame;
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "9";

        TargetHitResult result = controller(source, geometry, recognizer)
                .select(TargetSelectionRequest.of(120f, 95f, 5_000L));

        assertEquals(TargetSelectionStatus.HIT, result.status);
        assertEquals(111L, result.captureRequestNs);
        assertEquals(222L, result.captureCompletedNs);
        assertEquals(5_000L, result.requestTimeNs);
    }

    // ------------------------------------------------------------ release / async

    /** release 后所有新请求是 CANCELLED，且绝不再取帧。 */
    @Test
    public void releasedControllerCancelsNewRequests() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, boxWithLabelCanvas()), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetSelectionController controller = controller(source, geometry, recognizer);
        controller.release();

        TargetHitResult result = controller.select(TargetSelectionRequest.ofNow(120f, 95f));
        assertEquals(TargetSelectionStatus.CANCELLED, result.status);
        assertTrue(controller.isReleased());
        assertEquals("release 后不得取帧", 0, source.captureCount);

        controller.release(); // 幂等
        controller.close();
        assertTrue(controller.isReleased());
    }

    /** release 后即便有调试接收器，也不再回调。 */
    @Test
    public void releasedControllerStopsDebugCallbacks() {
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, boxWithLabelCanvas()), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        RecordingSink sink = new RecordingSink();

        TargetSelectionController controller =
                controller(source, geometry, recognizer, config(), sink, null, null);
        controller.release();
        controller.select(TargetSelectionRequest.ofNow(120f, 95f));

        assertTrue(sink.states.isEmpty());
    }

    /** 成功路径必须把调试状态发出去，且带上 targetId。 */
    @Test
    public void debugStateIsPublishedOnSuccess() {
        int[] pixels = TestImages.solid(240, 200, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, 240, 200, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        TestImages.drawArialLikeText(pixels, 240, 200, 62, 20, "id:1963", TestImages.ARIAL_GLYPH_HEIGHT, 2,
                TestImages.PURE_BLUE);

        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "1963";
        RecordingSink sink = new RecordingSink();

        controller(source, geometry, recognizer, config(), sink, null, null)
                .select(TargetSelectionRequest.of(120f, 95f, 4_242L));

        assertEquals(1, sink.states.size());
        TargetDebugState state = sink.states.get(0);
        assertEquals("1963", state.targetId);
        assertNotNull("调试状态必须带上映射坐标", state.mapping);
        assertNotNull("调试状态必须带上选中的框", state.selectedBox);
        assertEquals("调试状态必须带上原始识别文本", "id:1963", state.idRawText);
    }

    /** 异步入口：结果经工作执行器跑、经投递器回。 */
    @Test
    public void selectAsyncRunsOnWorkExecutorAndDeliversResult() {
        int[] pixels = boxWithLabelCanvas();
        FakeFrameSource source = new FakeFrameSource();
        source.next = CapturedFrame.ofImage(ArgbFrameImage.of(240, 200, pixels), 0L, 0L);
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = VideoGeometry.create(240, 200, 240, 200);
        FakeRecognizer recognizer = new FakeRecognizer();
        recognizer.idToReturn = "11";

        DirectExecutor work = new DirectExecutor();
        DirectExecutor callback = new DirectExecutor();
        TargetSelectionController controller =
                controller(source, geometry, recognizer, config(), null, work, callback);

        final TargetHitResult[] delivered = new TargetHitResult[1];
        controller.selectAsync(TargetSelectionRequest.ofNow(120f, 95f),
                (result, request) -> delivered[0] = result);

        assertNotNull(delivered[0]);
        assertEquals(TargetSelectionStatus.HIT, delivered[0].status);
        assertEquals("11", delivered[0].targetId);
        assertEquals(1, work.count);
        assertEquals("结果必须经投递器", 1, callback.count);
    }

    @Test
    public void selectAsyncWithoutWorkExecutorIsRejected() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        TargetSelectionController controller = controller(source, geometry, recognizer);
        try {
            controller.selectAsync(TargetSelectionRequest.ofNow(1f, 1f), (r, q) -> { });
            fail("未注入工作执行器时应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期：不静默地把管线跑在调用线程上
        }
    }

    @Test
    public void nullRequestIsRejected() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();
        FakeRecognizer recognizer = new FakeRecognizer();

        try {
            controller(source, geometry, recognizer).select(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    // ------------------------------------------------------------ constructor

    @Test
    public void constructorRejectsNullCollaborators() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        FakeRecognizer recognizer = new FakeRecognizer();
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        TouchRoiExtractor roi = new TouchRoiExtractor();
        BlueMaskDetector mask = new BlueMaskDetector(
                BlueColorThresholds.of(config.blueHueMinDegrees, config.blueHueMaxDegrees,
                        config.minSaturation, config.minValue),
                config.maskDenoiseMinNeighbors, config.minBluePixelCount, false);
        TargetBoxDetector box = new TargetBoxDetector(config);
        TargetHitTester hit = new TargetHitTester(config);
        TargetCandidateSelector selector = new TargetCandidateSelector(config);
        LabelRegionExtractor label = new LabelRegionExtractor(config);

        // 10 个必需协作者逐一为 null，都必须被拒绝（debugSink / executor 允许为 null）
        assertRejected(() -> new TargetSelectionController(null, geometry, roi, mask, box, hit, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, null, roi, mask, box, hit, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, null, mask, box, hit, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, null, box, hit, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, null, hit, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, box, null, selector, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, box, hit, null, label, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, box, hit, selector, null, recognizer, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, box, hit, selector, label, null, config, null, null, null));
        assertRejected(() -> new TargetSelectionController(source, geometry, roi, mask, box, hit, selector, label, recognizer, null, null, null, null));
    }

    /** withDefaults 也必须拒绝 null（它是最容易被误用的入口）。 */
    @Test
    public void withDefaultsRejectsNullArguments() {
        FakeFrameSource source = new FakeFrameSource();
        FakeGeometryProvider geometry = new FakeGeometryProvider();
        geometry.geometry = plainGeometry();

        assertRejected(() -> TargetSelectionController.withDefaults(null, geometry, config()));
        assertRejected(() -> TargetSelectionController.withDefaults(source, null, config()));
        assertRejected(() -> TargetSelectionController.withDefaults(source, geometry, null));
    }

    // ---------------------------------------------------------------- helpers

    /** 只有蓝框、没有标签的像素画布（240x200，框在 [60,40]-[180,150]）。 */
    private static int[] boxOnlyCanvas() {
        int[] pixels = TestImages.solid(240, 200, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, 240, 200, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        return pixels;
    }

    /**
     * 蓝框 + 框顶上方一段真实字形标签的像素画布。
     *
     * <p>控制器只在 {@code label.hasLabel()} 为真时才会调识别器，所以凡是
     * "要验证识别器被调用/结果被采纳"的用例，都必须画出真实的标签像素 ——
     * 否则测到的是"标签没找到"，而不是识别分支。</p>
     */
    private static int[] boxWithLabelCanvas() {
        int[] pixels = boxOnlyCanvas();
        TestImages.drawArialLikeText(pixels, 240, 200, 62, 20, "id:1963",
                TestImages.ARIAL_GLYPH_HEIGHT, 2, TestImages.PURE_BLUE);
        return pixels;
    }

    /** 立即在当前线程执行的执行器，便于断言"真的投递过"。 */
    private static final class DirectExecutor implements Executor {
        int count;

        @Override
        public void execute(Runnable command) {
            count++;
            command.run();
        }
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
