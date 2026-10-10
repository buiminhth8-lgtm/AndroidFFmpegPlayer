package com.example.motro.targetselection.vision;

/**
 * {@link TargetIdRecognizer} 的结局，<b>播放器无关、纯 JVM</b>。
 *
 * <p><b>为什么单独一个枚举，而不是复用 {@link LabelExtractionStatus} 或
 * {@link TargetBoxDetectionStatus}</b>：三者回答的是不同层次的问题 ——
 * 「有没有找到目标框」（{@code TargetBoxDetectionStatus}）、
 * 「框顶上那块编号区域有没有找到」（{@link LabelExtractionStatus}）、
 * 「那块区域里的编号是几」（本枚举）。层次一旦混起来，
 * 「有框但读不出编号」与「连框都没有」就会被同一个名字盖住 ——
 * 而前者恰恰要产出 {@code HIT_ID_UNKNOWN}（带框坐标）、后者才是 {@code MISS}。</p>
 *
 * <p><b>识别失败绝不影响已经确认的 bbox 命中（重要）</b>：本枚举的
 * {@link #NO_ID}/{@link #LOW_CONFIDENCE}/{@link #RECOGNITION_FAILED}/{@link #INVALID_LABEL}
 * 全部落在「框已经点中了，只是编号没读出来」这一侧。任何把它们当成「没有目标」的分支
 * 都是错的 —— 这也是 {@link #keepsHit()} 存在的原因：把这条区分写成可读的方法名，
 * 而不是让调用方去比较枚举字面量（那种写法迟早会在某处漏掉一个分支）。</p>
 *
 * <p>线程约定：枚举不可变，可跨线程安全共享。</p>
 */
public enum TargetIdRecognitionStatus {

    /** 读出了编号，{@link TargetIdRecognitionResult#targetId} 非 null。 */
    SUCCESS,

    /**
     * <b>标签区域是好的，但里面没有可用的编号</b>（OCR 没读出文本，或读出的文本里
     * 一个数字都没有）。它<b>不</b>表示这次点击没点中目标。
     */
    NO_ID,

    /**
     * 读出了编号，但置信度低于 {@code minIdConfidence} —— 宁可报「不确定」，
     * 也不把一个可能读错的编号交给上层。
     */
    LOW_CONFIDENCE,

    /** 交进来的标签本身不可用（无像素、尺寸非法、或坐标与像素不一致）。 */
    INVALID_LABEL,

    /** 识别过程本身失败（OCR 内部异常、预处理器抛错等）—— 与「没有编号」是两回事。 */
    RECOGNITION_FAILED;

    /** 是否真的读到了编号。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 是否「目标框仍然算命中」—— <b>除成功外全部为 true</b>。
     *
     * <p>这是本类的核心语义：识别是命中判定之后的<b>附加</b>步骤，
     * 它失败了不会把已经确认的命中倒退成 MISS。上层据此把结果映射成
     * {@code HIT}（{@link #SUCCESS}）或 {@code HIT_ID_UNKNOWN}（其余），
     * 只有「连框都没点中」才会是 {@code MISS}，而那件事根本不归本枚举管。</p>
     */
    public boolean keepsHit() {
        return true;
    }

    /** 是否属于「识别阶段出了问题」的失败集合（用于日志归类，不影响命中语义）。 */
    public boolean isFailure() {
        return this == INVALID_LABEL || this == RECOGNITION_FAILED;
    }
}
