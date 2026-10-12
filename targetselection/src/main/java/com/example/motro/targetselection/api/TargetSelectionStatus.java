package com.example.motro.targetselection.api;

/**
 * 一次目标选择的结局状态。**播放器无关**：取值只描述分析结果，不携带任何来自
 * FFmpegPlayer / Media3 / VLC / GStreamer / Camera2 的痕迹。
 *
 * <p>关键区分（上层据此决定 UI 行为，别把它们混成一句“失败”）：</p>
 * <ul>
 *   <li>{@link #HIT} / {@link #HIT_ID_UNKNOWN}：点到了目标，前者能拿到 id，后者只有框</li>
 *   <li>{@link #MISS}：分析跑了，但点上没有目标</li>
 *   <li>{@link #OUTSIDE_VIDEO}：点在黑边上，根本没映射到视频坐标，不该算 MISS</li>
 *   <li>{@link #GEOMETRY_UNAVAILABLE}：几何未知，无法映射</li>
 *   <li>{@link #CAPTURE_FAILED} / {@link #NO_FRAME}：取不到画面，前者是失败、后者是时序上没有帧</li>
 *   <li>{@link #CANCELLED}：请求在分析前被放弃</li>
 *   <li>{@link #INTERNAL_ERROR}：管线内部出现<b>不该发生</b>的错误（未检查异常被兜住），
 *       是编程错误的信号，不等于「没点中」</li>
 * </ul>
 */
public enum TargetSelectionStatus {

    /** 命中目标且拿到 id。 */
    HIT,

    /** 分析已执行，但探测点上没有目标。 */
    MISS,

    /** 命中目标框，但 id 无法确定（蓝框命中而 OCR 失败、元数据缺 id）。 */
    HIT_ID_UNKNOWN,

    /** 探测点落在显示内容区之外（letterbox / pillarbox 黑边）或 View 之外。 */
    OUTSIDE_VIDEO,

    /** 视频几何未知或退化（尺寸为 0、内容区越界），无法完成 view→video 映射。 */
    GEOMETRY_UNAVAILABLE,

    /** 帧获取确实失败（超时、解码/渲染错误），见对应 reason。 */
    CAPTURE_FAILED,

    /** 帧来源当前没有可用画面（首帧未到、尚未起播），属于正常时序而非错误。 */
    NO_FRAME,

    /** 请求被放弃（配置关闭、显式取消）。 */
    CANCELLED,

    /**
     * 管线内部出现不该发生的错误（未检查异常被兜住、协作者返回自相矛盾的结果等）。
     *
     * <p><b>它不等于 MISS</b>：MISS 意味着"分析跑了但点上没有目标"，
     * 而本状态意味着"分析根本没跑完"。上层看到它应当视为<b>缺陷信号</b>
     * （打点/上报），而不是正常的一次未命中。把它与 MISS 分开，
     * 是为了避免"一个 NPE 被当成用户没点中"这种把 bug 埋掉的行为。</p>
     */
    INTERNAL_ERROR;

    /** 是否"点到了目标"（含只有框没有 id 的情况）。 */
    public boolean isHit() {
        return this == HIT || this == HIT_ID_UNKNOWN;
    }

    /** 是否拿到了可用 id（只有 {@link #HIT} 为真）。 */
    public boolean hasResolvedId() {
        return this == HIT;
    }

    /** 是否属于"没拿到结果"的异常/放弃路径，用于决定是否给出用户提示。 */
    public boolean isFailure() {
        switch (this) {
            case CAPTURE_FAILED:
            case NO_FRAME:
            case GEOMETRY_UNAVAILABLE:
            case CANCELLED:
            case INTERNAL_ERROR:
                return true;
            default:
                return false;
        }
    }
}
