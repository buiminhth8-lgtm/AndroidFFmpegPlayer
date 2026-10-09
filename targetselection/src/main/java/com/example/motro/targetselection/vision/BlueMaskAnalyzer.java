package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoGeometry;

/**
 * 「触摸点 → 局部 ROI → 稳健蓝色掩码」的<b>单入口</b>，把
 * {@link TouchRoiExtractor} 与 {@link BlueMaskDetector} 串起来，并按
 * {@link TargetSelectionConfig} 注入半径与颜色阈值。
 *
 * <p><b>调用时机（性能要求的落点）</b>：</p>
 * <ul>
 *   <li><b>只在用户点击时</b>调用一次，绝不每帧调用；</li>
 *   <li>必须跑在<b>工作线程</b>上 —— 内部会做一次帧像素回读与一次 ROI 级像素扫描，
 *       放主线程会掉帧；</li>
 *   <li>不要放解码/渲染线程：那两条线程的调度优先级与帧节奏不该被分析打断。</li>
 * </ul>
 * <p>因为只分析点击附近几百像素见方的区域，单次耗时目标是几毫秒量级
 * （见 {@link BlueMaskResult#processingMs}），对 decode/render FPS 无可测影响。</p>
 *
 * <p><b>不做的事（本 Slice 的边界）</b>：不重建矩形、不做命中测试、不排序多目标、
 * 不做 Label ROI 与 OCR。本类只回答「点击附近有哪些像素是蓝的」。</p>
 *
 * <p><b>所有权</b>：不拥有传入的帧（调用方负责 {@code release()}），
 * 不长期持有任何 Bitmap —— 结果里只有紧凑位掩码。</p>
 *
 * <p>线程约定：本类无状态（配置不可变，内部检测器自带同步），可跨线程调用；
 * 但单次调用应完整跑在一个工作线程上。</p>
 */
public final class BlueMaskAnalyzer {

    private final TargetSelectionConfig config;
    private final TouchRoiExtractor extractor;
    private final BlueMaskDetector detector;

    /**
     * @param config 默认策略，非 null；ROI 半径、蓝色阈值、去噪与 debug 开关都从它读取
     * @throws IllegalArgumentException config 为 null
     */
    public BlueMaskAnalyzer(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.extractor = new TouchRoiExtractor();
        // 阈值全部来自配置：调参只改 TargetSelectionConfig，不在算法里留实验值
        this.detector = new BlueMaskDetector(
                BlueColorThresholds.of(config.blueHueMinDegrees, config.blueHueMaxDegrees,
                        config.minSaturation, config.minValue),
                config.maskDenoiseMinNeighbors,
                config.minBluePixelCount,
                config.debugMaskEnabled);
    }

    /**
     * 端到端分析一次点击。
     *
     * @param request 请求，非 null
     * @return 非 null 的结果；{@link BlueMaskResult#processingMs} 是
     *         <b>ROI 裁剪 + 颜色分析</b>的总耗时
     * @throws IllegalArgumentException request 为 null
     */
    public BlueMaskResult analyze(TouchRoiRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final long startNs = System.nanoTime();
        final TouchRoiResult roi = extractor.extract(request.frame, request.geometry,
                request.videoX, request.videoY, config.roiRadiusX, config.roiRadiusY);
        final BlueMaskResult result = detector.detect(roi);
        return result.withProcessingMs(elapsedMs(startNs));
    }

    /** {@link #analyze(TouchRoiRequest)} 的便捷重载。 */
    public BlueMaskResult analyze(CapturedFrame frame, VideoGeometry geometry,
                                  float videoX, float videoY) {
        return analyze(TouchRoiRequest.of(frame, geometry, videoX, videoY));
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return config;
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }
}
