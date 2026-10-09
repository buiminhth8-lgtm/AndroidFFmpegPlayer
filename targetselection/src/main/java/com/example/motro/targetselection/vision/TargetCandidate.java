package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 一次命中测试得到的候选目标，不可变。
 *
 * <p>{@link #targetId} 允许为 null / 空串，表示“框找到了但 id 拿不到”（像素法蓝框命中但 OCR 失败、
 * 元数据法只有框没有 id）。控制器据此产出 {@code HIT_ID_UNKNOWN} 而不是伪装成 HIT，
 * 因为“看得见框但读不出编号”和“什么都没有”对上层是完全不同的处理。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetCandidate {

    /** 目标 id；null 或空串表示 id 未知。 */
    public final String targetId;

    /** 目标框，视频像素坐标。 */
    public final TargetBoundingBox boundingBox;

    /** 置信度，约定取值 [0,1]。像素法可给 OCR 置信度，元数据法通常给 1。 */
    public final float confidence;

    private TargetCandidate(String targetId, TargetBoundingBox boundingBox, float confidence) {
        this.targetId = targetId;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
    }

    /**
     * @param targetId    目标 id，未知传 null 或空串
     * @param boundingBox 目标框，非 null
     * @param confidence  置信度，会被裁剪到 [0,1]
     * @throws IllegalArgumentException boundingBox 为 null
     */
    public static TargetCandidate of(String targetId, TargetBoundingBox boundingBox, float confidence) {
        if (boundingBox == null) {
            throw new IllegalArgumentException("boundingBox must not be null");
        }
        float clamped = Float.isNaN(confidence) ? 0f : Math.max(0f, Math.min(1f, confidence));
        return new TargetCandidate(
                targetId == null || targetId.isEmpty() ? null : targetId,
                boundingBox,
                clamped);
    }

    /** 只有框、没有 id 的候选（蓝框命中但 OCR 失败）。 */
    public static TargetCandidate withoutId(TargetBoundingBox boundingBox, float confidence) {
        return of(null, boundingBox, confidence);
    }

    public boolean hasId() {
        return targetId != null;
    }

    @Override
    public String toString() {
        return "TargetCandidate{targetId=" + targetId
                + ", box=" + boundingBox + ", confidence=" + confidence + "}";
    }
}
