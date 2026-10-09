package com.example.motro.targetselection.api;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.vision.TargetCandidate;
import com.example.motro.targetselection.vision.TargetProvider;

/**
 * 目标选择的协调者：把「取帧 → 坐标映射 → 目标识别」串成一次同步调用，返回
 * {@link TargetHitResult}。本类<b>不含任何识别算法、不接触任何播放器类型</b>，
 * 三个协作者都由外部（app 层）注入。
 *
 * <p><b>坐标换算的唯一出处</b>：本类不自己算坐标，一律调用
 * {@link VideoCoordinateMapper}；View↔视频 的任何换算式都不许出现在别处，
 * 免得旋转/镜像顺序出现第二份实现（写错的那份）。</p>
 *
 * <p><b>两个入口</b>：{@link #onTouchDown(TouchSelectionRequest)}（仅映射，主线程安全）
 * 与 {@link #selectTargetAtViewPoint(float, float)}（完整管线，同步阻塞，禁止主线程）。</p>
 *
 * <p>执行管线（与状态一一对应，便于定位“是哪一层坏了”）：</p>
 * <pre>
 *   [配置]            enabled=false                        → CANCELLED
 *   [Geometry]        VideoCoordinateMapper 返回
 *                       INVALID_GEOMETRY                    → GEOMETRY_UNAVAILABLE
 *                       OUTSIDE_VIDEO（黑边 / View 之外）    → OUTSIDE_VIDEO
 *   [FrameSource]     captureFrame 抛 FrameCaptureException → CAPTURE_FAILED
 *                     返回 null / 无图像载荷                 → NO_FRAME
 *   [TargetProvider]  hitTest 返回 null / 置信度不足        → MISS
 *                     有框有 id                             → HIT
 *                     有框无 id（且允许汇报）                → HIT_ID_UNKNOWN
 * </pre>
 *
 * <p><b>顺序说明</b>：几何校验被前置于取帧 —— 点在黑边上时不必浪费一次帧回读，
 * 这是对“取帧→映射”管线顺序的有意调整，语义不变。</p>
 *
 * <p><b>线程约定（重要）</b>：{@link #selectTargetAtViewPoint} 是<b>同步阻塞</b>调用，
 * 内部会做帧回读与识别，<b>禁止在主线程调用</b>。调用方负责切到工作线程，
 * 并把结果切回主线程更新 UI（本类返回不可变结果，可安全跨线程传递）。</p>
 *
 * <p><b>所有权</b>：本类不拥有任何资源 —— 帧来源、几何来源、识别实现均由调用方创建与关闭；
 * 每次调用取到的 {@link CapturedFrame} 由本类接管并保证在返回前释放，调用方无需处理。</p>
 *
 * <p><b>错误传播</b>：可预期的取帧失败应被实现方包装为 {@link FrameCaptureException}；
 * 未检查异常视为实现方的编程错误，本类不吞、直接向上传播（避免把崩溃伪装成 MISS）。</p>
 */
public final class TargetSelectionController {

    private final VideoFrameSource frameSource;
    private final VideoGeometryProvider geometryProvider;
    private final TargetProvider targetProvider;
    private final TargetSelectionConfig config;

    /**
     * @param frameSource      取帧实现，非 null
     * @param geometryProvider 几何来源，非 null
     * @param targetProvider   识别实现，非 null
     * @param config           默认策略，非 null
     * @throws IllegalArgumentException 任一参数为 null
     */
    public TargetSelectionController(VideoFrameSource frameSource,
                                     VideoGeometryProvider geometryProvider,
                                     TargetProvider targetProvider,
                                     TargetSelectionConfig config) {
        if (frameSource == null) {
            throw new IllegalArgumentException("frameSource must not be null");
        }
        if (geometryProvider == null) {
            throw new IllegalArgumentException("geometryProvider must not be null");
        }
        if (targetProvider == null) {
            throw new IllegalArgumentException("targetProvider must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.frameSource = frameSource;
        this.geometryProvider = geometryProvider;
        this.targetProvider = targetProvider;
        this.config = config;
    }

    /**
     * 在 View 坐标 (viewX, viewY) 处选择目标。
     *
     * @param viewX 相对承载 View 左边缘的像素 x
     * @param viewY 相对承载 View 上边缘的像素 y
     * @return 不可变结果，永不为 null；具体状态见类注释中的管线图
     */
    public TargetHitResult selectTargetAtViewPoint(float viewX, float viewY) {
        final long startNs = System.nanoTime();

        if (!config.enabled) {
            return TargetHitResult.cancelled("target selection disabled by config");
        }

        // 1) 坐标映射：统一走 VideoCoordinateMapper（黑边点击不取帧，也不消耗识别）
        final VideoPointMappingResult mapping = VideoCoordinateMapper.map(
                geometryProvider.currentGeometry(), viewX, viewY);
        if (mapping.status == VideoPointMappingStatus.INVALID_GEOMETRY) {
            return TargetHitResult.geometryUnavailable(elapsedMs(startNs),
                    "video geometry unavailable or degenerate");
        }
        if (mapping.status != VideoPointMappingStatus.SUCCESS) {
            return TargetHitResult.outsideVideo(elapsedMs(startNs));
        }
        final float videoX = mapping.videoX;
        final float videoY = mapping.videoY;

        // 2) 取帧：配置 → 单次请求
        final FrameCaptureRequest request = new FrameCaptureRequest(
                FrameCaptureRequest.NO_TARGET_PTS_US,
                config.captureTimeoutMs,
                config.maxFrameLongSidePx,
                config.maxFrameAgeNs);

        final CapturedFrame frame;
        try {
            frame = frameSource.captureFrame(request);
        } catch (FrameCaptureException e) {
            return TargetHitResult.captureFailed(elapsedMs(startNs), describe(e));
        }

        if (frame == null || !frame.hasImage()) {
            if (frame != null) {
                frame.release();
            }
            return TargetHitResult.noFrame(elapsedMs(startNs), "frame source returned no usable frame");
        }

        // 3) 识别：无论如何都释放帧，避免回读缓冲区泄漏
        try {
            final TargetCandidate candidate = targetProvider.hitTest(frame, videoX, videoY);
            final long processingMs = elapsedMs(startNs);

            if (candidate == null || candidate.confidence < config.minConfidence) {
                return TargetHitResult.miss(videoX, videoY, frame.ptsUs, processingMs);
            }
            if (candidate.hasId()) {
                return TargetHitResult.hit(candidate.targetId, candidate.boundingBox, candidate.confidence,
                        videoX, videoY, frame.ptsUs, processingMs);
            }
            if (!config.reportUnknownTargetId) {
                // 上层只认 id：半成品结果降级为 MISS，而不是让它自己去猜
                return TargetHitResult.miss(videoX, videoY, frame.ptsUs, processingMs);
            }
            return TargetHitResult.hitIdUnknown(candidate.boundingBox, candidate.confidence,
                    videoX, videoY, frame.ptsUs, processingMs);
        } finally {
            frame.release();
        }
    }

    /**
     * <b>触摸接入骨架</b>：把 {@code ACTION_DOWN} 的坐标映射成视频像素坐标，不取帧、不识别。
     *
     * <p>与 {@link #selectTargetAtViewPoint} 的分工：本方法只做「映射」这一步，是整个管线里
     * 最便宜、也最该在按压瞬间立即执行的部分（用于即时反馈、log 坐标、决定要不要继续分析）；
     * 真正的取帧 + 识别是否/何时触发，留给上层按策略决定（连续点击策略见后续 Slice）。</p>
     *
     * <p><b>线程约定</b>：<b>主线程可安全调用</b>。只读取一次几何快照并做纯几何运算，
     * 不做 IO、不取帧、不分配大对象。</p>
     *
     * <p>本方法不受 {@link TargetSelectionConfig#enabled} 影响 —— 坐标映射无副作用，
     * 关闭目标选择时仍可用于调试与埋点。</p>
     *
     * @param request 触摸请求，非 null
     * @return 映射结果（状态见 {@link VideoPointMappingStatus}），永不为 null
     * @throws IllegalArgumentException request 为 null
     */
    public VideoPointMappingResult onTouchDown(TouchSelectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return VideoCoordinateMapper.map(geometryProvider.currentGeometry(), request.viewX, request.viewY);
    }

    /**
     * {@link #onTouchDown(TouchSelectionRequest)} 的便捷重载。
     *
     * @param eventTimeNs 按下时刻（{@link System#nanoTime()} 基准），未知传
     *                    {@link TouchSelectionRequest#NO_EVENT_TIME_NS}
     */
    public VideoPointMappingResult onTouchDown(float viewX, float viewY, long eventTimeNs) {
        return onTouchDown(TouchSelectionRequest.down(viewX, viewY, eventTimeNs));
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return config;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
