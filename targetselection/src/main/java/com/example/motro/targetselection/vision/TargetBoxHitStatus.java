package com.example.motro.targetselection.vision;

/**
 * 一次触摸命中测试的结局。<b>只有这两种</b>取值是有意的：
 * 「取帧失败」「点在黑边」这类问题在更早的阶段（控制器管线）就已经分流掉了，
 * 落到本枚举时已经拿到过一张可用画面、也确实跑过检测，
 * 因此剩下唯一有意义的区分就是"点到框了"和"没点到"。
 *
 * <p>需要与 {@link com.example.motro.targetselection.api.TargetSelectionStatus} 区分：
 * 那是<b>对上层暴露</b>的全量状态（含取帧/几何/取消等失败路径）；本枚举只描述
 * 命中测试这一步。控制器把 {@link #HIT_BOX} 映射为
 * {@code HIT_ID_UNKNOWN}（本 Slice 还没有 id 识别），把 {@link #MISS} 映射为 {@code MISS}。</p>
 */
public enum TargetBoxHitStatus {

    /** 点到了某个候选框（含容差命中）。 */
    HIT_BOX,

    /** 没有点到任何候选框。 */
    MISS;

    public boolean isHit() {
        return this == HIT_BOX;
    }
}
