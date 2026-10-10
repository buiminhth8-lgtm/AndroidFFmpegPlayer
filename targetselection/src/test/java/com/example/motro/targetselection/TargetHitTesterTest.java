package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.RoiRect;
import com.example.motro.targetselection.vision.TargetHitTestResult;
import com.example.motro.targetselection.vision.TargetHitTester;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link TargetHitTester} 的<b>过滤</b>行为：哪些框被点到了。
 *
 * <p>这些用例是纯几何的：命中过滤不依赖掩码、颜色、ROI，因此可以把每条规则
 * 单独钉住，而不必去构造一张恰好满足条件的图片。</p>
 *
 * <p><b>多候选的排序与选择不在这里测</b> —— 那是
 * {@code TargetCandidateSelector} 的职责，见 {@code TargetCandidateSelectorTest}。
 * 本类只保证"过滤结果如实、完整、不排序"，边界划清以后，调选择权重就不会
 * 顺手动到这里已经很稳的几何判定。</p>
 */
public class TargetHitTesterTest {

    private static final float EPS = 0.001f;

    // ---------------------------------------------------------------- 基础判定

    @Test
    public void touchInsideTheBoxIsAHit() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        TargetHitTestResult result = new TargetHitTester(0).hitTest(150f, 150f, one(box));

        assertTrue(result.isHit());
        assertEquals(1, result.hitCount());
        assertSame(box, result.hitCandidates.get(0));
        assertEquals(1, result.candidateCount);
        assertEquals(0, result.tolerancePx);
        assertTrue(result.isUnambiguousHit());
    }

    @Test
    public void touchFarOutsideIsAMiss() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        TargetHitTestResult result = new TargetHitTester(10).hitTest(260f, 150f, one(box));

        assertFalse(result.isHit());
        assertTrue(result.hitCandidates.isEmpty());
        assertEquals(1, result.candidateCount);
        assertEquals(0, result.hitCount());
    }

    @Test
    public void nullOrEmptyCandidatesAreAMiss() {
        assertFalse(new TargetHitTester(10).hitTest(0f, 0f, null).isHit());
        assertFalse(new TargetHitTester(10).hitTest(0f, 0f, Collections.emptyList()).isHit());
        assertEquals(0, new TargetHitTester(10).hitTest(0f, 0f, null).candidateCount);
    }

    @Test
    public void nonFiniteTouchPointIsRejected() {
        List<DetectedTargetBox> boxes = one(box(100, 100, 200, 200, 0.5f));
        try {
            new TargetHitTester(10).hitTest(Float.NaN, 150f, boxes);
            fail("NaN 触摸点必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("finite"));
        }
        try {
            new TargetHitTester(10).hitTest(150f, Float.POSITIVE_INFINITY, boxes);
            fail("Infinity 触摸点必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("finite"));
        }
    }

    @Test
    public void negativeToleranceIsRejected() {
        try {
            new TargetHitTester(-1);
            fail("负容差必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tolerancePx"));
        }
    }

    // ---------------------------------------------------------------- 容差

    @Test
    public void toleranceExpandsTheHitArea() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        // 距离右边界 5px：无容差判 MISS，容差 12 判 HIT
        assertFalse(new TargetHitTester(0).hitTest(205f, 150f, one(box)).isHit());
        assertTrue(new TargetHitTester(12).hitTest(205f, 150f, one(box)).isHit());
    }

    /**
     * 容差<b>只进不出</b>：判定用扩张后的框，返回的必须是原始框。
     * 若把扩张后的框返回给上层，绘制/对齐/后续 Label ROI 都会整体外扩一圈。
     */
    @Test
    public void toleranceNeverChangesTheReturnedBox() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        TargetHitTestResult result = new TargetHitTester(50).hitTest(240f, 150f, one(box));

        assertTrue(result.isHit());
        assertEquals(50, result.tolerancePx);
        final TargetBoundingBox returned = result.hitCandidates.get(0).rectInVideo;
        assertEquals(100f, returned.left, EPS);
        assertEquals(100f, returned.top, EPS);
        assertEquals(200f, returned.right, EPS);
        assertEquals(200f, returned.bottom, EPS);
    }

    @Test
    public void zeroToleranceStillAcceptsPointsOnTheBorder() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        assertTrue(new TargetHitTester(0).hitTest(100f, 150f, one(box)).isHit());
        assertTrue(new TargetHitTester(0).hitTest(200f, 200f, one(box)).isHit());
    }

    @Test
    public void zeroToleranceRejectsAPointJustOutsideTheBorder() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        assertFalse("容差为 0 时边界外 1px 不该命中",
                new TargetHitTester(0).hitTest(201f, 150f, one(box)).isHit());
    }

    // ---------------------------------------------------------------- 顺序与完整性

    /**
     * 过滤结果<b>保持输入顺序</b>：排序是选择器的职责，过滤层不该带任何倾向。
     * 若这里偷偷排一次，就等于把"哪个更好"的知识塞进了过滤层 ——
     * 而选择权重明明配在另一处，于是两套优先级会各自漂移、逐渐不一致。
     */
    @Test
    public void allHitsAreReturnedInInputOrder() {
        DetectedTargetBox outer = box(100, 100, 300, 300, 0.5f);
        DetectedTargetBox middle = box(120, 120, 240, 240, 0.5f);
        DetectedTargetBox inner = box(140, 140, 180, 180, 0.5f);
        TargetHitTestResult result = new TargetHitTester(0).hitTest(160f, 160f,
                Arrays.asList(outer, middle, inner));

        assertEquals(3, result.hitCount());
        assertSame(outer, result.hitCandidates.get(0));
        assertSame(middle, result.hitCandidates.get(1));
        assertSame(inner, result.hitCandidates.get(2));
        assertFalse("三个都命中，不该号称「唯一命中」", result.isUnambiguousHit());
    }

    @Test
    public void nonHitsAreExcludedFromTheHitList() {
        DetectedTargetBox hit = box(100, 100, 200, 200, 0.5f);
        DetectedTargetBox far = box(400, 400, 500, 500, 0.5f);
        TargetHitTestResult result = new TargetHitTester(5).hitTest(150f, 150f,
                Arrays.asList(far, hit));

        assertEquals(2, result.candidateCount);
        assertEquals(1, result.hitCount());
        assertSame(hit, result.hitCandidates.get(0));
        assertTrue(result.isUnambiguousHit());
    }

    @Test
    public void nullEntriesInTheCandidateListAreIgnored() {
        List<DetectedTargetBox> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(box(100, 100, 200, 200, 0.5f));
        candidates.add(null);

        TargetHitTestResult result = new TargetHitTester(0).hitTest(150f, 150f, candidates);
        assertTrue(result.isHit());
        assertEquals(1, result.hitCount());
        assertEquals(3, result.candidateCount);   // 计数仍如实反映传入的规模
    }

    @Test
    public void toleranceComesFromTheConfigByDefault() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        assertEquals(config.touchTolerancePx, new TargetHitTester(config).tolerancePx());
    }

    @Test
    public void missKeepsTheTouchPointAndToleranceForDiagnostics() {
        TargetHitTestResult result = new TargetHitTester(7).hitTest(11f, 22f,
                one(box(100, 100, 200, 200, 0.5f)));

        assertFalse(result.isHit());
        assertTrue("MISS 时命中列表必须为空", result.hitCandidates.isEmpty());
        assertEquals(11f, result.touchVideoX, EPS);
        assertEquals(22f, result.touchVideoY, EPS);
        assertEquals(7, result.tolerancePx);
        assertTrue(result.debugString(), result.debugString().contains("hits=0"));
    }

    // ---------------------------------------------------------------- 辅助

    private static List<DetectedTargetBox> one(DetectedTargetBox box) {
        return Collections.singletonList(box);
    }

    /** 只关心视频坐标与置信度的测试替身：ROI 局部矩形同步给一个等尺寸的假值即可。 */
    private static DetectedTargetBox box(float left, float top, float right, float bottom,
                                        float confidence) {
        TargetBoundingBox video = TargetBoundingBox.of(left, top, right, bottom);
        RoiRect local = RoiRect.ofSize(0, 0,
                Math.max(1, Math.round(video.width())), Math.max(1, Math.round(video.height())));
        return DetectedTargetBox.of(local, video, confidence, confidence, 4, false);
    }
}
