package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 一次「点击点附近的局部检索区域」，<b>不可变</b>。它是 {@link TouchRoiExtractor} 的产物，
 * 也是 {@link BlueMaskDetector} 的输入。
 *
 * <p><b>为什么同时保留两套坐标（关键，别删任何一个）</b>：</p>
 * <ul>
 *   <li>{@link #videoRect}：<b>视频像素坐标</b>下的 ROI。给调试叠加层、日志、以及后续
 *       把检测结果映射回目标框用 —— 对外交流一律用视频坐标。</li>
 *   <li>{@link #frameLeft}/{@link #frameTop}/{@link #frameWidth}/{@link #frameHeight}：
 *       <b>分析帧像素坐标</b>下的实际裁剪矩形。像素只能按这个尺度去读，
 *       因为 {@code CapturedFrame} 是从 View 回读并可能被
 *       {@link com.example.motro.targetselection.api.TargetSelectionConfig#maxFrameLongSidePx}
 *       缩小的，与解码帧尺寸并不相同。</li>
 *   <li>{@link #touchFrameX}/{@link #touchFrameY} 与 {@link #touchLocalX}/{@link #touchLocalY}：
 *       点击点在帧坐标 / ROI 局部坐标下的位置。后续 Label ROI（Slice 7/8）要从
 *       「点击点上方/下方」找 `id:xxxx` 文字，需要的就是局部坐标。</li>
 * </ul>
 *
 * <p>两套坐标由同一个几何推导得出（见
 * {@link com.example.motro.targetselection.geometry.VideoCoordinateMapper#mapVideoToFrame}），
 * 不是两份独立实现；取整会带来 1px 级别差异，属于预期。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TouchRoi {

    /** 视频像素坐标下的 ROI（已夹在视频范围内）。 */
    public final TargetBoundingBox videoRect;

    /** 分析帧坐标下的裁剪左边界。 */
    public final int frameLeft;

    /** 分析帧坐标下的裁剪上边界。 */
    public final int frameTop;

    /** 裁剪宽度（&gt; 0）。 */
    public final int frameWidth;

    /** 裁剪高度（&gt; 0）。 */
    public final int frameHeight;

    /** 点击点在<b>分析帧</b>坐标下的 x（可为小数）。 */
    public final float touchFrameX;

    /** 点击点在<b>分析帧</b>坐标下的 y（可为小数）。 */
    public final float touchFrameY;

    /** 点击点在 ROI <b>局部</b>坐标下的 x（整数像素，已夹进 ROI 内）。 */
    public final int touchLocalX;

    /** 点击点在 ROI <b>局部</b>坐标下的 y（整数像素，已夹进 ROI 内）。 */
    public final int touchLocalY;

    private TouchRoi(TargetBoundingBox videoRect,
                     int frameLeft, int frameTop, int frameWidth, int frameHeight,
                     float touchFrameX, float touchFrameY) {
        this.videoRect = videoRect;
        this.frameLeft = frameLeft;
        this.frameTop = frameTop;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.touchFrameX = touchFrameX;
        this.touchFrameY = touchFrameY;
        // 局部坐标取整后夹进 ROI：像素索引必须是合法下标，否则下游读像素会越界
        this.touchLocalX = clampLocal(Math.round(touchFrameX) - frameLeft, frameWidth);
        this.touchLocalY = clampLocal(Math.round(touchFrameY) - frameTop, frameHeight);
    }

    /**
     * @param videoRect   视频坐标下的 ROI，非 null
     * @param frameLeft   帧坐标裁剪左边界
     * @param frameTop    帧坐标裁剪上边界
     * @param frameWidth  裁剪宽度，必须 &gt; 0
     * @param frameHeight 裁剪高度，必须 &gt; 0
     * @param touchFrameX 点击点在帧坐标下的 x
     * @param touchFrameY 点击点在帧坐标下的 y
     * @throws IllegalArgumentException videoRect 为 null、宽高 &le; 0，或坐标非有限值
     */
    public static TouchRoi of(TargetBoundingBox videoRect,
                              int frameLeft, int frameTop, int frameWidth, int frameHeight,
                              float touchFrameX, float touchFrameY) {
        if (videoRect == null) {
            throw new IllegalArgumentException("videoRect must not be null");
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("frameWidth/frameHeight must be > 0 but were "
                    + frameWidth + "x" + frameHeight);
        }
        if (!Float.isFinite(touchFrameX) || !Float.isFinite(touchFrameY)) {
            throw new IllegalArgumentException("touchFrameX/Y must be finite but were "
                    + touchFrameX + "," + touchFrameY);
        }
        return new TouchRoi(videoRect, frameLeft, frameTop, frameWidth, frameHeight,
                touchFrameX, touchFrameY);
    }

    /** 帧坐标下的裁剪右边界（不含）。 */
    public int frameRight() {
        return frameLeft + frameWidth;
    }

    /** 帧坐标下的裁剪下边界（不含）。 */
    public int frameBottom() {
        return frameTop + frameHeight;
    }

    /** 像素总数。 */
    public int pixelCount() {
        return frameWidth * frameHeight;
    }

    /** 局部坐标是否落在 ROI 内。 */
    public boolean containsLocal(int localX, int localY) {
        return localX >= 0 && localY >= 0 && localX < frameWidth && localY < frameHeight;
    }

    /** 一行摘要，便于与 {@code CapturedFrame}/{@code VideoGeometry} 的日志对齐。 */
    public String debugString() {
        return "videoRect=[" + videoRect.left + "," + videoRect.top
                + "," + videoRect.right + "," + videoRect.bottom + "]"
                + " frameRect=[" + frameLeft + "," + frameTop + "," + frameRight() + "," + frameBottom() + "]"
                + " " + frameWidth + "x" + frameHeight
                + " touchFrame=" + touchFrameX + "," + touchFrameY
                + " touchLocal=" + touchLocalX + "," + touchLocalY;
    }

    private static int clampLocal(long value, int limit) {
        if (value < 0L) {
            return 0;
        }
        return value >= limit ? limit - 1 : (int) value;
    }

    @Override
    public String toString() {
        return "TouchRoi{" + debugString() + "}";
    }
}
