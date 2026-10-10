package com.example.motro.targetselection.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「蓝色掩码 → 目标框候选 → 触摸命中」这一整段的最终产物，不可变、播放器无关、纯 JVM。
 *
 * <p><b>为什么检测结果与命中结果装在一个对象里</b>：它们是一次点击的两半，分开返回会让调用方
 * 不得不自己维护两份结果的对应关系（还容易把 A 次点击的框和 B 次点击的点配到一起）。
 * 装在一起以后，{@link #selectedBox} 必然是 {@link #candidates} 的子集元素，
 * 「选中的框不在候选列表里」这种自相矛盾的状态在类型层面就不可能出现。</p>
 *
 * <p><b>三个耗时分开记</b>：{@link #detectMs}（掩码 → 候选）、{@link #hitTestMs}（框 + 点 → 命中）、
 * {@link #totalMs}（两者之和加上外层编排）。真实样本上如果发现耗时超标，
 * 这三个数字能立刻指出是哪一段，不用再改代码加打点。</p>
 *
 * <p><b>无 id</b>：本 Slice 不识别 {@code id:xxxx}，所以这里<b>没有</b> targetId 字段 ——
 * 不设比设一个恒为 null 的字段更诚实，免得上层写出"读到了 null 就是识别失败"的误判逻辑。</p>
 *
 * <p>线程约定：不可变对象（列表为不可修改视图），可跨线程安全共享。</p>
 */
public final class TargetBoxDetectionResult {

    /** 检测结局。 */
    public final TargetBoxDetectionStatus status;

    /** 全部候选框，按置信度从高到低；无候选时为空列表。 */
    public final List<DetectedTargetBox> candidates;

    /** 命中的候选框，按选择优先级从高到低；未命中为空列表。 */
    public final List<DetectedTargetBox> hitCandidates;

    /** 最终选中的框；未命中或未做命中测试时为 null。 */
    public final DetectedTargetBox selectedBox;

    /** 候选框总数 = {@link #candidates}{@code .size()}。 */
    public final int candidateCount;

    /** 检测耗时（毫秒）：掩码 → 候选框。 */
    public final long detectMs;

    /** 命中测试耗时（毫秒）；未做命中测试时为 0。 */
    public final long hitTestMs;

    /** 本结果覆盖的总耗时（毫秒）。 */
    public final long totalMs;

    /** 失败原因（英文，供日志抓取）；正常时为 null。 */
    public final String debugReason;

    private TargetBoxDetectionResult(TargetBoxDetectionStatus status,
                                     List<DetectedTargetBox> candidates,
                                     List<DetectedTargetBox> hitCandidates,
                                     DetectedTargetBox selectedBox,
                                     long detectMs, long hitTestMs, long totalMs,
                                     String debugReason) {
        this.status = status;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.hitCandidates = Collections.unmodifiableList(new ArrayList<>(hitCandidates));
        this.selectedBox = selectedBox;
        this.candidateCount = this.candidates.size();
        this.detectMs = Math.max(0L, detectMs);
        this.hitTestMs = Math.max(0L, hitTestMs);
        this.totalMs = Math.max(0L, totalMs);
        this.debugReason = debugReason;
    }

    /** 入参/掩码不可用。 */
    public static TargetBoxDetectionResult failure(TargetBoxDetectionStatus status, String reason) {
        return new TargetBoxDetectionResult(status,
                Collections.emptyList(), Collections.emptyList(), null,
                0L, 0L, 0L, reason);
    }

    /**
     * 检测阶段的产物（还没有触摸点信息）。
     *
     * @param candidates 候选框，按置信度降序；null 视为空
     * @param detectMs   检测耗时毫秒
     */
    public static TargetBoxDetectionResult detected(List<DetectedTargetBox> candidates, long detectMs) {
        final List<DetectedTargetBox> safe = candidates == null ? Collections.emptyList() : candidates;
        return new TargetBoxDetectionResult(
                safe.isEmpty() ? TargetBoxDetectionStatus.NO_BOX : TargetBoxDetectionStatus.SUCCESS,
                safe, Collections.emptyList(), null, detectMs, 0L, detectMs, null);
    }

    /**
     * 挂上命中测试结果 —— 分析器的标准收尾。
     *
     * <p>只有检测到候选时才可能命中，因此这里会顺带校验
     * {@code selectedBox} 确实来自 {@link #candidates}；不一致说明上游串错了数据，
     * 直接抛出来比悄悄返回一个自相矛盾的结果好。</p>
     *
     * @param hit     命中结果，非 null
     * @param totalMs 端到端耗时毫秒
     * @throws IllegalArgumentException hit 为 null，或选中的框不在候选列表里
     */
    public TargetBoxDetectionResult withHitTest(TargetHitTestResult hit, long totalMs) {
        if (hit == null) {
            throw new IllegalArgumentException("hit must not be null");
        }
        if (hit.selectedBox != null && !candidates.contains(hit.selectedBox)) {
            throw new IllegalArgumentException(
                    "selectedBox must come from the candidate list of this result");
        }
        return new TargetBoxDetectionResult(status, candidates, hit.hitCandidates, hit.selectedBox,
                detectMs, hit.processingMs, totalMs, debugReason);
    }

    /** 是否拿到了候选框。 */
    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    /** 是否命中了某个候选框。 */
    public boolean isHit() {
        return selectedBox != null;
    }

    /** 命中候选个数。 */
    public int hitCount() {
        return hitCandidates.size();
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        final StringBuilder b = new StringBuilder(160);
        b.append("detect=").append(status)
                .append(" candidates=").append(candidateCount)
                .append(" hits=").append(hitCount())
                .append(" selected=").append(selectedBox == null ? "-" : selectedBox.debugString())
                .append(" detectMs=").append(detectMs)
                .append(" hitMs=").append(hitTestMs)
                .append(" totalMs=").append(totalMs);
        if (debugReason != null) {
            b.append(" reason=").append(debugReason);
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "TargetBoxDetectionResult{" + debugString() + "}";
    }
}
