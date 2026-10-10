package com.example.motro.targetselection.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「蓝色掩码 → 目标框候选 → 触摸命中 → 多目标选择」这一整段的最终产物，
 * 不可变、播放器无关、纯 JVM。
 *
 * <p><b>为什么检测结果、命中结果与选择结果装在一个对象里</b>：它们是一次点击的三段，
 * 分开返回会让调用方不得不自己维护三份结果的对应关系（还容易把 A 次点击的框和
 * B 次点击的点配到一起）。装在一起以后，{@link #selectedBox} 必然来自 {@link #candidates}，
 * 「选中的框不在候选列表里」这种自相矛盾的状态在类型层面就不可能出现。</p>
 *
 * <p><b>三/四个耗时分开记</b>：{@link #detectMs}（掩码 → 候选）、{@link #hitTestMs}
 * （框 + 点 → 命中）、{@link #selectionMs}（命中 → 选中）、{@link #totalMs}（整条链）。
 * 真实样本上如果发现耗时超标，这几个数字能立刻指出是哪一段，不用再改代码加打点。</p>
 *
 * <p><b>无 id</b>：本 Slice 仍不识别 {@code id:xxxx}，所以这里<b>没有</b> targetId 字段 ——
 * 不设比设一个恒为 null 的字段更诚实，免得上层写出"读到了 null 就是识别失败"的误判逻辑。
 * {@link #selectionConfidence} 也不是"选择正确"的概率，而是<b>选中框自身</b>的置信度
 * （框线完整度 × 达标边数的度量）—— 选择过程本身不臆造新的概率量，
 * 排序依据由 {@link #rankedCandidates} 与 {@link #selectionReason} 完整可查。</p>
 *
 * <p>线程约定：不可变对象（列表为不可修改视图），可跨线程安全共享。</p>
 */
public final class TargetBoxDetectionResult {

    /** 检测结局。 */
    public final TargetBoxDetectionStatus status;

    /** 全部候选框，按置信度从高到低；无候选时为空列表。 */
    public final List<DetectedTargetBox> candidates;

    /** 命中候选的评分，按 rank 从 0 递增（rank 0 即 {@link #selectedBox}）；未命中为空列表。 */
    public final List<TargetCandidateScore> rankedCandidates;

    /** 最终选中的框；未命中或未做选择时为 null。 */
    public final DetectedTargetBox selectedBox;

    /** 命中分级；未命中或未做选择时为 null。 */
    public final TargetHitType hitType;

    /** 选中框的加权总分；未命中为 0。 */
    public final float selectionScore;

    /** 选中框自身的置信度 [0,1]；未命中为 0。见类注释。 */
    public final float selectionConfidence;

    /** 选择理由（英文，供日志抓取）；未命中或未做选择时为 null。 */
    public final String selectionReason;

    /** 候选框总数 = {@link #candidates}{@code .size()}。 */
    public final int candidateCount;

    /** 命中候选个数 = {@link #rankedCandidates}{@code .size()}。 */
    public final int hitCount;

    /** 检测耗时（毫秒）：掩码 → 候选框。 */
    public final long detectMs;

    /** 命中过滤耗时（毫秒）；未做命中测试时为 0。 */
    public final long hitTestMs;

    /** 多目标选择耗时（毫秒）；未做选择时为 0。 */
    public final long selectionMs;

    /** 本结果覆盖的总耗时（毫秒）。 */
    public final long totalMs;

    /** 失败原因（英文，供日志抓取）；正常时为 null。 */
    public final String debugReason;

    private TargetBoxDetectionResult(TargetBoxDetectionStatus status,
                                     List<DetectedTargetBox> candidates,
                                     List<TargetCandidateScore> rankedCandidates,
                                     DetectedTargetBox selectedBox,
                                     TargetHitType hitType, float selectionScore,
                                     float selectionConfidence, String selectionReason,
                                     long detectMs, long hitTestMs, long selectionMs, long totalMs,
                                     String debugReason) {
        this.status = status;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.rankedCandidates = Collections.unmodifiableList(new ArrayList<>(rankedCandidates));
        this.selectedBox = selectedBox;
        this.hitType = hitType;
        this.selectionScore = selectionScore;
        this.selectionConfidence = selectionConfidence;
        this.selectionReason = selectionReason;
        this.candidateCount = this.candidates.size();
        this.hitCount = this.rankedCandidates.size();
        this.detectMs = Math.max(0L, detectMs);
        this.hitTestMs = Math.max(0L, hitTestMs);
        this.selectionMs = Math.max(0L, selectionMs);
        this.totalMs = Math.max(0L, totalMs);
        this.debugReason = debugReason;
    }

    /** 入参/掩码不可用。 */
    public static TargetBoxDetectionResult failure(TargetBoxDetectionStatus status, String reason) {
        return new TargetBoxDetectionResult(status,
                Collections.emptyList(), Collections.emptyList(), null, null, 0f, 0f, null,
                0L, 0L, 0L, 0L, reason);
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
                safe, Collections.emptyList(), null, null, 0f, 0f, null,
                detectMs, 0L, 0L, detectMs, null);
    }

    /**
     * 挂上命中过滤与多目标选择的结果 —— 分析器的标准收尾。
     *
     * <p>只有检测到候选时才可能命中，因此这里会顺带校验
     * {@code decision.selectedBox} 确实来自 {@link #candidates}；不一致说明上游串错了数据，
     * 直接抛出来比悄悄返回一个自相矛盾的结果好。</p>
     *
     * @param hit        命中过滤结果，非 null
     * @param decision   多目标选择结果，非 null
     * @param totalMs    端到端耗时毫秒
     * @throws IllegalArgumentException 任一参数为 null，或选中的框不在候选列表里
     */
    public TargetBoxDetectionResult withHitTest(TargetHitTestResult hit,
                                                TargetSelectionDecision decision,
                                                long totalMs) {
        if (hit == null) {
            throw new IllegalArgumentException("hit must not be null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (decision.selectedBox != null && !candidates.contains(decision.selectedBox)) {
            throw new IllegalArgumentException(
                    "selectedBox must come from the candidate list of this result");
        }
        final DetectedTargetBox selected = decision.selectedBox;
        return new TargetBoxDetectionResult(status, candidates, decision.rankedCandidates,
                selected, decision.hitType(),
                decision.selectionScore(),
                selected == null ? 0f : selected.confidence,
                decision.reason,
                detectMs, hit.processingMs, decision.selectionMs, totalMs, debugReason);
    }

    /** 是否拿到了候选框。 */
    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    /** 是否命中了某个候选框。 */
    public boolean isHit() {
        return selectedBox != null;
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        final StringBuilder b = new StringBuilder(200);
        b.append("detect=").append(status)
                .append(" candidates=").append(candidateCount)
                .append(" hits=").append(hitCount)
                .append(" selected=").append(selectedBox == null ? "-" : selectedBox.debugString())
                .append(" hitType=").append(hitType == null ? "-" : hitType)
                .append(" selScore=").append(selectionScore)
                .append(" selConf=").append(selectionConfidence)
                .append(" detectMs=").append(detectMs)
                .append(" hitMs=").append(hitTestMs)
                .append(" selectMs=").append(selectionMs)
                .append(" totalMs=").append(totalMs);
        if (selectionReason != null) {
            b.append(" reason=").append(selectionReason);
        }
        if (debugReason != null) {
            b.append(" debugReason=").append(debugReason);
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "TargetBoxDetectionResult{" + debugString() + "}";
    }
}
