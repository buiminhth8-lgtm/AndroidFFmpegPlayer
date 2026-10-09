package com.example.motro.targetselection.vision;

/**
 * {@link TouchRoiExtractor} 的结局。
 *
 * <p>刻意把「帧本身不可用」「ROI 参数/坐标不可用」「点不在视频范围内」「载体不支持像素回读」
 * 分成四种失败：它们对应的修复动作完全不同（重新取帧 / 修配置或调用方 / 拦在黑边点击 /
 * 换一个能读像素的 {@link com.example.motro.targetselection.frame.FrameImage} 实现），
 * 合成一个笼统的 {@code FAILED} 只会让现场排查退化成猜。</p>
 */
public enum TouchRoiStatus {

    /** 成功裁出 ROI 并读到了像素。 */
    SUCCESS,

    /** 帧为空、已释放、无图像载荷，或尺寸非法 —— 这一帧根本没法裁。 */
    INVALID_FRAME,

    /** 半径非法、触摸坐标非有限值，或裁剪后 ROI 退化（宽或高为 0）。 */
    INVALID_ROI,

    /** 触摸点落在视频范围之外（黑边或更远）—— 属于「点错了」而不是「参数错了」。 */
    OUTSIDE_VIDEO,

    /** 帧载体不支持像素回读（只有尺寸、没有像素，例如纯元数据通道）。 */
    PIXELS_UNAVAILABLE;

    /** 是否拿到了可用的 ROI 与像素。 */
    public boolean isUsable() {
        return this == SUCCESS;
    }
}
