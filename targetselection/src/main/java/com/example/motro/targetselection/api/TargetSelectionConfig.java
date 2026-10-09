package com.example.motro.targetselection.api;

import com.example.motro.targetselection.frame.FrameCaptureRequest;

/**
 * 目标选择的默认策略，不可变。用 {@link #toBuilder()} 做局部调整，避免 setter 带来的可变共享。
 *
 * <p>与 {@link FrameCaptureRequest} 的分工：本类是**长期默认策略**（控制器用它构造每次的取帧请求、
 * 决定是否汇报未知 id）；请求对象是**单次调用参数**。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetSelectionConfig {

    /** {@link #maxFrameLongSidePx} 的取值：不缩放。 */
    public static final int NO_SCALE_LIMIT = 0;

    /** {@link #maxFrameAgeNs} 的取值：不限帧龄。 */
    public static final long NO_FRAME_AGE_LIMIT_NS = 0L;

    /** 是否启用。false 时控制器直接返回 {@code CANCELLED}，完全不触达帧来源与识别实现。 */
    public final boolean enabled;

    /** 每次取帧的时间预算（毫秒），会被写入 {@link FrameCaptureRequest#timeoutMs}。 */
    public final long captureTimeoutMs;

    /** 分析用图最长边上限（像素，0 = 不缩放），决定精度/耗时的取舍。 */
    public final int maxFrameLongSidePx;

    /** 可接受帧龄（纳秒，0 = 不限）；用于避免分析到过旧的帧。 */
    public final long maxFrameAgeNs;

    /** 最小置信度 [0,1]；低于该值的候选按 {@code MISS} 处理。 */
    public final float minConfidence;

    /**
     * 是否汇报“命中但 id 未知”。true → {@code HIT_ID_UNKNOWN}；
     * false → 降级为 {@code MISS}（某些上层只认 id，不希望收到半成品结果）。
     */
    public final boolean reportUnknownTargetId;

    private TargetSelectionConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.captureTimeoutMs = builder.captureTimeoutMs;
        this.maxFrameLongSidePx = builder.maxFrameLongSidePx;
        this.maxFrameAgeNs = builder.maxFrameAgeNs;
        this.minConfidence = builder.minConfidence;
        this.reportUnknownTargetId = builder.reportUnknownTargetId;
    }

    /** 默认策略：启用、300ms 取帧预算、分析图最长边 640、不限帧龄、不设置信度门槛、汇报未知 id。 */
    public static TargetSelectionConfig defaults() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .captureTimeoutMs(captureTimeoutMs)
                .maxFrameLongSidePx(maxFrameLongSidePx)
                .maxFrameAgeNs(maxFrameAgeNs)
                .minConfidence(minConfidence)
                .reportUnknownTargetId(reportUnknownTargetId);
    }

    @Override
    public String toString() {
        return "TargetSelectionConfig{enabled=" + enabled
                + ", captureTimeoutMs=" + captureTimeoutMs
                + ", maxFrameLongSidePx=" + maxFrameLongSidePx
                + ", maxFrameAgeNs=" + maxFrameAgeNs
                + ", minConfidence=" + minConfidence
                + ", reportUnknownTargetId=" + reportUnknownTargetId + "}";
    }

    /** 构造器：非法组合在 {@link #build()} 处一次性失败，避免把坏配置带进点击路径。 */
    public static final class Builder {

        private boolean enabled = true;
        private long captureTimeoutMs = 300L;
        private int maxFrameLongSidePx = 640;
        private long maxFrameAgeNs = NO_FRAME_AGE_LIMIT_NS;
        private float minConfidence = 0f;
        private boolean reportUnknownTargetId = true;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder captureTimeoutMs(long captureTimeoutMs) {
            if (captureTimeoutMs < 0) {
                throw new IllegalArgumentException("captureTimeoutMs must be >= 0 but was " + captureTimeoutMs);
            }
            this.captureTimeoutMs = captureTimeoutMs;
            return this;
        }

        public Builder maxFrameLongSidePx(int maxFrameLongSidePx) {
            if (maxFrameLongSidePx < 0) {
                throw new IllegalArgumentException("maxFrameLongSidePx must be >= 0 but was " + maxFrameLongSidePx);
            }
            this.maxFrameLongSidePx = maxFrameLongSidePx;
            return this;
        }

        public Builder maxFrameAgeNs(long maxFrameAgeNs) {
            if (maxFrameAgeNs < 0) {
                throw new IllegalArgumentException("maxFrameAgeNs must be >= 0 but was " + maxFrameAgeNs);
            }
            this.maxFrameAgeNs = maxFrameAgeNs;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minConfidence(float minConfidence) {
            if (Float.isNaN(minConfidence) || minConfidence < 0f || minConfidence > 1f) {
                throw new IllegalArgumentException("minConfidence must be within [0,1] but was " + minConfidence);
            }
            this.minConfidence = minConfidence;
            return this;
        }

        public Builder reportUnknownTargetId(boolean reportUnknownTargetId) {
            this.reportUnknownTargetId = reportUnknownTargetId;
            return this;
        }

        public TargetSelectionConfig build() {
            return new TargetSelectionConfig(this);
        }
    }
}
