package com.example.motro.targetselection.frame;

/**
 * 一次帧获取请求的参数，不可变。
 *
 * <p>语义定位：{@link com.example.motro.targetselection.api.TargetSelectionConfig} 描述“默认策略”，
 * 本类描述“本次调用要什么”。控制器按配置构造本请求后传给 {@link VideoFrameSource}，
 * 因此实现方只需理解本类。</p>
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

    public FrameCaptureRequest(long targetPtsUs, long timeoutMs, int maxLongSidePx, long maxFrameAgeNs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0 but was " + timeoutMs);
        }
        if (maxLongSidePx < 0) {
            throw new IllegalArgumentException("maxLongSidePx must be >= 0 but was " + maxLongSidePx);
        }
        if (maxFrameAgeNs < 0) {
            throw new IllegalArgumentException("maxFrameAgeNs must be >= 0 but was " + maxFrameAgeNs);
        }
        this.targetPtsUs = targetPtsUs;
        this.timeoutMs = timeoutMs;
        this.maxLongSidePx = maxLongSidePx;
        this.maxFrameAgeNs = maxFrameAgeNs;
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

    @Override
    public String toString() {
        return "FrameCaptureRequest{targetPtsUs=" + targetPtsUs
                + ", timeoutMs=" + timeoutMs
                + ", maxLongSidePx=" + maxLongSidePx
                + ", maxFrameAgeNs=" + maxFrameAgeNs + "}";
    }
}
