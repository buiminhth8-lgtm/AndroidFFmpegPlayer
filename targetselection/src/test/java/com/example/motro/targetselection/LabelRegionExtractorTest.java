package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.LabelCandidate;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelExtractionStatus;
import com.example.motro.targetselection.vision.LabelRegionExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LabelRegionExtractor} 的纯 JVM 单测。
 *
 * <p>所有输入都由 {@link TestImages} 的纯算术图案构造（不依赖 Android、不依赖字体），
 * 因此每个断言都落在<b>具体像素</b>上，而不是"看起来差不多"。
 * 图案用「空心小方块并排」模拟 {@code id:xxxx}：它正好是
 * <b>细笔画 + 内部镂空 + 笔画间留白</b>的组合，对"文本形态"判据的考验比实心块更严苛。</p>
 *
 * <p>几何约定（与 Slice 5 样本一致）：{@code drawbox} 的线画在 w×h 内侧，
 * 而检测器/提取器返回的矩形用"外边界"语义 —— 所以
 * {@code strokeRect(x, y, w, h)} 的最外层蓝像素是 {@code [x, x+w-1] × [y, y+h-1]}，
 * 换算成矩形就是 {@code [x, y, x+w, y+h]}。</p>
 */
public class LabelRegionExtractorTest {

    private static final int IMAGE_WIDTH = 800;
    private static final int IMAGE_HEIGHT = 600;

    private static final int BOX_LEFT = 200;
    private static final int BOX_TOP = 300;
    private static final int BOX_WIDTH = 160;
    private static final int BOX_HEIGHT = 120;
    private static final int BOX_THICKNESS = 3;

    /** 模拟 {@code id:xxxx}：4 个 8×10 的空心小方块，间距 2px。 */
    private static final int GLYPH_WIDTH = 8;
    private static final int GLYPH_HEIGHT = 10;
    private static final int GLYPH_COUNT = 4;
    private static final int GLYPH_GAP = 2;
    private static final int LABEL_WIDTH = GLYPH_COUNT * GLYPH_WIDTH + (GLYPH_COUNT - 1) * GLYPH_GAP;
    private static final int LABEL_HEIGHT = GLYPH_HEIGHT;

    private static final int BLUE = TestImages.PURE_BLUE;

    private final LabelRegionExtractor extractor =
            new LabelRegionExtractor(TargetSelectionConfig.defaults());

    // ------------------------------------------------------------------ 1. 标准场景

    @Test
    public void findsTheLabelSittingOnTopOfTheBox() {
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, labelTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertTrue(result.hasLabel());
        assertEquals(1, result.candidateCount());
        assertLabelRect(result, BOX_LEFT, labelTop, BOX_LEFT + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
        // 编号紧贴框顶：gap = 4px，排序第一优先级（距离）给出的就是这个
        assertEquals(4f, result.selectedLabel.distanceToBox, 0.01f);
        assertEquals(4f, result.selectedLabel.signedGapToBox, 0.01f);
        // 整块都落在框的水平跨度内 → 对齐度满分
        assertEquals(1f, result.selectedLabel.alignmentScore, 0.01f);
        // 一行文字在"多段性/填充率/长宽比"三项上全对 → 文本形态满分
        assertEquals(1f, result.selectedLabel.textLikeScore, 0.01f);
        assertTrue(result.selectedLabel.confidence > 0.9f);
        // 搜索区域：框顶之上 48px、左右各 16px、向下压进框顶 6px
        assertEquals(184f, result.labelSearchRegion.left, 0.01f);
        assertEquals(376f, result.labelSearchRegion.right, 0.01f);
        assertEquals(BOX_TOP - 48f, result.labelSearchRegion.top, 0.01f);
        assertEquals(BOX_TOP + 6f, result.labelSearchRegion.bottom, 0.01f);
        assertNotNull(result.debugString());
    }

    @Test
    public void labelToTheLeftOfTheBoxIsStillFound() {
        // 编号左端超出框左边界 12px，但仍在左右各 16px 的搜索余量内
        final int labelLeft = BOX_LEFT - 12;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, labelLeft, labelTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertLabelRect(result, labelLeft, labelTop, labelLeft + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
        // 26/38 落在框跨度内 —— 它仍然被选中，但对齐度如实反映了"有一截伸到框外"
        assertEquals(26f / LABEL_WIDTH, result.selectedLabel.alignmentScore, 0.01f);
    }

    @Test
    public void labelOverhangingTheRightOfTheBoxIsStillFound() {
        final int labelLeft = BOX_LEFT + BOX_WIDTH - 26;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, labelLeft, labelTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertLabelRect(result, labelLeft, labelTop, labelLeft + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
        assertEquals(26f / LABEL_WIDTH, result.selectedLabel.alignmentScore, 0.01f);
    }

    // ------------------------------------------------------------------ 2. 多候选

    @Test
    public void picksTheCandidateClosestToTheBoxTop() {
        // 两块文字：上面那块离框顶 20px、下面那块离框顶 2px。
        // 下面的先画在上面那块之后（扫描顺序是行号升序，所以"更近的那块"反而后出现）——
        // 排序若依赖遍历顺序就会选错，这里正是用来钉住"顺序无关"的。
        final int nearTop = BOX_TOP - LABEL_HEIGHT - 2;
        final int farTop = BOX_TOP - 30;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, farTop);
        drawLabel(pixels, BOX_LEFT + 20, nearTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertEquals(2, result.candidateCount());
        assertEquals(2f, result.selectedLabel.distanceToBox, 0.01f);
        assertLabelRect(result, BOX_LEFT + 20, nearTop,
                BOX_LEFT + 20 + LABEL_WIDTH, nearTop + LABEL_HEIGHT);
        // 排序是"先按距离"：远的那块必须排在后面，而不是因为先被扫到就排第一
        final LabelCandidate second = result.candidates.get(1);
        assertEquals(20f, second.distanceToBox, 0.01f);
    }

    /** 同样的输入跑两次，候选序列必须逐项一致（不存在随时间/哈希顺序变化的排序）。 */
    @Test
    public void candidateOrderIsStableAcrossRuns() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP - LABEL_HEIGHT - 2);
        drawLabel(pixels, BOX_LEFT + 30, BOX_TOP - 30);
        final CapturedFrame frame = frameOf(pixels);

        final LabelExtractionResult first = extractor.extract(frame, defaultBox());
        final LabelExtractionResult second = extractor.extract(frame, defaultBox());

        assertEquals(2, first.candidateCount());
        assertEquals("两次提取的候选序列必须一致", describe(first), describe(second));
    }

    // ------------------------------------------------------------------ 3. 靠边 / clip

    @Test
    public void boxNearTheTopEdgeClipsTheSearchRegionAndStillFindsTheLabel() {
        final int boxTop = 10;
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                BOX_LEFT, boxTop, BOX_WIDTH, BOX_HEIGHT, BOX_THICKNESS, BLUE);
        drawLabel(pixels, BOX_LEFT, 0);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels),
                TargetBoundingBox.of(BOX_LEFT, boxTop, BOX_LEFT + BOX_WIDTH, boxTop + BOX_HEIGHT));

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        // 搜索区域被画面顶边夹住：本该是 -38，实际是 0
        assertEquals(0f, result.labelSearchRegion.top, 0f);
        assertEquals(boxTop + 6f, result.labelSearchRegion.bottom, 0.01f);
        assertLabelRect(result, BOX_LEFT, 0, BOX_LEFT + LABEL_WIDTH, LABEL_HEIGHT);
        assertEquals(0f, result.selectedLabel.distanceToBox, 0.01f);
    }

    @Test
    public void boxNearTheLeftEdgeClipsTheSearchRegion() {
        final int boxLeft = 4;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                boxLeft, BOX_TOP, BOX_WIDTH, BOX_HEIGHT, BOX_THICKNESS, BLUE);
        drawLabel(pixels, boxLeft, labelTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels),
                TargetBoundingBox.of(boxLeft, BOX_TOP, boxLeft + BOX_WIDTH, BOX_TOP + BOX_HEIGHT));

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertEquals(0f, result.labelSearchRegion.left, 0f);
        assertLabelRect(result, boxLeft, labelTop, boxLeft + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
        assertEquals(1f, result.selectedLabel.alignmentScore, 0.01f);
    }

    @Test
    public void boxNearTheRightEdgeClipsTheSearchRegion() {
        final int boxLeft = IMAGE_WIDTH - BOX_WIDTH - 4;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                boxLeft, BOX_TOP, BOX_WIDTH, BOX_HEIGHT, BOX_THICKNESS, BLUE);
        drawLabel(pixels, boxLeft, labelTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels),
                TargetBoundingBox.of(boxLeft, BOX_TOP, boxLeft + BOX_WIDTH, BOX_TOP + BOX_HEIGHT));

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertEquals((float) IMAGE_WIDTH, result.labelSearchRegion.right, 0.01f);
        assertLabelRect(result, boxLeft, labelTop, boxLeft + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
    }

    /** 框完全在画面之外：没有像素可看，必须是 OUT_OF_BOUNDS 而不是"没找到标签"。 */
    @Test
    public void boxOutsideTheVideoIsOutOfBounds() {
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        final LabelExtractionResult result = extractor.extract(frameOf(pixels),
                TargetBoundingBox.of(200, IMAGE_HEIGHT + 100, 360, IMAGE_HEIGHT + 220));

        assertEquals(LabelExtractionStatus.OUT_OF_BOUNDS, result.status);
        assertTrue(result.candidates.isEmpty());
        assertNull(result.getLabelRectInVideo());
    }

    // ------------------------------------------------------------------ 3b. 行带分组的两个结构判据（真实样本逼出来的）

    /**
     * 真实推流端的字形间距约 10px，<b>必须仍然算同一个编号</b>。
     *
     * <p>这条用例来自真实帧实测：{@code id:1850} 的 {@code d} 与 {@code :} 之间空了
     * 约 10 个像素。早期实现只用一个「水平间隙」判据（约 7px），于是把 {@code id} 前缀
     * 切了出去 —— ROI 只包住 {@code :1850}，而需求要的是"包住整个编号"。
     * 修法是把水平间隙放宽到 12px（见 {@link #mergeIntoTextLines} 的说明）。</p>
     */
    @Test
    public void wideGlyphGapsStayInsideASingleCandidate() {
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 2;
        final int wideGap = 10;
        final int expectedWidth = GLYPH_COUNT * GLYPH_WIDTH + (GLYPH_COUNT - 1) * wideGap;
        final int[] pixels = blankWithBox();
        TestImages.drawLabelGlyphs(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                BOX_LEFT, labelTop, GLYPH_WIDTH, GLYPH_HEIGHT, GLYPH_COUNT, wideGap, BLUE);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertEquals("10px 的字形空隙不得把编号切成两块", 1, result.candidateCount());
        assertLabelRect(result, BOX_LEFT, labelTop,
                BOX_LEFT + expectedWidth, labelTop + LABEL_HEIGHT);
    }

    /**
     * 水平方向够近、但<b>不是同一行</b>的邻居编号不得被并进来。
     *
     * <p>这正是「放宽字形间隙」带来的风险，也是真实样本里同时出现的情形：
     * t=45 帧上两个相邻目标（框宽 70px / 106px）的编号 {@code id:1980} 与 {@code id:1975}
     * 水平只隔约 10px（与"同一个编号内部的字形空隙"同量级），
     * 但垂直方向只重叠 4/10 行。所以判据里必须有垂直那条 ——
     * 只有水平间隙的话，放宽到能接住 {@code id:1850} 的同时必然把 {@code id:1980} 并进来。</p>
     *
     * <p>邻居放在搜索区左边界上（x=184），于是它有一半落在框外，不会被框顶边线吞掉；
     * 两个编号的重叠行数是 4 行，不到较矮者（10 行）的一半 → 必须判为两行。
     * 水平间隙取 5px（比实测的 10px 更紧）：紧到"单遍 + 一个水平间隙"的老判据必然会把
     * 它们并起来，于是这条用例真正卡住的是<b>垂直判据本身</b>，而不只是某个间隙取值。</p>
     */
    @Test
    public void aNeighbouringLabelSittingOnADifferentRowIsNotMergedIn() {
        final int ourLabelLeft = BOX_LEFT + 28;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 2;
        final int neighbourTop = BOX_TOP - LABEL_HEIGHT + 4;
        final int[] pixels = blankWithBox();
        // 我们自己的编号（离框顶 2px）
        drawLabel(pixels, ourLabelLeft, labelTop);
        // 邻居编号：水平只隔 5px，但整体低 6 行（重叠 4 行 < 10/2）
        drawLabel(pixels, BOX_LEFT - 16, neighbourTop);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        // 若被误并成一块，候选数会变成 1，且选中框的左边界会退到 184
        assertEquals("两个编号必须各自成一个候选", 2, result.candidateCount());
        assertLabelRect(result, ourLabelLeft, labelTop,
                ourLabelLeft + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
    }

    /**
     * 被压缩切碎的框<b>顶边残段</b>不得被并进标签、把 ROI 撑到框宽。
     *
     * <p>真实形态：t=45 帧的 { 1610,1722 } 框真边宽 113px，但 y=884 那一行的抗锯齿残段
     * 被 4:2:0 色度子采样切成了 45px / 32px / 16px 三段。如果"长横段"的门槛按「框宽的
     * 一半」（56.5px）定，这两段就都算"笔画"了 —— 它们与标签行相邻、水平相接，
     * 于是标签带被撑到 83px 宽（真正需要的只有 60px）。门槛改成「框宽 × 0.25」
     * （并且下限仍是 24px）之后，45px 与 32px 都被剔除，只剩 16px 那一段，
     * 而它离标签带还差 36px，接不上。</p>
     */
    @Test
    public void aSplitBoxEdgeRemnantIsNotMergedIntoTheLabel() {
        final int narrowBoxWidth = 113;
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 2;
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                BOX_LEFT, BOX_TOP, narrowBoxWidth, BOX_HEIGHT, BOX_THICKNESS, BLUE);
        drawLabel(pixels, BOX_LEFT, labelTop);
        // 框顶边上方 1px 处、45px 长的一段"残边"（真边宽 113px，即它的 40%）
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT, BOX_LEFT, BOX_TOP - 1, 45, 1, BLUE);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels),
                TargetBoundingBox.of(BOX_LEFT, BOX_TOP, BOX_LEFT + narrowBoxWidth,
                        BOX_TOP + BOX_HEIGHT));

        assertEquals(LabelExtractionStatus.SUCCESS, result.status);
        assertLabelRect(result, BOX_LEFT, labelTop,
                BOX_LEFT + LABEL_WIDTH, labelTop + LABEL_HEIGHT);
    }

    // ------------------------------------------------------------------ 4. 长直线 / 框边线不得被当成文字

    @Test
    public void theBoxesOwnEdgesNeverBecomeALabel() {
        // 只有框、没有编号：框顶边（与框等宽的横线）与两条竖边都必须出局。
        final int[] pixels = blankWithBox();

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.NO_LABEL, result.status);
        assertFalse(result.hasLabel());
        assertTrue(result.candidates.isEmpty());
        // 但搜索确实做完了 —— 这不是"帧/框不可用"
        assertNotNull(result.labelSearchRegion);
        assertTrue(result.debugReason != null && !result.debugReason.isEmpty());
    }

    /** 框外上方的一条长蓝直线（例如另一个目标框的边）也不得被当成编号。 */
    @Test
    public void aLongBlueLineAboveTheBoxIsNotTakenForAText() {
        final int[] pixels = blankWithBox();
        // 与框等宽、4px 厚的一条线，离框顶 20px
        TestImages.fillRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                BOX_LEFT, BOX_TOP - 24, BOX_WIDTH, 4, BLUE);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.NO_LABEL, result.status);
        assertTrue(result.candidates.isEmpty());
    }

    /** 框**下方**的编号不取：本 Slice 明确只从框顶附近找（真实推流端把编号压在框顶上方）。 */
    @Test
    public void aLabelBelowTheBoxIsNotTaken() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP + BOX_HEIGHT + 8);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.NO_LABEL, result.status);
    }

    /** 画面里一个蓝像素都没有 → NO_LABEL（而不是崩溃或误报）。 */
    @Test
    public void aFrameWithoutAnyBlueReportsNoLabel() {
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertEquals(LabelExtractionStatus.NO_LABEL, result.status);
        assertTrue(result.candidates.isEmpty());
    }

    // ------------------------------------------------------------------ 5. 入参非法

    @Test
    public void nullOrEmptyFrameReportsInvalidFrame() {
        assertEquals(LabelExtractionStatus.INVALID_FRAME,
                extractor.extract(null, defaultBox()).status);
        assertEquals(LabelExtractionStatus.INVALID_FRAME,
                extractor.extract(CapturedFrame.empty(0L, 0L), defaultBox()).status);
    }

    /** 载体尺寸与 frame 记录的尺寸不一致会让所有坐标静默错位 —— 必须硬失败。 */
    @Test
    public void frameRecordSizeMismatchingTheImageReportsInvalidFrame() {
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        final CapturedFrame mismatched = CapturedFrame.of(
                ArgbFrameImage.of(IMAGE_WIDTH, IMAGE_HEIGHT, pixels),
                IMAGE_WIDTH / 2, IMAGE_HEIGHT, 0L, 0L, CapturedFrame.NO_PTS_US,
                CapturedFrame.NO_TIMESTAMP_NS);

        assertEquals(LabelExtractionStatus.INVALID_FRAME,
                extractor.extract(mismatched, defaultBox()).status);
    }

    /** 载体不支持像素回读（纯元数据帧）也归为 INVALID_FRAME，而不是"当作没有蓝"。 */
    @Test
    public void aFrameThatCannotReadPixelsReportsInvalidFrame() {
        final ArgbFrameImage image = ArgbFrameImage.solid(IMAGE_WIDTH, IMAGE_HEIGHT, BLUE);
        image.readoutSupported = false;
        final CapturedFrame frame = CapturedFrame.ofImage(image, 0L, 0L);

        assertEquals(LabelExtractionStatus.INVALID_FRAME, extractor.extract(frame, defaultBox()).status);
    }

    @Test
    public void nullOrDegenerateBoxReportsInvalidBox() {
        final int[] pixels = blankWithBox();
        final CapturedFrame frame = frameOf(pixels);

        assertEquals(LabelExtractionStatus.INVALID_BOX, extractor.extract(frame, null).status);
        assertEquals(LabelExtractionStatus.INVALID_BOX,
                extractor.extract(frame, TargetBoundingBox.of(200, 300, 200, 420)).status);
    }

    /**
     * {@code NO_LABEL} 是「点到了目标、只是没读到编号」，<b>不是</b> MISS ——
     * 上层必须据此报 {@code HIT_ID_UNKNOWN}。把它当成"没有目标"是这类需求里最典型的误接。
     */
    @Test
    public void noLabelIsNotTheSameAsAMiss() {
        assertTrue(LabelExtractionStatus.NO_LABEL.isHitWithoutLabel());
        assertTrue(LabelExtractionStatus.NO_LABEL.isBoxUsable());
        assertFalse(LabelExtractionStatus.NO_LABEL.hasLabel());

        assertFalse(LabelExtractionStatus.SUCCESS.isHitWithoutLabel());
        assertFalse(LabelExtractionStatus.INVALID_BOX.isBoxUsable());
        assertFalse(LabelExtractionStatus.OUT_OF_BOUNDS.isBoxUsable());

        final int[] pixels = blankWithBox();
        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());
        assertTrue(result.isHitWithoutLabel());
        assertEquals(LabelExtractionStatus.NO_LABEL, result.status);
    }

    // ------------------------------------------------------------------ 6. 配置真的被读取

    @Test
    public void shrinkingTheSearchHeightMakesTheSameLabelUnfindable() {
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 30;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, labelTop);

        // 默认配置（向上 48px）能找到
        assertEquals(LabelExtractionStatus.SUCCESS,
                extractor.extract(frameOf(pixels), defaultBox()).status);

        // 把搜索带压到 12px 以后，离框顶 30px 的那块就落在带外了
        final LabelRegionExtractor narrow = new LabelRegionExtractor(
                TargetSelectionConfig.defaults().toBuilder()
                        .labelSearchHeightPx(12)
                        .labelMaxDistanceFromBoxPx(12)
                        .build());
        assertEquals(LabelExtractionStatus.NO_LABEL,
                narrow.extract(frameOf(pixels), defaultBox()).status);
    }

    @Test
    public void raisingTheMinimumLabelHeightRejectsTheSameLabel() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP - LABEL_HEIGHT - 4);

        assertEquals(LabelExtractionStatus.SUCCESS,
                extractor.extract(frameOf(pixels), defaultBox()).status);

        final LabelRegionExtractor strict = new LabelRegionExtractor(
                TargetSelectionConfig.defaults().toBuilder()
                        .minLabelCandidateHeight(LABEL_HEIGHT + 4)
                        .build());
        assertEquals(LabelExtractionStatus.NO_LABEL,
                strict.extract(frameOf(pixels), defaultBox()).status);
    }

    @Test
    public void maxLabelCandidateCountCapsTheKeptCandidates() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP - 4 - LABEL_HEIGHT);
        drawLabel(pixels, BOX_LEFT, BOX_TOP - 18 - LABEL_HEIGHT);
        drawLabel(pixels, BOX_LEFT, BOX_TOP - 32 - LABEL_HEIGHT);

        final LabelExtractionResult all = extractor.extract(frameOf(pixels), defaultBox());
        assertEquals(3, all.candidateCount());

        final LabelRegionExtractor capped = new LabelRegionExtractor(
                TargetSelectionConfig.defaults().toBuilder().maxLabelCandidateCount(2).build());
        final LabelExtractionResult limited = capped.extract(frameOf(pixels), defaultBox());
        assertEquals(2, limited.candidateCount());
        // 上限只截断名次明细，不影响最终选中结果
        assertEquals(all.selectedLabel.rectInVideo.top, limited.selectedLabel.rectInVideo.top, 0.01f);
    }

    // ------------------------------------------------------------------ 7. 裁剪

    @Test
    public void croppedPixelsMatchTheSourceFrame() {
        final int labelTop = BOX_TOP - LABEL_HEIGHT - 4;
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, labelTop);
        final int[] snapshot = pixels.clone();

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertNotNull(result.labelArgb);
        // 裁剪矩形 = 标签矩形外扩 1px padding
        assertEquals(BOX_LEFT - 1, result.labelRectInFrame.left);
        assertEquals(labelTop - 1, result.labelRectInFrame.top);
        assertEquals(BOX_LEFT + LABEL_WIDTH, result.labelRectInFrame.right);
        assertEquals(labelTop + LABEL_HEIGHT, result.labelRectInFrame.bottom);
        assertEquals(LABEL_WIDTH + 2, result.labelWidth);
        assertEquals(LABEL_HEIGHT + 2, result.labelHeight);

        // 逐像素比对：确实是从原帧的对应位置搬过来的，没有位移也没有翻转
        for (int row = 0; row < result.labelHeight; row++) {
            for (int column = 0; column < result.labelWidth; column++) {
                final int sourceX = result.labelRectInFrame.left + column;
                final int sourceY = result.labelRectInFrame.top + row;
                assertEquals("crop mismatch at " + sourceX + "," + sourceY,
                        pixels[sourceY * IMAGE_WIDTH + sourceX],
                        result.labelArgb[row * result.labelWidth + column]);
            }
        }
        // 裁出来的那一块确实有蓝像素（否则"裁剪正确"就变成了空谈）
        int blueCount = 0;
        for (int value : result.labelArgb) {
            if (value == BLUE) {
                blueCount++;
            }
        }
        assertEquals(GLYPH_COUNT * (2 * GLYPH_WIDTH + 2 * (GLYPH_HEIGHT - 2)), blueCount);

        assertArrayEquals("标签提取不得修改输入像素", snapshot, pixels);
    }

    @Test
    public void labelRectInVideoMatchesTheSelectedCandidate() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP - LABEL_HEIGHT - 4);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertNotNull(result.getLabelRectInVideo());
        assertEquals(result.selectedLabel.rectInVideo, result.getLabelRectInVideo());
        assertTrue(result.labelArgb.length == result.labelWidth * result.labelHeight);
    }

    // ------------------------------------------------------------------ 8. 耗时字段

    @Test
    public void everyStageKeepsItsOwnTiming() {
        final int[] pixels = blankWithBox();
        drawLabel(pixels, BOX_LEFT, BOX_TOP - LABEL_HEIGHT - 4);

        final LabelExtractionResult result = extractor.extract(frameOf(pixels), defaultBox());

        assertTrue(result.labelDetectMs >= 0L);
        assertTrue(result.labelCropMs >= 0L);
        assertTrue(result.totalMs >= 0L);
        assertEquals(result.totalMs, result.processingMs());
        assertTrue("totalMs 必须覆盖两段之和", result.totalMs >= result.labelDetectMs + result.labelCropMs);
    }

    // ------------------------------------------------------------------ utils

    private static int[] blankWithBox() {
        final int[] pixels = TestImages.solid(IMAGE_WIDTH, IMAGE_HEIGHT, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                BOX_LEFT, BOX_TOP, BOX_WIDTH, BOX_HEIGHT, BOX_THICKNESS, BLUE);
        return pixels;
    }

    private static void drawLabel(int[] pixels, int x, int y) {
        TestImages.drawLabelGlyphs(pixels, IMAGE_WIDTH, IMAGE_HEIGHT,
                x, y, GLYPH_WIDTH, GLYPH_HEIGHT, GLYPH_COUNT, BLUE);
    }

    private static TargetBoundingBox defaultBox() {
        return TargetBoundingBox.of(BOX_LEFT, BOX_TOP, BOX_LEFT + BOX_WIDTH, BOX_TOP + BOX_HEIGHT);
    }

    /** 不带几何 → 分析帧与视频 1:1，于是所有断言都可以直接落在像素坐标上。 */
    private static CapturedFrame frameOf(int[] pixels) {
        return CapturedFrame.ofImage(ArgbFrameImage.of(IMAGE_WIDTH, IMAGE_HEIGHT, pixels), 0L, 0L);
    }

    private static void assertLabelRect(LabelExtractionResult result,
                                        int left, int top, int right, int bottom) {
        final TargetBoundingBox rect = result.getLabelRectInVideo();
        assertNotNull("没有选中标签", rect);
        assertEquals("left", (float) left, rect.left, 0.01f);
        assertEquals("top", (float) top, rect.top, 0.01f);
        assertEquals("right", (float) right, rect.right, 0.01f);
        assertEquals("bottom", (float) bottom, rect.bottom, 0.01f);
    }

    /**
     * 只取候选本身（矩形 + 各项得分）—— 刻意不取 {@code debugString()}：
     * 那里面含耗时，同一份输入两次跑不可能逐字相等，拿它比较会把"排序不稳定"
     * 和"耗时不同"混成同一个失败。
     */
    private static List<String> describe(LabelExtractionResult result) {
        final List<String> lines = new ArrayList<>();
        lines.add("status=" + result.status + " candidates=" + result.candidateCount()
                + " selected=" + result.getLabelRectInVideo());
        for (LabelCandidate candidate : result.candidates) {
            lines.add(candidate.debugString());
        }
        return lines;
    }}
