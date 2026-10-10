package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoGeometry;

/**
 * 「触摸点 → ROI → 蓝色掩码 → 目标框候选 → 命中过滤 → 多目标选择」的<b>单入口</b>，
 * 把 Slice 4 / 5 / 6 的五段串成一次调用。播放器无关、纯 JVM。
 *
 * <p><b>为什么要有这个类而不是让调用方自己拼</b>：五段的接口各不相同
 * （请求 → 掩码 → 候选 → 命中 → 选择），拼装顺序与"哪一段失败该报什么状态"的规则
 * 一旦散落到 app 层，就会出现多处实现、其中一处忘了做容差或忘了传几何。
 * 集中在这里以后，{@code FrameCaptureProbeActivity}、控制器适配器、离线验证脚本
 * 走的是同一条路径 —— 于是"真机看到的"和"离线验证的"必然一致。</p>
 *
 * <p><b>调用时机（性能要求）</b>：</p>
 * <ul>
 *   <li>只在<b>用户点击</b>时执行一次，绝不每帧调用；</li>
 *   <li>必须跑在<b>工作线程</b>上（内部有帧像素回读，放主线程会掉帧）；</li>
 *   <li>不要放解码/渲染线程；</li>
 *   <li>只分析 RoI 内的像素，不扫描整帧。</li>
 * </ul>
 *
 * <p><b>触屏坐标来源</b>：用的是 {@link TouchRoiRequest#videoX}/{@code videoY}
 * ——「同一次点击、同一个视频坐标系」。不要拿 {@code BlueMaskResult.touchLocalX}
 * 反推视频点（那个值已经取整并夹进过 ROI，会引入半个像素的偏差），也不要拿 View 坐标充数。</p>
 *
 * <p><b>所有权</b>：不拥有传入的帧（调用方负责 {@code release()}），
 * 不长期持有任何 Bitmap 或大数组。</p>
 *
 * <p>线程约定：内部三个组件各自无状态/自带同步，可跨线程调用；
 * 但单次调用应完整跑在一个工作线程上。</p>
 */
public final class TargetBoxAnalyzer {

    private final TargetSelectionConfig config;
    private final BlueMaskAnalyzer maskAnalyzer;
    private final TargetBoxDetector detector;
    private final TargetHitTester hitTester;
    private final TargetCandidateSelector candidateSelector;

    /**
     * @param config 默认策略，非 null；ROI 半径、颜色阈值、方框尺寸/覆盖率/容差、
     *               以及多目标选择的权重与 tie-break 开关都从它读取
     * @throws IllegalArgumentException config 为 null
     */
    public TargetBoxAnalyzer(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.maskAnalyzer = new BlueMaskAnalyzer(config);
        this.detector = new TargetBoxDetector(config);
        this.hitTester = new TargetHitTester(config);
        this.candidateSelector = new TargetCandidateSelector(config);
    }

    /**
     * 端到端分析一次点击。
     *
     * @param request 请求，非 null
     * @return 非 null 的结果；{@code totalMs} 是本方法覆盖的全部耗时
     * @throws IllegalArgumentException request 为 null
     */
    public TargetBoxDetectionResult analyze(TouchRoiRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final BlueMaskResult maskResult = maskAnalyzer.analyze(request);
        return analyze(maskResult, request.videoX, request.videoY);
    }

    /**
     * 用<b>已经算好的掩码</b>继续走「候选 → 命中」两段。
     *
     * <p>存在的理由是复用而不是懒省事：调试探针既要在叠加层上显示蓝像素统计（Slice 4），
     * 又要显示候选框与命中（Slice 5）。若它直接调 {@link #analyze(TouchRoiRequest)}，
     * 就只拿得到 {@link TargetBoxDetectionResult}，掩码统计全丢了；
     * 若它自己再算一遍掩码，同一次点击的像素回读与 HSV 遍历就白跑两遍。
     * 有了这个重载，两段信息来自<b>同一次</b>掩码计算。</p>
     *
     * <p>{@code totalMs} 仍然表示"整条链"的耗时 —— 掩码那一段取
     * {@link BlueMaskResult#processingMs}（它是在别处测的），加上本方法内实测的后两段，
     * 于是无论从哪个重载进来，{@code totalMs} 的语义都一致。</p>
     *
     * @param maskResult 已完成 ROI 裁剪与颜色分析的掩码，非 null
     * @param videoX     触摸点视频坐标 x（与掩码同一次点击、同一坐标系）
     * @param videoY     触摸点视频坐标 y
     * @return 非 null 的结果
     * @throws IllegalArgumentException maskResult 为 null
     */
    public TargetBoxDetectionResult analyze(BlueMaskResult maskResult, float videoX, float videoY) {
        if (maskResult == null) {
            throw new IllegalArgumentException("maskResult must not be null");
        }
        final long startNs = System.nanoTime();

        final TargetBoxDetectionResult detection = detector.detect(maskResult);
        // 掩码/入参本身不可用时，最后一段没有意义，也不该给出"耗时"这种像是跑完了的信息
        if (detection.status == TargetBoxDetectionStatus.INVALID_INPUT
                || detection.status == TargetBoxDetectionStatus.INVALID_MASK) {
            return detection;
        }
        // 没有候选时也照样走一遍命中测试：它本身就会给出 MISS，
        // 这样"无框"与"有框但没点到"这两条路径的出口状态完全一致，上层不必写两个分支。
        final TargetHitTestResult hit = hitTester.hitTest(videoX, videoY, detection.candidates);
        // 命中过滤（哪些框被点到）与多目标选择（其中哪个该被选中）是两件事，分两步走。
        // 选择器再复核一次命中分级，因此即使 hit 里混进了没命中的框也不会被选中。
        final TargetSelectionDecision decision = candidateSelector.select(videoX, videoY, hit.hitCandidates);
        return detection.withHitTest(hit, decision, maskResult.processingMs + elapsedMs(startNs));
    }

    /** {@link #analyze(TouchRoiRequest)} 的便捷重载。 */
    public TargetBoxDetectionResult analyze(CapturedFrame frame, VideoGeometry geometry,
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
