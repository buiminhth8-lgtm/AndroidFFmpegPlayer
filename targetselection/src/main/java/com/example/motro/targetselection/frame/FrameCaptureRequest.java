package com.example.motro.targetselection.frame;

/**
 * 一次帧获取请求的参数，不可变。
 *
 * <p>语义定位：{@link com.example.motro.targetselection.api.TargetSelectionConfig} 描述“默认策略”，
 * 本类描述“本次调用要什么”。控制器按配置构造本请求后传给 {@link VideoFrameSource}，
 * 因此实现方只需理解本类。</p>
 *
 * <p><b>刻意保持最小</b>：裁剪/ROI/目标框等在“点击取帧”场景里用不到（分析的是整帧画面，
 * 坐标映射由 geometry 层负责），所以不在这里预留矩形参数 —— 等到真有 ROI 需求时再加，
 * 避免现在就把一条没人走的代码路径固化进 API。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class FrameCaptureRequest {

    /** {@link #targetPtsUs} 的哨兵值：不指定时间点，取当前可用的最新帧。 */
    public static final long NO_TARGET_PTS_US = -1L;

    /** {@link #maxFrameAgeNs} 的哨兵值：不限制帧龄。 */
    public static final long NO_FRAME_AGE_LIMIT_NS = 0L;

    /** {@link #maxLongSidePx} 的哨兵值：不缩放，按原始分辨率分析。 */
    public static final int NO_SCALE_LIMIT = 0;

    /** {@link #requestTimeNs} 的哨兵值：未由调用方标注发起时刻。 */
    public static final long NO_REQUEST_TIME_NS = 0L;

    /** 常用的“取最新帧、不缩放、不限帧龄”请求，timeout 由调用方给出。 */
    public static FrameCaptureRequest latestFrame(long timeoutMs) {
        return new FrameCaptureRequest(NO_TARGET_PTS_US, timeoutMs, NO_SCALE_LIMIT, NO_FRAME_AGE_LIMIT_NS);
    }

    /**
     * 目标帧的 PTS（微秒），用于“分析某一时刻画面”的场景（回放/拖动进度条后点击）。
     * 等于 {@link #NO_TARGET_PTS_US} 时表示取最新帧。
     */
    public final long targetPtsUs;

    /**
     * 本次获取的时间预算（毫秒）。0 表示允许实现立即返回当前可用帧（拿不到就返回 null）。
     * 实现方不得显著超出该预算后再返回。
     */
    public final long timeoutMs;

    /**
     * 分析用图的最长边像素上限（缩放提示，0 = 不缩放）。
     * 只影响分析精度与耗时，不影响返回坐标的坐标系（始终是原始视频像素）。
     */
    public final int maxLongSidePx;

    /**
     * 可接受的帧龄（纳秒，0 = 不限制）。相对请求时刻计算，用于避免“点下去却分析到几百毫秒前的旧帧”。
     * 超出该帧龄时实现应继续等待新帧，或返回 null 让控制器报 {@code NO_FRAME}。
     */
    public final long maxFrameAgeNs;

    /**
     * 请求**发起**时刻（{@code System.nanoTime()} 基准），未知为 {@link #NO_REQUEST_TIME_NS}。
     *
     * <p>用途：让“请求时刻”与实现内部的“实际开始取帧时刻”可区分 ——
     * 请求可能在队列里等过一小会儿，把两者都记下来才能解释端到端延迟。
     * 不填时实现方会在自己进入取帧流程处打点（见
     * {@link CapturedFrame#captureRequestNs}），因此这是可选项而非必填项。</p>
     */
    public final long requestTimeNs;

    public FrameCaptureRequest(long targetPtsUs, long timeoutMs, int maxLongSidePx, long maxFrameAgeNs) {
        this(targetPtsUs, timeoutMs, maxLongSidePx, maxFrameAgeNs, NO_REQUEST_TIME_NS);
    }

    /**
     * @param requestTimeNs 请求发起时刻（{@code System.nanoTime()}），未知传 {@link #NO_REQUEST_TIME_NS}
     * @throws IllegalArgumentException timeoutMs / maxLongSidePx / maxFrameAgeNs / requestTimeNs 为负数
     */
    public FrameCaptureRequest(long targetPtsUs, long timeoutMs, int maxLongSidePx, long maxFrameAgeNs,
                               long requestTimeNs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0 but was " + timeoutMs);
        }
        if (maxLongSidePx < 0) {
            throw new IllegalArgumentException("maxLongSidePx must be >= 0 but was " + maxLongSidePx);
        }
        if (maxFrameAgeNs < 0) {
            throw new IllegalArgumentException("maxFrameAgeNs must be >= 0 but was " + maxFrameAgeNs);
        }
        if (requestTimeNs < 0) {
            throw new IllegalArgumentException("requestTimeNs must be >= 0 but was " + requestTimeNs);
        }
        this.targetPtsUs = targetPtsUs;
        this.timeoutMs = timeoutMs;
        this.maxLongSidePx = maxLongSidePx;
        this.maxFrameAgeNs = maxFrameAgeNs;
        this.requestTimeNs = requestTimeNs;
    }

    /** 复制一份并标注请求发起时刻。 */
    public FrameCaptureRequest withRequestTimeNs(long requestTimeNs) {
        return new FrameCaptureRequest(targetPtsUs, timeoutMs, maxLongSidePx, maxFrameAgeNs, requestTimeNs);
    }

    public boolean hasTargetPts() {
        return targetPtsUs != NO_TARGET_PTS_US;
    }

    public boolean isScaleLimited() {
        return maxLongSidePx != NO_SCALE_LIMIT;
    }

    public boolean isFrameAgeLimited() {
        return maxFrameAgeNs != NO_FRAME_AGE_LIMIT_NS;
    }

    public boolean hasRequestTimeNs() {
        return requestTimeNs != NO_REQUEST_TIME_NS;
    }

    @Override
    public String toString() {
        return "FrameCaptureRequest{targetPtsUs=" + targetPtsUs
                + ", timeoutMs=" + timeoutMs
                + ", maxLongSidePx=" + maxLongSidePx
                + ", maxFrameAgeNs=" + maxFrameAgeNs
                + ", requestTimeNs=" + requestTimeNs + "}";
    }
}
