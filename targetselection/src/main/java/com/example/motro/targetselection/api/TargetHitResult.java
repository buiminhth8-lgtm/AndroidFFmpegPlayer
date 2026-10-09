package com.example.motro.targetselection.api;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

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

    private TargetHitResult(TargetSelectionStatus status, String targetId, TargetBoundingBox boundingBox,
                            float touchVideoX, float touchVideoY, float confidence,
                            long framePtsUs, long processingMs, String reason) {
        this.status = status;
        this.targetId = targetId;
        this.boundingBox = boundingBox;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.confidence = confidence;
        this.framePtsUs = framePtsUs;
        this.processingMs = processingMs;
        this.reason = reason;
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
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        final String normalizedId = (targetId == null || targetId.isEmpty()) ? null : targetId;
        // HIT 缺 id → 降级，保证 "HIT 必有 id" 这一不变量
        final TargetSelectionStatus effective = (status == TargetSelectionStatus.HIT && normalizedId == null)
                ? TargetSelectionStatus.HIT_ID_UNKNOWN
                : status;
        final boolean carriesTarget = effective.isHit();
        return new TargetHitResult(
                effective,
                carriesTarget ? normalizedId : null,
                carriesTarget ? boundingBox : null,
                touchVideoX,
                touchVideoY,
                carriesTarget ? clamp01(confidence) : 0f,
                framePtsUs,
                Math.max(0L, processingMs),
                reason);
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

    /** 分析已执行但点上没有目标。 */
    public static TargetHitResult miss(float touchVideoX, float touchVideoY,
                                       long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.MISS, null, null,
                touchVideoX, touchVideoY, 0f, framePtsUs, processingMs, null);
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
                + ", framePtsUs=" + framePtsUs
                + ", processingMs=" + processingMs
                + (reason != null ? ", reason=" + reason : "") + "}";
    }
}
