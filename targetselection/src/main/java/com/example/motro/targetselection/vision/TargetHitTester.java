package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 「触摸点 vs 候选框」的命中<b>过滤</b>。<b>独立于检测、也独立于选择</b>：
 * 它只吃 {@link DetectedTargetBox} 列表与一个视频坐标点，不知道掩码、ROI、颜色阈值的存在，
 * 因此可以在纯 JVM 里穷举验证，也不会因为检测算法或选择策略改动而失效。
 *
 * <h2>职责边界（与 {@link TargetCandidateSelector} 的分工）</h2>
 * 本类只回答一个问题：<b>哪些框被点到了</b>。给一个点和一个框，答案唯一，与策略无关。
 * 「其中哪个才是用户想点的」由 {@link TargetCandidateSelector} 负责 ——
 * 因此这里返回的 {@link TargetHitTestResult#hitCandidates} <b>不排序</b>，
 * 保持输入顺序（确定但无倾向），排序与选择只有一处实现。
 *
 * <h2>容差</h2>
 * 判定用的是把框向外扩 {@code touchTolerancePx} 之后的矩形（见
 * {@link TargetBoundingBox#inflated}），但<b>返回的框永远是原始框</b>。
 * 这么设计的原因很实际：手指点击有 ±10px 级别的抖动，而目标框可能只有几十像素高，
 * 没有容差会出现"明明点在框上却 MISS"；但把扩过的框返回给上层，
 * 又会让绘制、对齐、后续 Label ROI 全部外扩一圈 —— 这是最难被发现的一类错误，
 * 因为画面"看起来还行"。所以容差<b>只进不出</b>。
 *
 * <p>「点在框里」与「靠容差够到」的区别由
 * {@link TargetCandidateSelector} 在分级时重新判定（{@link TargetHitType}）——
 * 那属于排序信息，不是过滤信息，所以不在这里产出。</p>
 *
 * <p>线程约定：不可变（只有容差一个配置值），可跨线程调用。</p>
 */
public final class TargetHitTester {

    private final int tolerancePx;

    /**
     * @param config 默认策略，非 null；容差取 {@link TargetSelectionConfig#touchTolerancePx}
     * @throws IllegalArgumentException config 为 null
     */
    public TargetHitTester(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.tolerancePx = config.touchTolerancePx;
    }

    /**
     * @param tolerancePx 触摸容差（视频像素），&ge; 0
     * @throws IllegalArgumentException 为负
     */
    public TargetHitTester(int tolerancePx) {
        if (tolerancePx < 0) {
            throw new IllegalArgumentException("tolerancePx must be >= 0 but was " + tolerancePx);
        }
        this.tolerancePx = tolerancePx;
    }

    /**
     * 过滤出被触摸点命中的候选框。
     *
     * @param touchVideoX 触摸点视频像素 x，必须有限
     * @param touchVideoY 触摸点视频像素 y，必须有限
     * @param candidates  候选框；null 或空视为没有候选；元素为 null 时跳过但计入
     *                    {@link TargetHitTestResult#candidateCount}
     * @return 非 null 的结果；命中时 {@code hitCandidates} 为<b>输入顺序</b>的全部命中框
     * @throws IllegalArgumentException 触摸坐标非有限值
     */
    public TargetHitTestResult hitTest(float touchVideoX, float touchVideoY,
                                       List<DetectedTargetBox> candidates) {
        final long startNs = System.nanoTime();
        if (!Float.isFinite(touchVideoX) || !Float.isFinite(touchVideoY)) {
            throw new IllegalArgumentException("touch point must be finite but was "
                    + touchVideoX + "," + touchVideoY);
        }
        final int total = candidates == null ? 0 : candidates.size();
        if (total == 0) {
            return TargetHitTestResult.miss(touchVideoX, touchVideoY, 0, tolerancePx, elapsedMs(startNs));
        }

        final List<DetectedTargetBox> hits = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final DetectedTargetBox box = candidates.get(i);
            if (box == null) {
                continue;
            }
            if (box.rectInVideo.inflated(tolerancePx).contains(touchVideoX, touchVideoY)) {
                hits.add(box);
            }
        }
        if (hits.isEmpty()) {
            return TargetHitTestResult.miss(touchVideoX, touchVideoY, total, tolerancePx, elapsedMs(startNs));
        }
        return TargetHitTestResult.hits(hits, touchVideoX, touchVideoY,
                total, tolerancePx, elapsedMs(startNs));
    }

    /** 当前生效的容差（视频像素）。 */
    public int tolerancePx() {
        return tolerancePx;
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }
}
