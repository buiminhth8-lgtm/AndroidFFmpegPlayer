package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;

/**
 * 几何模型（{@link VideoGeometry}）新增能力测试：缩放方式字段、按缩放方式自动推导内容区、
 * 以及重新适配。坐标数值的正确性由 {@link VideoCoordinateMapperTest} 负责，这里只盯模型自洽性。
 */
public class VideoGeometryScaleTest {

    @Test
    public void createWithScaleDerivesContentRectForBothScaleTypes() {
        VideoGeometry fit = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);
        assertSame(VideoScaleType.FIT_CENTER, fit.scaleType);
        assertEquals(0, fit.contentLeft);
        assertEquals(25, fit.contentTop);
        assertEquals(100, fit.contentRight);
        assertEquals(75, fit.contentBottom);
        assertTrue(fit.isUsable());

        VideoGeometry crop = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP);
        assertSame(VideoScaleType.CENTER_CROP, crop.scaleType);
        // 铺满语义：可见区域就是整个 View，没有黑边
        assertEquals(0, crop.contentLeft);
        assertEquals(0, crop.contentTop);
        assertEquals(100, crop.contentRight);
        assertEquals(100, crop.contentBottom);
        assertTrue(crop.isUsable());

        // 推导结果必须与 mapper 的推导完全一致（单一来源，不许两处算法漂移）
        int[] expected = VideoCoordinateMapper.deriveContentRect(100, 100, 200, 100, VideoScaleType.CENTER_CROP, 0);
        assertEquals(expected[0], crop.contentLeft);
        assertEquals(expected[1], crop.contentTop);
        assertEquals(expected[2], crop.contentRight);
        assertEquals(expected[3], crop.contentBottom);
    }

    @Test
    public void createWithScaleAccountsForRotationAxisSwap() {
        // 100x50 转 90° 后显示为 50x100：铺进方形 View 时应左右留黑边，而不是上下
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 100, 100,
                VideoScaleType.FIT_CENTER, 90, false, false);
        assertEquals(90, geometry.rotationDegrees);
        assertEquals(25, geometry.contentLeft);
        assertEquals(75, geometry.contentRight);
        assertEquals(0, geometry.contentTop);
        assertEquals(100, geometry.contentBottom);
    }

    @Test
    public void explicitContentRectFactoryDefaultsToFitCenter() {
        VideoGeometry geometry = VideoGeometry.create(200, 100, 100, 100, 0, 25, 100, 75, 0, false, false);
        assertSame(VideoScaleType.FIT_CENTER, geometry.scaleType);
        // 调用方自带 contentRect 时不做任何“善意重算”，原样保留
        assertEquals(25, geometry.contentTop);
        assertEquals(75, geometry.contentBottom);
    }

    @Test
    public void withScaleTypeRefitsTheContentRect() {
        VideoGeometry fit = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);
        VideoGeometry crop = fit.withScaleType(VideoScaleType.CENTER_CROP);

        assertSame(VideoScaleType.CENTER_CROP, crop.scaleType);
        assertEquals(0, crop.contentTop);
        assertEquals(100, crop.contentHeight());
        // 视频尺寸不变，只是可见窗口变了
        assertEquals(200, crop.videoWidth);
        assertEquals(100, crop.viewHeight);
    }

    @Test
    public void withScaleTypeOnDegenerateGeometryStaysUnusable() {
        // 尺寸非法时推导不出内容区：保留原样，绝不把退化几何悄悄变成“看起来可用”
        VideoGeometry degenerate = VideoGeometry.EMPTY.withScaleType(VideoScaleType.CENTER_CROP);
        assertSame(VideoScaleType.CENTER_CROP, degenerate.scaleType);
        assertFalse(degenerate.isUsable());
    }

    @Test
    public void copyHelpersPreserveScaleType() {
        VideoGeometry crop = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP);
        assertSame(VideoScaleType.CENTER_CROP, crop.withContent(0, 0, 50, 50).scaleType);
        assertSame(VideoScaleType.CENTER_CROP, crop.withViewSize(120, 120).scaleType);
        assertSame(VideoScaleType.CENTER_CROP, crop.withOrientation(180, false, false).scaleType);
    }

    @Test
    public void nullScaleTypeFallsBackToFitCenter() {
        assertSame(VideoScaleType.FIT_CENTER,
                VideoGeometry.createWithScale(200, 100, 100, 100, null).scaleType);
        assertSame(VideoScaleType.FIT_CENTER,
                VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP).withScaleType(null).scaleType);
    }

    @Test
    public void emptyGeometryCarriesDefaultsAndStaysUnusable() {
        assertSame(VideoScaleType.FIT_CENTER, VideoGeometry.EMPTY.scaleType);
        assertEquals(0, VideoGeometry.EMPTY.rotationDegrees);
        assertFalse(VideoGeometry.EMPTY.isUsable());
    }
}
