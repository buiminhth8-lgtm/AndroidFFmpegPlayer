package com.example.motro.targetselection.vision;

/**
 * 一次「蓝色掩码 → 目标框候选」的结局。<b>播放器无关</b>，只描述检测这一步本身。
 *
 * <p>与 {@link TargetBoxHitStatus} 的分工：本枚举回答「<b>找到了几个框</b>」，
 * 那个枚举回答「<b>点在不在框里</b>」。把它们合成一个枚举会让
 * "没有框" 与 "有框但没点到" 这两个完全不同的结论变得难以区分。</p>
 */
public enum TargetBoxDetectionStatus {

    /** 检测跑完并且拿到了至少一个候选框。 */
    SUCCESS,

    /**
     * 检测跑完，但没有找到任何合法候选框。
     *
     * <p>这是<b>正常结论</b>而不是错误：掩码里可能只有文字、只有噪声、
     * 或者蓝色内容不成矩形（一整块实心蓝色物体）。上层据此报 MISS，
     * 而不是把它当成"算法坏了"。</p>
     */
    NO_BOX,

    /**
     * 掩码本身不可用：没有 ROI、尺寸非法、或与 ROI 尺寸不一致
     * （后者说明上游有 bug —— 掩码不是按这个 ROI 裁出来的）。
     */
    INVALID_MASK,

    /** 入参非法（{@link BlueMaskResult} 为 null）。 */
    INVALID_INPUT
}
