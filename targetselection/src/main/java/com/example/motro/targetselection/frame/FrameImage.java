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

    /**
     * 读取一块矩形区域的像素，格式为 <b>ARGB_8888 打包整数</b>
     * （与 {@code android.graphics.Bitmap.getPixels} 完全一致：{@code 0xAARRGGBB}）。
     *
     * <p><b>为什么像素回读要放在这个接口上，而不是让调用方去 {@code instanceof Bitmap}</b>：
     * {@link FrameImage} 是本模块与「具体帧格式」之间的唯一收敛点。把「能不能读像素」
     * 也当作这一层的能力，后续接入 NV12/OES/ImageReader 的实现就只需各自实现一次，
     * 而 ROI 裁剪与颜色分析全部代码一行不改 —— 这正是「播放器无关」在像素层面的含义。</p>
     *
     * <p><b>默认实现返回 false</b>（不支持像素回读），因此纯元数据通道、只有尺寸的
     * 占位实现无需写任何代码即可继续编译；只有真正能出像素的载体才覆写它。
     * 调用方<b>必须</b>把 false 当作「本载体读不出像素」并向上报明确状态，
     * 不要退化成「当作全黑」—— 那会让「读不到」伪装成「画面里没有蓝色」。</p>
     *
     * <p><b>调用方契约（重要）</b>：{@code x/y/width/height} 必须是<b>已夹进图像范围内</b>的
     * 合法矩形（{@code x >= 0 && y >= 0 && x + width <= width() && y + height <= height()}），
     * 且 {@code out} 容量足够容纳 {@code height} 行、每行 {@code width} 个像素。
     * 实现可以按平台 API 的语义直接抛越界异常 —— 本方法不做二次校验，
     * 裁剪的边界处理属于 ROI 层（见 {@code TouchRoiExtractor}）的职责。</p>
     *
     * @param x      左边界（含）
     * @param y      上边界（含）
     * @param width  宽度，&gt; 0
     * @param height 高度，&gt; 0
     * @param out    目标数组
     * @param offset 写入起点下标
     * @param stride 相邻两行首元素在 {@code out} 中的间距（通常等于 {@code width}）
     * @return true = 已写入；false = 本载体不支持像素回读（或底层图像已释放）
     */
    default boolean readArgbRegion(int x, int y, int width, int height,
                                   int[] out, int offset, int stride) {
        return false;
    }
}
