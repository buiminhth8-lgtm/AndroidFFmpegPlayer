package com.example.motro.targetselection.api;

import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.TargetHitType;

/**
 * 一次目标选择的结果，不可变。
 *
 * <p>字段与 {@link TargetSelectionStatus} 强相关，构造时会被归一化以保证自洽：</p>
 * <ul>
 *   <li>只有 {@code HIT} / {@code HIT_ID_UNKNOWN} 会带 {@link #boundingBox} 与 {@link #confidence}；
 *       其余状态一律为 null / 0，避免上层误读到上次残留。</li>
 *   <li>{@link #targetId} 仅 {@code HIT} 非 null；{@code HIT_ID_UNKNOWN} 明确为 null。</li>
 *   <li>{@link #touchVideoX}/{@link #touchVideoY} 在未能映射到视频坐标时（黑边、几何未知、取帧失败）
 *       为 {@link Float#NaN}，用 {@link #hasVideoCoordinates()} 判断，别跟 0 混淆
 *       —— 0 是一个合法坐标。</li>
 *   <li>{@link #hitType}/{@link #candidateCount}（Slice 6）是<b>诊断信息</b>：
 *       前者区分"点在框里"与"靠容差够到"，后者说明当时有几个候选参与竞争。
 *       命中与 MISS 都可能带着它们（MISS 也能有候选，只是没点中）；
 *       而几何未知/取帧失败这类<b>还没开始分析</b>的状态里它们恒为 null / 0，
 *       因为那时压根没有候选可言。</li>
 * </ul>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享（可安全地从工作线程传回主线程）。</p>
 */
public final class TargetHitResult {

    /** {@link #framePtsUs} 的哨兵值：本次没有可用的帧 PTS。 */
    public static final long NO_PTS_US = -1L;

    /** {@link #touchVideoX}/{@link #touchVideoY} 的哨兵值：未映射到视频坐标。 */
    public static final float NO_COORDINATE = Float.NaN;

    /** 本次尝试的结局。 */
    public final TargetSelectionStatus status;

    /** 命中的目标 id；仅 {@link TargetSelectionStatus#HIT} 非 null。 */
    public final String targetId;

    /** 命中的目标框（视频像素坐标）；仅命中类状态非 null。 */
    public final TargetBoundingBox boundingBox;

    /** 探测点的视频像素 x；未映射时为 {@link #NO_COORDINATE}。 */
    public final float touchVideoX;

    /** 探测点的视频像素 y；未映射时为 {@link #NO_COORDINATE}。 */
    public final float touchVideoY;

    /** 候选置信度 [0,1]；未命中为 0。 */
    public final float confidence;

    /** 本次分析所用帧的 PTS（微秒）；未知为 {@link #NO_PTS_US}。 */
    public final long framePtsUs;

    /** 本次选择从进入控制器到返回的耗时（毫秒），含取帧与识别。 */
    public final long processingMs;

    /** 诊断信息（如 {@code CAPTURE_FAILED} 的原因）；无则 null。不会用于业务判断。 */
    public final String reason;

    /**
     * 命中分级（Slice 6 诊断字段）：严格包含还是靠容差够到；
     * 未命中、或实现方不区分时为 null。
     *
     * <p>刻意放在 {@code api} 包而不是藏进实现里：上层要显示"这次点击是扎实命中还是
     * 擦边命中"，靠它做埋点与阈值调整。但它<b>不参与任何判定</b> ——
     * 判定已在视觉层完成，这里只是把结论带上来。</p>
     */
    public final TargetHitType hitType;

    /**
     * 本次竞争该目标框的候选总数（Slice 6 诊断字段，含未命中者）；
     * 命中与 MISS 都可能 &gt; 0，未开始分析的状态恒为 0。
     */
    public final int candidateCount;

    private TargetHitResult(TargetSelectionStatus status, String targetId, TargetBoundingBox boundingBox,
                            float touchVideoX, float touchVideoY, float confidence,
                            long framePtsUs, long processingMs, String reason,
                            TargetHitType hitType, int candidateCount) {
        this.status = status;
        this.targetId = targetId;
        this.boundingBox = boundingBox;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.confidence = confidence;
        this.framePtsUs = framePtsUs;
        this.processingMs = processingMs;
        this.reason = reason;
        this.hitType = hitType;
        this.candidateCount = candidateCount;
    }

    /**
     * 规范构造入口：按 status 归一化载荷字段（非命中状态下强制清空 targetId / boundingBox / confidence），
     * 保证结果自洽。其余工厂方法都委托到这里。
     *
     * <p>关键不变量：<b>{@code HIT} 必然携带 id</b>。若调用方传入 {@code HIT} 但没有 id，
     * 会自动降级为 {@code HIT_ID_UNKNOWN} 而不是留下一个“名为 HIT 却取不到 id”的矛盾结果 ——
     * 上层按 {@code status == HIT} 分支时就能安心直接取 {@link #targetId}。</p>
     *
     * @param status      结局，非 null
     * @param targetId    目标 id，仅 HIT 有意义；null / 空串视为未知
     * @param boundingBox 目标框，仅命中类状态有意义
     * @param touchVideoX 视频坐标 x，未映射传 {@link #NO_COORDINATE}
     * @param touchVideoY 视频坐标 y，未映射传 {@link #NO_COORDINATE}
     * @param confidence  置信度，非命中状态会被置 0
     * @param framePtsUs  帧 PTS，未知传 {@link #NO_PTS_US}
     * @param processingMs 耗时毫秒，负数会被置 0
     * @param reason      诊断信息，可为 null
     * @throws IllegalArgumentException status 为 null
     */
    public static TargetHitResult of(TargetSelectionStatus status, String targetId,
                                     TargetBoundingBox boundingBox,
                                     float touchVideoX, float touchVideoY, float confidence,
                                     long framePtsUs, long processingMs, String reason) {
        return of(status, targetId, boundingBox, touchVideoX, touchVideoY, confidence,
                framePtsUs, processingMs, reason, null, 0);
    }

    /**
     * 规范构造入口（带 Slice 6 诊断字段）。
     *
     * <p>在上一版基础上多两个字段，归一化规则如下：</p>
     * <ul>
     *   <li>{@code hitType}：仅命中类状态保留；{@code MISS} 与更早失败的状态一律置 null ——
     *       "没命中却带着命中分级"是自相矛盾的。</li>
     *   <li>{@code candidateCount}：命中与 {@code MISS} 都保留（MISS 也能有候选，只是没点中，
     *       这个数字正是排查"为什么没点中"的关键）；几何未知/取帧失败/取消这类
     *       <b>还没开始分析</b>的状态强制置 0。</li>
     * </ul>
     *
     * @param hitType        命中分级，未命中或未知传 null
     * @param candidateCount 候选总数，未知传 0；负值被置 0
     * @throws IllegalArgumentException status 为 null
     */
    public static TargetHitResult of(TargetSelectionStatus status, String targetId,
                                     TargetBoundingBox boundingBox,
                                     float touchVideoX, float touchVideoY, float confidence,
                                     long framePtsUs, long processingMs, String reason,
                                     TargetHitType hitType, int candidateCount) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        final String normalizedId = (targetId == null || targetId.isEmpty()) ? null : targetId;
        // HIT 缺 id → 降级，保证 "HIT 必有 id" 这一不变量
        final TargetSelectionStatus effective = (status == TargetSelectionStatus.HIT && normalizedId == null)
                ? TargetSelectionStatus.HIT_ID_UNKNOWN
                : status;
        final boolean carriesTarget = effective.isHit();
        final boolean analyzed = carriesTarget || effective == TargetSelectionStatus.MISS;
        return new TargetHitResult(
                effective,
                carriesTarget ? normalizedId : null,
                carriesTarget ? boundingBox : null,
                touchVideoX,
                touchVideoY,
                carriesTarget ? clamp01(confidence) : 0f,
                framePtsUs,
                Math.max(0L, processingMs),
                reason,
                carriesTarget ? hitType : null,
                analyzed ? Math.max(0, candidateCount) : 0);
    }

    /** 命中且拿到 id。 */
    public static TargetHitResult hit(String targetId, TargetBoundingBox boundingBox, float confidence,
                                      float touchVideoX, float touchVideoY,
                                      long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.HIT, targetId, boundingBox,
                touchVideoX, touchVideoY, confidence, framePtsUs, processingMs, null);
    }

    /** 命中目标框但 id 未知（蓝框命中而 OCR 失败）。 */
    public static TargetHitResult hitIdUnknown(TargetBoundingBox boundingBox, float confidence,
                                              float touchVideoX, float touchVideoY,
                                              long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.HIT_ID_UNKNOWN, null, boundingBox,
                touchVideoX, touchVideoY, confidence, framePtsUs, processingMs, null);
    }

    /**
     * 命中目标框但 id 未知，并带上命中诊断信息（Slice 6）。
     *
     * @param hitType        严格包含还是靠容差够到
     * @param candidateCount 参与竞争的候选总数
     */
    public static TargetHitResult hitIdUnknown(TargetBoundingBox boundingBox, float confidence,
                                              float touchVideoX, float touchVideoY,
                                              long framePtsUs, long processingMs,
                                              TargetHitType hitType, int candidateCount) {
        return of(TargetSelectionStatus.HIT_ID_UNKNOWN, null, boundingBox,
                touchVideoX, touchVideoY, confidence, framePtsUs, processingMs, null,
                hitType, candidateCount);
    }

    /** 分析已执行但点上没有目标。 */
    public static TargetHitResult miss(float touchVideoX, float touchVideoY,
                                       long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.MISS, null, null,
                touchVideoX, touchVideoY, 0f, framePtsUs, processingMs, null);
    }

    /**
     * 分析已执行但点上没有目标，并说明当时有多少候选参与竞争。
     *
     * <p>"有 3 个候选但一个都没点中"与"画面上压根没有候选"是两种完全不同的现场，
     * 排查时需要的动作也不同，因此这个数字要留下来。</p>
     */
    public static TargetHitResult miss(float touchVideoX, float touchVideoY,
                                       long framePtsUs, long processingMs, int candidateCount) {
        return of(TargetSelectionStatus.MISS, null, null,
                touchVideoX, touchVideoY, 0f, framePtsUs, processingMs, null, null, candidateCount);
    }

    /** 点在黑边 / 内容区之外，无法映射到视频坐标。 */
    public static TargetHitResult outsideVideo(long processingMs) {
        return of(TargetSelectionStatus.OUTSIDE_VIDEO, null, null,
                NO_COORDINATE, NO_COORDINATE, 0f, NO_PTS_US, processingMs, null);
    }

    /** 几何未知或退化，无法映射。 */
    public static TargetHitResult geometryUnavailable(long processingMs, String reason) {
        return of(TargetSelectionStatus.GEOMETRY_UNAVAILABLE, null, null,
                NO_COORDINATE, NO_COORDINATE, 0f, NO_PTS_US, processingMs, reason);
    }

    /** 取帧失败，reason 携带失败原因。 */
    public static TargetHitResult captureFailed(long processingMs, String reason) {
        return of(TargetSelectionStatus.CAPTURE_FAILED, null, null,
                NO_COORDINATE, NO_COORDINATE, 0f, NO_PTS_US, processingMs, reason);
    }

    /** 帧来源当前没有可用画面。 */
    public static TargetHitResult noFrame(long processingMs, String reason) {
        return of(TargetSelectionStatus.NO_FRAME, null, null,
                NO_COORDINATE, NO_COORDINATE, 0f, NO_PTS_US, processingMs, reason);
    }

    /** 请求被放弃（配置关闭 / 显式取消）。 */
    public static TargetHitResult cancelled(String reason) {
        return of(TargetSelectionStatus.CANCELLED, null, null,
                NO_COORDINATE, NO_COORDINATE, 0f, NO_PTS_US, 0L, reason);
    }

    public boolean isHit() {
        return status.isHit();
    }

    /** 是否拿到了可用 id：{@code HIT} 且 {@link #targetId} 非 null。 */
    public boolean hasResolvedId() {
        return status.hasResolvedId() && targetId != null;
    }

    /** 是否已完成 view→video 映射；false 时 {@link #touchVideoX}/{@link #touchVideoY} 为 NaN。 */
    public boolean hasVideoCoordinates() {
        return !Float.isNaN(touchVideoX) && !Float.isNaN(touchVideoY);
    }

    public boolean hasFramePts() {
        return framePtsUs != NO_PTS_US;
    }

    /** 是否带有命中分级（即真的做过一次命中判定并命中）。 */
    public boolean hasHitType() {
        return hitType != null;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public String toString() {
        return "TargetHitResult{status=" + status
                + ", targetId=" + targetId
                + ", box=" + boundingBox
                + ", touchVideo=(" + touchVideoX + "," + touchVideoY + ")"
                + ", confidence=" + confidence
                + ", hitType=" + hitType
                + ", candidateCount=" + candidateCount
                + ", framePtsUs=" + framePtsUs
                + ", processingMs=" + processingMs
                + (reason != null ? ", reason=" + reason : "") + "}";
    }
}
