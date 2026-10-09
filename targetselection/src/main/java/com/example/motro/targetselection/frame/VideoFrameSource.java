package com.example.motro.targetselection.frame;

/**
 * 点击时需要分析的画面的来源。是播放器与 targetselection 之间唯一的取帧接口。
 *
 * <p><b>边界约束</b>：本接口不得暴露 FFmpeg / AVFrame / MediaCodec / Surface / Media3 /
 * VLC 等任何具体类型；实现方负责把自家帧数据适配成 {@link CapturedFrame}。
 * 这样 FFmpegPlayer、Media3、VLC、GStreamer、Camera2 都可以各写一个实现直接复用本模块。</p>
 *
 * <p><b>本模块自带的通用实现</b>（只要播放器把画面输出到标准 View 就能直接复用，
 * 不需要写任何适配代码）：</p>
 * <ul>
 *   <li>{@link SurfaceViewFrameSource} —— 面向 {@code SurfaceView}，走 PixelCopy 异步回读；
 *       FFmpegPlayer / Media3 / VLC / GStreamer 输出到 SurfaceView 时都能用同一条路径。</li>
 *   <li>{@link TextureViewFrameSource} —— 面向 {@code TextureView}，取 View 位图。</li>
 * </ul>
 *
 * <p>共享的实现骨架（异步投递、恰好一次回调、取消、关闭语义）在 {@link AbstractFrameSource}。</p>
 *
 * <p><b>线程约定</b>：{@link #captureFrame} 在**调用方线程**（点击后的工作线程，非主线程）执行，
 * 实现方可以阻塞，但不得超过 {@link FrameCaptureRequest#timeoutMs}。
 * 同一实例可能被连续多次调用，实现需自行保证线程安全。</p>
 *
 * <p><b>所有权</b>：返回的 {@link CapturedFrame} 所有权移交给调用方（控制器），
 * 由控制器负责 {@code release()}。本接口自身由 app 层创建与关闭，控制器不调用 {@link #close()}。</p>
 */
public interface VideoFrameSource extends AutoCloseable {

    /**
     * 获取用于分析的一帧。
     *
     * @param request 取帧参数，非 null
     * @return 采集到的帧；**当前没有可用画面**时返回 {@code null} 或
     *         {@link CapturedFrame#empty}（控制器统一报 {@code NO_FRAME}）
     * @throws FrameCaptureException 采集确实失败（超时、解码/渲染错误等）→ 控制器报 {@code CAPTURE_FAILED}
     */
    CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException;

    /**
     * 获取用于分析的一帧，并把失败原因作为**结果**返回而不是异常（推荐入口）。
     *
     * <p>与 {@link #captureFrame} 的分工：本方法能区分“还没出图”“View 没就绪”
     * “尺寸非法”“被取消”等时序差异，便于上层做不同的重试/提示策略；
     * 异常版本只能表达“失败”。默认实现把 {@link #captureFrame} 的结果按以下规则桥接：</p>
     * <ul>
     *   <li>返回了有图像的帧 → {@code SUCCESS}，帧的所有权移到结果对象里；</li>
     *   <li>返回 null 或无图像 → {@code NO_FRAME}；</li>
     *   <li>抛出 {@link FrameCaptureException} → {@code CAPTURE_FAILED}，异常消息进 {@code debugReason}。</li>
     * </ul>
     *
     * <p>实现方若需要更精确的状态（推荐），直接覆写本方法；
     * 继承 {@link AbstractFrameSource} 时会自动获得精确状态与反向桥接。</p>
     *
     * @param request 取帧参数，非 null
     * @return 非 null 的结果；调用方必须 {@link FrameCaptureResult#takeFrame()} 或
     *         {@link FrameCaptureResult#release()}
     * @throws IllegalArgumentException request 为 null
     */
    default FrameCaptureResult capture(FrameCaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final CapturedFrame frame;
        try {
            frame = captureFrame(request);
        } catch (FrameCaptureException e) {
            return FrameCaptureResult.failure(FrameCaptureStatus.CAPTURE_FAILED, describe(e));
        }
        if (frame == null || !frame.hasImage()) {
            if (frame != null) {
                frame.release();
            }
            return FrameCaptureResult.noFrame("frame source returned no usable frame");
        }
        return FrameCaptureResult.success(frame);
    }

    /**
     * 释放实现内部持有的资源（回读缓冲区、线程等）。
     * 默认空实现，便于无状态实现直接使用。关闭后不应再调用 {@link #captureFrame}。
     */
    @Override
    default void close() {
    }

    /** 异常的人可读描述，用于填充 {@code debugReason}。 */
    static String describe(Throwable error) {
        if (error == null) {
            return "unknown capture failure";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
