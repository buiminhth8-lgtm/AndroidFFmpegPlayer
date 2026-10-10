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
import com.example.motro.targetselection.vision.TargetHitTestResult;
import com.example.motro.targetselection.vision.TargetHitTester;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link TargetHitTester} 的命中判定与多候选选择顺序。
 *
 * <p>这些用例是纯几何的：命中测试不依赖掩码、颜色、ROI，因此可以把每条优先级规则
 * 单独钉住，而不必去构造一张恰好满足条件的图片。</p>
 */
public class TargetHitTesterTest {

    private static final float EPS = 0.001f;

    // ---------------------------------------------------------------- 基础判定

    @Test
    public void touchInsideTheBoxIsAHit() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        TargetHitTestResult result = new TargetHitTester(0).hitTest(150f, 150f, one(box));

        assertTrue(result.isHit());
        assertSame(box, result.selectedBox);
        assertEquals(1, result.hitCount());
        assertEquals(1, result.candidateCount);
        assertEquals(0, result.tolerancePx);
    }

    @Test
    public void touchFarOutsideIsAMiss() {
        DetectedTargetBox box = box(100, 100, 200, 200, 0.8f);
        TargetHitTestResult result = new TargetHitTester(10).hitTest(260f, 150f, one(box));

        assertFalse(result.isHit());
        assertNull(result.selectedBox);
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
        final TargetBoundingBox returned = result.selectedBox.rectInVideo;
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

    // ---------------------------------------------------------------- 多候选优先级

    /**
     * 优先级 1：严格包含（不看容差）胜过"靠容差勉强够到" —— 即使后者面积小得多。
     */
    @Test
    public void strictContainmentBeatsToleranceEvenWhenTheOtherBoxIsSmaller() {
        DetectedTargetBox big = box(100, 100, 200, 200, 0.5f);      // 严格包含 (150,150)，面积大
        DetectedTargetBox small = box(160, 140, 180, 160, 0.5f);    // 面积小，但不严格包含
        TargetHitTestResult result = new TargetHitTester(20).hitTest(150f, 150f,
                Arrays.asList(small, big));

        assertTrue(result.isHit());
        assertEquals(2, result.hitCount());
        assertSame("严格包含者必须优先", big, result.selectedBox);
    }

    /**
     * 优先级 2：都没有严格包含时，触摸点到框中心更近者优先。
     */
    @Test
    public void closerCenterWinsWhenNeitherContainsTheTouch() {
        DetectedTargetBox near = box(120, 80, 200, 120, 0.5f);      // 中心 (160,100)，距 (100,100) = 60
        DetectedTargetBox far = box(140, 80, 220, 120, 0.5f);       // 中心 (180,100)，距 (100,100) = 80
        TargetHitTestResult result = new TargetHitTester(50).hitTest(100f, 100f,
                Arrays.asList(far, near));

        assertEquals(2, result.hitCount());
        assertSame("中心更近者必须优先", near, result.selectedBox);
    }

    /**
     * 优先级 3：同为中心距离、都严格包含时，面积更小者优先。
     */
    @Test
    public void smallerAreaWinsWhenCentersAndContainmentTie() {
        DetectedTargetBox outer = box(100, 100, 200, 200, 0.5f);    // 中心 (150,150)，面积 10000
        DetectedTargetBox inner = box(120, 120, 180, 180, 0.5f);    // 中心 (150,150)，面积 3600
        TargetHitTestResult result = new TargetHitTester(0).hitTest(150f, 150f,
                Arrays.asList(outer, inner));

        assertEquals(2, result.hitCount());
        assertSame("中心重合时面积更小者优先", inner, result.selectedBox);
    }

    /**
     * 优先级 4：前三级全部打平时，置信度更高者优先。
     */
    @Test
    public void higherConfidenceWinsWhenEverythingElseTies() {
        DetectedTargetBox low = box(100, 100, 200, 200, 0.3f);
        DetectedTargetBox high = box(100, 100, 200, 200, 0.9f);
        TargetHitTestResult result = new TargetHitTester(0).hitTest(150f, 150f,
                Arrays.asList(low, high));

        assertSame("置信度更高者优先", high, result.selectedBox);
    }

    /**
     * 顺序必须<b>确定</b>：同样的输入换一下列表顺序，结果仍然一致。
     * 这是"复杂重叠策略留到 Slice 6"的前提 —— 本 Slice 至少不能是不确定的。
     */
    @Test
    public void selectionIsIndependentOfInputOrder() {
        DetectedTargetBox a = box(100, 100, 200, 200, 0.5f);
        DetectedTargetBox b = box(120, 120, 180, 180, 0.5f);
        DetectedTargetBox c = box(140, 140, 160, 160, 0.5f);

        DetectedTargetBox first = new TargetHitTester(0)
                .hitTest(150f, 150f, Arrays.asList(a, b, c)).selectedBox;
        DetectedTargetBox second = new TargetHitTester(0)
                .hitTest(150f, 150f, Arrays.asList(c, a, b)).selectedBox;
        DetectedTargetBox third = new TargetHitTester(0)
                .hitTest(150f, 150f, Arrays.asList(b, c, a)).selectedBox;

        assertSame(first, second);
        assertSame(first, third);
        assertSame("三者都严格包含，面积最小的 c 应当胜出", c, first);
    }

    @Test
    public void allHitsAreReturnedSortedByPriority() {
        DetectedTargetBox outer = box(100, 100, 300, 300, 0.5f);    // 面积 40000
        DetectedTargetBox middle = box(120, 120, 240, 240, 0.5f);   // 面积 14400
        DetectedTargetBox inner = box(140, 140, 180, 180, 0.5f);    // 面积 1600
        TargetHitTestResult result = new TargetHitTester(0).hitTest(160f, 160f,
                Arrays.asList(outer, middle, inner));

        assertEquals(3, result.hitCount());
        List<DetectedTargetBox> hits = result.hitCandidates;
        assertSame(inner, hits.get(0));
        assertSame(middle, hits.get(1));
        assertSame(outer, hits.get(2));
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
