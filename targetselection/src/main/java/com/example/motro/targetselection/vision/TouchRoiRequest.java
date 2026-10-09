package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;

/**
 * 一次「触摸点 → 局部 ROI → 蓝色掩码」请求，<b>不可变</b>。
 *
 * <p><b>为什么要有几何快照</b>：{@link #videoX}/{@link #videoY} 是<b>视频（解码帧）</b>
 * 坐标，而像素只能按 {@link CapturedFrame} 的<b>帧</b>坐标去读。两者在「回读帧被
 * {@code maxFrameLongSidePx} 缩小」「View 有黑边」等情况下并不相等，必须借
 * {@link VideoGeometry} 换算。没有几何就<b>退化假设「帧就是视频帧」</b>（1:1）——
 * 这对「直接把解码帧给分析」的通道是对的，对 View 回读通道只是兜底，
 * 所以 {@link #geometry} 为 null 时不要指望多精确。</p>
 *
 * <p>半径不在这里：它属于长期策略，取自
 * {@link com.example.motro.targetselection.api.TargetSelectionConfig#roiRadiusX} /
 * {@code roiRadiusY}，由 {@link BlueMaskAnalyzer} 注入。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享（典型用法：主线程按点击构造，工作线程消费）。</p>
 */
public final class TouchRoiRequest {

    /** 待分析的帧，非 null；其像素所有权仍在调用方（分析器不释放它）。 */
    public final CapturedFrame frame;

    /** 几何快照，可为 null（null 表示「帧坐标 == 视频坐标」）。 */
    public final VideoGeometry geometry;

    /** 点击点在视频像素坐标下的 x。 */
    public final float videoX;

    /** 点击点在视频像素坐标下的 y。 */
    public final float videoY;

    private TouchRoiRequest(CapturedFrame frame, VideoGeometry geometry, float videoX, float videoY) {
        this.frame = frame;
        this.geometry = geometry;
        this.videoX = videoX;
        this.videoY = videoY;
    }

    /**
     * @param frame    待分析帧，非 null
     * @param geometry 几何快照，可为 null
     * @param videoX   视频像素 x
     * @param videoY   视频像素 y
     * @throws IllegalArgumentException frame 为 null
     */
    public static TouchRoiRequest of(CapturedFrame frame, VideoGeometry geometry,
                                     float videoX, float videoY) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        return new TouchRoiRequest(frame, geometry, videoX, videoY);
    }

    /**
     * 由一次坐标映射结果构造 —— 点击路径的标准接法，避免把 {@code viewX} 当成
     * {@code videoX} 传进来（这两者是最容易被顺手写错的一对参数）。
     *
     * @return 映射成功时返回请求；{@code mapping} 为 null 或未拿到视频坐标时返回 <b>null</b>
     */
    public static TouchRoiRequest forMapping(CapturedFrame frame, VideoGeometry geometry,
                                            VideoPointMappingResult mapping) {
        if (frame == null || mapping == null || !mapping.hasVideoCoordinates()) {
            return null;
        }
        return new TouchRoiRequest(frame, geometry, mapping.videoX, mapping.videoY);
    }

    @Override
    public String toString() {
        return "TouchRoiRequest{video=" + videoX + "," + videoY
                + ", frame=" + frame.width + "x" + frame.height
                + ", geometry=" + (geometry == null ? "none(1:1)" : geometry) + "}";
    }
}
