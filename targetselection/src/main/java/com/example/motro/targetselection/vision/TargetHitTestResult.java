package com.example.motro.targetselection.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次「触摸点 vs 候选框集合」的<b>过滤</b>结果，不可变。
 *
 * <p><b>它不含"选中了哪个框"</b>：那是 {@link TargetCandidateSelector} 的产出
 * （见 {@link TargetSelectionDecision}）。这里只回答"哪些框被点到了"，
 * 所以 {@link #hitCandidates} 保持<b>输入顺序</b>，不做任何带倾向的排序 ——
 * "哪个更好"是策略，只应有一处实现，否则过滤与选择会各自形成一套优先级、
 * 而且它们会随着调参逐渐不一致。</p>
 *
 * <p><b>返回的永远是原始框，不是扩张后的框</b>：容差（{@link #tolerancePx}）只用来决定
 * "算不算点到"，不改变几何。若把扩张后的框返回给上层，下游拿到的坐标会比实际大一圈，
 * 绘制、对齐、后续 Label ROI 全都会跟着偏 —— 这类错误在画面上往往"看起来还行"，
 * 所以必须从接口上堵死。</p>
 *
 * <p>线程约定：不可变对象（列表为不可修改视图），可跨线程安全共享。</p>
 */
public final class TargetHitTestResult {

    /** 结局。 */
    public final TargetBoxHitStatus status;

    /** 触摸点视频像素 x。 */
    public final float touchVideoX;

    /** 触摸点视频像素 y。 */
    public final float touchVideoY;

    /**
     * 全部命中的候选，<b>按输入顺序</b>；未命中为空列表。
     * 排序与选择见 {@link TargetCandidateSelector}。
     */
    public final List<DetectedTargetBox> hitCandidates;

    /** 本次参与判定的候选框总数（含未命中的与 null 项）。 */
    public final int candidateCount;

    /** 实际生效的触摸容差（视频像素）。 */
    public final int tolerancePx;

    /** 命中过滤耗时（毫秒）；不足 1ms 时为 0。 */
    public final long processingMs;

    private TargetHitTestResult(TargetBoxHitStatus status,
                                float touchVideoX, float touchVideoY,
                                List<DetectedTargetBox> hitCandidates,
                                int candidateCount, int tolerancePx, long processingMs) {
        this.status = status;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.candidateCount = Math.max(0, candidateCount);
        this.tolerancePx = Math.max(0, tolerancePx);
        this.processingMs = Math.max(0L, processingMs);
        this.hitCandidates = Collections.unmodifiableList(new ArrayList<>(hitCandidates));
    }

    /** 未命中：没有任何候选，或所有候选都在容差之外。 */
    static TargetHitTestResult miss(float touchVideoX, float touchVideoY,
                                    int candidateCount, int tolerancePx, long processingMs) {
        return new TargetHitTestResult(TargetBoxHitStatus.MISS, touchVideoX, touchVideoY,
                Collections.emptyList(), candidateCount, tolerancePx, processingMs);
    }

    /**
     * 命中一个或多个候选。
     *
     * @param hitCandidates 全部命中候选（输入顺序），非 null 且非空
     * @throws IllegalArgumentException hitCandidates 为 null 或空
     */
    static TargetHitTestResult hits(List<DetectedTargetBox> hitCandidates,
                                    float touchVideoX, float touchVideoY,
                                    int candidateCount, int tolerancePx, long processingMs) {
        if (hitCandidates == null || hitCandidates.isEmpty()) {
            throw new IllegalArgumentException("hitCandidates must not be empty");
        }
        return new TargetHitTestResult(TargetBoxHitStatus.HIT_BOX, touchVideoX, touchVideoY,
                hitCandidates, candidateCount, tolerancePx, processingMs);
    }

    public boolean isHit() {
        return status.isHit();
    }

    /** 命中候选个数。 */
    public int hitCount() {
        return hitCandidates.size();
    }

    /** 是否只有唯一一个命中候选（叠加层据此可以省掉名次显示）。 */
    public boolean isUnambiguousHit() {
        return hitCandidates.size() == 1;
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "hit=" + status
                + " touch=" + touchVideoX + "," + touchVideoY
                + " tol=" + tolerancePx
                + " candidates=" + candidateCount
                + " hits=" + hitCount()
                + " ms=" + processingMs;
    }

    @Override
    public String toString() {
        return "TargetHitTestResult{" + debugString() + "}";
    }
}
