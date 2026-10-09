package com.example.motro.targetselection.frame;

/**
 * 一次帧获取的结果：模块统一的图像载体 + 尺寸 + 采集时间戳。不可变（图像句柄可释放）。
 *
 * <p><b>图像载体</b>：用 {@link FrameImage} 作为统一载体，平台默认实现是
 * {@link BitmapFrameImage}。这样做的好处是：核心 API 不被 {@code Bitmap} 绑死
 * （Camera2 的 {@code Image}、Media3/VLC 的位图都能各写一个适配），
 * 且核心调用链能在纯 JVM 单测里跑通。</p>
 *
 * <p><b>所有权与释放（必须遵守）</b>：</p>
 * <ul>
 *   <li>{@link VideoFrameSource} 返回本对象即把所有权移交给调用方（控制器）。</li>
 *   <li>控制器保证在本次分析结束前调用 {@link #release()}，无论成功、失败还是抛异常。</li>
 *   <li>{@link #release()} 幂等，内部委托给 {@link FrameImage#release()}；
 *       是否真正 {@code recycle()} 由载体自己的所有权标志决定。</li>
 *   <li>释放后 {@link #image()} 返回 null、{@link #hasImage()} 为 false；
 *       尺寸与时间戳字段仍可读（它们是历史记录，不随释放失效）。</li>
 * </ul>
 *
 * <p>线程约定：时间戳/尺寸字段为 final，可安全跨线程读；图像句柄读写受内部锁保护。</p>
 */
public final class CapturedFrame {

    /** {@link #ptsUs} 的哨兵值：来源未提供 PTS。 */
    public static final long NO_PTS_US = -1L;

    /** {@link #frameTimestampNs} 的哨兵值：来源未提供帧时间戳。 */
    public static final long NO_TIMESTAMP_NS = -1L;

    private final Object lock = new Object();
    private FrameImage image;
    private boolean released;

    /** 图像宽度（像素）；无图像载荷时为 0。 */
    public final int width;

    /** 图像高度（像素）；无图像载荷时为 0。 */
    public final int height;

    /** 采集**发起**时刻（{@code System.nanoTime()} 基准）。 */
    public final long captureRequestNs;

    /** 采集**完成**时刻（{@code System.nanoTime()} 基准），用于算真实取帧延迟。 */
    public final long captureCompletedNs;

    /** 解码帧 PTS（微秒），无则 {@link #NO_PTS_US}；与 {@link FrameCaptureRequest#targetPtsUs} 同语义。 */
    public final long ptsUs;

    /** 帧时间戳（纳秒，媒体时间轴），无则 {@link #NO_TIMESTAMP_NS}。 */
    public final long frameTimestampNs;

    private CapturedFrame(FrameImage image, int width, int height,
                          long captureRequestNs, long captureCompletedNs,
                          long ptsUs, long frameTimestampNs) {
        this.image = image;
        this.width = width;
        this.height = height;
        this.captureRequestNs = captureRequestNs;
        this.captureCompletedNs = captureCompletedNs;
        this.ptsUs = ptsUs;
        this.frameTimestampNs = frameTimestampNs;
    }

    /**
     * 创建帧。
     *
     * @param image             图像载荷，可为 null（表示本次没有可用画面 → {@code NO_FRAME}）
     * @param width             宽度；&lt;= 0 时回退为 {@code image.width()}（无 image 则为 0）
     * @param height            高度；&lt;= 0 时回退为 {@code image.height()}（无 image 则为 0）
     * @param captureRequestNs  采集发起时刻（{@code System.nanoTime()}）
     * @param captureCompletedNs 采集完成时刻（{@code System.nanoTime()}）
     * @param ptsUs             解码帧 PTS（微秒），未知传 {@link #NO_PTS_US}
     * @param frameTimestampNs  帧时间戳（纳秒），未知传 {@link #NO_TIMESTAMP_NS}
     */
    public static CapturedFrame of(FrameImage image, int width, int height,
                                   long captureRequestNs, long captureCompletedNs,
                                   long ptsUs, long frameTimestampNs) {
        int resolvedWidth = width > 0 ? width : (image != null ? image.width() : 0);
        int resolvedHeight = height > 0 ? height : (image != null ? image.height() : 0);
        return new CapturedFrame(image, resolvedWidth, resolvedHeight,
                captureRequestNs, captureCompletedNs, ptsUs, frameTimestampNs);
    }

    /** 便捷重载：尺寸取自载体，不带 PTS / 帧时间戳。 */
    public static CapturedFrame ofImage(FrameImage image, long captureRequestNs, long captureCompletedNs) {
        return of(image, 0, 0, captureRequestNs, captureCompletedNs, NO_PTS_US, NO_TIMESTAMP_NS);
    }

    /** 无图像载荷的“空帧”，用于显式表达 {@code NO_FRAME}。 */
    public static CapturedFrame empty(long captureRequestNs, long captureCompletedNs) {
        return new CapturedFrame(null, 0, 0, captureRequestNs, captureCompletedNs,
                NO_PTS_US, NO_TIMESTAMP_NS);
    }

    /** 图像载荷；已释放或无载荷时返回 null。 */
    public FrameImage image() {
        synchronized (lock) {
            return released ? null : image;
        }
    }

    public boolean hasImage() {
        return image() != null;
    }

    public boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    public boolean hasPts() {
        return ptsUs != NO_PTS_US;
    }

    public boolean hasFrameTimestamp() {
        return frameTimestampNs != NO_TIMESTAMP_NS;
    }

    /**
     * 释放图像载荷。幂等；具体回收策略由 {@link FrameImage} 实现决定。
     * 释放后 {@link #image()} 返回 null，尺寸/时间戳仍可读。
     */
    public void release() {
        final FrameImage toRelease;
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            toRelease = image;
            image = null;
        }
        // 锁外释放：不持锁等待系统回收
        if (toRelease != null) {
            toRelease.release();
        }
    }

    @Override
    public String toString() {
        return "CapturedFrame{" + width + "x" + height
                + ", released=" + isReleased()
                + ", captureMs=" + (captureCompletedNs - captureRequestNs) / 1_000_000.0
                + ", ptsUs=" + ptsUs + ", frameTimestampNs=" + frameTimestampNs + "}";
    }
}
