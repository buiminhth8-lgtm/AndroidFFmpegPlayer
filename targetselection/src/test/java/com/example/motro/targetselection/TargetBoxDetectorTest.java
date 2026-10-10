package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.vision.BlueMaskAnalyzer;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.TargetBoxAnalyzer;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetBoxDetectionStatus;
import com.example.motro.targetselection.vision.TargetBoxDetector;

import org.junit.Test;

import java.util.List;

/**
 * 矩形恢复算法（{@link TargetBoxDetector}）的行为测试。
 *
 * <p>绝大多数用例走 {@link TargetBoxAnalyzer} 的完整链路（合成 ARGB 图 → 真掩码 → 候选框），
 * 而不是手工捏一个掩码：这样测到的是<b>真实会跑的那条路</b>，
 * 顺带把「ROI 尺度 → 视频尺度」的换算也覆盖了。只有非法入参那几条才直接调检测器。</p>
 */
public class TargetBoxDetectorTest {

    private static final int IMAGE_WIDTH = 800;
    private static final int IMAGE_HEIGHT = 600;

    /** 允许 1px 的误差：框边厚度取整 + ROI 边界夹取都会带来 1px 级别的差异。 */
    private static final float EDGE_TOLERANCE = 1.5f;

    // ---------------------------------------------------------------- 正向：能找到框

    /**
     * 标准完整矩形：期望拿到 1 个候选、四边全覆盖、置信度为 1，
     * 而且恢复出的矩形与画上去的框基本重合。
     */
    @Test
    public void completeBoxIsRecoveredWithFullConfidence() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 250, 180, 4,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 325f, 240f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        assertEquals(1, result.candidateCount);
        DetectedTargetBox box = result.candidates.get(0);
        assertEquals("四边都应该达标", 4, box.presentEdges);
        assertEquals(1f, box.completeness, 0.01f);
        assertEquals(1f, box.confidence, 0.01f);
        assertFalse("框完全在 ROI 内，不该被判为截断", box.clippedByRoi);
        assertBoxNear(box, 200f, 150f, 450f, 330f);
    }

    /**
     * 断边矩形：上边线中间缺一段。覆盖率要如实反映断裂，
     * 但几何仍必须是<b>整条边</b>的范围（并集），而不是断掉的那一截。
     */
    @Test
    public void brokenTopEdgeStillRecoversTheWholeBox() {
        int[] pixels = canvas();
        // 上边线断成两截：x∈[200,300) 与 x∈[350,450)
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 100, 4, TestImages.SHIFTED_BLUE);
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 350, 150, 100, 4, TestImages.SHIFTED_BLUE);
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 326, 250, 4, TestImages.SHIFTED_BLUE);
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 4, 180, TestImages.SHIFTED_BLUE);
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 446, 150, 4, 180, TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 325f, 240f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        DetectedTargetBox box = result.candidates.get(0);
        assertBoxNear(box, 200f, 150f, 450f, 330f);
        assertTrue("断裂应当体现在完整度上，而不是几何上：" + box.debugString(),
                box.completeness < 1f && box.completeness > 0.9f);
    }

    /**
     * 「C」形（缺一整条右边线）：本 Slice 明确<b>不</b>要求闭合轮廓，
     * 三条边齐全就该被判成框 —— 这正是 HEVC 把一条边整条吃掉时的真实情形。
     */
    @Test
    public void threeSidedBoxIsAcceptedWithoutClosedContour() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 250, 180, 4,
                TestImages.SHIFTED_BLUE);
        // 擦掉右边线（x∈[446,450)）
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 446, 150, 4, 180, TestImages.GRAY_BACKGROUND);

        TargetBoxDetectionResult result = analyze(pixels, 325f, 240f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        DetectedTargetBox box = result.candidates.get(0);
        assertEquals("应当是三条边达标", 3, box.presentEdges);
        assertEquals(0.75f, box.completeness, 0.02f);
        assertTrue(box.confidence >= TargetSelectionConfig.DEFAULT_MIN_BOX_CONFIDENCE);
    }

    /** 多个框同屏：每个真实框都应该有一个对应的候选（可能还会多出合并候选，见类注释）。 */
    @Test
    public void eachBoxOnScreenGetsItsOwnCandidate() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 240, 140, 150, 110, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 420, 150, 170, 120, 4,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 415f, 205f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        assertTrue("应当至少有 2 个候选，实际 " + result.candidateCount, result.candidateCount >= 2);
        assertHasBoxNear(result.candidates, 240f, 140f, 390f, 250f);
        assertHasBoxNear(result.candidates, 420f, 150f, 590f, 270f);
    }

    // ---------------------------------------------------------------- 反向：不该误判

    @Test
    public void plainBackgroundYieldsNoBox() {
        TargetBoxDetectionResult result = analyze(canvas(), 400f, 300f);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, result.status);
        assertEquals(0, result.candidateCount);
    }

    /**
     * 只有 {@code id:xxxx} 文字：<b>绝不能</b>被当成目标框。
     * 这是最小框高的主要用武之地 —— 文字块的"边"太短，凑不出合法矩形。
     */
    @Test
    public void idTextAloneIsNeverABox() {
        int[] pixels = canvas();
        TestImages.drawLabelGlyphs(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 300, 250, 20, 30, 6,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 350f, 265f);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, result.status);
    }

    /**
     * 一整块实心蓝色区域（蓝色车身 / 蓝屏 / 天空）不是框。
     * 挡住它的是「边线厚度上限」：实心区域在投影上是几十上百行满覆盖。
     */
    @Test
    public void solidBlueRegionIsNotABox() {
        int[] pixels = canvas();
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 200, 200, 150,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 350f, 275f);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, result.status);
    }

    @Test
    public void redBoxIsNotABox() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 250, 180, 4,
                TestImages.rgb(220, 30, 30));

        // 红色根本进不了蓝色掩码，所以是"没有框"而不是"有框但低分"
        assertEquals(TargetBoxDetectionStatus.NO_BOX, analyze(pixels, 325f, 240f).status);
    }

    @Test
    public void boxSmallerThanTheMinimumSizeIsRejected() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 400, 300, 30, 25, 4,
                TestImages.SHIFTED_BLUE);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, analyze(pixels, 415f, 312f).status);
    }

    @Test
    public void extremeAspectRatioIsRejected() {
        int[] pixels = canvas();
        // 350x50 → 长宽比 7，超过默认上限 5
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 225, 250, 350, 50, 4,
                TestImages.SHIFTED_BLUE);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, analyze(pixels, 400f, 275f).status);
    }

    /**
     * 单条竖线：只有一条边，配不出框。这保证"点击落在一条无关蓝线上"不会变成命中。
     */
    @Test
    public void singleLineIsNotABox() {
        int[] pixels = canvas();
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 400, 200, 4, 200,
                TestImages.SHIFTED_BLUE);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, analyze(pixels, 402f, 300f).status);
    }

    /**
     * 两条平行横线（无竖边）：覆盖率均值 0.5、达标边数 2 → 置信度 0.25，
     * 低于默认门槛 0.3，必须被挡住（否则文字下划线、窗棂之类会变成"框"）。
     */
    @Test
    public void twoParallelLinesAreNotABox() {
        int[] pixels = canvas();
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 150, 250, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 326, 250, 4,
                TestImages.SHIFTED_BLUE);

        assertEquals(TargetBoxDetectionStatus.NO_BOX, analyze(pixels, 375f, 240f).status);
    }

    // ---------------------------------------------------------------- ROI 边界与被截断的框

    /**
     * 目标框被 ROI 边界截断：右侧竖线落在 ROI 之外，只能靠上下两条边 + 左边线恢复。
     * 几何只能给到 ROI 边界，因此必须如实标记 {@code clippedByRoi}。
     */
    @Test
    public void boxCrossingTheRoiEdgeIsFlaggedAsClipped() {
        int[] pixels = canvas();
        // 真实框宽 300，但点击点的 ROI 只覆盖到 x=500
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 180, 300, 220, 4,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 300f, 280f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        DetectedTargetBox box = result.candidates.get(0);
        assertTrue("被 ROI 边界截断时必须标记出来：" + box.debugString(), box.clippedByRoi);
        assertEquals("左边线应在 250 附近", 250f, box.rectInVideo.left, EDGE_TOLERANCE);
        assertEquals("右边线只能给到 ROI 边界 500", 500f, box.rectInVideo.right, EDGE_TOLERANCE);
        assertEquals("上下边线仍然是准的", 180f, box.rectInVideo.top, EDGE_TOLERANCE);
        assertEquals(400f, box.rectInVideo.bottom, EDGE_TOLERANCE);
    }

    /** ROI 与视频不是 1:1 时（帧被缩小），恢复出的视频坐标必须跟着放大，不能被当成原始像素。 */
    @Test
    public void candidateCoordinatesFollowTheRoiScale() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 250, 180, 4,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 325f, 240f);
        DetectedTargetBox box = result.candidates.get(0);

        // 没有几何时帧 == 视频，ROI 的缩放系数为 1，所以两边应当一致
        assertEquals(box.rectInRoi.width(), box.rectInVideo.width(), 1.5f);
        assertEquals(box.rectInRoi.height(), box.rectInVideo.height(), 1.5f);
    }

    // ---------------------------------------------------------------- 限额与确定性

    @Test
    public void candidateCountNeverExceedsTheConfiguredCap() {
        int[] pixels = manyBoxes();
        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .maxCandidateCount(2).build();

        TargetBoxDetectionResult result = new TargetBoxAnalyzer(config).analyze(frame(pixels), null, 325f, 225f);

        assertTrue("候选数必须被压在上限内，实际 " + result.candidateCount, result.candidateCount <= 2);
        assertTrue("至少应当找到一些框，否则这个用例没测到上限", result.candidateCount >= 1);
    }

    /** 上限确实在起作用：放宽上限后能拿到的候选不会更少。 */
    @Test
    public void aLargerCapRevealsMoreCandidates() {
        int[] pixels = manyBoxes();
        TargetBoxDetectionResult tight = new TargetBoxAnalyzer(
                TargetSelectionConfig.defaults().toBuilder().maxCandidateCount(2).build())
                .analyze(frame(pixels), null, 325f, 225f);
        TargetBoxDetectionResult loose = new TargetBoxAnalyzer(
                TargetSelectionConfig.defaults().toBuilder().maxCandidateCount(16).build())
                .analyze(frame(pixels), null, 325f, 225f);

        assertTrue("放宽上限后候选数不应减少：" + tight.candidateCount + " -> " + loose.candidateCount,
                loose.candidateCount >= tight.candidateCount);
        assertTrue(loose.candidateCount > 2);
    }

    @Test
    public void detectionIsDeterministic() {
        int[] pixels = manyBoxes();
        TargetBoxAnalyzer analyzer = new TargetBoxAnalyzer(TargetSelectionConfig.defaults());

        TargetBoxDetectionResult first = analyzer.analyze(frame(pixels), null, 325f, 225f);
        TargetBoxDetectionResult second = analyzer.analyze(frame(pixels), null, 325f, 225f);

        assertEquals(first.candidateCount, second.candidateCount);
        for (int i = 0; i < first.candidateCount; i++) {
            assertEquals(first.candidates.get(i).debugString(), second.candidates.get(i).debugString());
        }
    }

    /**
     * id 文字贴在框沿上（真实推流端的常见样式）时，<b>不得</b>多造出候选框。
     *
     * <p>这一条盯的是一个很隐蔽的误检机制：文字每一列同时穿过框的<b>上、下两条边线</b>，
     * 于是该列的蓝像素数被"借"到了垂直阈值以上，而它的跨度并集又被拉到整框高度 ——
     * 一列文字就此<b>冒充</b>成一条贯穿全框的竖边，与真竖边配对后造出若干"截断的子框"。
     * 子框比真框小，一旦点击点偏离真框中心，"面积更小者优先"就会让它胜出，
     * 下游拿这个框去取标签区域就会取错。</p>
     *
     * <p>挡不住它的原因是厚度判据失灵：那些文字列只有几像素宽，比真框线还细。
     * 真正把它们与线区分开的是"线是实的"（见 {@code minEdgeSolidity}）。</p>
     */
    @Test
    public void labelTextTouchingTheBoxEdgeDoesNotCreateExtraCandidates() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 120, 40, 300, 240, 4,
                TestImages.SHIFTED_BLUE);
        // 紧贴下边线之下的一行"文字"（字高 26 > 垂直阈值 20 - 8，足够借到像素）
        TestImages.drawLabelGlyphs(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 140, 288, 20, 26, 6,
                TestImages.SHIFTED_BLUE);

        TargetBoxDetectionResult result = analyze(pixels, 270f, 160f);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, result.status);
        assertEquals("文字只应出现在覆盖率里，不该多造出候选框：" + dump(result),
                1, result.candidateCount);
        assertBoxNear(result.candidates.get(0), 120f, 40f, 420f, 280f);
    }

    /** 把候选列表摊平进断言消息，失败时不必再跑一次才知道多出来的是什么。 */
    private static String dump(TargetBoxDetectionResult result) {
        final StringBuilder b = new StringBuilder("candidates=").append(result.candidateCount);
        for (DetectedTargetBox box : result.candidates) {
            b.append('\n').append("  ").append(box.debugString());
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- 单入口重载（复用同一份掩码）

    /**
     * {@code analyze(BlueMaskResult, x, y)} 必须与走完整链路的
     * {@code analyze(frame, geometry, x, y)} 得出<b>相同</b>的候选与命中。
     *
     * <p>这条重载存在的唯一理由是复用掩码（不重复回读像素），它绝不能顺手改变判定结果 ——
     * 否则"探针上看到的"与"正式路径算的"就会分叉，而那正是调试工具最不该出现的事。</p>
     */
    @Test
    public void maskOverloadMatchesTheFullChain() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 200, 150, 250, 180, 4,
                TestImages.SHIFTED_BLUE);

        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        CapturedFrame captured = frame(pixels);

        // 完整链路
        TargetBoxDetectionResult viaChain = new TargetBoxAnalyzer(config)
                .analyze(captured, null, 325f, 240f);
        // 复用掩码
        BlueMaskResult mask = new BlueMaskAnalyzer(config).analyze(captured, null, 325f, 240f);
        TargetBoxDetectionResult viaMask = new TargetBoxAnalyzer(config)
                .analyze(mask, 325f, 240f);

        assertEquals(viaChain.status, viaMask.status);
        assertEquals(viaChain.candidateCount, viaMask.candidateCount);
        assertEquals(viaChain.isHit(), viaMask.isHit());
        for (int i = 0; i < viaChain.candidateCount; i++) {
            assertEquals(viaChain.candidates.get(i).debugString(),
                    viaMask.candidates.get(i).debugString());
        }
        // totalMs 两条路径语义一致：都覆盖"掩码 + 检测 + 命中"，因此都不小于掩码耗时
        assertTrue("totalMs 不应小于掩码耗时：" + viaMask.totalMs + " < " + mask.processingMs,
                viaMask.totalMs >= mask.processingMs);
    }

    @Test
    public void maskOverloadRejectsNullMask() {
        try {
            new TargetBoxAnalyzer(TargetSelectionConfig.defaults()).analyze(null, 10f, 10f);
            throw new AssertionError("maskResult 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    // ---------------------------------------------------------------- 入参非法

    @Test
    public void nullMaskResultIsInvalidInput() {
        TargetBoxDetectionResult result = new TargetBoxDetector(TargetSelectionConfig.defaults())
                .detect(null);
        assertEquals(TargetBoxDetectionStatus.INVALID_INPUT, result.status);
        assertEquals(0, result.candidateCount);
        assertNotNull(result.candidates);
    }

    /** 取不到像素时没有 ROI，检测器必须报 INVALID_MASK 而不是给出一个空结果。 */
    @Test
    public void maskWithoutRoiIsInvalidMask() {
        ArgbFrameImage image = ArgbFrameImage.of(IMAGE_WIDTH, IMAGE_HEIGHT, canvas());
        image.readoutSupported = false;
        CapturedFrame captured = CapturedFrame.ofImage(image, 0L, 0L);

        BlueMaskResult mask = new BlueMaskAnalyzer(TargetSelectionConfig.defaults())
                .analyze(captured, null, 400f, 300f);
        assertFalse("前置条件：这次分析应当拿不到 ROI", mask.hasRoi());

        TargetBoxDetectionResult result = new TargetBoxDetector(TargetSelectionConfig.defaults())
                .detect(mask);
        assertEquals(TargetBoxDetectionStatus.INVALID_MASK, result.status);
    }

    // ---------------------------------------------------------------- 辅助

    private static int[] canvas() {
        return TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
    }

    /** 在 ROI 内塞 5 个小框，用来测候选上限。 */
    private static int[] manyBoxes() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 150, 60, 50, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 350, 150, 60, 50, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 250, 250, 60, 50, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 350, 250, 60, 50, 4,
                TestImages.SHIFTED_BLUE);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, 300, 350, 60, 50, 4,
                TestImages.SHIFTED_BLUE);
        return pixels;
    }

    private static CapturedFrame frame(int[] pixels) {
        return CapturedFrame.ofImage(ArgbFrameImage.of(IMAGE_WIDTH, IMAGE_HEIGHT, pixels), 0L, 0L);
    }

    private static TargetBoxDetectionResult analyze(int[] pixels, float touchX, float touchY) {
        return new TargetBoxAnalyzer(TargetSelectionConfig.defaults())
                .analyze(frame(pixels), null, touchX, touchY);
    }

    /** 断言存在一个与给定视频坐标矩形基本重合的候选。 */
    private static void assertHasBoxNear(List<DetectedTargetBox> candidates,
                                        float left, float top, float right, float bottom) {
        for (DetectedTargetBox box : candidates) {
            if (Math.abs(box.rectInVideo.left - left) <= EDGE_TOLERANCE
                    && Math.abs(box.rectInVideo.top - top) <= EDGE_TOLERANCE
                    && Math.abs(box.rectInVideo.right - right) <= EDGE_TOLERANCE
                    && Math.abs(box.rectInVideo.bottom - bottom) <= EDGE_TOLERANCE) {
                return;
            }
        }
        final StringBuilder message = new StringBuilder("没有找到期望的框 [")
                .append(left).append(',').append(top).append(',').append(right).append(',')
                .append(bottom).append("]，实际候选：");
        for (DetectedTargetBox box : candidates) {
            message.append('\n').append("  ").append(box.debugString());
        }
        throw new AssertionError(message.toString());
    }

    private static void assertBoxNear(DetectedTargetBox box,
                                      float left, float top, float right, float bottom) {
        assertEquals("左边线", left, box.rectInVideo.left, EDGE_TOLERANCE);
        assertEquals("上边线", top, box.rectInVideo.top, EDGE_TOLERANCE);
        assertEquals("右边线", right, box.rectInVideo.right, EDGE_TOLERANCE);
        assertEquals("下边线", bottom, box.rectInVideo.bottom, EDGE_TOLERANCE);
    }
}
