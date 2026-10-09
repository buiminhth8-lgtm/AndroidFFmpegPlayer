package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.geometry.VideoGeometry;

import org.junit.Test;

/**
 * 坐标映射数学测试。用例都是能手算验证的整数比，避免“测试自己算错还不知道”。
 * 约定：显示内容 = 镜像( 顺时针旋转( 解码帧 ) )。
 */
public class VideoGeometryTest {

    private static final float EPS = 0.001f;

    @Test
    public void identityMappingWhenNoLetterboxNoRotation() {
        VideoGeometry geometry = VideoGeometry.create(100, 50, 100, 50);
        assertTrue(geometry.isUsable());

        float[] mapped = geometry.mapViewPointToVideo(25f, 12f);
        assertNotNull(mapped);
        assertEquals(25f, mapped[0], EPS);
        assertEquals(12f, mapped[1], EPS);
    }

    @Test
    public void letterboxBarsAreRejectedInsteadOfBeingTreatedAsHit() {
        // 2:1 视频放进正方形 View：上下各留 25px 黑边，内容区 = [0,25,100,75]
        VideoGeometry geometry = VideoGeometry.create(200, 100, 100, 100, 0, 25, 100, 75, 0, false, false);
        assertTrue(geometry.isUsable());
        assertEquals(100, geometry.contentWidth());
        assertEquals(50, geometry.contentHeight());

        assertNull("黑边上的点击不该映射成坐标", geometry.mapViewPointToVideo(50f, 10f));
        assertNull(geometry.mapViewPointToVideo(50f, 90f));
        assertFalse(geometry.containsViewPoint(50f, 10f));

        float[] center = geometry.mapViewPointToVideo(50f, 50f);
        assertNotNull(center);
        assertEquals(100f, center[0], EPS);
        assertEquals(50f, center[1], EPS);

        // 内容区顶边 → 视频顶边
        float[] top = geometry.mapViewPointToVideo(50f, 25f);
        assertNotNull(top);
        assertEquals(100f, top[0], EPS);
        assertEquals(0f, top[1], EPS);
    }

    @Test
    public void rotation90MapsDisplayCornersToExpectedVideoCorners() {
        // 100x50 的横向视频做 90° 顺时针旋转后显示在 50x100 的 View 上
        VideoGeometry geometry = VideoGeometry.create(100, 50, 50, 100, 0, 0, 50, 100, 90, false, false);

        float[] displayTopLeft = geometry.mapViewPointToVideo(0f, 0f);
        assertNotNull(displayTopLeft);
        assertEquals(0f, displayTopLeft[0], EPS);
        assertEquals(50f, displayTopLeft[1], EPS);

        float[] center = geometry.mapViewPointToVideo(25f, 50f);
        assertNotNull(center);
        assertEquals(50f, center[0], EPS);
        assertEquals(25f, center[1], EPS);
    }

    @Test
    public void rotation180And270MapCornersConsistently() {
        VideoGeometry r180 = VideoGeometry.create(100, 50, 100, 50, 0, 0, 100, 50, 180, false, false);
        float[] topLeft180 = r180.mapViewPointToVideo(0f, 0f);
        assertNotNull(topLeft180);
        assertEquals(100f, topLeft180[0], EPS);
        assertEquals(50f, topLeft180[1], EPS);

        VideoGeometry r270 = VideoGeometry.create(100, 50, 50, 100, 0, 0, 50, 100, 270, false, false);
        float[] topLeft270 = r270.mapViewPointToVideo(0f, 0f);
        assertNotNull(topLeft270);
        assertEquals(100f, topLeft270[0], EPS);
        assertEquals(0f, topLeft270[1], EPS);
    }

    @Test
    public void mirrorXFlipsDisplayHorizontally() {
        VideoGeometry mirrored = VideoGeometry.create(100, 50, 100, 50, 0, 0, 100, 50, 0, true, false);
        float[] mapped = mirrored.mapViewPointToVideo(0f, 0f);
        assertNotNull(mapped);
        assertEquals(100f, mapped[0], EPS);
        assertEquals(0f, mapped[1], EPS);
    }

    @Test
    public void mirrorYFlipsDisplayVertically() {
        VideoGeometry mirrored = VideoGeometry.create(100, 50, 100, 50, 0, 0, 100, 50, 0, false, true);
        float[] mapped = mirrored.mapViewPointToVideo(0f, 0f);
        assertNotNull(mapped);
        assertEquals(0f, mapped[0], EPS);
        assertEquals(50f, mapped[1], EPS);
    }

    @Test
    public void mappedCoordinatesAreClampedIntoVideoBounds() {
        VideoGeometry geometry = VideoGeometry.create(100, 50, 100, 50);
        float[] max = geometry.mapViewPointToVideo(100f, 50f);
        assertNotNull(max);
        assertEquals(100f, max[0], EPS);
        assertEquals(50f, max[1], EPS);
    }

    @Test
    public void emptyAndDegenerateGeometryAreNotUsable() {
        assertFalse(VideoGeometry.EMPTY.isUsable());
        assertFalse(VideoGeometry.create(0, 50, 100, 50).isUsable());
        assertFalse(VideoGeometry.create(100, 50, 0, 50).isUsable());
        // 内容区越界 → 不可用，宁可报 GEOMETRY_UNAVAILABLE 也不猜坐标
        assertFalse(VideoGeometry.create(100, 50, 100, 50, 0, 0, 120, 50, 0, false, false).isUsable());
        assertNull(VideoGeometry.EMPTY.mapViewPointToVideo(1f, 1f));
    }

    @Test
    public void illegalRotationFallsBackToZero() {
        // 45 不是 90 的倍数，无法反解 → 归零，绝不留下无法映射的快照
        assertEquals(0, VideoGeometry.create(10, 10, 10, 10, 0, 0, 10, 10, 45, false, false).rotationDegrees);
        // 负数角度按模 360 归一
        assertEquals(270, VideoGeometry.create(10, 10, 10, 10, 0, 0, 10, 10, -90, false, false).rotationDegrees);
        assertEquals(180, VideoGeometry.create(10, 10, 10, 10, 0, 0, 10, 10, -180, false, false).rotationDegrees);
        assertEquals(90, VideoGeometry.create(10, 10, 10, 10, 0, 0, 10, 10, 450, false, false).rotationDegrees);
    }

    @Test
    public void copyHelpersRebuildConsistentSnapshots() {
        VideoGeometry geometry = VideoGeometry.create(200, 100, 100, 100);
        VideoGeometry resized = geometry.withViewSize(200, 200);
        assertEquals(200, resized.viewWidth);
        assertEquals(200, resized.viewHeight);
        assertEquals(200, resized.contentWidth());

        VideoGeometry repositioned = geometry.withContent(5, 5, 95, 95);
        assertEquals(90, repositioned.contentWidth());
        assertEquals(200, repositioned.videoWidth);

        VideoGeometry oriented = geometry.withOrientation(90, true, true);
        assertEquals(90, oriented.rotationDegrees);
        assertTrue(oriented.mirrorX);
        assertTrue(oriented.mirrorY);
    }
}
