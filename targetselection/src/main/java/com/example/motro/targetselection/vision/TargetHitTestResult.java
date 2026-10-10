package com.example.motro.targetselection.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次「触摸点 vs 候选框集合」的命中测试结果，不可变。
 *
 * <p><b>返回的永远是原始框，不是扩张后的框</b>：容差（{@link #tolerancePx}）只用来决定
 * "算不算点到"，不改变几何。若把扩张后的框返回给上层，下游拿到的坐标会比实际大一圈，
 * 绘制、对齐、后续 Label ROI 全都会跟着偏 —— 这类错误在画面上往往"看起来还行"，
 * 所以必须从接口上堵死。</p>
 *
 * <p><b>允许一次命中多个框</b>：{@link #hitCandidates} 按选择优先级排好序，
 * {@link #selectedBox} 就是其中第一个。本 Slice 不做复杂的重叠消歧（留 Slice 6），
 * 但保证顺序是<b>确定</b>的：同样的输入必然得到同样的选择。</p>
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

    /** 最终选中的框；未命中为 null。 */
    public final DetectedTargetBox selectedBox;

    /** 全部命中的候选，按选择优先级从高到低排列；未命中为空列表。 */
    public final List<DetectedTargetBox> hitCandidates;

    /** 本次参与判定的候选框总数（含未命中的）。 */
    public final int candidateCount;

    /** 实际生效的触摸容差（视频像素）。 */
    public final int tolerancePx;

    /** 命中测试耗时（毫秒）。 */
    public final long processingMs;

    private TargetHitTestResult(TargetBoxHitStatus status,
                                float touchVideoX, float touchVideoY,
                                DetectedTargetBox selectedBox,
                                List<DetectedTargetBox> hitCandidates,
                                int candidateCount, int tolerancePx, long processingMs) {
        this.status = status;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.selectedBox = selectedBox;
        this.candidateCount = Math.max(0, candidateCount);
        this.tolerancePx = Math.max(0, tolerancePx);
        this.processingMs = Math.max(0L, processingMs);
        this.hitCandidates = Collections.unmodifiableList(new ArrayList<>(hitCandidates));
    }

    /**
     * 未命中。
     */
    static TargetHitTestResult miss(float touchVideoX, float touchVideoY,
                                    int candidateCount, int tolerancePx, long processingMs) {
        return new TargetHitTestResult(TargetBoxHitStatus.MISS, touchVideoX, touchVideoY,
                null, Collections.emptyList(), candidateCount, tolerancePx, processingMs);
    }

    /**
     * 命中一个或多个候选。
     *
     * @param selectedBox  选中的框，非 null
     * @param hitCandidates 全部命中候选，按优先级降序，非 null 且非空
     * @throws IllegalArgumentException selectedBox 为 null 或 hitCandidates 为空
     */
    static TargetHitTestResult hit(DetectedTargetBox selectedBox,
                                   List<DetectedTargetBox> hitCandidates,
                                   float touchVideoX, float touchVideoY,
                                   int candidateCount, int tolerancePx, long processingMs) {
        if (selectedBox == null) {
            throw new IllegalArgumentException("selectedBox must not be null");
        }
        if (hitCandidates == null || hitCandidates.isEmpty()) {
            throw new IllegalArgumentException("hitCandidates must not be empty");
        }
        return new TargetHitTestResult(TargetBoxHitStatus.HIT_BOX, touchVideoX, touchVideoY,
                selectedBox, hitCandidates, candidateCount, tolerancePx, processingMs);
    }

    public boolean isHit() {
        return status.isHit();
    }

    /** 命中候选个数。 */
    public int hitCount() {
        return hitCandidates.size();
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "hit=" + status
                + " touch=" + touchVideoX + "," + touchVideoY
                + " tol=" + tolerancePx
                + " candidates=" + candidateCount
                + " hits=" + hitCount()
                + " selected=" + (selectedBox == null ? "-" : selectedBox.debugString())
                + " ms=" + processingMs;
    }

    @Override
    public String toString() {
        return "TargetHitTestResult{" + debugString() + "}";
    }
}
