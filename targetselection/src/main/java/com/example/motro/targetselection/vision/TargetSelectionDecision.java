package com.example.motro.targetselection.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「多个命中候选 → 到底选哪个」这一步的产物，不可变。
 *
 * <p>它是 {@link TargetCandidateSelector} 的返回值，也是整个选择链路的终点：
 * {@link #selectedBox} 就是本次点击最终认定的目标框，
 * 而 {@link #rankedCandidates} 把<b>全部</b>命中候选连同评分一起留下 ——
 * 调试时"为什么不是旁边那个"这个问题的答案就在里面。</p>
 *
 * <p><b>与 {@link TargetHitTestResult} 的分工</b>：那边回答"哪些框被点到了"（过滤），
 * 这边回答"其中哪个才是用户想点的"（排序选择）。两者刻意分开：
 * 过滤是几何判定，选择是策略；策略将来一定会调（权重、tie-break），
 * 而过滤不该跟着一起改。</p>
 *
 * <p>线程约定：不可变对象（列表为不可修改视图），可跨线程安全共享。</p>
 */
public final class TargetSelectionDecision {

    /** 结局：{@link TargetBoxHitStatus#HIT_BOX} 或有候选但一个都没选中时的
     *  {@link TargetBoxHitStatus#MISS}。 */
    public final TargetBoxHitStatus status;

    /** 触摸点视频像素 x。 */
    public final float touchVideoX;

    /** 触摸点视频像素 y。 */
    public final float touchVideoY;

    /** 最终选中的框；未命中为 null。 */
    public final DetectedTargetBox selectedBox;

    /** 选中框的评分明细（{@link #rankedCandidates} 的第 0 项）；未命中为 null。 */
    public final TargetCandidateScore selectedScore;

    /** 全部命中候选的评分，按 rank 从 0 递增排列；未命中为空列表。 */
    public final List<TargetCandidateScore> rankedCandidates;

    /** 选择耗时（毫秒）；不足 1ms 时为 0。 */
    public final long selectionMs;

    /** 一句话说明"为什么是它"（英文，供日志抓取）；未命中时为 null。 */
    public final String reason;

    private TargetSelectionDecision(TargetBoxHitStatus status,
                                    float touchVideoX, float touchVideoY,
                                    TargetCandidateScore selectedScore,
                                    List<TargetCandidateScore> rankedCandidates,
                                    long selectionMs, String reason) {
        this.status = status;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.selectedScore = selectedScore;
        this.selectedBox = selectedScore == null ? null : selectedScore.box;
        this.rankedCandidates = Collections.unmodifiableList(new ArrayList<>(rankedCandidates));
        this.selectionMs = Math.max(0L, selectionMs);
        this.reason = reason;
    }

    /** 没有任何命中候选。 */
    static TargetSelectionDecision miss(float touchVideoX, float touchVideoY,
                                        long selectionMs, String reason) {
        return new TargetSelectionDecision(TargetBoxHitStatus.MISS, touchVideoX, touchVideoY,
                null, Collections.emptyList(), selectionMs, reason);
    }

    /**
     * 选中了其中一个候选。
     *
     * @param selectedScore    第 0 名的评分，非 null，且必须等于 rankedCandidates 的首项
     * @param rankedCandidates 全部命中候选评分，非 null 且非空
     * @param selectionMs      选择耗时毫秒
     * @param reason           选择理由
     * @throws IllegalArgumentException 任一参数非法
     */
    static TargetSelectionDecision selected(float touchVideoX, float touchVideoY,
                                            TargetCandidateScore selectedScore,
                                            List<TargetCandidateScore> rankedCandidates,
                                            long selectionMs, String reason) {
        if (selectedScore == null) {
            throw new IllegalArgumentException("selectedScore must not be null");
        }
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            throw new IllegalArgumentException("rankedCandidates must not be empty");
        }
        return new TargetSelectionDecision(TargetBoxHitStatus.HIT_BOX,
                touchVideoX, touchVideoY, selectedScore, rankedCandidates, selectionMs, reason);
    }

    public boolean isHit() {
        return status.isHit();
    }

    /** 命中分级；未命中为 null。 */
    public TargetHitType hitType() {
        return selectedScore == null ? null : selectedScore.hitType;
    }

    /** 命中候选个数（{@link #rankedCandidates} 的规模）。 */
    public int hitCount() {
        return rankedCandidates.size();
    }

    /** 选中框的加权总分；未命中为 0。 */
    public float selectionScore() {
        return selectedScore == null ? 0f : selectedScore.totalScore;
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "select=" + status
                + " touch=" + touchVideoX + "," + touchVideoY
                + " hits=" + hitCount()
                + " hitType=" + (hitType() == null ? "-" : hitType())
                + " selected=" + (selectedBox == null ? "-" : selectedBox.debugString())
                + " score=" + selectionScore()
                + " ms=" + selectionMs
                + (reason == null ? "" : " reason=" + reason);
    }

    @Override
    public String toString() {
        return "TargetSelectionDecision{" + debugString() + "}";
    }
}
