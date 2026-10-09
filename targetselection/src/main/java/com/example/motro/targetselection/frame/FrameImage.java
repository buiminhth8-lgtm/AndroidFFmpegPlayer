package com.example.motro.targetselection.frame;

/**
 * 模块统一的图像载体。**这是本模块与“具体播放器/相机帧格式”之间的唯一收敛点。**
 *
 * <p>为什么要这层抽象，而不是直接在 {@link CapturedFrame} 里放 {@code Bitmap}：</p>
 * <ul>
 *   <li><b>可复用</b>：FFmpegPlayer 回读 NV12/OES、Media3 给 Bitmap、VLC 给 Bitmap、
 *       Camera2 给 Image、GStreamer 给 map 后的 ByteBuffer —— 各写一个适配实现即可，
 *       核心 API 不用改，也不被任何一方绑死。</li>
 *   <li><b>可测</b>：核心调用链能在纯 JVM 单测里跑通，不需要 Robolectric 或真机
 *       （{@code Bitmap} 无法在 JVM 中实例化）。</li>
 *   <li><b>生命周期明确</b>：释放语义收敛到 {@link #release()} 一个方法，
 *       由 {@link CapturedFrame} 统一触发，避免各处各自 recycle。</li>
 * </ul>
 *
 * <p>Android 平台最常用的实现是 {@link BitmapFrameImage}。</p>
 *
 * <p>线程约定：实现应保证 {@link #release()} 幂等且可被调用方线程安全地调用一次；
 * {@link #width()}/{@link #height()} 允许在释放后继续返回历史尺寸。</p>
 */
public interface FrameImage {

    /** 图像宽度（像素）。 */
    int width();

    /** 图像高度（像素）。 */
    int height();

    /**
     * 释放底层资源（{@link BitmapFrameImage} 会按所有权决定是否 {@code recycle()}）。
     * 必须幂等：重复调用不得抛异常、不得重复回收。
     */
    void release();
}
