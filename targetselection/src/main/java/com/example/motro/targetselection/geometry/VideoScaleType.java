package com.example.motro.targetselection.geometry;

/**
 * 解码画面（旋转之后）适配到承载 View 时的缩放方式。**播放器无关**：语义对齐
 * {@code android.widget.ImageView.ScaleType#FIT_CENTER} / {@code CENTER_CROP}，
 * 但不引用任何 Android 类型，Media3 / VLC / GStreamer / Camera2 预览也能用同一套取值描述自己。
 *
 * <p><b>它与 {@link VideoGeometry#contentLeft}/{@code contentTop} 等的关系</b>：
 * {@code contentRect} 始终表示「View 坐标下**可见**的视频区域」。</p>
 * <ul>
 *   <li>{@link #FIT_CENTER}：等比完整显示。可见区域 == 实际画面，
 *       多出来的部分就是 letterbox / pillarbox 黑边 → {@code contentRect} 小于 View。</li>
 *   <li>{@link #CENTER_CROP}：等比铺满。可见区域 == 整个 View（无黑边），
 *       超出部分被裁掉 → {@code contentRect} 等于 View，被裁掉的范围由
 *       {@link VideoCoordinateMapper} 依据 View/视频尺寸反推。</li>
 * </ul>
 *
 * <p>线程约定：枚举常量，天然线程安全。</p>
 */
public enum VideoScaleType {

    /** 等比缩放到完整可见，四周可能留黑边（letterbox / pillarbox）。 */
    FIT_CENTER,

    /** 等比缩放到铺满 View，超出的部分被裁掉（无黑边）。 */
    CENTER_CROP;

    /** 是否属于「铺满并裁切」语义 —— 此时可见区域等于整个 View，坐标需要反推裁切窗口。 */
    public boolean isCrop() {
        return this == CENTER_CROP;
    }
}
