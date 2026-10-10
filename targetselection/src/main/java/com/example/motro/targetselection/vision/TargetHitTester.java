package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「触摸点 vs 候选框」的命中测试。<b>独立于检测</b>：它只吃
 * {@link DetectedTargetBox} 列表与一个视频坐标点，不知道掩码、ROI、颜色阈值的存在，
 * 因此可以在纯 JVM 里穷举验证，也不会因为检测算法改动而失效。
 *
 * <h2>容差</h2>
 * 判定用的是把框向外扩 {@code touchTolerancePx} 之后的矩形（见
 * {@link TargetBoundingBox#inflated}），但<b>返回的框永远是原始框</b>。
 * 这么设计的原因很实际：手指点击有 ±10px 级别的抖动，而目标框可能只有几十像素高，
 * 没有容差会出现"明明点在框上却 MISS"；但把扩过的框返回给上层，
 * 又会让绘制、对齐、后续 Label ROI 全部外扩一圈 —— 这是最难被发现的一类错误，
 * 因为画面"看起来还行"。所以容差<b>只进不出</b>。
 *
 * <h2>多候选选择顺序（本 Slice 有意保持简单、确定）</h2>
 * <ol>
 *   <li><b>严格包含</b>触摸点的优先（不看容差的那一层判定）—— 点真正落在框内，
 *       比"靠容差勉强够到"可信得多；</li>
 *   <li>触摸点到框<b>中心</b>距离更小者优先；</li>
 *   <li><b>面积更小</b>者优先（点在大小重叠的两框里时，小框通常是更具体的那个目标）；</li>
 *   <li>置信度更高者优先；</li>
 *   <li>最后按上边界、左边界排序，纯粹为了让<b>同分情况下结果也唯一确定</b>。</li>
 * </ol>
 * <p>复杂的重叠消歧（例如"框中有框"该选谁）留给 Slice 6，这里不引入任何随机性与启发式权重。</p>
 *
 * <p>线程约定：不可变（只有容差一个配置值），可跨线程调用。</p>
 */
public final class TargetHitTester {

    private final int tolerancePx;

    /**
     * @param config 默认策略，非 null；容差取 {@link TargetSelectionConfig#touchTolerancePx}
     * @throws IllegalArgumentException config 为 null
     */
    public TargetHitTester(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.tolerancePx = config.touchTolerancePx;
    }

    /**
     * @param tolerancePx 触摸容差（视频像素），&ge; 0
     * @throws IllegalArgumentException 为负
     */
    public TargetHitTester(int tolerancePx) {
        if (tolerancePx < 0) {
            throw new IllegalArgumentException("tolerancePx must be >= 0 but was " + tolerancePx);
        }
        this.tolerancePx = tolerancePx;
    }

    /**
     * 判定触摸点是否落在某个候选框上。
     *
     * @param touchVideoX 触摸点视频像素 x，必须有限
     * @param touchVideoY 触摸点视频像素 y，必须有限
     * @param candidates  候选框；null 或空视为没有候选
     * @return 非 null 的结果；命中时 {@code selectedBox} 为优先级最高的那个
     * @throws IllegalArgumentException 触摸坐标非有限值
     */
    public TargetHitTestResult hitTest(float touchVideoX, float touchVideoY,
                                       List<DetectedTargetBox> candidates) {
        final long startNs = System.nanoTime();
        if (!Float.isFinite(touchVideoX) || !Float.isFinite(touchVideoY)) {
            throw new IllegalArgumentException("touch point must be finite but was "
                    + touchVideoX + "," + touchVideoY);
        }
        final int total = candidates == null ? 0 : candidates.size();
        if (total == 0) {
            return TargetHitTestResult.miss(touchVideoX, touchVideoY, 0, tolerancePx, elapsedMs(startNs));
        }

        final List<DetectedTargetBox> hits = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final DetectedTargetBox box = candidates.get(i);
            if (box == null) {
                continue;
            }
            if (box.rectInVideo.inflated(tolerancePx).contains(touchVideoX, touchVideoY)) {
                hits.add(box);
            }
        }
        if (hits.isEmpty()) {
            return TargetHitTestResult.miss(touchVideoX, touchVideoY, total, tolerancePx, elapsedMs(startNs));
        }

        hits.sort(byPriority(touchVideoX, touchVideoY));
        return TargetHitTestResult.hit(hits.get(0), hits, touchVideoX, touchVideoY,
                total, tolerancePx, elapsedMs(startNs));
    }

    /** 当前生效的容差（视频像素）。 */
    public int tolerancePx() {
        return tolerancePx;
    }

    // ------------------------------------------------------------------ 优先级

    private static Comparator<DetectedTargetBox> byPriority(float touchX, float touchY) {
        return (a, b) -> {
            // 1) 严格包含（不含容差）优先
            final boolean insideA = a.rectInVideo.contains(touchX, touchY);
            final boolean insideB = b.rectInVideo.contains(touchX, touchY);
            if (insideA != insideB) {
                return insideA ? -1 : 1;
            }
            // 2) 到中心的距离更小优先（用平方距离，省掉开方）
            int result = Float.compare(centerDistanceSq(a, touchX, touchY), centerDistanceSq(b, touchX, touchY));
            if (result != 0) {
                return result;
            }
            // 3) 面积更小优先
            result = Float.compare(a.videoArea(), b.videoArea());
            if (result != 0) {
                return result;
            }
            // 4) 置信度更高优先
            result = Float.compare(b.confidence, a.confidence);
            if (result != 0) {
                return result;
            }
            // 5) 兜底：让结果唯一确定，而不是依赖排序算法的稳定性
            result = Float.compare(a.rectInVideo.top, b.rectInVideo.top);
            if (result != 0) {
                return result;
            }
            return Float.compare(a.rectInVideo.left, b.rectInVideo.left);
        };
    }

    private static float centerDistanceSq(DetectedTargetBox box, float touchX, float touchY) {
        final float dx = box.rectInVideo.centerX() - touchX;
        final float dy = box.rectInVideo.centerY() - touchY;
        return dx * dx + dy * dy;
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }
}
