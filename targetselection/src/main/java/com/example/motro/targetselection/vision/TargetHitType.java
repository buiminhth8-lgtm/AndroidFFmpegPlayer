package com.example.motro.targetselection.vision;

/**
 * 一次命中「够不够扎实」的分级。它回答的是：触摸点究竟落在框里，还是只靠容差勉强够到。
 *
 * <h2>为什么必须分级，而不是只判"命中/未命中"</h2>
 * 手指点击有 ±8~10px 抖动，所以命中判定要留容差（{@link TargetHitTester}）。但容差一旦存在，
 * 两个目标之间那条"缝"就变成了一片会同时命中两边、且都不算真命中的区域。
 * 若不加区分地把它们平等对待，点击两个目标中间的空白处时，算法就只能靠面积/置信度去猜，
 * 很容易吸附到旁边那个更大的框上 —— 用户看到的是"我明明点在空白处，却选中了右边的目标"。
 *
 * <p>于是把"点在框里"（{@link #EXACT_HIT}）与"靠容差够到"（{@link #TOLERANCE_HIT}）分开，
 * 并在排序时把前者列为<b>硬约束</b>（见 {@link TargetCandidateSelector}）：
 * 只要存在一个真正的包含命中，就绝不让容差命中抢走它，无论后者的分数多高。</p>
 *
 * <p><b>声明顺序即优先级</b>：{@link #EXACT_HIT} 在前、{@link #TOLERANCE_HIT} 在后。
 * {@link TargetCandidateSelector} 的排序直接依赖 {@link #ordinal()}，
 * 因此不要调整这两个常量的先后。</p>
 *
 * <p>线程约定：枚举，天然不可变。</p>
 */
public enum TargetHitType {

    /** 触摸点落在<b>原始</b>框内（闭区间，点正好压在框线上也算）。 */
    EXACT_HIT,

    /** 触摸点不在原始框内，但落在向外扩 {@code touchTolerancePx} 之后的范围内。 */
    TOLERANCE_HIT;

    /** 是否为严格包含。 */
    public boolean isExact() {
        return this == EXACT_HIT;
    }
}
