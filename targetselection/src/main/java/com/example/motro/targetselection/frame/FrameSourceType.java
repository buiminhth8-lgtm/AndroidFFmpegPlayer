package com.example.motro.targetselection.frame;

/**
 * 一帧画面是从哪类承载 View 抓来的。**播放器无关**：只描述“取像通道”，
 * 不出现任何具体播放器/相机 SDK 的名字。
 *
 * <p>用途是把同一套取帧 API 的不同实现标注清楚，便于上层按来源做诊断
 * （例如 GL 渲染的 SurfaceView 与 TextureView 的取像特性并不一样）。
 * 新增第三方来源（Camera2 适配、自定义录制回读等）时一律用 {@link #UNKNOWN}，
 * 不需要为此扩展本枚举。</p>
 */
public enum FrameSourceType {

    /** 来源未知或未标注（自定义 {@link VideoFrameSource} 实现的默认值）。 */
    UNKNOWN,

    /** 从 {@code SurfaceView} 的 {@code Surface} 经 PixelCopy 回读。 */
    SURFACE_VIEW,

    /** 从 {@code TextureView} 的位图取出。 */
    TEXTURE_VIEW
}
