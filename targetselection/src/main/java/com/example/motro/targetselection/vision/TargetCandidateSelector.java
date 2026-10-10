package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「多个命中候选里到底选哪个」—— Slice 6 的核心策略类，播放器无关、纯 JVM。
 *
 * <p>它<b>只吃几何数据</b>（触摸点 + 已经判定为命中的 {@link DetectedTargetBox}），
 * 不碰掩码、不碰像素、不碰 ROI：矩形恢复的产物就是它的全部输入。
 * 因此整段策略可以在纯 JVM 里穷举验证 —— 与"权重该怎么调"这件事脱钩。</p>
 *
 * <h2>为什么从 {@link TargetHitTester} 里抽出来</h2>
 * 过滤与选择是两件事，只是碰巧都需要触摸点：
 * <ul>
 *   <li>{@link TargetHitTester}：几何判定 —— 哪些框被点到了（含容差）。这个答案与策略无关，
 *       给一个点和一个框，答案唯一。</li>
 *   <li>本类：策略 —— 其中哪个才是用户想点的。这个答案会随权重、场景、产品口味变。</li>
 * </ul>
 * 混在一起时，每次调权重都要动"命中判定"这个已经很稳的部件，风险与噪声都大；
 * 分开以后两者可以各自演进、各自测试。</p>
 *
 * <h2>排序规则（从强到弱，逐级退化；公式集中在本类，不散落 if/else）</h2>
 * <ol>
 *   <li><b>{@link TargetHitType}（硬主键）</b>：{@link TargetHitType#EXACT_HIT}
 *       永远排在 {@link TargetHitType#TOLERANCE_HIT} 之前，<b>不受任何权重与分数影响</b>。
 *       这是"点击两个目标中间时不被错误吸附"的结构性保证 ——
 *       哪怕旁边那个靠容差够到的框面积更小、置信度更高，也不能把真正的包含命中挤掉。
 *       做成硬主键而不是靠 {@code exactHitWeight} 加分，是因为加分可以被权重调没，
 *       而"点在框里"与"点在框外"是质的不同，不该被一个浮点数抹平。</li>
 *   <li><b>{@link TargetCandidateScore#totalScore}（降序）</b>：见下面的评分公式。</li>
 *   <li><b>归一化中心距离（升序）</b>：同分时点得更"正"的优先。</li>
 *   <li><b>到边界的绝对距离（升序）</b>：同分时与框边贴合得更紧的优先 ——
 *       对严格命中意味着"点在框内更靠近某条边"（真实点击习惯就是点框线附近），
 *       对容差命中意味着"探出的幅度更小"。</li>
 *   <li><b>面积（升序，仅 {@code preferSmallerBox} 时）</b>：嵌套/重叠时更小、更具体的框优先。</li>
 *   <li><b>置信度、完整度（降序）</b>：框线画得更全的优先。</li>
 *   <li><b>未被 ROI 截断的优先</b>：几何完整 ⇒ 坐标更可信。被截断的框面积偏小，
 *       若不看这一项，它会因为"面积更小"而占便宜 —— 这正是本项存在的理由。</li>
 *   <li><b>上边界、左边界（升序）</b>：最后的兜底，只为让同分情况下结果<b>唯一确定</b>，
 *       而不是听凭排序算法是否稳定。</li>
 * </ol>
 *
 * <h2>评分公式</h2>
 * <pre>
 *   totalScore = exactBonus                                   // 严格命中：+exactHitWeight
 *              - centerDistanceWeight * normalizedCenterDist  // 越居中越好
 *              - areaWeight * normalizedArea                   // 越小越具体（可关）
 *              + confidenceWeight * boxConfidence
 *              + completenessWeight * completeness
 *
 *   normalizedCenterDist = |touch − boxCenter| / boxHalfDiagonal   // 与框大小无关，故可跨框比较
 *   normalizedArea       = boxArea / maxBoxAreaAmongHits           // 相对本次命中的最大框
 * </pre>
 * <p>两处归一化都是刻意的：<b>绝对</b>像素距离无法在大小不同的框之间比较（点在小框中心
 * 可能有 30px 偏差，在大框中心可能 200px 还很居中），除以半对角线以后两者可比；
 * 面积用「本次命中的最大框」而不是整帧或 ROI 作分母，于是同一套权重在近景/远景、
 * 高分辨率/低分辨率下表现一致，现场换推流端不必重调。</p>
 * <p>权重全部来自 {@link TargetSelectionConfig}。默认值刻意只让"几何关系"说话
 * （中心距离与面积各 1.0，置信度/完整度各 0.5 作辅助），
 * 因为"点得准不准"比"框线画得全不全"更接近用户的真实意图。</p>
 *
 * <p><b>不做的事</b>：不重新扫掩码、不重跑矩形恢复、不访问 Bitmap ——
 * 输入只有上一步已经算好的框。单次调用的复杂度是 O(N log N)（N 为命中候选数，
 * 通常个位数），实测在微秒量级。</p>
 *
 * <p>线程约定：不可变（只有一组配置值），可跨线程调用。</p>
 */
public final class TargetCandidateSelector {

    private final int tolerancePx;
    private final float exactHitWeight;
    private final float centerDistanceWeight;
    private final float areaWeight;
    private final float confidenceWeight;
    private final float completenessWeight;
    private final boolean preferSmallerBox;
    private final int maxRankedCandidates;

    /**
     * @param config 默认策略，非 null；权重、tie-break 开关、候选上限、容差都从它读取
     * @throws IllegalArgumentException config 为 null
     */
    public TargetCandidateSelector(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.tolerancePx = config.touchTolerancePx;
        this.exactHitWeight = config.exactHitWeight;
        this.centerDistanceWeight = config.centerDistanceWeight;
        this.areaWeight = config.areaWeight;
        this.confidenceWeight = config.confidenceWeight;
        this.completenessWeight = config.completenessWeight;
        this.preferSmallerBox = config.preferSmallerBox;
        this.maxRankedCandidates = config.maxRankedCandidates;
    }

    /**
     * 在命中候选里选出最佳目标。
     *
     * <p><b>入参约定</b>：{@code candidates} 是<b>已经通过命中测试</b>的框
     * （即 {@link TargetHitTester} 输出的命中列表）。本类会再独立复核一次分级
     * （严格包含 / 容差），因此即便误传了没命中的框，它们也只会被跳过、不会被选中 ——
     * 这既是一道防线，也让本类可以脱离 HitTester 单独使用。</p>
     *
     * @param touchVideoX 触摸点视频像素 x，必须有限
     * @param touchVideoY 触摸点视频像素 y，必须有限
     * @param candidates  命中候选；null 或空视为没有命中
     * @return 非 null 的选择结果；{@code candidateCount} 反映的是传入规模，
     *         命中个数见 {@link TargetSelectionDecision#hitCount()}
     * @throws IllegalArgumentException 触摸坐标非有限值
     */
    public TargetSelectionDecision select(float touchVideoX, float touchVideoY,
                                          List<DetectedTargetBox> candidates) {
        final long startNs = System.nanoTime();
        if (!Float.isFinite(touchVideoX) || !Float.isFinite(touchVideoY)) {
            throw new IllegalArgumentException("touch point must be finite but was "
                    + touchVideoX + "," + touchVideoY);
        }
        final int total = candidates == null ? 0 : candidates.size();
        if (total == 0) {
            return TargetSelectionDecision.miss(touchVideoX, touchVideoY,
                    elapsedMs(startNs), "no-candidates");
        }

        // 第一遍：分级 + 量测，同时求本次命中的最大面积（面积归一化的分母）。
        final List<Measured> measured = new ArrayList<>(Math.min(total, maxRankedCandidates + 1));
        float maxArea = 0f;
        for (int i = 0; i < total; i++) {
            final DetectedTargetBox box = candidates.get(i);
            if (box == null) {
                continue;
            }
            final TargetHitType hitType = classify(box, touchVideoX, touchVideoY);
            if (hitType == null) {
                continue;   // 连容差都够不到 —— 不是命中项
            }
            final Measured entry = measure(box, hitType, touchVideoX, touchVideoY);
            measured.add(entry);
            if (entry.area > maxArea) {
                maxArea = entry.area;
            }
        }
        if (measured.isEmpty()) {
            return TargetSelectionDecision.miss(touchVideoX, touchVideoY,
                    elapsedMs(startNs), "no-hit");
        }

        // 第二遍：补上面积归一化项，得到最终总分。
        final List<TargetCandidateScore> ranked = new ArrayList<>(measured.size());
        for (int i = 0; i < measured.size(); i++) {
            final Measured entry = measured.get(i);
            ranked.add(TargetCandidateScore.of(entry.box, entry.hitType,
                    totalScore(entry, maxArea), entry.normalizedCenterDistance,
                    entry.edgeDistance, entry.area, entry.box.confidence, entry.box.completeness));
        }

        // 排序 → 截断到上限 → 赋排名（排名只在排序后才有意义）。
        ranked.sort(this::compare);
        if (ranked.size() > maxRankedCandidates) {
            ranked.subList(maxRankedCandidates, ranked.size()).clear();
        }
        for (int i = 0; i < ranked.size(); i++) {
            ranked.set(i, ranked.get(i).withRank(i));
        }

        final TargetCandidateScore best = ranked.get(0);
        return TargetSelectionDecision.selected(touchVideoX, touchVideoY, best, ranked,
                elapsedMs(startNs), describe(best, ranked.size()));
    }

    /** 当前生效的触摸容差（视频像素）。本类只用它做命中分级复核。 */
    public int tolerancePx() {
        return tolerancePx;
    }

    /** 单次选择最多保留多少个已排名候选（rank 0 永远保留）。 */
    public int maxRankedCandidates() {
        return maxRankedCandidates;
    }

    // ------------------------------------------------------------------ 分级与量测

    /**
     * 判定命中分级。
     *
     * @return {@link TargetHitType#EXACT_HIT} / {@link TargetHitType#TOLERANCE_HIT}；
     *         连容差都够不到时返回 null（该框不参与选择）
     */
    private TargetHitType classify(DetectedTargetBox box, float touchX, float touchY) {
        final TargetBoundingBox rect = box.rectInVideo;
        if (rect.contains(touchX, touchY)) {
            return TargetHitType.EXACT_HIT;
        }
        return rect.inflated(tolerancePx).contains(touchX, touchY)
                ? TargetHitType.TOLERANCE_HIT
                : null;
    }

    private static Measured measure(DetectedTargetBox box, TargetHitType hitType,
                                    float touchX, float touchY) {
        final TargetBoundingBox rect = box.rectInVideo;
        final float area = rect.width() * rect.height();
        final float dx = rect.centerX() - touchX;
        final float dy = rect.centerY() - touchY;
        final float centerDistance = (float) Math.hypot(dx, dy);
        // 半对角线为零只可能来自退化框；此时把归一化距离记为 1，
        // 让它排在所有正常框之后，而不是让 0/0 产生 NaN 污染整条比较链。
        final float halfDiagonal = 0.5f * (float) Math.hypot(rect.width(), rect.height());
        final float normalized = halfDiagonal <= 0f ? 1f : centerDistance / halfDiagonal;
        return new Measured(box, hitType, area, normalized, edgeDistance(rect, touchX, touchY));
    }

    /**
     * 触摸点到框边界的<b>带符号</b>距离：框内为负（到最近边的深度），框外为正（到框的欧氏距离）。
     *
     * <p>用欧氏距离而非"水平/垂直各自超出的最大值"，是因为对角线方向探出时后者会低估距离 ——
     * 而"超出去多远"正是容差命中之间比较贴合程度时要看的量。</p>
     */
    private static float edgeDistance(TargetBoundingBox rect, float touchX, float touchY) {
        final float overhangX = Math.max(Math.max(rect.left - touchX, touchX - rect.right), 0f);
        final float overhangY = Math.max(Math.max(rect.top - touchY, touchY - rect.bottom), 0f);
        if (overhangX > 0f || overhangY > 0f) {
            return (float) Math.hypot(overhangX, overhangY);
        }
        final float nearestEdge = Math.min(Math.min(touchX - rect.left, rect.right - touchX),
                Math.min(touchY - rect.top, rect.bottom - touchY));
        return -nearestEdge;
    }

    // ------------------------------------------------------------------ 评分

    private float totalScore(Measured entry, float maxArea) {
        float score = 0f;
        if (entry.hitType.isExact()) {
            score += exactHitWeight;
        }
        score -= centerDistanceWeight * entry.normalizedCenterDistance;
        // preferSmallerBox 关掉时整项消失（而不是权重置 0）：
        // 让"这条判据在不在"是显式的，读代码时不用再从权重里反推。
        if (preferSmallerBox && maxArea > 0f) {
            score -= areaWeight * (entry.area / maxArea);
        }
        score += confidenceWeight * entry.box.confidence;
        score += completenessWeight * entry.box.completeness;
        return score;
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 优先级的唯一出处。逐级比较、任一级分出胜负即返回，
     * 因此结果与输入顺序<b>无关</b>，也不依赖排序算法的稳定性。
     */
    private int compare(TargetCandidateScore a, TargetCandidateScore b) {
        // 1) 命中分级：硬约束，EXACT 永远优先（声明顺序即优先级）
        int result = Integer.compare(a.hitType.ordinal(), b.hitType.ordinal());
        if (result != 0) {
            return result;
        }
        // 2) 总分，降序
        result = Float.compare(b.totalScore, a.totalScore);
        if (result != 0) {
            return result;
        }
        // 3) 归一化中心距离，升序
        result = Float.compare(a.normalizedCenterDistance, b.normalizedCenterDistance);
        if (result != 0) {
            return result;
        }
        // 4) 到边界的绝对距离，升序（贴着框边的新手点击优先）
        result = Float.compare(a.absEdgeDistance(), b.absEdgeDistance());
        if (result != 0) {
            return result;
        }
        // 5) 面积，升序 —— 但仅在策略开启"更小更具体"时；
        //    否则这里会把 preferSmallerBox=false 的效果悄悄改回去。
        if (preferSmallerBox) {
            result = Float.compare(a.area, b.area);
            if (result != 0) {
                return result;
            }
        }
        // 6) 框线质量，降序
        result = Float.compare(b.boxConfidence, a.boxConfidence);
        if (result != 0) {
            return result;
        }
        result = Float.compare(b.completeness, a.completeness);
        if (result != 0) {
            return result;
        }
        // 7) 几何完整优先：被 ROI 截断的框面积偏小，不挡一下会因"面积更小"占便宜
        result = Boolean.compare(a.box.clippedByRoi, b.box.clippedByRoi);
        if (result != 0) {
            return result;
        }
        // 8) 兜底：位置固定排序，保证任何输入下顺序都唯一
        result = Float.compare(a.box.rectInVideo.top, b.box.rectInVideo.top);
        if (result != 0) {
            return result;
        }
        return Float.compare(a.box.rectInVideo.left, b.box.rectInVideo.left);
    }

    private static String describe(TargetCandidateScore best, int hitCount) {
        if (hitCount <= 1) {
            return "only-candidate " + best.hitType;
        }
        return String.format(Locale.ROOT, "%s best-of-%d score=%.3f",
                best.hitType, hitCount, best.totalScore);
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }

    /**
     * 分级 + 量测的中间结果：此时还不知道本次命中的最大面积，因此总分留到第二遍再算。
     * 独立成一个小类，是为了让"需要全局量的评分"与"纯局部的量测"在结构上分开。
     */
    private static final class Measured {
        final DetectedTargetBox box;
        final TargetHitType hitType;
        final float area;
        final float normalizedCenterDistance;
        final float edgeDistance;

        Measured(DetectedTargetBox box, TargetHitType hitType, float area,
                 float normalizedCenterDistance, float edgeDistance) {
            this.box = box;
            this.hitType = hitType;
            this.area = area;
            this.normalizedCenterDistance = normalizedCenterDistance;
            this.edgeDistance = edgeDistance;
        }
    }
}
