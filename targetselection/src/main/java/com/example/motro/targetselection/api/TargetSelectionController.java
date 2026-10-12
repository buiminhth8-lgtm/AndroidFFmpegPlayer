package com.example.motro.targetselection.api;

import com.example.motro.targetselection.debug.TargetDebugState;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.FrameCaptureResult;
import com.example.motro.targetselection.frame.FrameCaptureStatus;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.vision.BlueMaskDetector;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetBoxDetector;
import com.example.motro.targetselection.vision.TargetCandidateSelector;
import com.example.motro.targetselection.vision.TargetHitTester;
import com.example.motro.targetselection.vision.TargetHitTestResult;
import com.example.motro.targetselection.vision.TargetIdRecognitionResult;
import com.example.motro.targetselection.vision.TargetIdRecognizer;
import com.example.motro.targetselection.vision.TargetSelectionDecision;
import com.example.motro.targetselection.vision.TouchRoiExtractor;
import com.example.motro.targetselection.vision.TouchRoiResult;

import java.util.concurrent.Executor;

/**
 * 目标选择的<b>业务编排者</b>：把 Slice 0~8 的能力串成完整的一次
 * 「点击 → 目标框 → 目标 id」调用链，不承担任何 CV / OCR 算法。
 *
 * <h2>调用链（严格顺序，任一阶段失败即短路）</h2>
 * <pre>
 *   Touch (TargetSelectionRequest)
 *     → VideoGeometryProvider.currentGeometry()
 *     → VideoCoordinateMapper.map(view → video)
 *     → VideoFrameSource.capture()                      ── 帧从此归本类所有
 *     → TouchRoiExtractor.extract()
 *     → BlueMaskDetector.detect()
 *     → TargetBoxDetector.detect()
 *     → TargetHitTester.hitTest()
 *     → TargetCandidateSelector.select()                ── 得到 selectedBox
 *     → LabelRegionExtractor.extract(selectedBox)
 *     → TargetIdRecognizer.recognize()
 *     → TargetHitResult
 * </pre>
 *
 * <h2>短路规则（与状态一一对应）</h2>
 * <ul>
 *   <li>配置关闭 → {@link TargetSelectionStatus#CANCELLED}，不取帧；</li>
 *   <li>几何未知/退化 → {@link TargetSelectionStatus#GEOMETRY_UNAVAILABLE}，<b>不取帧</b>
 *       （对外的黑白边语义仍是 OUTSIDE_VIDEO 的分支由映射状态给出）；</li>
 *   <li>映射到黑边/View 之外 → {@link TargetSelectionStatus#OUTSIDE_VIDEO}，<b>不取帧</b>；</li>
 *   <li>取帧抛 {@link FrameCaptureException} → {@link TargetSelectionStatus#CAPTURE_FAILED}，
 *       不进入 CV；</li>
 *   <li>取帧返回空帧 → {@link TargetSelectionStatus#NO_FRAME}，不进入 CV；</li>
 *   <li>ROI / 掩码不可用（无蓝像素）→ 走向"没有候选"分支；</li>
 *   <li>没有框命中（含掩码为空、候选为空、点在容差外）→ {@link TargetSelectionStatus#MISS}，
 *       <b>不做 Label/OCR</b>；</li>
 *   <li>框命中 + 识别成功 → {@link TargetSelectionStatus#HIT} + id；</li>
 *   <li>框命中 + Label/OCR 任何失败 → {@link TargetSelectionStatus#HIT_ID_UNKNOWN} + selectedBox。
 *       <b>OCR 失败绝不把结果降级成 MISS</b>；</li>
 *   <li>管线内部未检查异常 → {@link TargetSelectionStatus#INTERNAL_ERROR}（不崩到 UI）。</li>
 * </ul>
 *
 * <h2>坐标一致性（三套坐标系，只有一个出处）</h2>
 * View 坐标 → 视频坐标由 {@link VideoCoordinateMapper} 唯一给出；视频坐标 → ROI 局部坐标
 * 由 {@link TouchRoiExtractor} 内部经同一个 mapper 完成。本类<b>不含任何换算公式</b>，
 * 只负责按顺序调用。对外返回的 {@link TargetHitResult#boundingBox} 恒为<b>视频坐标</b>。
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@link #select(TargetSelectionRequest)} 是<b>同步阻塞</b>的完整管线，禁止主线程调用；</li>
 *   <li>{@link #selectAsync(TargetSelectionRequest)} 把它投到注入的执行器上，结果回主线程 ——
 *       UI 线程只提交与接收，CV/OCR 永远在工作线程；</li>
 *   <li>本类不创建线程、不持有解码/渲染线程，因此不阻塞播放。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link #release()} 幂等；调用后 {@link #isReleased()} 为 true，所有新请求返回
 *       {@code CANCELLED}（不打到已销毁的对象上）；</li>
 *   <li>每次取到的帧在方法返回前<b>必被释放</b>（含异常路径），本类<b>不长期缓存整帧 Bitmap</b>；</li>
 *   <li>不拥有 FrameSource / 识别实现 —— 它们由 app 层创建与关闭。</li>
 * </ul>
 *
 * <p><b>所有权</b>：本类不拥有任何注入的协作者，因此 {@link #release()} 不调用它们的
 * {@code close()}（帧来源由 app 层统一管理）。它只保证"不再接收新请求、不再回调已销毁对象"。</p>
 */
public final class TargetSelectionController {

    // ------------------------------------------------------------------ 协作者（全部构造注入）

    private final VideoFrameSource frameSource;
    private final VideoGeometryProvider geometryProvider;
    private final TouchRoiExtractor roiExtractor;
    private final BlueMaskDetector maskDetector;
    private final TargetBoxDetector boxDetector;
    private final TargetHitTester hitTester;
    private final TargetCandidateSelector candidateSelector;
    private final LabelRegionExtractor labelExtractor;
    private final TargetIdRecognizer idRecognizer;
    private final TargetSelectionConfig config;

    /** 状态接收器（可为 null）：每次请求跑完后把 {@link TargetDebugState} 交给它。 */
    private final DebugStateSink debugSink;

    /** 异步执行器（可为 null）：{@link #selectAsync} 用它把管线投到工作线程。 */
    private final Executor workExecutor;

    /** 结果投递器（可为 null）：{@link #selectAsync} 用它把结果送回 UI 线程。 */
    private final Executor callbackExecutor;

    /** 释放标志；置位后拒绝一切新请求。 */
    private volatile boolean released;

    /**
     * 调试状态接收器。控制器<b>不直接持有 View</b>，而是把已经算好的
     * {@link TargetDebugState} 交给这个接口，由 app 层在主线程交给叠加层。
     *
     * <p>回调可能在<b>工作线程</b>上被调用（同步管线在哪跑，它就在哪跑），
     * 实现者若要更新 UI 必须自行切到主线程。控制器保证：{@link #release()} 之后
     * 不再调用它。</p>
     */
    public interface DebugStateSink {
        /**
         * @param state 本次请求的完整调试状态，非 null
         */
        void onDebugState(TargetDebugState state);
    }

    /**
     * 完整构造（Slice 9）：显式注入全部协作者。
     *
     * <p><b>为什么把 {@link TouchRoiExtractor} / {@link BlueMaskDetector} /
     * {@link TargetBoxDetector} / {@link TargetHitTester} / {@link TargetCandidateSelector} /
     * {@link LabelRegionExtractor} / {@link TargetIdRecognizer} 都做成注入</b>：
     * 它们各自是可替换的能力（尤其识别器将来会换成 TFLite/OCR SDK），
     * 而控制器只应依赖它们的契约。让控制器在内部 {@code new} 这些实现，
     * 会把"用哪个实现"这个决定焊死在控制器里，替换时就得改控制器 —— 违背本 Slice 的目标。</p>
     *
     * @param frameSource        取帧实现，非 null
     * @param geometryProvider   几何来源，非 null
     * @param roiExtractor       ROI 裁剪器，非 null
     * @param maskDetector       蓝色掩码检测器，非 null
     * @param boxDetector        目标框检测器，非 null
     * @param hitTester          命中过滤器，非 null
     * @param candidateSelector  多目标选择器，非 null
     * @param labelExtractor     标签区域提取器，非 null
     * @param idRecognizer       编号识别器，非 null
     * @param config             默认策略，非 null
     * @param debugSink          调试状态接收器，可为 null（不需要调试时）
     * @param workExecutor       异步工作执行器，可为 null（则 {@link #selectAsync} 不可用）
     * @param callbackExecutor   异步结果投递器，可为 null（则结果在工作线程回调）
     * @throws IllegalArgumentException 任一必需协作者为 null
     */
    public TargetSelectionController(VideoFrameSource frameSource,
                                     VideoGeometryProvider geometryProvider,
                                     TouchRoiExtractor roiExtractor,
                                     BlueMaskDetector maskDetector,
                                     TargetBoxDetector boxDetector,
                                     TargetHitTester hitTester,
                                     TargetCandidateSelector candidateSelector,
                                     LabelRegionExtractor labelExtractor,
                                     TargetIdRecognizer idRecognizer,
                                     TargetSelectionConfig config,
                                     DebugStateSink debugSink,
                                     Executor workExecutor,
                                     Executor callbackExecutor) {
        this.frameSource = require(frameSource, "frameSource");
        this.geometryProvider = require(geometryProvider, "geometryProvider");
        this.roiExtractor = require(roiExtractor, "roiExtractor");
        this.maskDetector = require(maskDetector, "maskDetector");
        this.boxDetector = require(boxDetector, "boxDetector");
        this.hitTester = require(hitTester, "hitTester");
        this.candidateSelector = require(candidateSelector, "candidateSelector");
        this.labelExtractor = require(labelExtractor, "labelExtractor");
        this.idRecognizer = require(idRecognizer, "idRecognizer");
        this.config = require(config, "config");
        this.debugSink = debugSink;
        this.workExecutor = workExecutor;
        this.callbackExecutor = callbackExecutor;
    }

    /**
     * 便捷构造：用配置从零装配默认协作者（全部是无状态、纯 JVM 的实现），
     * 不接调试接收器、不接异步执行器。适合离线验证脚本与单元测试。
     *
     * <p><b>注意</b>：它只装配<b>默认</b>实现。业务代码若需要替换识别器（将来接 OCR SDK），
     * 请用完整构造显式注入 —— 那样依赖关系在代码里一眼可见，不必去猜"这个默认值是什么"。</p>
     *
     * @param frameSource      取帧实现，非 null
     * @param geometryProvider 几何来源，非 null
     * @param config           默认策略，非 null
     * @throws IllegalArgumentException 任一参数为 null
     */
    public static TargetSelectionController withDefaults(VideoFrameSource frameSource,
                                                         VideoGeometryProvider geometryProvider,
                                                         TargetSelectionConfig config) {
        require(frameSource, "frameSource");
        require(geometryProvider, "geometryProvider");
        require(config, "config");
        return new TargetSelectionController(frameSource, geometryProvider,
                new TouchRoiExtractor(),
                new BlueMaskDetector(
                        com.example.motro.targetselection.vision.BlueColorThresholds.of(
                                config.blueHueMinDegrees, config.blueHueMaxDegrees,
                                config.minSaturation, config.minValue),
                        config.maskDenoiseMinNeighbors,
                        config.minBluePixelCount,
                        config.debugMaskEnabled),
                new TargetBoxDetector(config),
                new TargetHitTester(config),
                new TargetCandidateSelector(config),
                new LabelRegionExtractor(config),
                defaultRecognizer(config),
                config, null, null, null);
    }

    /**
     * 默认识别器的装配点。抽成方法是为了让"默认用哪个实现"集中在一处 ——
     * 将来换成别的实现时只改这里，且不引入对 {@code api} 包的额外依赖风险。
     */
    private static TargetIdRecognizer defaultRecognizer(TargetSelectionConfig config) {
        return new com.example.motro.targetselection.vision.GlyphTemplateTargetIdRecognizer(config);
    }

    // ------------------------------------------------------------------ 同步入口

    /**
     * 在请求的 View 坐标处完成一次完整的「点击 → 目标框 → 目标 id」。
     *
     * <p><b>同步阻塞</b>：内部有帧回读与逐像素分析，<b>禁止在主线程调用</b>。
     * 需要从 UI 触发时请用 {@link #selectAsync(TargetSelectionRequest)}。</p>
     *
     * @param request 请求，非 null
     * @return 非 null 的不可变结果；永不为 null，也<b>绝不抛识别相关异常</b>
     *         （管线内部未检查异常会被兜成 {@link TargetSelectionStatus#INTERNAL_ERROR}）
     * @throws IllegalArgumentException request 为 null
     */
    public TargetHitResult select(TargetSelectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final long startNs = System.nanoTime();

        if (released) {
            return TargetHitResult.cancelled("controller released");
        }
        if (!config.enabled) {
            return TargetHitResult.cancelled("target selection disabled by config");
        }

        // 管线内部任何未检查异常都必须变成稳定结果，不许崩到 UI。
        try {
            return runPipeline(request, startNs);
        } catch (RuntimeException e) {
            return TargetHitResult.internalError(elapsedMs(startNs), describe(e));
        }
    }

    /**
     * 完整管线。把各阶段拆成有小标题的段落，方便与类注释的调用链逐步对照。
     *
     * @param request 非 null，已通过入口校验
     * @param startNs 本次请求的起点（nanoTime），用于总耗时
     */
    private TargetHitResult runPipeline(TargetSelectionRequest request, long startNs) {
        final TargetSelectionConfig cfg = config;

        // ---- 1) 几何 + 坐标映射：唯一出处是 VideoCoordinateMapper，本类不写公式 ----
        final VideoGeometry geometry = geometryProvider.currentGeometry();
        final VideoPointMappingResult mapping =
                VideoCoordinateMapper.map(geometry, request.viewX, request.viewY);

        if (mapping.status == VideoPointMappingStatus.INVALID_GEOMETRY) {
            // 几何未知与"点在黑边"是两回事：前者无法判断，后者是明确在画面外。
            publishDebug(mapping, request, null, null, null, null, null);
            return TargetHitResult.geometryUnavailable(elapsedMs(startNs),
                    "video geometry unavailable or degenerate");
        }
        if (mapping.status != VideoPointMappingStatus.SUCCESS) {
            // 点在黑边/View 之外：不取帧、不做 CV —— 这是最省的一次短路。
            publishDebug(mapping, request, null, null, null, null, null);
            return TargetHitResult.outsideVideo(elapsedMs(startNs));
        }
        final float videoX = mapping.videoX;
        final float videoY = mapping.videoY;

        // ---- 2) 取帧：所有权从这一刻起归本类，finally 必须释放 ----
        final FrameCaptureRequest captureRequest = new FrameCaptureRequest(
                FrameCaptureRequest.NO_TARGET_PTS_US,
                cfg.captureTimeoutMs,
                cfg.maxFrameLongSidePx,
                cfg.maxFrameAgeNs);

        // frameSource.capture(...) 是 VideoFrameSource 的推荐入口：它把"抛异常"的失败
        // 统一桥接成 FrameCaptureResult 里的状态（见接口默认实现）。但它内部仍然可能
        // 抛出未检查异常（实现方的编程错误），所以这里也兜一层。
        final FrameCaptureResult captureResult;
        try {
            captureResult = frameSource.capture(captureRequest);
        } catch (RuntimeException e) {
            publishDebug(mapping, request, null, null, null, null, null);
            return TargetHitResult.captureFailed(elapsedMs(startNs), describe(e));
        }

        if (captureResult == null || !captureResult.isSuccess() || !captureResult.hasFrame()) {
            final TargetSelectionStatus mapped = mapCaptureFailure(captureResult);
            final String reason = captureResult == null
                    ? "frame source returned null result"
                    : captureResult.debugReason;
            if (captureResult != null) {
                captureResult.release();
            }
            publishDebug(mapping, request, null, null, null, null, null);
            if (mapped == TargetSelectionStatus.NO_FRAME) {
                return TargetHitResult.noFrame(elapsedMs(startNs),
                        reason == null ? "no usable frame" : reason);
            }
            return TargetHitResult.captureFailed(elapsedMs(startNs),
                    reason == null ? "frame capture failed" : reason);
        }

        final CapturedFrame frame = captureResult.takeFrame();
        final long captureLatencyMs = frame == null ? 0L : Math.max(0L, frame.captureDurationMs());

        // 帧的所有权已在 frame 上；captureResult 不再持有图像，release 是空操作。
        try {
            return runVisionPipeline(request, mapping, geometry, frame,
                    videoX, videoY, startNs, captureLatencyMs);
        } finally {
            // 硬约束：无论命中、MISS 还是抛异常，帧必须在返回前释放，否则每次点击泄一个回读缓冲。
            frame.release();
        }
    }

    /**
     * 帧到手之后的视觉链：ROI → 掩码 → 候选框 → 命中 → 选择 → 标签 → 编号。
     *
     * @param frame 非 null，所有权归调用方（{@code runPipeline} 的 finally 会释放）
     */
    private TargetHitResult runVisionPipeline(TargetSelectionRequest request,
                                              VideoPointMappingResult mapping,
                                              VideoGeometry geometry,
                                              CapturedFrame frame,
                                              float videoX, float videoY,
                                              long startNs, long captureLatencyMs) {
        final TargetSelectionConfig cfg = config;

        // ---- 3) ROI 裁剪（视频坐标 → ROI 局部坐标的唯一通道）----
        final long roiStartNs = System.nanoTime();
        final TouchRoiResult roi = roiExtractor.extract(
                frame, geometry, videoX, videoY, cfg.roiRadiusX, cfg.roiRadiusY);
        final long roiMs = elapsedSince(roiStartNs);
        if (!roi.isUsable()) {
            // ROI 都裁不出来（帧载荷失效/半径非法）：分析没跑成，按 MISS 处理而不是崩溃。
            // 注意这里不是"没点中"，但也不是取帧失败 —— 归到 MISS 与本模块既有语义一致
            // （旧控制器对 provider 返回 null 同样报 MISS），并保留 reason 供排查。
            publishDebug(mapping, request, null, null, null, null, null);
            return TargetHitResult.miss(videoX, videoY,
                    frame.ptsUs, elapsedMs(startNs))
                    .withReason(roi.debugReason == null ? "roi unavailable" : roi.debugReason);
        }

        // ---- 4) 蓝色掩码 ----
        final long maskStartNs = System.nanoTime();
        final BlueMaskResult mask = maskDetector.detect(roi);
        final long maskMs = elapsedSince(maskStartNs);

        // ---- 5) 候选框恢复 + 命中 + 选择（一段不可分割的几何分析）----
        final long boxStartNs = System.nanoTime();
        final TargetBoxDetectionResult detection = boxDetector.detect(mask);
        TargetBoxDetectionResult analyzed = detection;
        if (detection.status != com.example.motro.targetselection.vision.TargetBoxDetectionStatus.INVALID_INPUT
                && detection.status != com.example.motro.targetselection.vision.TargetBoxDetectionStatus.INVALID_MASK) {
            final TargetHitTestResult hit = hitTester.hitTest(videoX, videoY, detection.candidates);
            final TargetSelectionDecision decision =
                    candidateSelector.select(videoX, videoY, hit.hitCandidates);
            analyzed = detection.withHitTest(hit, decision, mask.processingMs);
        }
        final long boxDetectMs = elapsedSince(boxStartNs);

        // ---- 6) 没有框命中：到此为止，不做 Label / OCR ----
        if (!analyzed.isHit()) {
            publishDebug(mapping, request, mask, analyzed, null, null, null);
            return TargetHitResult.miss(videoX, videoY, frame.ptsUs, elapsedMs(startNs),
                            analyzed.candidateCount)
                    .withTimings(roiMs, maskMs, boxDetectMs, 0L);
        }

        // ---- 7) 框命中 → 标签区域提取（always 以 selectedBox 为输入）----
        final long labelStartNs = System.nanoTime();
        final LabelExtractionResult label = labelExtractor.extract(
                frame, geometry, analyzed.selectedBox.rectInVideo);
        final long labelMs = elapsedSince(labelStartNs);

        // ---- 8) 编号识别 ----
        final long idStartNs = System.nanoTime();
        final TargetIdRecognitionResult idResult = label.hasLabel()
                ? idRecognizer.recognize(label, cfg)
                : null;
        final long idMs = elapsedSince(idStartNs);

        publishDebug(mapping, request, mask, analyzed, label, idResult, null);

        return buildHitResult(analyzed, label, idResult,
                request, videoX, videoY, frame.ptsUs,
                frame.captureRequestNs, frame.captureCompletedNs,
                captureLatencyMs, roiMs, maskMs, boxDetectMs, labelMs, idMs,
                elapsedMs(startNs));
    }

    /**
     * 把「框命中 + 标签 + 识别」折叠成命中类结果。
     *
     * <p>核心不变量：<b>只要框命中了，结果就一定是 HIT 或 HIT_ID_UNKNOWN，绝不会是 MISS</b>。
     * 识别失败（{@code NO_ID}/{@code LOW_CONFIDENCE}/{@code RECOGNITION_FAILED}/{@code INVALID_LABEL}）
     * 或标签根本没找到，都只影响 targetId 是否为 null。</p>
     */
    private TargetHitResult buildHitResult(TargetBoxDetectionResult analyzed,
                                           LabelExtractionResult label,
                                           TargetIdRecognitionResult idResult,
                                           TargetSelectionRequest request,
                                           float videoX, float videoY,
                                           long framePtsUs, long captureRequestNs, long captureCompletedNs,
                                           long captureLatencyMs,
                                           long roiMs, long maskMs, long boxDetectMs,
                                           long labelMs, long idMs, long totalMs) {
        final boolean hasId = idResult != null && idResult.status.isSuccess() && idResult.targetId != null;
        final Float idConfidence = idResult == null ? null : idResult.confidence;

        final TargetHitResult result = TargetHitResult.of(
                hasId ? TargetSelectionStatus.HIT : TargetSelectionStatus.HIT_ID_UNKNOWN,
                hasId ? idResult.targetId : null,
                analyzed.selectedBox.rectInVideo,
                request.viewX, request.viewY,
                videoX, videoY,
                analyzed.selectedBox.confidence, idConfidence,
                framePtsUs, request.eventTimeNs,
                captureRequestNs, captureCompletedNs,
                captureLatencyMs, boxDetectMs, idMs,
                totalMs, request.requestId,
                describeIdFailure(label, idResult),
                analyzed.hitType, analyzed.candidateCount);
        return result.withTimings(roiMs, maskMs, boxDetectMs, idMs);
    }

    /** 识别失败时给出可读原因；成功时返回 null。 */
    private static String describeIdFailure(LabelExtractionResult label, TargetIdRecognitionResult idResult) {
        if (idResult != null && idResult.status.isSuccess()) {
            return null;
        }
        if (idResult != null) {
            return idResult.debugReason == null
                    ? "id " + idResult.status
                    : idResult.status + ": " + idResult.debugReason;
        }
        // idResult == null 只可能是因为没有标签
        return label == null || label.status == null
                ? "no label region"
                : "label " + label.status;
    }

    // ------------------------------------------------------------------ 异步入口

    /**
     * 异步完整的「点击 → 目标框 → 目标 id」：把管线投到工作执行器，结果经投递器回主线程。
     *
     * <p><b>UI 线程只做两件事</b>：提交请求、在回调里接收结果。CV / OCR 一定不在
     * UI / Decoder / Render 线程上跑，因此不阻塞播放。</p>
     *
     * <p>本 Slice <b>不实现 latest-wins</b>（连续点击取消/去重留到 Slice 11）：
     * 连续调用会并发跑多次管线，最后一次提交不一定最后一次返回。
     * 调用方可借 {@link TargetSelectionRequest#requestId} 自行识别"哪个结果回来了"。</p>
     *
     * @param request  请求，非 null
     * @param callback 结果回调，非 null；在执行器线程上被调用（投递器为 null 时即工作线程）
     * @throws IllegalArgumentException request / callback 为 null
     * @throws IllegalStateException    未注入工作执行器
     */
    public void selectAsync(TargetSelectionRequest request, SelectionCallback callback) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (workExecutor == null) {
            throw new IllegalStateException("no work executor injected; use select(...) or inject one");
        }
        if (released) {
            // 已销毁：不打到工作线程上，直接以 CANCELLED 回调（也绝不回调到已销毁的接收对象）。
            callback.onResult(TargetHitResult.cancelled("controller released"), request);
            return;
        }
        workExecutor.execute(() -> {
            final TargetHitResult result = select(request);
            // 完成时不回调已销毁对象
            if (released) {
                return;
            }
            if (callbackExecutor == null) {
                callback.onResult(result, request);
            } else {
                callbackExecutor.execute(() -> {
                    if (released) {
                        return;
                    }
                    callback.onResult(result, request);
                });
            }
        });
    }

    /** 异步结果回调。 */
    public interface SelectionCallback {
        /**
         * @param result  非 null 的结果
         * @param request 触发它的原始请求（便于用 requestId 关联）
         */
        void onResult(TargetHitResult result, TargetSelectionRequest request);
    }

    // ------------------------------------------------------------------ 触摸映射骨架（Slice 0 起保留）

    /**
     * <b>触摸接入骨架</b>：把 {@code ACTION_DOWN} 的坐标映射成视频像素坐标，不取帧、不识别。
     *
     * <p>与 {@link #select} 的分工：本方法只做「映射」这一步，是最便宜、也最该在按压瞬间
     * 立即执行的部分（即时反馈、log 坐标）；真正的取帧 + CV 是否/何时触发由上层决定。</p>
     *
     * <p><b>线程约定</b>：<b>主线程可安全调用</b>。只读取一次几何快照并做纯几何运算。</p>
     *
     * <p>本方法不受 {@link TargetSelectionConfig#enabled} 影响 —— 坐标映射无副作用。</p>
     *
     * @param request 触摸请求，非 null
     * @return 映射结果，永不为 null
     * @throws IllegalArgumentException request 为 null
     */
    public VideoPointMappingResult onTouchDown(TouchSelectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return VideoCoordinateMapper.map(geometryProvider.currentGeometry(),
                request.viewX, request.viewY);
    }

    /**
     * {@link #onTouchDown(TouchSelectionRequest)} 的便捷重载。
     *
     * @param eventTimeNs 按下时刻，未知传 {@link TouchSelectionRequest#NO_EVENT_TIME_NS}
     */
    public VideoPointMappingResult onTouchDown(float viewX, float viewY, long eventTimeNs) {
        return onTouchDown(TouchSelectionRequest.down(viewX, viewY, eventTimeNs));
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 释放控制器：置位后拒绝一切新请求（返回 {@code CANCELLED}），且异步管线完成时
     * 不再回调。幂等。
     *
     * <p><b>不关闭注入的协作者</b>：帧来源与识别实现由 app 层拥有并统一关闭 ——
     * 控制器只是"使用者"。若由它代为关闭，会出现两个上层共享同一帧来源时
     * 一方退出就把另一方也废掉的问题。</p>
     */
    public void release() {
        released = true;
    }

    /** {@link #release()} 的别名，便于放进 try-with-resources 风格的管理代码。 */
    public void close() {
        release();
    }

    public boolean isReleased() {
        return released;
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 内部工具

    private void publishDebug(VideoPointMappingResult mapping,
                              TargetSelectionRequest request,
                              BlueMaskResult mask,
                              TargetBoxDetectionResult box,
                              LabelExtractionResult label,
                              TargetIdRecognitionResult idResult,
                              String extraReason) {
        final DebugStateSink sink = debugSink;
        if (sink == null || released) {
            return;
        }
        try {
            TargetDebugState.Builder builder = TargetDebugState.builder(mapping, request.eventTimeNs);
            if (mask != null) {
                builder = builder.blueMask(mask);
            }
            if (box != null) {
                builder = builder.boxDetection(box);
            }
            if (label != null) {
                builder = builder.labelExtraction(label);
            }
            if (idResult != null) {
                builder = builder.targetIdRecognition(idResult);
            }
            sink.onDebugState(builder.build());
        } catch (RuntimeException ignored) {
            // 调试回调是旁路：它自己出错不该把业务结果带崩，静默吞掉。
        }
    }

    private static TargetSelectionStatus mapCaptureFailure(FrameCaptureResult result) {
        if (result == null) {
            return TargetSelectionStatus.CAPTURE_FAILED;
        }
        switch (result.status) {
            case NO_FRAME:
                return TargetSelectionStatus.NO_FRAME;
            case CANCELLED:
                return TargetSelectionStatus.CANCELLED;
            default:
                return TargetSelectionStatus.CAPTURE_FAILED;
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static long elapsedSince(long startNs) {
        final long delta = System.nanoTime() - startNs;
        return delta <= 0L ? 0L : delta / 1_000_000L;
    }

    private static long elapsedMs(long startNs) {
        return elapsedSince(startNs);
    }

    private static String describe(Throwable e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
