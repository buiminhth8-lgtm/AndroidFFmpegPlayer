package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;

/**
 * 把 Slice 5 的像素法检测接到既有 {@link TargetProvider} 契约上的适配器 ——
 * 于是 {@code TargetSelectionController} 那条 Slice 0 就定好的管线
 * （取帧 → 映射 → 识别 → 命中状态）无需任何改动就能真正跑出结果。
 *
 * <p><b>为什么需要注入 {@link VideoGeometryProvider}</b>：控制器把
 * {@code videoX/videoY}（<b>视频</b>坐标）交给 provider，但像素只能按
 * {@link CapturedFrame} 的<b>帧</b>坐标去读；当回读帧被
 * {@code maxFrameLongSidePx} 缩小、或 View 有黑边时，两者并不相等，
 * 必须借几何换算（见 {@link TouchRoiExtractor}）。控制器不把几何传给 provider，
 * 所以这里由 app 层注入<b>同一个</b>几何来源 —— 与控制器读到的是同一份快照，
 * 不会出现两套坐标。</p>
 *
 * <p><b>状态映射</b>：命中 → {@link TargetCandidate#withoutId}
 * （本 Slice 没有 {@code id:xxxx} 识别，所以只给框不给 id，
 * 控制器会据此产出 {@code HIT_ID_UNKNOWN}）；未命中 → {@code null}（控制器报 {@code MISS}）。</p>
 *
 * <p><b>关闭语义</b>：本类不持有需要释放的资源（没有 native 句柄、没有模型文件），
 * 因此 {@link #close()} 用默认空实现即可。</p>
 *
 * <p>线程约定：{@link #hitTest} 在控制器的工作线程上同步执行，可阻塞；
 * 内部组件自带同步，本类本身无状态。</p>
 */
public final class BlueBoxTargetProvider implements TargetProvider {

    private final VideoGeometryProvider geometryProvider;
    private final TargetBoxAnalyzer analyzer;

    /**
     * @param geometryProvider 几何来源，非 null；由 app 层提供，须与控制器使用同一个实例
     * @param config           默认策略，非 null
     * @throws IllegalArgumentException 任一参数为 null
     */
    public BlueBoxTargetProvider(VideoGeometryProvider geometryProvider, TargetSelectionConfig config) {
        if (geometryProvider == null) {
            throw new IllegalArgumentException("geometryProvider must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.geometryProvider = geometryProvider;
        this.analyzer = new TargetBoxAnalyzer(config);
    }

    @Override
    public TargetCandidate hitTest(CapturedFrame frame, float videoX, float videoY) {
        final VideoGeometry geometry = geometryProvider.currentGeometry();
        final TargetBoxDetectionResult result = analyzer.analyze(frame, geometry, videoX, videoY);
        if (!result.isHit()) {
            return null;
        }
        final DetectedTargetBox box = result.selectedBox;
        // 顺带把命中分级（EXACT/TOLERANCE）与候选总数带上去：它们是 Slice 6 之后
        // 排查"为什么选中了这个框 / 为什么没点中"的主要线索，而 provider 是唯一
        // 能看到完整分析结果的地方。控制器会原样透传，不做任何判定。
        return TargetCandidate.withoutId(box.rectInVideo, box.confidence,
                result.hitType, result.candidateCount);
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return analyzer.config();
    }
}
