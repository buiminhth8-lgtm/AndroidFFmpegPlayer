package com.example.motro.targetselection.geometry;

/**
 * 一次「View 坐标 → 视频像素坐标」映射的结局。**播放器无关**，只描述几何结论。
 *
 * <p>三态刻意分开，别把它们混成一句“失败”—— 上层对它们的处理完全不同：</p>
 * <ul>
 *   <li>{@link #SUCCESS}：映射成功，{@link VideoPointMappingResult#videoX}/{@code videoY} 可用。</li>
 *   <li>{@link #OUTSIDE_VIDEO}：点在可见视频区域之外（黑边、pillarbox，或 View 之外）。
 *       这不是错误，用户点到黑边是正常操作，不应提示、不应取帧、更不该算成“没命中目标”。</li>
 *   <li>{@link #INVALID_GEOMETRY}：几何缺失或退化（尺寸为 0、内容区越界），<b>无法</b>映射。
 *       这属于环境/接线问题，需要排查，而不是“用户点错了”。</li>
 * </ul>
 *
 * <p>线程约定：枚举常量，天然线程安全。</p>
 */
public enum VideoPointMappingStatus {

    /** 映射成功，且点落在可见视频区域内。 */
    SUCCESS,

    /** 点在可见视频区域之外（黑边 / View 之外），不产生视频坐标。 */
    OUTSIDE_VIDEO,

    /** 几何未知或退化，无法映射。 */
    INVALID_GEOMETRY;

    /** 是否得到了可用的视频坐标（仅 {@link #SUCCESS} 为真）。 */
    public boolean isMapped() {
        return this == SUCCESS;
    }
}
