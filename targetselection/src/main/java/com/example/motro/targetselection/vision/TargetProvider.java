package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.frame.CapturedFrame;

/**
 * 目标信息来源的抽象。本模块**不含任何识别算法**，只定义契约；具体实现由后续 Slice / app 层提供：
 *
 * <ul>
 *   <li><b>Pixel/CV 实现</b>：在帧上找蓝框，对命中框做 OCR 得到 id（后续引入 OpenCV/OCR 依赖时，
 *       依赖加在实现所在模块，不进本模块）。</li>
 *   <li><b>Metadata 实现</b>：从 SEI / UDP 报文 / JSON 元数据里直接查目标框与 id，不做像素分析。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：{@link #hitTest} 在调用方线程（点击后的工作线程）执行，可以阻塞；
 * 控制器不保证同一帧不会被重复分析。实现需自行保证线程安全。</p>
 *
 * <p><b>所有权</b>：传入的 {@link CapturedFrame} 在调用期间由控制器持有并保证有效，
 * 实现**不得**保存该引用供后续使用，也不得 {@code release()} 它（所有权仍在控制器）。
 * Provider 自身由 app 层创建与关闭，控制器不调用 {@link #close()}。</p>
 */
public interface TargetProvider extends AutoCloseable {

    /**
     * 在视频像素坐标处做命中测试。
     *
     * @param frame  待分析帧，保证非 null 且 {@code hasImage()} 为 true
     * @param videoX 视频像素 x（已由控制器完成 view→video 映射），范围 [0, videoWidth]
     * @param videoY 视频像素 y，范围 [0, videoHeight]
     * @return 命中点上的目标；该点没有目标时返回 {@code null}（控制器报 {@code MISS}）。
     *         框命中但 id 未知时返回 {@link TargetCandidate#withoutId}
     */
    TargetCandidate hitTest(CapturedFrame frame, float videoX, float videoY);

    /**
     * 释放实现内部持有的资源（模型、native 句柄等）。
     * 默认空实现，便于无状态实现直接使用。关闭后不应再调用 {@link #hitTest}。
     */
    @Override
    default void close() {
    }
}
