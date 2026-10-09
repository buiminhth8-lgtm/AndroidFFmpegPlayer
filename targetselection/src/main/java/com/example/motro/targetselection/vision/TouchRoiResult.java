package com.example.motro.targetselection.vision;

/**
 * {@link TouchRoiExtractor} 的完整产物，<b>不可变</b>：状态 + ROI 几何 + 裁出来的 ARGB 像素。
 *
 * <p><b>像素的所有权（必须遵守）</b>：{@link #argb} 是本对象独占的缓冲，
 * 由提取器每次调用<b>新分配</b>，可安全地跨线程传给下游（例如
 * {@link BlueMaskDetector}）。之所以不复用共享缓冲：ROI 只有几百 KB，
 * 一次点击分配一次完全可接受，而共享缓冲会引入「结果里的数组被下一次分析改写」这类
 * 极难排查的别名 bug —— 用一次分配换掉一类竞态，这个取舍很划算。</p>
 *
 * <p>失败时 {@link #roi} 与 {@link #argb} 均为 null，{@link #status} 说明原因。
 * 调用方必须先判 {@link #status}，不要靠 null 检查分支。</p>
 *
 * <p>线程约定：不可变对象（{@link #argb} 视为只读），可跨线程安全共享。</p>
 */
public final class TouchRoiResult {

    /** 结局。 */
    public final TouchRoiStatus status;

    /** ROI 几何；仅 {@link TouchRoiStatus#SUCCESS} 时非 null。 */
    public final TouchRoi roi;

    /**
     * 裁剪出的 ARGB_8888 像素，行优先排列，长度 = {@code roi.frameWidth * roi.frameHeight}；
     * 仅 {@link TouchRoiStatus#SUCCESS} 时非 null。被视为只读，调用方不得修改。
     */
    public final int[] argb;

    /** 失败原因（英文，供日志抓取）；成功时为 null。 */
    public final String debugReason;

    private TouchRoiResult(TouchRoiStatus status, TouchRoi roi, int[] argb, String debugReason) {
        this.status = status;
        this.roi = roi;
        this.argb = argb;
        this.debugReason = debugReason;
    }

    static TouchRoiResult success(TouchRoi roi, int[] argb) {
        return new TouchRoiResult(TouchRoiStatus.SUCCESS, roi, argb, null);
    }

    static TouchRoiResult failure(TouchRoiStatus status, String reason) {
        return new TouchRoiResult(status, null, null, reason);
    }

    /** 是否拿到了 ROI 与像素。 */
    public boolean isUsable() {
        return status == TouchRoiStatus.SUCCESS && roi != null && argb != null;
    }

    /** 像素总数；不可用时为 0。 */
    public int pixelCount() {
        return roi == null ? 0 : roi.pixelCount();
    }

    public String debugString() {
        return "status=" + status
                + " roi=" + (roi == null ? "-" : roi.debugString())
                + " pixels=" + (argb == null ? 0 : argb.length)
                + (debugReason == null ? "" : " reason=" + debugReason);
    }

    @Override
    public String toString() {
        return "TouchRoiResult{" + debugString() + "}";
    }
}
