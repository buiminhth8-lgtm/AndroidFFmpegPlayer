package com.example.motro.targetselection.geometry;

/**
 * 目标框：**视频像素坐标系**（video pixel space）下的轴对齐矩形，不可变。
 *
 * <p>坐标系约定：原点在视频画面左上角，x 向右、y 向下，单位为视频原始像素，
 * 与 {@link VideoGeometry#videoWidth}/{@link VideoGeometry#videoHeight} 同尺度。
 * 本类不做裁剪，越界值原样保留，由调用方按需要判断。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetBoundingBox {

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    private TargetBoundingBox(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * 创建目标框。四个分量若顺序颠倒会自动归一化（left/right、top/bottom 各自取小/大），
     * 这样上层不必关心 provider 给出的顶点顺序。
     *
     * @throws IllegalArgumentException 任一分量为 NaN / Infinity
     */
    public static TargetBoundingBox of(float left, float top, float right, float bottom) {
        requireFinite(left, "left");
        requireFinite(top, "top");
        requireFinite(right, "right");
        requireFinite(bottom, "bottom");
        return new TargetBoundingBox(
                Math.min(left, right),
                Math.min(top, bottom),
                Math.max(left, right),
                Math.max(top, bottom));
    }

    /**
     * 按“像素左上角 + 尺寸”创建目标框。
     *
     * @throws IllegalArgumentException 宽高为负或任一分量非有限值
     */
    public static TargetBoundingBox ofSize(float x, float y, float width, float height) {
        if (width < 0f || height < 0f) {
            throw new IllegalArgumentException("width/height must be >= 0");
        }
        return of(x, y, x + width, y + height);
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) * 0.5f;
    }

    public float centerY() {
        return (top + bottom) * 0.5f;
    }

    /** 退化框（无面积）视为无效目标框。 */
    public boolean isEmpty() {
        return width() <= 0f || height() <= 0f;
    }

    /** 命中测试：边界视为命中，对“点正好落在框线上”的点击更宽容。 */
    public boolean contains(float videoX, float videoY) {
        return videoX >= left && videoX <= right && videoY >= top && videoY <= bottom;
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    @Override
    public String toString() {
        return "TargetBoundingBox{" + left + "," + top + "," + right + "," + bottom + "}";
    }
}
