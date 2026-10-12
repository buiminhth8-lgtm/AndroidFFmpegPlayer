package com.example.motro.targetselection.api;

import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.TargetHitType;

/**
 * 一次目标选择的结果，不可变。
 *
 * <p>字段与 {@link TargetSelectionStatus} 强相关，构造时会被归一化以保证自洽：</p>
 * <ul>
 *   <li>只有 {@code HIT} / {@code HIT_ID_UNKNOWN} 会带 {@link #boundingBox} 与
 *       {@link #boxConfidence}；其余状态一律为 null / 0，避免上层误读到上次残留。</li>
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
 * <p><b>坐标与时间的落点（Slice 9）</b>：{@link #touchViewX}/{@link #touchViewY} 是原始 View 坐标
 * （原样回带，便于上层把结果标在屏幕上）；{@link #touchVideoX}/{@link #touchVideoY} 是映射后的
 * <b>视频</b>坐标；{@link #boundingBox} 也是对外的<b>视频</b>坐标 bbox —— 三者刻意都保留，
 * 因为"点在哪"与"命中框在哪"分属两套坐标系，上层做 UI 时都要用。
 * {@link #requestTimeNs} 是点击时刻，{@link #captureRequestNs}/{@link #captureCompletedNs}
 * 是取帧的起止时刻，{@link #captureLatencyMs} 是两者的差。若帧来源拿不到 PTS，
 * {@link #framePtsUs} 保持 {@link #NO_PTS_US}（<b>绝不伪造</b>）。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享（可安全地从工作线程传回主线程）。</p>
 */
public final class TargetHitResult {

    /** {@link #framePtsUs} 的哨兵值：本次没有可用的帧 PTS。 */
    public static final long NO_PTS_US = -1L;

    /** {@link #touchVideoX}/{@link #touchVideoY} 的哨兵值：未映射到视频坐标。 */
    public static final float NO_COORDINATE = Float.NaN;

    /** {@link #requestTimeNs} / {@link #captureRequestNs} / {@link #captureCompletedNs} 的哨兵值：未知。 */
    public static final long NO_TIME_NS = -1L;

    /** {@link #requestId} 的哨兵值：调用方未提供请求 id。 */
    public static final long NO_REQUEST_ID = -1L;

    /** 本次尝试的结局。 */
    public final TargetSelectionStatus status;

    /** 命中的目标 id；仅 {@link TargetSelectionStatus#HIT} 非 null。 */
    public final String targetId;

    /** 命中的目标框（视频像素坐标）；仅命中类状态非 null。 */
    public final TargetBoundingBox boundingBox;

    /** 原始触摸点 View 坐标 x。 */
    public final float touchViewX;

    /** 原始触摸点 View 坐标 y。 */
    public final float touchViewY;

    /** 探测点的视频像素 x；未映射时为 {@link #NO_COORDINATE}。 */
    public final float touchVideoX;

    /** 探测点的视频像素 y；未映射时为 {@link #NO_COORDINATE}。 */
    public final float touchVideoY;

    /** 候选框置信度 [0,1]（{@code DetectedTargetBox.confidence}）；未命中为 0。 */
    public final float boxConfidence;

    /** 编号规则置信度 [0,1]；未识别成功或实现无法给出时为 null。 */
    public final Float idConfidence;

    /** 本次分析所用帧的 PTS（微秒）；未知为 {@link #NO_PTS_US}（不伪造）。 */
    public final long framePtsUs;

    /** 点击时刻（{@link System#nanoTime()} 基准）；未知为 {@link #NO_TIME_NS}。 */
    public final long requestTimeNs;

    /** 取帧发起时刻（{@link System#nanoTime()} 基准）；未取帧时为 {@link #NO_TIME_NS}。 */
    public final long captureRequestNs;

    /** 取帧完成时刻（{@link System#nanoTime()} 基准）；未取帧时为 {@link #NO_TIME_NS}。 */
    public final long captureCompletedNs;

    /** 取帧延迟（毫秒）；未取帧时为 0。 */
    public final long captureLatencyMs;

    /** 框检测耗时（毫秒）：掩码 + 候选恢复 + 命中 + 选择。 */
    public final long boxDetectMs;

    /** 编号识别耗时（毫秒）；未做识别时为 0。 */
    public final long idRecognizeMs;

    /** 本次选择从进入控制器到返回的总耗时（毫秒），含取帧与识别。 */
    public final long totalProcessingMs;

    /**
     * {@link #totalProcessingMs} 的历史别名（Slice 0 起的名字）。
     *
     * <p>保留它是为了不破坏 Slice 0~8 已经写好的调用点与测试：那段时间这个字段就叫
     * {@code processingMs}。两者<b>恒等</b>，新代码请用语义更明确的
     * {@link #totalProcessingMs}。</p>
     */
    public final long processingMs;

    /** 请求标识（来自 {@link TargetSelectionRequest#requestId}）；未知为 {@link #NO_REQUEST_ID}。 */
    public final long requestId;

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
                            float touchViewX, float touchViewY,
                            float touchVideoX, float touchVideoY,
                            float boxConfidence, Float idConfidence,
                            long framePtsUs, long requestTimeNs,
                            long captureRequestNs, long captureCompletedNs,
                            long captureLatencyMs, long boxDetectMs, long idRecognizeMs,
                            long totalProcessingMs, long requestId,
                            String reason, TargetHitType hitType, int candidateCount,
                            long roiMs, long maskMs) {
        this.status = status;
        this.targetId = targetId;
        this.boundingBox = boundingBox;
        this.touchViewX = touchViewX;
        this.touchViewY = touchViewY;
        this.touchVideoX = touchVideoX;
        this.touchVideoY = touchVideoY;
        this.boxConfidence = boxConfidence;
        this.idConfidence = idConfidence;
        this.framePtsUs = framePtsUs;
        this.requestTimeNs = requestTimeNs;
        this.captureRequestNs = captureRequestNs;
        this.captureCompletedNs = captureCompletedNs;
        this.captureLatencyMs = captureLatencyMs;
        this.boxDetectMs = boxDetectMs;
        this.idRecognizeMs = idRecognizeMs;
        this.totalProcessingMs = totalProcessingMs;
        this.processingMs = totalProcessingMs;
        this.requestId = requestId;
        this.reason = reason;
        this.hitType = hitType;
        this.candidateCount = candidateCount;
        this.roiMs = Math.max(0L, roiMs);
        this.maskMs = Math.max(0L, maskMs);
    }

    /**
     * 规范构造入口（Slice 9 完整字段）：按 status 归一化载荷字段，保证结果自洽。
     *
     * <p>关键不变量：<b>{@code HIT} 必然携带 id</b>。若调用方传入 {@code HIT} 但没有 id，
     * 会自动降级为 {@code HIT_ID_UNKNOWN} 而不是留下一个"名为 HIT 却取不到 id"的矛盾结果 ——
     * 上层按 {@code status == HIT} 分支时就能安心直接取 {@link #targetId}。</p>
     *
     * <p>非命中状态会强制清空 {@link #boundingBox}/{@link #boxConfidence}/{@link #idConfidence}
     * 与 {@link #hitType}；{@code candidateCount} 只在"分析过"的状态（命中或 MISS）保留。
     * 耗时字段一律非负钳制。</p>
     *
     * @param status      结局，非 null
     * @param targetId    目标 id，仅 HIT 有意义；null / 空串视为未知
     * @param boundingBox 目标框（视频坐标），仅命中类状态有意义
     * @param touchViewX  View 坐标 x（原样回带，允许 NaN 表示未知）
     * @param touchViewY  View 坐标 y
     * @param touchVideoX 视频坐标 x，未映射传 {@link #NO_COORDINATE}
     * @param touchVideoY 视频坐标 y，未映射传 {@link #NO_COORDINATE}
     * @param boxConfidence 候选框置信度，非命中状态会被置 0
     * @param idConfidence 编号置信度，可为 null（未知）
     * @param framePtsUs  帧 PTS，未知传 {@link #NO_PTS_US}
     * @param requestTimeNs 点击时刻，未知传 {@link #NO_TIME_NS}
     * @param captureRequestNs 取帧发起时刻，未取帧传 {@link #NO_TIME_NS}
     * @param captureCompletedNs 取帧完成时刻，未取帧传 {@link #NO_TIME_NS}
     * @param captureLatencyMs 取帧延迟毫秒
     * @param boxDetectMs 框检测耗时毫秒
     * @param idRecognizeMs 编号识别耗时毫秒
     * @param totalProcessingMs 总耗时毫秒，负数会被置 0
     * @param requestId   请求标识，未知传 {@link #NO_REQUEST_ID}
     * @param reason      诊断信息，可为 null
     * @param hitType     命中分级，未命中或未知传 null
     * @param candidateCount 候选总数，未知传 0；负值被置 0
     * @throws IllegalArgumentException status 为 null
     */
    public static TargetHitResult of(TargetSelectionStatus status, String targetId,
                                     TargetBoundingBox boundingBox,
                                     float touchViewX, float touchViewY,
                                     float touchVideoX, float touchVideoY,
                                     float boxConfidence, Float idConfidence,
                                     long framePtsUs, long requestTimeNs,
                                     long captureRequestNs, long captureCompletedNs,
                                     long captureLatencyMs, long boxDetectMs, long idRecognizeMs,
                                     long totalProcessingMs, long requestId,
                                     String reason, TargetHitType hitType, int candidateCount) {
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
                touchViewX,
                touchViewY,
                touchVideoX,
                touchVideoY,
                carriesTarget ? clamp01(boxConfidence) : 0f,
                carriesTarget ? normalizeConfidence(idConfidence) : null,
                framePtsUs,
                requestTimeNs,
                captureRequestNs,
                captureCompletedNs,
                Math.max(0L, captureLatencyMs),
                Math.max(0L, boxDetectMs),
                Math.max(0L, idRecognizeMs),
                Math.max(0L, totalProcessingMs),
                requestId,
                reason,
                carriesTarget ? hitType : null,
                analyzed ? Math.max(0, candidateCount) : 0,
                0L, 0L);
    }

    /**
     * 精简构造入口（不带 Slice 9 的坐标/耗时字段）：委托到完整入口，
     * 把新字段填成哨兵值。保留它是为了让 Slice 6/7 已经写好的调用点与测试不必全部改写。
     */
    public static TargetHitResult of(TargetSelectionStatus status, String targetId,
                                     TargetBoundingBox boundingBox,
                                     float touchVideoX, float touchVideoY, float confidence,
                                     long framePtsUs, long processingMs, String reason) {
        return of(status, targetId, boundingBox,
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY,
                confidence, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, null, 0);
    }

    /** 精简构造入口（带 Slice 6 诊断字段）。 */
    public static TargetHitResult of(TargetSelectionStatus status, String targetId,
                                     TargetBoundingBox boundingBox,
                                     float touchVideoX, float touchVideoY, float confidence,
                                     long framePtsUs, long processingMs, String reason,
                                     TargetHitType hitType, int candidateCount) {
        return of(status, targetId, boundingBox,
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY,
                confidence, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, hitType, candidateCount);
    }

    /** 命中且拿到 id。 */
    public static TargetHitResult hit(String targetId, TargetBoundingBox boundingBox,
                                      float boxConfidence, Float idConfidence,
                                      float touchVideoX, float touchVideoY,
                                      long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.HIT, targetId, boundingBox,
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY,
                boxConfidence, idConfidence,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, null, 0);
    }

    /** 命中且拿到 id（不带 id 置信度）。 */
    public static TargetHitResult hit(String targetId, TargetBoundingBox boundingBox, float confidence,
                                      float touchVideoX, float touchVideoY,
                                      long framePtsUs, long processingMs) {
        return hit(targetId, boundingBox, confidence, null, touchVideoX, touchVideoY, framePtsUs, processingMs);
    }

    /** 命中目标框但 id 未知（蓝框命中而 OCR 失败）。 */
    public static TargetHitResult hitIdUnknown(TargetBoundingBox boundingBox, float confidence,
                                              float touchVideoX, float touchVideoY,
                                              long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.HIT_ID_UNKNOWN, null, boundingBox,
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY, confidence, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, null, 0);
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
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY, confidence, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, hitType, candidateCount);
    }

    /** 分析已执行但点上没有目标。 */
    public static TargetHitResult miss(float touchVideoX, float touchVideoY,
                                       long framePtsUs, long processingMs) {
        return of(TargetSelectionStatus.MISS, null, null,
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY, 0f, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, null, 0);
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
                NO_COORDINATE, NO_COORDINATE, touchVideoX, touchVideoY, 0f, null,
                framePtsUs, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, null, candidateCount);
    }

    /** 点在黑边 / 内容区之外，无法映射到视频坐标。 */
    public static TargetHitResult outsideVideo(long processingMs) {
        return of(TargetSelectionStatus.OUTSIDE_VIDEO, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, null, null, 0);
    }

    /** 几何未知或退化，无法映射。 */
    public static TargetHitResult geometryUnavailable(long processingMs, String reason) {
        return of(TargetSelectionStatus.GEOMETRY_UNAVAILABLE, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, null, 0);
    }

    /** 取帧失败，reason 携带失败原因。 */
    public static TargetHitResult captureFailed(long processingMs, String reason) {
        return of(TargetSelectionStatus.CAPTURE_FAILED, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, null, 0);
    }

    /** 帧来源当前没有可用画面。 */
    public static TargetHitResult noFrame(long processingMs, String reason) {
        return of(TargetSelectionStatus.NO_FRAME, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, null, 0);
    }

    /** 请求被放弃（配置关闭 / 显式取消）。 */
    public static TargetHitResult cancelled(String reason) {
        return of(TargetSelectionStatus.CANCELLED, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                0L, NO_REQUEST_ID, reason, null, 0);
    }

    /**
     * 管线内部出现不该发生的错误（未检查异常被兜住）。
     *
     * <p>与 {@link #miss} 刻意分开：MISS 是"正常跑完但没点中"，INTERNAL_ERROR 是"根本没跑完"。
     * 上层若把两者等同，一个 NPE 就会被静默地当成用户没点中。</p>
     */
    public static TargetHitResult internalError(long processingMs, String reason) {
        return of(TargetSelectionStatus.INTERNAL_ERROR, null, null,
                NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, 0f, null,
                NO_PTS_US, NO_TIME_NS, NO_TIME_NS, NO_TIME_NS, 0L, 0L, 0L,
                processingMs, NO_REQUEST_ID, reason, null, 0);
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

    /** 是否带有可用 View 坐标（未被置为 NaN 哨兵）。 */
    public boolean hasViewCoordinates() {
        return !Float.isNaN(touchViewX) && !Float.isNaN(touchViewY);
    }

    public boolean hasFramePts() {
        return framePtsUs != NO_PTS_US;
    }

    /** 是否带有请求标识。 */
    public boolean hasRequestId() {
        return requestId != NO_REQUEST_ID;
    }

    /** 是否带有编号置信度。 */
    public boolean hasIdConfidence() {
        return idConfidence != null;
    }

    /** 是否带有命中分级（即真的做过一次命中判定并命中）。 */
    public boolean hasHitType() {
        return hitType != null;
    }

    /**
     * 复制一份并替换诊断原因（其余字段不变）。用于先构造结果、再补一段更精确的说明。
     *
     * @param newReason 新的诊断信息，可为 null
     */
    public TargetHitResult withReason(String newReason) {
        return new TargetHitResult(status, targetId, boundingBox,
                touchViewX, touchViewY, touchVideoX, touchVideoY,
                boxConfidence, idConfidence,
                framePtsUs, requestTimeNs, captureRequestNs, captureCompletedNs,
                captureLatencyMs, boxDetectMs, idRecognizeMs, totalProcessingMs,
                requestId, newReason, hitType, candidateCount, roiMs, maskMs);
    }

    /**
     * 复制一份并补齐/覆盖分阶段耗时（ROI / 掩码 / 框检测 / 编号识别），其余字段不变。
     *
     * <p>分阶段耗时在管线的不同段落各自测量，最后汇总到结果里；用这个<b>不可变副本</b>
     * 方法而不是可变字段，是为了让 {@link TargetHitResult} 保持不可变、可安全跨线程传递
     * （它正是从工作线程传回主线程的载体）。</p>
     *
     * @param newRoiMs         ROI 裁剪耗时毫秒
     * @param newMaskMs        蓝色掩码耗时毫秒
     * @param newBoxDetectMs   框检测耗时毫秒（覆盖已有值）
     * @param newIdRecognizeMs 编号识别耗时毫秒（覆盖已有值）
     */
    public TargetHitResult withTimings(long newRoiMs, long newMaskMs,
                                       long newBoxDetectMs, long newIdRecognizeMs) {
        return new TargetHitResult(status, targetId, boundingBox,
                touchViewX, touchViewY, touchVideoX, touchVideoY,
                boxConfidence, idConfidence,
                framePtsUs, requestTimeNs, captureRequestNs, captureCompletedNs,
                captureLatencyMs, Math.max(0L, newBoxDetectMs), Math.max(0L, newIdRecognizeMs),
                totalProcessingMs, requestId, reason, hitType, candidateCount,
                Math.max(0L, newRoiMs), Math.max(0L, newMaskMs));
    }

    /** ROI 裁剪阶段耗时（毫秒）；未执行时为 0。 */
    public final long roiMs;

    /** 蓝色掩码阶段耗时（毫秒）；未执行时为 0。 */
    public final long maskMs;

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static Float normalizeConfidence(Float value) {
        if (value == null || Float.isNaN(value)) {
            return null;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public String toString() {
        return "TargetHitResult{status=" + status
                + ", targetId=" + targetId
                + ", box=" + boundingBox
                + ", touchView=(" + touchViewX + "," + touchViewY + ")"
                + ", touchVideo=(" + touchVideoX + "," + touchVideoY + ")"
                + ", boxConf=" + boxConfidence
                + ", idConf=" + idConfidence
                + ", hitType=" + hitType
                + ", candidateCount=" + candidateCount
                + ", framePtsUs=" + framePtsUs
                + ", requestTimeNs=" + requestTimeNs
                + ", captureLatencyMs=" + captureLatencyMs
                + ", boxDetectMs=" + boxDetectMs
                + ", idRecognizeMs=" + idRecognizeMs
                + ", totalProcessingMs=" + totalProcessingMs
                + (reason != null ? ", reason=" + reason : "") + "}";
    }
}
