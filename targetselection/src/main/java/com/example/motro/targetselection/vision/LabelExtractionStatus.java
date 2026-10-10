package com.example.motro.targetselection.vision;

/**
 * {@link LabelRegionExtractor} 的结局，<b>播放器无关、纯 JVM</b>。
 *
 * <p><b>为什么单独一个枚举，而不是复用 {@link TargetBoxDetectionStatus}</b>：两者回答的
 * 是完全不同的问题。{@code TargetBoxDetectionStatus} 回答「掩码里有没有矩形」，
 * 本枚举回答「已经选中的那个框，它的 {@code id:xxxx} 标签区域有没有找到」。
 * 复用会让「没有框」与「有框但没标签」在同一个名字下混起来 —— 而后者恰恰是
 * {@code HIT_ID_UNKNOWN}（看得见框、读不出编号）的判据，必须能被上层单独识别。</p>
 *
 * <p><b>{@link #NO_LABEL} 绝不是 MISS（重要）</b>：命中了一个目标框、只是没找到它的标签，
 * 与「什么都没点到」对上层是完全不同的两件事。前者应当产出
 * {@code TargetSelectionStatus#HIT_ID_UNKNOWN}（带着框的坐标），
 * 后者才是 {@code MISS}。因此任何把 {@link #NO_LABEL} 当成「没有目标」的分支都是错的，
 * 这也是 {@link #isHitWithoutLabel()} 存在的原因：把这条区分写成可读的方法名，
 * 而不是让调用方去比较枚举字面量。</p>
 *
 * <p>线程约定：枚举不可变，可跨线程安全共享。</p>
 */
public enum LabelExtractionStatus {

    /** 找到了标签区域，{@link LabelExtractionResult#getSelectedLabel()} 非 null。 */
    SUCCESS,

    /**
     * <b>目标框可用，但标签没找到</b>。
     *
     * <p>常见成因：标签被推流端画在框内/框下（不在搜索带里）、标签颜色没通过蓝阈值、
     * 或标签太小/太碎，凑不出一个文本形态的候选。它<b>不</b>表示这次点击没点中目标 ——
     * 上层据此报 {@code HIT_ID_UNKNOWN}。</p>
     */
    NO_LABEL,

    /** 选中的目标框本身不可用（为 null、退化、或坐标非有限值）。 */
    INVALID_BOX,

    /** 帧不可用：无图像载荷、尺寸非法、载体不支持像素回读、或读取期间被释放。 */
    INVALID_FRAME,

    /** 目标框与视频画面没有交集，或搜索区域夹取后为空 —— 没有像素可看。 */
    OUT_OF_BOUNDS;

    /** 是否真的拿到了标签区域。 */
    public boolean hasLabel() {
        return this == SUCCESS;
    }

    /**
     * 是否「点到了目标、只是没读到编号」—— 为 true 时上层必须报
     * {@code HIT_ID_UNKNOWN} 而不是 {@code MISS}。
     */
    public boolean isHitWithoutLabel() {
        return this == NO_LABEL;
    }

    /**
     * 目标框是否有效（即失败原因是否<b>不是</b>框本身的问题）。
     * 用来把「框有问题」（{@link #INVALID_BOX}）与「框没问题、只是没标签」区分开。
     */
    public boolean isBoxUsable() {
        return this == SUCCESS || this == NO_LABEL;
    }
}
