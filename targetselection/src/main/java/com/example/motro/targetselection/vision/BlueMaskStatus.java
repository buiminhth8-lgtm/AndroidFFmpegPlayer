package com.example.motro.targetselection.vision;

/**
 * {@link BlueMaskDetector} 的结局。
 *
 * <p>本阶段<b>只回答「这块 ROI 里有没有蓝色像素」</b>，绝不回答「有没有目标」——
 * 蓝框检测成功不等于识别到目标，那是后续 Slice 的事。因此这里没有
 * {@code HIT}/{@code MISS}，只有「掩码非空 / 掩码为空」以及几种输入不可用。</p>
 *
 * <p>{@link #EMPTY_MASK} 与 {@link #SUCCESS} 的区别是「有没有轮到后续处理的意义」：
 * 掩码里蓝色像素少到 {@code minBluePixelCount} 以下时，交给下游只会浪费一次矩形恢复，
 * 所以提前收敛为 {@link #EMPTY_MASK}；但 {@code bluePixelCount}/{@code bluePixelRatio}
 * 仍然如实汇报原始计数，便于调参时看到「差多少」。</p>
 */
public enum BlueMaskStatus {

    /** 掩码里有足够多的蓝色像素（&ge; {@code minBluePixelCount}）。 */
    SUCCESS,

    /** 掩码为空，或蓝色像素数低于 {@code minBluePixelCount}。 */
    EMPTY_MASK,

    /** 帧不可用（空 / 已释放 / 尺寸非法）。 */
    INVALID_FRAME,

    /** ROI 参数或点击坐标不可用，或裁剪后退化。 */
    INVALID_ROI,

    /** 点击点落在视频范围之外。 */
    OUTSIDE_VIDEO,

    /** 帧载体不支持像素回读。 */
    PIXELS_UNAVAILABLE;

    /** 是否拿到了非空的蓝色掩码。 */
    public boolean hasBluePixels() {
        return this == SUCCESS;
    }

    /** 把 ROI 层的结局映射成本层结局（失败原因一一对应，不做有损合并）。 */
    public static BlueMaskStatus fromTouchRoiStatus(TouchRoiStatus status) {
        if (status == null) {
            return INVALID_FRAME;
        }
        switch (status) {
            case SUCCESS:
                return SUCCESS;
            case INVALID_FRAME:
                return INVALID_FRAME;
            case OUTSIDE_VIDEO:
                return OUTSIDE_VIDEO;
            case PIXELS_UNAVAILABLE:
                return PIXELS_UNAVAILABLE;
            case INVALID_ROI:
            default:
                return INVALID_ROI;
        }
    }
}
