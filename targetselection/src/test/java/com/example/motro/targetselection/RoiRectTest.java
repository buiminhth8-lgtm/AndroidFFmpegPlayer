package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.RoiRect;

import org.junit.Test;

/**
 * {@link RoiRect} 的坐标语义测试。重点是那唯一一条「ROI 局部 → 视频」的换算：
 * 它是 Slice 5 里最容易被写错、而且写错了也不会崩的地方（只会让命中判定整体偏移）。
 */
public class RoiRectTest {

    @Test
    public void ofSizeTreatsBoundsAsInclusive() {
        RoiRect rect = RoiRect.ofSize(10, 20, 5, 7);
        assertEquals(10, rect.left);
        assertEquals(20, rect.top);
        assertEquals(14, rect.right);
        assertEquals(26, rect.bottom);
        assertEquals(5, rect.width());
        assertEquals(7, rect.height());
        assertEquals(35L, rect.area());
    }

    @Test
    public void singlePixelRectHasSizeOne() {
        RoiRect rect = RoiRect.of(3, 4, 3, 4);
        assertEquals(1, rect.width());
        assertEquals(1, rect.height());
        assertTrue(rect.contains(3, 4));
        assertFalse(rect.contains(4, 4));
    }

    @Test
    public void invertedBoundsAreRejected() {
        try {
            RoiRect.of(10, 0, 5, 10);
            fail("right < left 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("right"));
        }
        try {
            RoiRect.of(0, 10, 10, 5);
            fail("bottom < top 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bottom"));
        }
    }

    @Test
    public void degenerateSizeIsRejected() {
        try {
            RoiRect.ofSize(0, 0, 0, 10);
            fail("宽度为 0 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("width"));
        }
    }

    @Test
    public void containsIsInclusiveOnEveryEdge() {
        RoiRect rect = RoiRect.of(10, 10, 20, 30);
        assertTrue(rect.contains(10, 10));
        assertTrue(rect.contains(20, 30));
        assertTrue(rect.contains(15, 20));
        assertFalse(rect.contains(9, 20));
        assertFalse(rect.contains(21, 20));
    }

    @Test
    public void touchesBoundaryDetectsEveryRoiEdge() {
        final int width = 100;
        final int height = 80;
        assertTrue("贴左边界", RoiRect.of(0, 20, 50, 60).touchesBoundary(width, height));
        assertTrue("贴上边界", RoiRect.of(20, 0, 50, 60).touchesBoundary(width, height));
        assertTrue("贴右边界", RoiRect.of(20, 20, 99, 60).touchesBoundary(width, height));
        assertTrue("贴下边界", RoiRect.of(20, 20, 50, 79).touchesBoundary(width, height));
        assertFalse("完全在内", RoiRect.of(20, 20, 50, 60).touchesBoundary(width, height));
    }

    /**
     * 帧未被缩放时（{@code maxFrameLongSidePx} 不生效），局部坐标 → 视频坐标
     * 退化成单纯的偏移，这正是 spec 里写的「+ roi.left/top」那种情形。
     */
    @Test
    public void toVideoRectIsAPlainOffsetWhenScaleIsOne() {
        TargetBoundingBox roiVideo = TargetBoundingBox.ofSize(300, 200, 400, 400);
        RoiRect local = RoiRect.of(10, 20, 59, 79);   // 50x60
        TargetBoundingBox video = local.toVideoRect(roiVideo, 400, 400);

        assertEquals(310f, video.left, 0.001f);
        assertEquals(220f, video.top, 0.001f);
        assertEquals(360f, video.right, 0.001f);      // 300 + (59 + 1)
        assertEquals(280f, video.bottom, 0.001f);     // 200 + (79 + 1)
        assertEquals(50f, video.width(), 0.001f);
        assertEquals(60f, video.height(), 0.001f);
    }

    /**
     * 关键回归：回读帧被缩小到一半时，ROI 上的 200px 对应视频上的 400px。
     * 少了缩放（只加偏移）会让所有返回坐标缩水一半 —— 而且画面"看起来还行"，
     * 属于最难发现的一类错误。
     */
    @Test
    public void toVideoRectAppliesScaleWhenTheFrameIsDownscaled() {
        TargetBoundingBox roiVideo = TargetBoundingBox.ofSize(200, 100, 400, 400);
        RoiRect local = RoiRect.of(0, 0, 199, 199);   // 占满 200x200 的帧坐标 ROI
        TargetBoundingBox video = local.toVideoRect(roiVideo, 200, 200);

        assertEquals(200f, video.left, 0.001f);
        assertEquals(100f, video.top, 0.001f);
        assertEquals(600f, video.right, 0.001f);
        assertEquals(500f, video.bottom, 0.001f);
        assertEquals(400f, video.width(), 0.001f);
        assertEquals(400f, video.height(), 0.001f);
    }

    @Test
    public void toVideoRectUsesExclusiveOuterEdge() {
        TargetBoundingBox roiVideo = TargetBoundingBox.ofSize(0, 0, 800, 600);
        // 单个像素 (0,0)：在 x 与 y 方向都只覆盖 1px 的外沿
        TargetBoundingBox video = RoiRect.of(0, 0, 0, 0).toVideoRect(roiVideo, 400, 300);
        assertEquals(0f, video.left, 0.001f);
        assertEquals(0f, video.top, 0.001f);
        assertEquals(2f, video.right, 0.001f);
        assertEquals(2f, video.bottom, 0.001f);
    }

    @Test
    public void toVideoRectRejectsDegenerateInput() {
        TargetBoundingBox roiVideo = TargetBoundingBox.ofSize(0, 0, 400, 400);
        try {
            RoiRect.of(0, 0, 10, 10).toVideoRect(null, 400, 400);
            fail("roiRectInVideo 为 null 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("roiRectInVideo"));
        }
        try {
            RoiRect.of(0, 0, 10, 10).toVideoRect(roiVideo, 0, 400);
            fail("roiWidth 为 0 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("roiWidth"));
        }
    }
}
