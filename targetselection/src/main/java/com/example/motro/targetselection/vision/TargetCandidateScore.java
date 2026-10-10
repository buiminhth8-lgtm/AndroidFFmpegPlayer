package com.example.motro.targetselection.vision;

/**
 * 一个候选框在<b>本次触摸</b>下的评分明细，不可变。
 *
 * <p><b>为什么不只返回一个数字</b>：选择结果如果只有一个 {@link #totalScore}，
 * 调试时只能看到"谁赢了"，看不到"为什么赢"。而这类几何优先级一旦选错，
 * 最有价值的信息恰恰是分项 —— 是中心距离把它顶上去的，还是面积把它压下来的？
 * 所以这里把参与评分的每一项都留了下来（{@link #normalizedCenterDistance}、
 * {@link #area}、{@link #boxConfidence}、{@link #completeness}），
 * 加上不参与评分但用来解释现象的 {@link #edgeDistance}。叠加层据此能直接回答
 * "为什么这个框最终被选中"。这些值同时也让后续调参不必"猜"——
 * 真实样本上把每一项打出来，权重该怎么改一目了然。</p>
 *
 * <h2>字段含义</h2>
 * <ul>
 *   <li>{@link #hitType}：严格包含还是靠容差够到，排序的<b>硬主键</b>。</li>
 *   <li>{@link #totalScore}：加权总分，<b>越大越好</b>。公式与权重见
 *       {@link TargetCandidateSelector}（集中在一处，不散落成 if/else 魔数）。</li>
 *   <li>{@link #normalizedCenterDistance}：触摸点到框中心的距离 ÷ 框的半对角线。
 *       同尺度下比较"点得正不正"；严格命中时通常 ∈[0,1]，
 *       容差命中时可 &gt; 1（点已在框外）。</li>
 *   <li>{@link #edgeDistance}：触摸点到框边界的<b>带符号</b>距离（像素）。
 *       点在框内为负（数值 = 到最近边的深度），点在框外为正（= 到框的欧氏距离）。
 *       它<b>不参与</b> {@link #totalScore} —— 与中心距离、面积高度相关，
 *       再加一项权重只会让三者互相抵消、调参更糊；它作为可读的解释量与
 *       {@link TargetCandidateSelector} 的一级 tie-break 存在。</li>
 *   <li>{@link #area}：框在<b>视频</b>像素下的面积。注意用的是<b>原始框</b>，
 *       不是扩张容差后的框 —— 否则每个候选的面积都会被同一个增量污染，
 *       "更小更具体"这条判据就被稀释了。</li>
 *   <li>{@link #boxConfidence} / {@link #completeness}：来自
 *       {@link DetectedTargetBox} 的框质量度量（框线完整性），只作为辅助项。</li>
 *   <li>{@link #rank}：排好序之后的位置，0 = 最优（也就是被选中的那个）。
 *       排序前为 {@link #UNRANKED}。</li>
 * </ul>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetCandidateScore {

    /** {@link #rank} 的哨兵值：尚未参与排序。 */
    public static final int UNRANKED = -1;

    /** 对应的候选框。 */
    public final DetectedTargetBox box;

    /** 命中分级（严格包含 / 容差）。 */
    public final TargetHitType hitType;

    /** 加权总分，越大越好。 */
    public final float totalScore;

    /** 触摸点到框中心的距离 ÷ 框半对角线。 */
    public final float normalizedCenterDistance;

    /** 触摸点到框边界的带符号距离（像素）：框内为负、框外为正。 */
    public final float edgeDistance;

    /** 框在视频像素下的面积（原始框，不含容差扩张）。 */
    public final float area;

    /** 框自身置信度 [0,1]。 */
    public final float boxConfidence;

    /** 框自身完整度 [0,1]。 */
    public final float completeness;

    /** 排名，0 = 最优；未排序为 {@link #UNRANKED}。 */
    public final int rank;

    private TargetCandidateScore(DetectedTargetBox box, TargetHitType hitType, float totalScore,
                                 float normalizedCenterDistance, float edgeDistance, float area,
                                 float boxConfidence, float completeness, int rank) {
        this.box = box;
        this.hitType = hitType;
        this.totalScore = totalScore;
        this.normalizedCenterDistance = normalizedCenterDistance;
        this.edgeDistance = edgeDistance;
        this.area = area;
        this.boxConfidence = boxConfidence;
        this.completeness = completeness;
        this.rank = rank;
    }

    /**
     * 构造一个尚未排名的评分。
     *
     * @param box                    候选框，非 null
     * @param hitType                命中分级，非 null
     * @param totalScore             加权总分，必须有限（可以为负）
     * @param normalizedCenterDistance 归一化中心距离，必须有限且 &ge; 0
     * @param edgeDistance           带符号边界距离，必须有限
     * @param area                   视频像素面积，必须有限且 &ge; 0
     * @param boxConfidence          框置信度 [0,1]
     * @param completeness           框完整度 [0,1]
     * @throws IllegalArgumentException 任一参数非法
     */
    static TargetCandidateScore of(DetectedTargetBox box, TargetHitType hitType, float totalScore,
                                   float normalizedCenterDistance, float edgeDistance, float area,
                                   float boxConfidence, float completeness) {
        if (box == null) {
            throw new IllegalArgumentException("box must not be null");
        }
        if (hitType == null) {
            throw new IllegalArgumentException("hitType must not be null");
        }
        requireFinite(totalScore, "totalScore");
        requireFinite(normalizedCenterDistance, "normalizedCenterDistance");
        requireFinite(edgeDistance, "edgeDistance");
        if (!Float.isFinite(area) || area < 0f) {
            throw new IllegalArgumentException("area must be finite and >= 0 but was " + area);
        }
        if (normalizedCenterDistance < 0f) {
            throw new IllegalArgumentException("normalizedCenterDistance must be >= 0 but was "
                    + normalizedCenterDistance);
        }
        return new TargetCandidateScore(box, hitType, totalScore, normalizedCenterDistance,
                edgeDistance, area, boxConfidence, completeness, UNRANKED);
    }

    /**
     * 赋予排名（0 = 最优）。包内可见：排名由 {@link TargetCandidateSelector} 在排序完成后统一给出，
     * 别的调用方不该自行编排次序 —— 那是选择逻辑，只应有一处。
     *
     * @param rank 排名，&ge; 0
     * @throws IllegalArgumentException rank 为负
     */
    TargetCandidateScore withRank(int rank) {
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0 but was " + rank);
        }
        if (rank == this.rank) {
            return this;
        }
        return new TargetCandidateScore(box, hitType, totalScore, normalizedCenterDistance,
                edgeDistance, area, boxConfidence, completeness, rank);
    }

    /** 是否已经排过名。 */
    public boolean isRanked() {
        return rank != UNRANKED;
    }

    /** 是否为严格包含命中。 */
    public boolean isExact() {
        return hitType.isExact();
    }

    /** {@link #edgeDistance} 的绝对值：点离框边界有多远（不分内外）。 */
    public float absEdgeDistance() {
        return Math.abs(edgeDistance);
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "rank=" + rank
                + " box=" + box.rectInVideo
                + " " + hitType
                + " score=" + totalScore
                + " ncd=" + normalizedCenterDistance
                + " edge=" + edgeDistance
                + " area=" + area
                + " conf=" + boxConfidence
                + " comp=" + completeness;
    }

    @Override
    public String toString() {
        return "TargetCandidateScore{" + debugString() + "}";
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }
}
