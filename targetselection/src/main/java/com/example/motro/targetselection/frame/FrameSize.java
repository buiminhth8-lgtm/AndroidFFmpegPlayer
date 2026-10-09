package com.example.motro.targetselection.frame;

/**
 * 取帧要用的目标位图尺寸（像素），不可变。
 *
 * <p>只做一件事：把“View 当前尺寸”与“分析用的最长边上限”折算成一个合法尺寸。
 * 抽成独立的值对象是为了让这段算术能在纯 JVM 里被单测覆盖 ——
 * 缩放出错会导致坐标映射整体偏移，是本模块最容易悄悄坏掉的地方之一。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class FrameSize {

    /** 目标宽度（像素），恒 &gt;= 1。 */
    public final int width;

    /** 目标高度（像素），恒 &gt;= 1。 */
    public final int height;

    private FrameSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * 计算取帧的目标尺寸。
     *
     * <p>规则：View 尺寸非法（任一边 &lt;= 0）时返回 null —— 这是调用方必须显式处理的
     * “尺寸未知”信号，而不是悄悄退化成 1x1；{@code maxLongSidePx &lt;= 0} 或原尺寸
     * 已在限制内时按原尺寸返回；否则等比缩放，并把长边收敛到不超过上限。</p>
     *
     * @param viewWidth     View 宽度（像素）
     * @param viewHeight    View 高度（像素）
     * @param maxLongSidePx 分析用图最长边上限，&lt;= 0 表示不缩放
     * @return 目标尺寸；View 尺寸非法时返回 null
     */
    public static FrameSize forCapture(int viewWidth, int viewHeight, int maxLongSidePx) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        final int longSide = Math.max(viewWidth, viewHeight);
        if (maxLongSidePx <= 0 || longSide <= maxLongSidePx) {
            return new FrameSize(viewWidth, viewHeight);
        }
        final double scale = (double) maxLongSidePx / (double) longSide;
        final int scaledWidth = Math.max(1, (int) Math.round(viewWidth * scale));
        final int scaledHeight = Math.max(1, (int) Math.round(viewHeight * scale));
        return new FrameSize(scaledWidth, scaledHeight);
    }

    /** 是否与给定尺寸一致（用于判断是否可以走免缩放的快路径）。 */
    public boolean matches(int otherWidth, int otherHeight) {
        return width == otherWidth && height == otherHeight;
    }

    /** 像素总数，用于估算内存占用。 */
    public long pixelCount() {
        return (long) width * (long) height;
    }

    @Override
    public String toString() {
        return "FrameSize{" + width + "x" + height + "}";
    }
}
