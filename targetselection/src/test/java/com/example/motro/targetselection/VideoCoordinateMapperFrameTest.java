package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;

/**
 * 「视频坐标 → 分析帧坐标」这一步的契约。
 *
 * <p>这层存在的理由：{@code VideoCoordinateMapper.map} 给出的视频坐标以<b>解码帧</b>为尺度，
 * 而 {@code CapturedFrame} 是从承载 View 回读、通常还被
 * {@code maxFrameLongSidePx} 缩小过的<b>另一幅图</b>。少了这一步，
 * 「用视频坐标去帧里裁像素」会在有黑边或被缩小的情况下系统性取错区域。</p>
 */
public class VideoCoordinateMapperFrameTest {

    private static final float EPS = 0.01f;

    @Test
    public void nullOrUnusableGeometryYieldsNull() {
        assertNull(VideoCoordinateMapper.mapVideoToFrame(null, 640, 360, 100f, 100f));
        assertNull(VideoCoordinateMapper.mapVideoToFrame(
                VideoGeometry.EMPTY, 640, 360, 100f, 100f));
    }

    @Test
    public void nonPositiveFrameSizeYieldsNull() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        assertNull(VideoCoordinateMapper.mapVideoToFrame(geometry, 0, 360, 10f, 10f));
        assertNull(VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 0, 10f, 10f));
    }

    @Test
    public void nonFiniteInputYieldsNull() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        assertNull(VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, Float.NaN, 10f));
        assertNull(VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, 10f, Float.POSITIVE_INFINITY));
    }

    /** 1:1：帧尺寸 == 视频尺寸，帧坐标应当等于视频坐标。 */
    @Test
    public void oneToOneFrameKeepsVideoCoordinates() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        float[] frame = VideoCoordinateMapper.mapVideoToFrame(geometry, 1280, 720, 640f, 360f);
        assertNotNull(frame);
        assertEquals(640f, frame[0], EPS);
        assertEquals(360f, frame[1], EPS);
        assertEquals(1f, VideoCoordinateMapper.videoToFrameScale(geometry, 1280, 720), EPS);
    }

    /** 回读帧被缩小到一半（默认 maxFrameLongSidePx=640 下的典型场景）。 */
    @Test
    public void downscaledFrameHalvesCoordinatesAndScale() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        float[] frame = VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, 640f, 360f);
        assertNotNull(frame);
        assertEquals(320f, frame[0], EPS);
        assertEquals(180f, frame[1], EPS);
        assertEquals(0.5f, VideoCoordinateMapper.videoToFrameScale(geometry, 640, 360), EPS);
    }

    /**
     * FIT_CENTER 黑边：视频 1000x500 放进 1000x1000 的 View，上下各 250px 黑边。
     * 帧是<b>整个 View</b> 的等比缩放（500x500），所以黑边也在帧里 ——
     * 这正是「按 View 尺寸折算而不是按内容区折算」的判据。
     */
    @Test
    public void letterboxedFrameScalesByViewSizeNotContentSize() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1000, 500, 1000, 1000, VideoScaleType.FIT_CENTER);
        assertEquals(250, geometry.contentTop);
        assertEquals(500, geometry.contentHeight());

        // 视频正中心 → View (500,500) → 帧 (250,250)
        float[] center = VideoCoordinateMapper.mapVideoToFrame(geometry, 500, 500, 500f, 250f);
        assertNotNull(center);
        assertEquals(250f, center[0], EPS);
        assertEquals(250f, center[1], EPS);

        // 视频左上角 → View (0,250) → 帧 (0,125)：正好是黑边下沿，而不是帧的顶边
        float[] topLeft = VideoCoordinateMapper.mapVideoToFrame(geometry, 500, 500, 0f, 0f);
        assertNotNull(topLeft);
        assertEquals(0f, topLeft[0], EPS);
        assertEquals(125f, topLeft[1], EPS);

        // 半径换算：视频 200px → 内容区 200px → 帧 100px
        assertEquals(0.5f, VideoCoordinateMapper.videoToFrameScale(geometry, 500, 500), EPS);
    }

    /** CENTER_CROP：内容区等于整个 View（画面被裁），帧坐标应当铺满整个帧。 */
    @Test
    public void centerCropSendsVisibleCornersToFrameCorners() {
        // 视频 2:1 放进方形 View：横向被裁掉一半，纵向铺满
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1000, 500, 500, 500, VideoScaleType.CENTER_CROP);
        assertTrue(geometry.scaleType.isCrop());

        // 可见窗口四角（视频坐标）应当正推到帧的四角附近
        float[] topLeft = VideoCoordinateMapper.mapVideoToFrame(geometry, 500, 500, 250f, 0f);
        float[] bottomRight = VideoCoordinateMapper.mapVideoToFrame(geometry, 500, 500, 750f, 500f);
        assertNotNull(topLeft);
        assertNotNull(bottomRight);
        assertEquals(0f, topLeft[0], EPS);
        assertEquals(0f, topLeft[1], EPS);
        assertEquals(500f, bottomRight[0], EPS);
        assertEquals(500f, bottomRight[1], EPS);

        // 被裁掉的区域（视频最左/最右）被夹到帧边缘，且纵横比保持一致
        assertEquals(0.5f, VideoCoordinateMapper.videoToFrameScale(geometry, 500, 500), EPS);
    }

    /** 旋转 90°：视频的横向在显示与帧里变成纵向，比例必须按显示宽度推导才自洽。 */
    @Test
    public void rotatedGeometryUsesDisplayedWidthForScale() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER, 90, false, false);

        // 视频正中心始终映射到 View 正中心 → 帧正中心
        float[] frame = VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, 640f, 360f);
        assertNotNull(frame);
        assertEquals(320f, frame[0], 0.5f);
        assertEquals(180f, frame[1], 0.5f);

        // 视频→内容区 0.5625，内容区→帧 0.5，合成 0.28125
        assertEquals(0.28125f, VideoCoordinateMapper.videoToFrameScale(geometry, 640, 360), EPS);

        // 一致性：视频横向 1280px 走完整幅视频，在旋转后落到帧的纵向 360px 上
        assertEquals(360f, 1280f * VideoCoordinateMapper.videoToFrameScale(geometry, 640, 360), 0.5f);
    }

    /** 结果恒被夹在帧范围内，调用方拿去裁像素永远不会越界。 */
    @Test
    public void resultIsClampedIntoFrameBounds() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        float[] frame = VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, 1280f, 720f);
        assertNotNull(frame);
        assertEquals(640f, frame[0], EPS);
        assertEquals(360f, frame[1], EPS);

        float[] origin = VideoCoordinateMapper.mapVideoToFrame(geometry, 640, 360, 0f, 0f);
        assertNotNull(origin);
        assertEquals(0f, origin[0], EPS);
        assertEquals(0f, origin[1], EPS);
    }

    @Test
    public void scaleIsNaNWhenGeometryOrFrameSizeUnusable() {
        assertTrue(Float.isNaN(VideoCoordinateMapper.videoToFrameScale(null, 640, 360)));
        assertTrue(Float.isNaN(VideoCoordinateMapper.videoToFrameScale(
                VideoGeometry.EMPTY, 640, 360)));
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 1280, 720, VideoScaleType.FIT_CENTER);
        assertTrue(Float.isNaN(VideoCoordinateMapper.videoToFrameScale(geometry, 0, 360)));
    }
}
