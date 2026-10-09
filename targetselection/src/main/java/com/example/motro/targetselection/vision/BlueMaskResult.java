package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 本 Slice 的最终产物：<b>一次点击 → 局部 ROI → 稳健蓝色掩码</b>的全部结果，
 * 不可变、播放器无关、纯 JVM。
 *
 * <p><b>坐标一律用视频像素</b>：{@link #roiRectInVideo} 与
 * {@link #touchLocalX}/{@link #touchLocalY} 是给上层与调试层看的；
 * 「局部坐标」以 ROI 左上角为原点，后续 Slice 要从点击点上方找 `id:xxxx` 文字时
 * 用的正是它。ROI 在<b>分析帧</b>里的裁剪矩形属于实现细节，只保留在
 * {@link TouchRoi} 里，不重复暴露（避免两套矩形在调用方眼里"都像是对的"）。</p>
 *
 * <p><b>{@link #mask} 是紧凑位掩码而不是 Bitmap</b>：每个像素 1 bit，
 * 400×400 的 ROI 只要 20KB。这样「调试层、后续矩形恢复层、日志」可以同时持有同一份结果
 * 而不产生多份大缓冲 —— 生产路径上不存在需要及时释放的图像资源，
 * 也就不会出现「忘了 recycle」这类泄漏。</p>
 *
 * <p><b>Debug 模式</b>：{@link #debugRoiArgb} 只在
 * {@code TargetSelectionConfig.debugMaskEnabled} 打开时非 null，且<b>直接引用</b>
 * 本次 ROI 的那块像素缓冲（不复拷）。它是给离线/真机调试核对颜色用的，
 * 调用方<b>不得</b>长期持有、不得修改。默认关闭，因此生产路径不会有额外大数组。</p>
 *
 * <p>线程约定：不可变对象（{@link #debugRoiArgb} 视为只读），可跨线程安全共享。</p>
 */
public final class BlueMaskResult {

    /** 无局部坐标时的哨兵值。0 是合法下标，所以不能用 0 表示「没有」。 */
    public static final float NO_COORDINATE = Float.NaN;

    /** 结局。 */
    public final BlueMaskStatus status;

    /** 视频像素坐标下的 ROI 矩形；ROI 不可用时为 null。 */
    public final TargetBoundingBox roiRectInVideo;

    /** 点击点在 ROI 内的局部 x；ROI 不可用时为 {@link #NO_COORDINATE}。 */
    public final float touchLocalX;

    /** 点击点在 ROI 内的局部 y；ROI 不可用时为 {@link #NO_COORDINATE}。 */
    public final float touchLocalY;

    /** ROI 宽度（分析帧像素）；不可用时为 0。 */
    public final int roiWidth;

    /** ROI 高度（分析帧像素）；不可用时为 0。 */
    public final int roiHeight;

    /** 二值蓝色掩码，<b>永不为 null</b>（失败时是 0×0 的空掩码）。 */
    public final BinaryMask mask;

    /** 掩码中置位像素数（原始值，已含去噪影响，不受 minBluePixelCount 裁剪）。 */
    public final int bluePixelCount;

    /** 置位像素占 ROI 的比例 [0,1]。 */
    public final float bluePixelRatio;

    /** 本结果的处理耗时（毫秒）。 */
    public final long processingMs;

    /** Debug 模式下保留的 ROI 原始 ARGB 像素；默认关闭时恒为 null。 */
    public final int[] debugRoiArgb;

    /** 失败原因（英文，供日志抓取）；成功时为 null。 */
    public final String debugReason;

    private BlueMaskResult(BlueMaskStatus status,
                           TargetBoundingBox roiRectInVideo,
                           float touchLocalX, float touchLocalY,
                           int roiWidth, int roiHeight,
                           BinaryMask mask,
                           int bluePixelCount, float bluePixelRatio,
                           long processingMs, int[] debugRoiArgb, String debugReason) {
        this.status = status;
        this.roiRectInVideo = roiRectInVideo;
        this.touchLocalX = touchLocalX;
        this.touchLocalY = touchLocalY;
        this.roiWidth = roiWidth;
        this.roiHeight = roiHeight;
        this.mask = mask;
        this.bluePixelCount = bluePixelCount;
        this.bluePixelRatio = bluePixelRatio;
        this.processingMs = processingMs;
        this.debugRoiArgb = debugRoiArgb;
        this.debugReason = debugReason;
    }

    static BlueMaskResult of(BlueMaskStatus status, TouchRoi roi,
                             BinaryMask mask, int bluePixelCount, float bluePixelRatio,
                             long processingMs, int[] debugRoiArgb, String debugReason) {
        return new BlueMaskResult(status,
                roi == null ? null : roi.videoRect,
                roi == null ? NO_COORDINATE : roi.touchLocalX,
                roi == null ? NO_COORDINATE : roi.touchLocalY,
                roi == null ? 0 : roi.frameWidth,
                roi == null ? 0 : roi.frameHeight,
                mask == null ? BinaryMask.empty(0, 0) : mask,
                bluePixelCount, bluePixelRatio, processingMs, debugRoiArgb, debugReason);
    }

    /** 输入不可用（帧 / ROI / 像素回读任一环节失败）。 */
    public static BlueMaskResult failure(BlueMaskStatus status, String reason) {
        return of(status, null, BinaryMask.empty(0, 0), 0, 0f, 0L, null, reason);
    }

    /** 是否拿到了可用的 ROI（此时 {@link #roiRectInVideo} 与局部坐标才有意义）。 */
    public boolean hasRoi() {
        return roiRectInVideo != null;
    }

    /** 掩码里是否有蓝色像素。 */
    public boolean hasBluePixels() {
        return bluePixelCount > 0;
    }

    /**
     * 替换处理耗时。给 {@link BlueMaskAnalyzer} 用：它要汇报的是
     * <b>ROI 裁剪 + 颜色分析</b>的端到端耗时，而不只是检测那一段。
     */
    BlueMaskResult withProcessingMs(long newProcessingMs) {
        if (newProcessingMs == processingMs) {
            return this;
        }
        return new BlueMaskResult(status, roiRectInVideo, touchLocalX, touchLocalY,
                roiWidth, roiHeight, mask, bluePixelCount, bluePixelRatio,
                newProcessingMs, debugRoiArgb, debugReason);
    }

    /**
     * 单行摘要，格式稳定（便于日志抓取与真实样本验证脚本解析）：
     * <pre>
     * status=SUCCESS roi=[400.0,260.0,800.0,660.0] local=200.0,200.0 size=400x400
     * blue=1832 ratio=0.011450 mask={"400x400, set=1832"...} ms=3
     * </pre>
     */
    public String debugString() {
        return "status=" + status
                + " roi=" + (roiRectInVideo == null ? "-" : roiRectInVideo)
                + " local=" + touchLocalX + "," + touchLocalY
                + " size=" + roiWidth + "x" + roiHeight
                + " blue=" + bluePixelCount + " ratio=" + bluePixelRatio
                + " mask=" + mask
                + " ms=" + processingMs
                + (debugReason == null ? "" : " reason=" + debugReason);
    }

    @Override
    public String toString() {
        return "BlueMaskResult{" + debugString() + "}";
    }
}
