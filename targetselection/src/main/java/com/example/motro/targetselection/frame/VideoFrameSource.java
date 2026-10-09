package com.example.motro.targetselection.frame;

/**
 * 点击时需要分析的画面的来源。由 app 层实现，是播放器与 targetselection 之间唯一的取帧接口。
 *
 * <p><b>边界约束</b>：本接口不得暴露 FFmpeg / AVFrame / MediaCodec / Surface / Media3 /
 * VLC 等任何具体类型；实现方负责把自家帧数据适配成 {@link CapturedFrame}。
 * 这样 FFmpegPlayer、Media3、VLC、GStreamer、Camera2 都可以各写一个实现直接复用本模块。</p>
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
     * 释放实现内部持有的资源（回读缓冲区、线程等）。
     * 默认空实现，便于无状态实现直接使用。关闭后不应再调用 {@link #captureFrame}。
     */
    @Override
    default void close() {
    }
}
