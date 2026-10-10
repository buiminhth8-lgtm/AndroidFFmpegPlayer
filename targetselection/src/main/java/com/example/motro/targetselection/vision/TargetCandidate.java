package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 一次命中测试得到的候选目标，不可变。
 *
 * <p>{@link #targetId} 允许为 null / 空串，表示“框找到了但 id 拿不到”（像素法蓝框命中但 OCR 失败、
 * 元数据法只有框没有 id）。控制器据此产出 {@code HIT_ID_UNKNOWN} 而不是伪装成 HIT，
 * 因为“看得见框但读不出编号”和“什么都没有”对上层是完全不同的处理。</p>
 *
 * <p>{@link #hitType} 与 {@link #candidateCount} 是 Slice 6 加上的<b>可选诊断信息</b>：
 * 前者说明这次命中是"点在框里"还是"靠容差够到"，后者说明当时有几个候选参与竞争。
 * 两者都可能为 null / 0（例如元数据法根本没有"候选框竞争"这个概念），
 * 因此<b>不得</b>用于业务判断，只用于日志、埋点与调试展示。</p>
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

    /** 命中分级；实现方不区分时为 null。仅用于诊断。 */
    public final TargetHitType hitType;

    /** 本次竞争该框的候选总数（含未命中者）；未知为 0。仅用于诊断。 */
    public final int candidateCount;

    private TargetCandidate(String targetId, TargetBoundingBox boundingBox, float confidence,
                            TargetHitType hitType, int candidateCount) {
        this.targetId = targetId;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
        this.hitType = hitType;
        this.candidateCount = candidateCount;
    }

    /**
     * @param targetId    目标 id，未知传 null 或空串
     * @param boundingBox 目标框，非 null
     * @param confidence  置信度，会被裁剪到 [0,1]
     * @throws IllegalArgumentException boundingBox 为 null
     */
    public static TargetCandidate of(String targetId, TargetBoundingBox boundingBox, float confidence) {
        return of(targetId, boundingBox, confidence, null, 0);
    }

    /**
     * 带命中诊断信息的版本。
     *
     * @param hitType        命中分级，未知传 null
     * @param candidateCount 候选总数，未知传 0；负值会被置 0
     * @throws IllegalArgumentException boundingBox 为 null
     */
    public static TargetCandidate of(String targetId, TargetBoundingBox boundingBox, float confidence,
                                     TargetHitType hitType, int candidateCount) {
        if (boundingBox == null) {
            throw new IllegalArgumentException("boundingBox must not be null");
        }
        float clamped = Float.isNaN(confidence) ? 0f : Math.max(0f, Math.min(1f, confidence));
        return new TargetCandidate(
                targetId == null || targetId.isEmpty() ? null : targetId,
                boundingBox,
                clamped,
                hitType,
                Math.max(0, candidateCount));
    }

    /** 只有框、没有 id 的候选（蓝框命中但 OCR 失败）。 */
    public static TargetCandidate withoutId(TargetBoundingBox boundingBox, float confidence) {
        return of(null, boundingBox, confidence);
    }

    /** 只有框、没有 id，但带命中诊断信息。 */
    public static TargetCandidate withoutId(TargetBoundingBox boundingBox, float confidence,
                                            TargetHitType hitType, int candidateCount) {
        return of(null, boundingBox, confidence, hitType, candidateCount);
    }

    public boolean hasId() {
        return targetId != null;
    }

    @Override
    public String toString() {
        return "TargetCandidate{targetId=" + targetId
                + ", box=" + boundingBox + ", confidence=" + confidence
                + ", hitType=" + hitType + ", candidateCount=" + candidateCount + "}";
    }
}
