package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * <b>ROI 局部像素坐标</b>下的整数矩形，左右/上下均为<b>闭区间</b>（与
 * {@link BinaryMask} 的像素索引一致），不可变。
 *
 * <p><b>为什么必须单独有这么一个类型（别用 {@link TargetBoundingBox} 顶替）</b>：
 * {@code TargetBoundingBox} 的语义是<b>视频像素坐标</b>。检测阶段只能在 ROI 局部坐标里
 * 干活（掩码就是这个尺度的），如果直接拿 {@code TargetBoundingBox} 装局部坐标，
 * 那么"某个矩形到底是局部还是视频"就只靠变量名和注释区分了 —— 这类错误不会有编译期提示，
 * 也不会崩，只会让命中测试<b>悄悄判错</b>。用两个类型把它们分开以后，
 * {@link #toVideoRect} 就成了两套坐标之间<b>唯一</b>的通道，转换公式只有一份。</p>
 *
 * <p><b>为什么闭区间而不是左闭右开</b>：掩码索引、行内连续段（{@code runs()}）都是闭区间，
 * 检测代码全程在闭区间上算；回到视频坐标时才在 {@link #toVideoRect} 里一次性
 * 把「像素索引区间」翻成「像素外沿区间」（{@code [left, right]} → {@code [left, right+1)} 的外沿）。</p>
 *
 * <p><b>ROI 局部 ≠ 视频，而且两者往往还不是 1:1（关键）</b>：ROI 是按<b>视频</b>像素半径
 * 展开、再换算到<b>分析帧</b>像素后裁剪的；当 {@code maxFrameLongSidePx} 把回读帧缩小时，
 * 帧上 200px 可能对应视频上 400px。所以转换必须是「偏移 + 缩放」而不是单纯加
 * {@code roi.left}。只有帧未被缩放时才退化成后者。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class RoiRect {

    /** 左边界（含）。 */
    public final int left;

    /** 上边界（含）。 */
    public final int top;

    /** 右边界（含）。 */
    public final int right;

    /** 下边界（含）。 */
    public final int bottom;

    private RoiRect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * @param right  含；必须 &ge; {@code left}
     * @param bottom 含；必须 &ge; {@code top}
     * @throws IllegalArgumentException 右/下边界小于左/上边界
     */
    public static RoiRect of(int left, int top, int right, int bottom) {
        if (right < left) {
            throw new IllegalArgumentException("right must be >= left but was " + right + " < " + left);
        }
        if (bottom < top) {
            throw new IllegalArgumentException("bottom must be >= top but was " + bottom + " < " + top);
        }
        return new RoiRect(left, top, right, bottom);
    }

    /**
     * @throws IllegalArgumentException 宽或高不为正
     */
    public static RoiRect ofSize(int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be > 0 but were " + width + "x" + height);
        }
        return new RoiRect(left, top, left + width - 1, top + height - 1);
    }

    /** 像素宽度（闭区间，因此 = right - left + 1，最小为 1）。 */
    public int width() {
        return right - left + 1;
    }

    /** 像素高度（闭区间，最小为 1）。 */
    public int height() {
        return bottom - top + 1;
    }

    /** 像素面积。 */
    public long area() {
        return (long) width() * (long) height();
    }

    /** 局部坐标是否落在本矩形内（闭区间）。 */
    public boolean contains(int x, int y) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /**
     * 是否贴着 ROI 边界 —— 说明这个矩形是被 ROI <b>截断</b>的，真实目标框比它大。
     * 上层据此降低可信度：截断框的位置在边界方向上是不可信的。
     */
    public boolean touchesBoundary(int roiWidth, int roiHeight) {
        return left <= 0 || top <= 0 || right >= roiWidth - 1 || bottom >= roiHeight - 1;
    }

    /**
     * 换算到<b>视频像素坐标</b>。这是本类型存在的意义：ROI 局部 → 视频的公式只此一处。
     *
     * <p>做法：像素索引区间 {@code [left, right]} 代表一个宽 {@code right-left+1} 的像素带，
     * 它映射到视频上的外沿区间 {@code [roi.left + left*s, roi.left + (right+1)*s)}，
     * 其中 {@code s = roi.width() / roiWidth} 是帧像素 → 视频像素的缩放。
     * 用外沿（而不是索引）作为结果，是为了让视频矩形描述「这块区域实际覆盖的画面范围」，
     * 与 {@code TargetBoundingBox} 的几何语义一致。</p>
     *
     * @param roiRectInVideo ROI 自身的视频坐标矩形（{@code BlueMaskResult.roiRectInVideo}），非 null
     * @param roiWidth       ROI 在分析帧上的宽度（{@code BlueMaskResult.roiWidth}），&gt; 0
     * @param roiHeight      ROI 在分析帧上的高度，&gt; 0
     * @throws IllegalArgumentException 任一参数非法
     */
    public TargetBoundingBox toVideoRect(TargetBoundingBox roiRectInVideo, int roiWidth, int roiHeight) {
        if (roiRectInVideo == null) {
            throw new IllegalArgumentException("roiRectInVideo must not be null");
        }
        if (roiWidth <= 0 || roiHeight <= 0) {
            throw new IllegalArgumentException("roiWidth/roiHeight must be > 0 but were "
                    + roiWidth + "x" + roiHeight);
        }
        final float scaleX = roiRectInVideo.width() / (float) roiWidth;
        final float scaleY = roiRectInVideo.height() / (float) roiHeight;
        return TargetBoundingBox.of(
                roiRectInVideo.left + left * scaleX,
                roiRectInVideo.top + top * scaleY,
                roiRectInVideo.left + (right + 1) * scaleX,
                roiRectInVideo.top + (bottom + 1) * scaleY);
    }

    @Override
    public String toString() {
        return "RoiRect{" + left + "," + top + "," + right + "," + bottom
                + " " + width() + "x" + height() + "}";
    }
}
