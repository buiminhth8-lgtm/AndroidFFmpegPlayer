package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.RoiRect;
import com.example.motro.targetselection.vision.TargetBoxHitStatus;
import com.example.motro.targetselection.vision.TargetCandidateScore;
import com.example.motro.targetselection.vision.TargetCandidateSelector;
import com.example.motro.targetselection.vision.TargetHitType;
import com.example.motro.targetselection.vision.TargetSelectionDecision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link TargetCandidateSelector} 的候选评分、排序与 tie-break。
 *
 * <p>用例按需求里的场景 A~H 组织，全部是纯几何的：选择只吃已经算好的框，
 * 不碰掩码与像素，因此每条规则都能单独钉住，而不必去构造一张恰好满足条件的图片。</p>
 *
 * <p>两个贯穿全类的检查同样重要：<b>结果与输入顺序无关</b>，以及
 * <b>同样的输入必然得到同样的选择</b> —— 这两条是"稳定、可重复"的定义，
 * 一旦被破坏，真机上就会出现"同样的点击有时选 A 有时选 B"这种最难查的问题。</p>
 */
public class TargetCandidateSelectorTest {

    private static final float EPS = 0.0001f;

    /** 默认配置：容差 12px、EXACT 加分 1.0、中心距离/面积各 1.0、置信度/完整度各 0.5。 */
    private static final TargetSelectionConfig CONFIG = TargetSelectionConfig.defaults();

    // ================================================================ 基础

    /** 单候选：不需要任何比较，直接选中它。 */
    @Test
    public void singleCandidateIsSelectedDirectly() {
        DetectedTargetBox only = box(100, 100, 200, 200);
        TargetSelectionDecision decision = select(150f, 150f, only);

        assertTrue(decision.isHit());
        assertSame(only, decision.selectedBox);
        assertEquals(TargetBoxHitStatus.HIT_BOX, decision.status);
        assertEquals(TargetHitType.EXACT_HIT, decision.hitType());
        assertEquals(1, decision.hitCount());
        assertEquals(0, decision.rankedCandidates.get(0).rank);
        assertTrue(decision.reason, decision.reason.contains("only-candidate"));
        assertTrue(decision.reason, decision.reason.contains("EXACT_HIT"));
        assertTrue("选择耗时不该为负", decision.selectionMs >= 0L);
    }

    /** 无候选（null / 空列表）→ MISS，且不留下任何选中痕迹。 */
    @Test
    public void noCandidatesIsAMiss() {
        TargetSelectionDecision nullList = new TargetCandidateSelector(CONFIG).select(10f, 10f, null);
        assertEquals(TargetBoxHitStatus.MISS, nullList.status);
        assertFalse(nullList.isHit());
        assertNull(nullList.selectedBox);
        assertNull(nullList.selectedScore);
        assertNull("MISS 不该有命中分级", nullList.hitType());
        assertEquals(0, nullList.hitCount());
        assertTrue(nullList.rankedCandidates.isEmpty());
        assertEquals(0f, nullList.selectionScore(), 0f);

        TargetSelectionDecision empty = select(10f, 10f);
        assertEquals(TargetBoxHitStatus.MISS, empty.status);
        assertEquals(0, empty.hitCount());
    }

    /** 候选都在容差之外 → 也不是命中（不能因为"有候选"就随便选一个）。 */
    @Test
    public void candidatesOutsideTheToleranceAreNotSelected() {
        TargetSelectionDecision decision = select(1000f, 1000f, box(100, 100, 200, 200));
        assertEquals(TargetBoxHitStatus.MISS, decision.status);
        assertEquals(0, decision.hitCount());
        assertTrue(decision.reason, decision.reason.contains("no-hit"));
    }

    @Test
    public void nullEntriesAreSkipped() {
        List<DetectedTargetBox> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(box(100, 100, 200, 200));
        candidates.add(null);

        TargetSelectionDecision decision = select(150f, 150f, candidates);
        assertTrue(decision.isHit());
        assertEquals(1, decision.hitCount());
    }

    @Test
    public void nonFiniteTouchPointIsRejected() {
        List<DetectedTargetBox> candidates = Collections.singletonList(box(100, 100, 200, 200));
        try {
            new TargetCandidateSelector(CONFIG).select(Float.NaN, 150f, candidates);
            fail("NaN 触摸点必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("finite"));
        }
        try {
            new TargetCandidateSelector(CONFIG).select(150f, Float.NEGATIVE_INFINITY, candidates);
            fail("Infinity 触摸点必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("finite"));
        }
    }

    @Test
    public void nullConfigIsRejected() {
        try {
            new TargetCandidateSelector(null);
            fail("config 为 null 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("config"));
        }
    }

    // ================================================================ 场景 B / 需求 3：EXACT 优先

    /**
     * <b>EXACT_HIT 是硬主键</b>：这里刻意把配置里所有能加分的项都指向"容差命中的那个框"
     * （EXACT 加分与两个距离/面积惩罚全部置 0，只留置信度/完整度），
     * 于是容差框的总分<b>确实更高</b> —— 但严格包含的那个框仍必须胜出。
     *
     * <p>这一条是"点击两个目标中间时不被错误吸附"的核心保证：
     * 若把它做成靠权重加分的软规则，现场调一次权重就会失守。</p>
     */
    @Test
    public void exactHitAlwaysBeatsToleranceHitEvenWhenTheToleranceBoxScoresHigher() {
        final TargetSelectionConfig biasFree = CONFIG.toBuilder()
                .exactHitWeight(0f)
                .centerDistanceWeight(0f)
                .areaWeight(0f)
                .build();
        DetectedTargetBox contained = box(100, 100, 300, 300, 0.2f, 0.2f);  // 严格包含，质量差
        DetectedTargetBox nearly = box(160, 140, 180, 160, 1f, 1f);          // 只靠容差够到，质量满分

        TargetSelectionDecision decision = new TargetCandidateSelector(biasFree)
                .select(150f, 150f, Arrays.asList(nearly, contained));

        assertEquals(2, decision.hitCount());
        assertSame("严格包含者必须优先", contained, decision.selectedBox);
        assertEquals(TargetHitType.EXACT_HIT, decision.hitType());
        assertSame(nearly, decision.rankedCandidates.get(1).box);
        assertEquals(TargetHitType.TOLERANCE_HIT, decision.rankedCandidates.get(1).hitType);
        assertTrue("构造前提：容差框的分数确实更高（否则这条测试没测到硬主键）",
                decision.rankedCandidates.get(1).totalScore
                        > decision.rankedCandidates.get(0).totalScore);
        assertTrue(decision.reason, decision.reason.contains("best-of-2"));
    }

    /**
     * 场景 B：<b>两个框边缘非常接近</b>。点明显落在左框内、只擦到右框的边，
     * 必须选左框 —— 这正是"避免点击两个目标中间时被错误吸附"的现场。
     */
    @Test
    public void nearTouchingBoxesPickTheOneThatActuallyContainsTheTouch() {
        DetectedTargetBox left = box(120, 100, 220, 200);
        DetectedTargetBox right = box(226, 100, 326, 200);

        // (218,150) 在左框内；距右框左边线 8px ≤ 容差 12 → 右框只能算 TOLERANCE
        TargetSelectionDecision decision = select(218f, 150f, right, left);

        assertEquals(2, decision.hitCount());
        assertSame(left, decision.selectedBox);
        assertEquals(TargetHitType.EXACT_HIT, decision.hitType());
        assertEquals(TargetHitType.TOLERANCE_HIT, decision.rankedCandidates.get(1).hitType);
        assertTrue("两个框都该被找到，否则这条测试退化成单候选",
                decision.rankedCandidates.size() == 2);
    }

    // ================================================================ 场景 C / E：重叠

    /**
     * 场景 C / E：两个 bbox 部分重叠，触摸点落在重叠区域内。
     * 两个都是严格命中，于是比"谁更接近触摸点" —— 中心越近越优先。
     */
    @Test
    public void overlappingBoxesPickTheOneWhoseCenterIsCloser() {
        DetectedTargetBox near = box(200, 150, 400, 350);    // 中心 (300,250)
        DetectedTargetBox far = box(300, 250, 500, 450);     // 中心 (400,350)

        TargetSelectionDecision decision = select(330f, 280f, far, near);

        assertEquals(2, decision.hitCount());
        assertTrue(decision.rankedCandidates.get(0).hitType.isExact());
        assertTrue(decision.rankedCandidates.get(1).hitType.isExact());
        assertSame(near, decision.selectedBox);
        assertTrue("更近的中心应当得到更小的归一化中心距离",
                decision.rankedCandidates.get(0).normalizedCenterDistance
                        < decision.rankedCandidates.get(1).normalizedCenterDistance);
    }

    /**
     * 两个严格命中的框若在<b>几何上完全对称</b>（同尺寸、同中心距、同面积、同质量），
     * 分数必须<b>精确相等</b>，并由最后一级 tie-break（上边界 → 左边界）唯一确定结果。
     * 断言"分数相等"很关键：否则这条用例可能只是被分数分出了胜负，
     * 并没有真正测到 tie-break。
     */
    @Test
    public void equalScoresAreBrokenByDeterministicGeometry() {
        DetectedTargetBox left = box(100, 100, 200, 200);
        DetectedTargetBox right = box(220, 100, 320, 200);

        TargetSelectionDecision decision = select(210f, 150f, right, left);

        assertEquals(2, decision.hitCount());
        assertEquals("构造前提：两者分数必须完全相等，否则测的不是 tie-break",
                decision.rankedCandidates.get(0).totalScore,
                decision.rankedCandidates.get(1).totalScore, 0f);
        assertSame("分数打平后由左边界兜底，左框胜出", left, decision.selectedBox);
    }

    /** 场景 F：触摸点同时只靠容差命中两个框时，更接近的那个胜出。 */
    @Test
    public void toleranceOnlyHitsPickTheCloserOne() {
        DetectedTargetBox left = box(100, 100, 200, 200);
        DetectedTargetBox right = box(210, 100, 310, 200);

        // (208,150)：两个框都不包含它，距左框右线 8px、距右框左线 2px
        TargetSelectionDecision decision = select(208f, 150f, left, right);

        assertEquals(2, decision.hitCount());
        assertFalse(decision.rankedCandidates.get(0).hitType.isExact());
        assertFalse(decision.rankedCandidates.get(1).hitType.isExact());
        assertSame(right, decision.selectedBox);
    }

    // ================================================================ 场景 D / G：嵌套与小目标

    /** 场景 D / G：大框套小框、两者都包含触摸点 → 更小、更具体的那个优先。 */
    @Test
    public void nestedBoxesPreferTheSmallerMoreSpecificOne() {
        DetectedTargetBox big = box(100, 100, 300, 300);      // 200x200
        DetectedTargetBox small = box(170, 170, 230, 230);    // 60x60

        TargetSelectionDecision decision = select(200f, 200f, big, small);

        assertEquals(2, decision.hitCount());
        assertSame("嵌套时小框更具体", small, decision.selectedBox);
        assertTrue(decision.rankedCandidates.get(0).area < decision.rankedCandidates.get(1).area);
    }

    /** 场景 G：小目标紧邻大框，点在小目标上 → 选小目标，大框连命中都不该有。 */
    @Test
    public void smallTargetNextToABigBoxIsSelectedOnItsOwn() {
        DetectedTargetBox big = box(200, 150, 500, 470);
        DetectedTargetBox small = box(520, 300, 610, 370);    // 90x70

        TargetSelectionDecision decision = select(565f, 335f, big, small);

        assertEquals("大框距触摸点 65px，超出容差，不该参与命中", 1, decision.hitCount());
        assertSame(small, decision.selectedBox);
        assertEquals(TargetHitType.EXACT_HIT, decision.hitType());
    }

    // ================================================================ 场景 H：ROI 边缘的不完整框

    /**
     * 场景 H：一个被 ROI 截断的框与一个完整框打得难分难解时，必须选<b>完整</b>的那个。
     *
     * <p>构造刻意让左边界兜底规则指向被截断的框（它的 left 更小）：
     * 若没有"几何完整优先"这一级，结果就会翻到这个被截断的框上 ——
     * 而它的右边界其实是被 ROI 夹出来的假值。</p>
     */
    @Test
    public void clippedBoxLosesOnlyWhenEverythingElseTies() {
        DetectedTargetBox complete = box(300, 100, 400, 200, 1f, 1f, false);
        DetectedTargetBox clipped = box(200, 100, 300, 200, 1f, 1f, true);

        // (300,150) 正好落在两框的公共边上 → 都是严格命中，且各项指标完全对称
        TargetSelectionDecision decision = select(300f, 150f, clipped, complete);

        assertEquals(2, decision.hitCount());
        assertEquals("构造前提：两者分数必须完全相等",
                decision.rankedCandidates.get(0).totalScore,
                decision.rankedCandidates.get(1).totalScore, 0f);
        assertSame("几何完整的框优先于被 ROI 截断的框", complete, decision.selectedBox);
        assertFalse(decision.selectedBox.clippedByRoi);
    }

    // ================================================================ 确定性

    /**
     * 输入顺序不得影响结果：嵌套三层的框，六种排列必须给出完全相同的名次与选择。
     */
    @Test
    public void selectionIsIndependentOfInputOrder() {
        DetectedTargetBox outer = box(100, 100, 300, 300);
        DetectedTargetBox middle = box(130, 130, 270, 270);
        DetectedTargetBox inner = box(170, 170, 230, 230);

        final List<List<DetectedTargetBox>> permutations = Arrays.asList(
                Arrays.asList(outer, middle, inner),
                Arrays.asList(inner, middle, outer),
                Arrays.asList(middle, outer, inner),
                Arrays.asList(middle, inner, outer),
                Arrays.asList(inner, outer, middle),
                Arrays.asList(outer, inner, middle));

        for (List<DetectedTargetBox> permutation : permutations) {
            TargetSelectionDecision decision = select(200f, 200f, permutation);
            assertEquals(permutation + " 的名次必须一致", 3, decision.hitCount());
            assertSame(inner, decision.rankedCandidates.get(0).box);
            assertSame(middle, decision.rankedCandidates.get(1).box);
            assertSame(outer, decision.rankedCandidates.get(2).box);
            assertSame(inner, decision.selectedBox);
        }
    }

    /** 重复调用同一输入必须给出同一结果（防止引入随机或依赖时间的启发式）。 */
    @Test
    public void repeatedCallsAreBitwiseStable() {
        DetectedTargetBox a = box(100, 100, 300, 300);
        DetectedTargetBox b = box(170, 170, 230, 230);
        DetectedTargetBox c = box(250, 100, 350, 200);

        final TargetSelectionDecision first = select(200f, 200f, a, b, c);
        for (int i = 0; i < 20; i++) {
            TargetSelectionDecision again = select(200f, 200f, a, b, c);
            assertSame(first.selectedBox, again.selectedBox);
            assertEquals(first.hitCount(), again.hitCount());
            for (int r = 0; r < first.hitCount(); r++) {
                assertSame(first.rankedCandidates.get(r).box, again.rankedCandidates.get(r).box);
                assertEquals(first.rankedCandidates.get(r).totalScore,
                        again.rankedCandidates.get(r).totalScore, 0f);
            }
        }
    }

    /** 名次必须是 0..n-1 连续，否则叠加层按 rank 显示名次时会露馅。 */
    @Test
    public void ranksAreContiguousStartingAtZero() {
        TargetSelectionDecision decision = select(200f, 200f,
                box(100, 100, 300, 300), box(130, 130, 270, 270), box(170, 170, 230, 230));
        for (int i = 0; i < decision.rankedCandidates.size(); i++) {
            assertEquals(i, decision.rankedCandidates.get(i).rank);
        }
        assertSame(decision.rankedCandidates.get(0).box, decision.selectedBox);
        assertEquals(decision.rankedCandidates.get(0), decision.selectedScore);
    }

    // ================================================================ 容差与边界点

    @Test
    public void toleranceZeroOnlyAcceptsStrictContainment() {
        final TargetSelectionConfig strict = CONFIG.toBuilder().touchTolerancePx(0).build();
        DetectedTargetBox box = box(100, 100, 200, 200);
        List<DetectedTargetBox> candidates = Collections.singletonList(box);

        assertTrue(new TargetCandidateSelector(strict).select(200f, 150f, candidates).isHit());
        assertFalse("容差为 0 时边界外 1px 不该命中",
                new TargetCandidateSelector(strict).select(201f, 150f, candidates).isHit());
    }

    @Test
    public void pointExactlyOnTheBorderIsAnExactHit() {
        DetectedTargetBox box = box(100, 100, 200, 200);
        for (float[] point : new float[][]{{100f, 150f}, {200f, 150f}, {150f, 100f}, {150f, 200f}}) {
            TargetSelectionDecision decision = select(point[0], point[1], box);
            assertTrue(decision.isHit());
            assertEquals(TargetHitType.EXACT_HIT, decision.hitType());
        }
    }

    /** 到边界的带符号距离：框内为负、框外为正 —— 叠加层与 tie-break 都依赖这个符号。 */
    @Test
    public void edgeDistanceIsSigned() {
        DetectedTargetBox box = box(100, 100, 200, 200);

        TargetCandidateScore inside = select(100f, 150f, box).rankedCandidates.get(0);
        assertEquals("点在左边界上，到最近边的距离为 0", 0f, inside.edgeDistance, EPS);

        TargetCandidateScore centered = select(150f, 150f, box).rankedCandidates.get(0);
        assertEquals("点在正中心，距最近边 50px，符号取负", -50f, centered.edgeDistance, EPS);

        final TargetSelectionConfig tolerant = CONFIG.toBuilder().touchTolerancePx(20).build();
        TargetCandidateScore outside = new TargetCandidateSelector(tolerant)
                .select(210f, 150f, Collections.singletonList(box))
                .rankedCandidates.get(0);
        assertEquals("点在框外 10px，距离取正", 10f, outside.edgeDistance, EPS);
    }

    // ================================================================ 非法与极值输入

    @Test
    public void degenerateBoxDoesNotProduceNaN() {
        DetectedTargetBox degenerate = box(100, 100, 100, 100);   // 零面积
        TargetSelectionDecision decision = select(100f, 100f, degenerate);

        assertTrue(decision.isHit());
        assertTrue("总分必须有限", Float.isFinite(decision.selectedScore.totalScore));
        assertTrue("归一化中心距离必须有限", Float.isFinite(decision.selectedScore.normalizedCenterDistance));
        assertEquals(0f, decision.selectedScore.area, 0f);
    }

    @Test
    public void extremeConfidenceAndCompletenessDoNotBreakTheScore() {
        DetectedTargetBox worst = box(100, 100, 200, 200, 0f, 0f);
        DetectedTargetBox best = box(100, 100, 200, 200, 1f, 1f);

        TargetSelectionDecision decision = select(150f, 150f, worst, best);
        assertEquals(2, decision.hitCount());
        assertSame("几何完全相同时，框质量更高者优先", best, decision.selectedBox);
        assertEquals(1f, decision.rankedCandidates.get(0).boxConfidence, 0f);
        assertEquals(0f, decision.rankedCandidates.get(1).boxConfidence, 0f);
        assertTrue(Float.isFinite(decision.rankedCandidates.get(1).totalScore));

        // NaN 置信度在 DetectedTargetBox 里已被钳到 0，评分链不该被污染
        DetectedTargetBox nan = box(100, 100, 200, 200, Float.NaN, Float.NaN);
        TargetSelectionDecision nanDecision = select(150f, 150f, nan);
        assertTrue(nanDecision.isHit());
        assertTrue(Float.isFinite(nanDecision.selectedScore.totalScore));
        assertEquals(0f, nanDecision.selectedScore.boxConfidence, 0f);
    }

    @Test
    public void invalidBoxArgumentsAreRejectedByTheValueTypes() {
        try {
            DetectedTargetBox.of(null, TargetBoundingBox.of(0f, 0f, 1f, 1f), 1f, 1f, 4, false);
            fail("rectInRoi 为 null 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            DetectedTargetBox.of(RoiRect.of(0, 0, 1, 1), null, 1f, 1f, 4, false);
            fail("rectInVideo 为 null 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            TargetBoundingBox.of(Float.NaN, 0f, 1f, 1f);
            fail("NaN 坐标必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    // ================================================================ 候选上限

    /** 候选上限只影响"保留多少条名次明细"，绝不影响最终选择（rank 0 永远保留）。 */
    @Test
    public void rankedCandidatesAreCappedWithoutChangingTheWinner() {
        final int cap = 2;
        final TargetSelectionConfig capped = CONFIG.toBuilder().maxRankedCandidates(cap).build();

        // 5 个同心嵌套框，全部严格包含触摸点
        List<DetectedTargetBox> candidates = Arrays.asList(
                box(100, 100, 500, 500),
                box(140, 140, 460, 460),
                box(180, 180, 420, 420),
                box(220, 220, 380, 380),
                box(260, 260, 340, 340));

        TargetSelectionDecision decision = new TargetCandidateSelector(capped)
                .select(300f, 300f, candidates);

        assertEquals(cap, decision.rankedCandidates.size());
        assertEquals(0, decision.rankedCandidates.get(0).rank);
        assertEquals(1, decision.rankedCandidates.get(1).rank);
        assertSame("上限截断后，选中的仍是最小、最具体的那个框",
                candidates.get(4), decision.selectedBox);

        // 不设上限时应当保留全部 5 条
        TargetSelectionDecision uncapped = select(300f, 300f, candidates);
        assertEquals(5, uncapped.hitCount());
        assertSame(uncapped.selectedBox, decision.selectedBox);
    }

    @Test
    public void maxRankedCandidatesComesFromTheConfig() {
        TargetCandidateSelector selector = new TargetCandidateSelector(
                CONFIG.toBuilder().maxRankedCandidates(3).build());
        assertEquals(3, selector.maxRankedCandidates());
        assertEquals(CONFIG.touchTolerancePx, selector.tolerancePx());
    }

    // ================================================================ 配置驱动

    /**
     * 权重与 {@code preferSmallerBox} 必须真的生效：同一组输入、只换配置，
     * 结果必须从"大框"翻到"小框"。
     *
     * <p>这条同时验证了两件事 —— 嵌套框的选择确实由面积项主导，
     * 而中心距离项在关掉面积项之后能把结果拿回去。</p>
     */
    @Test
    public void weightsAndPreferSmallerBoxChangeTheOutcome() {
        DetectedTargetBox big = box(100, 100, 300, 300);      // 中心 (200,200)，半对角线 141.4
        DetectedTargetBox small = box(170, 170, 230, 230);    // 中心 (200,200)，半对角线 42.4
        // 偏离中心 20px：大框的归一化中心距离更小，小框的面积更小 —— 两条判据指向相反方向
        final float touchX = 200f;
        final float touchY = 220f;

        TargetSelectionDecision preferSmall = new TargetCandidateSelector(
                CONFIG.toBuilder().preferSmallerBox(true).build())
                .select(touchX, touchY, Arrays.asList(big, small));
        assertSame("开启面积项时，更小更具体的框胜出", small, preferSmall.selectedBox);

        TargetSelectionDecision preferLarge = new TargetCandidateSelector(
                CONFIG.toBuilder().preferSmallerBox(false).build())
                .select(touchX, touchY, Arrays.asList(big, small));
        assertSame("关掉面积项后，更居中的大框胜出", big, preferLarge.selectedBox);
    }

    @Test
    public void centerDistanceWeightIsReadFromTheConfig() {
        DetectedTargetBox near = box(200, 150, 400, 350);
        DetectedTargetBox far = box(300, 250, 500, 450);
        List<DetectedTargetBox> candidates = Arrays.asList(near, far);

        assertSame("默认配置下中心更近者胜出", near,
                select(330f, 280f, candidates).selectedBox);

        TargetSelectionDecision noDistanceTerm = new TargetCandidateSelector(
                CONFIG.toBuilder().centerDistanceWeight(0f).build())
                .select(330f, 280f, candidates);
        assertSame("关掉中心距离项后，两项打平，由左边界兜底", near,
                noDistanceTerm.selectedBox);
        assertEquals(noDistanceTerm.rankedCandidates.get(0).totalScore,
                noDistanceTerm.rankedCandidates.get(1).totalScore, 0f);
    }

    // ================================================================ 性能

    /**
     * 选择是次毫秒级的纯算术：候选本来就少（个位数），因此只要求 O(N log N) 且不碰像素。
     *
     * <p>阈值放得极宽（0.2ms/次，约比实测高两个数量级）：这条用例的目的是<b>挡住量级错误</b>
     * （例如有人在循环里做了一次像素扫描），不是做精确基准，因此在共享 CI 机器上也不该抖动。</p>
     */
    @Test
    public void selectionStaysFarBelowOneMillisecond() {
        // 16 个同心嵌套框：全部命中，最坏情况下每个候选都要量测 + 排序
        List<DetectedTargetBox> candidates = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            float inset = i * 10f;
            candidates.add(box(100f + inset, 100f + inset, 500f - inset, 500f - inset));
        }

        final TargetCandidateSelector selector = new TargetCandidateSelector(CONFIG);
        final int iterations = 20_000;
        // 预热，避免把 JIT 编译时间算进来
        for (int i = 0; i < 2_000; i++) {
            selector.select(300f, 300f, candidates);
        }

        final long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            selector.select(300f, 300f, candidates);
        }
        final long elapsedNs = System.nanoTime() - startNs;
        final double nsPerCall = elapsedNs / (double) iterations;

        final String message = String.format(java.util.Locale.ROOT,
                "16 候选单次选择耗时 %.0f ns（%.3f ms）", nsPerCall, nsPerCall / 1_000_000d);
        System.out.println(message);
        assertTrue(message, nsPerCall < 200_000d);   // < 0.2ms
    }

    // ================================================================ 辅助

    private static TargetSelectionDecision select(float x, float y, DetectedTargetBox... boxes) {
        return new TargetCandidateSelector(CONFIG).select(x, y, Arrays.asList(boxes));
    }

    private static TargetSelectionDecision select(float x, float y, List<DetectedTargetBox> boxes) {
        return new TargetCandidateSelector(CONFIG).select(x, y, boxes);
    }

    private static DetectedTargetBox box(float left, float top, float right, float bottom) {
        return box(left, top, right, bottom, 1f, 1f, false);
    }

    private static DetectedTargetBox box(float left, float top, float right, float bottom,
                                        float confidence, float completeness) {
        return box(left, top, right, bottom, confidence, completeness, false);
    }

    /**
     * 测试替身：ROI 局部矩形同步给一个等尺寸的假值即可 —— 选择器只用视频坐标。
     */
    private static DetectedTargetBox box(float left, float top, float right, float bottom,
                                        float confidence, float completeness, boolean clipped) {
        TargetBoundingBox video = TargetBoundingBox.of(left, top, right, bottom);
        RoiRect local = RoiRect.ofSize(0, 0,
                Math.max(1, Math.round(video.width())), Math.max(1, Math.round(video.height())));
        return DetectedTargetBox.of(local, video, confidence, completeness, 4, clipped);
    }
}
