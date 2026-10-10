package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 一个<b>候选目标框</b>：由蓝色掩码里的细线段恢复出来的矩形，不可变。
 *
 * <p><b>同时带两套坐标是有意的</b>：{@link #rectInRoi} 是检测时真正用的（ROI 局部像素，
 * 闭区间），{@link #rectInVideo} 是换算到视频像素后的对外结果。两者都在
 * {@code TargetBoxDetector} 里一次算好，调用方<b>不需要</b>也不应该自己做换算 ——
 * 那样迟早会出现两份公式，而且其中一份是错的。</p>
 *
 * <p><b>{@link #completeness} 与 {@link #confidence} 的分工</b>：</p>
 * <ul>
 *   <li>{@link #completeness}：四条边蓝色覆盖率的<b>算术平均</b>，纯粹回答
 *       "这个框线画得有多全"。断裂、缺角、被 ROI 截断都会把它拉低。</li>
 *   <li>{@link #confidence}：{@code completeness × (达标边数 / 4)}，额外惩罚
 *       "只有一两条边像样"的形状 —— 两条平行线被误配成框时 completeness 可能不低，
 *       但达标边数只有 2，confidence 会被腰斩。</li>
 * </ul>
 * <p>两者都不要求内部是蓝的（目标框是空心的，内部就是画面本身）。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class DetectedTargetBox {

    /** ROI 局部像素坐标下的矩形（闭区间）。 */
    public final RoiRect rectInRoi;

    /** 视频像素坐标下的矩形。 */
    public final TargetBoundingBox rectInVideo;

    /** 置信度 [0,1]，见类注释。 */
    public final float confidence;

    /** 边覆盖率均值 [0,1]，见类注释。 */
    public final float completeness;

    /** 达标的边数（覆盖率 &ge; {@code minEdgeCoverage}），取值 0~4。 */
    public final int presentEdges;

    /**
     * 是否被 ROI 边界截断（矩形某条边正好压在 ROI 边界上）。
     * 为 true 时说明真实目标框比 {@link #rectInVideo} 更大 ——
     * 位置在截断方向上是不可信的，下游若要做精确对齐需要先扩大 ROI。
     */
    public final boolean clippedByRoi;

    private DetectedTargetBox(RoiRect rectInRoi, TargetBoundingBox rectInVideo,
                              float confidence, float completeness, int presentEdges,
                              boolean clippedByRoi) {
        this.rectInRoi = rectInRoi;
        this.rectInVideo = rectInVideo;
        this.confidence = confidence;
        this.completeness = completeness;
        this.presentEdges = presentEdges;
        this.clippedByRoi = clippedByRoi;
    }

    /**
     * @param rectInRoi    ROI 局部矩形，非 null
     * @param rectInVideo  视频坐标矩形，非 null
     * @param confidence   置信度，会被裁剪到 [0,1]（NaN → 0）
     * @param completeness 完整度，会被裁剪到 [0,1]（NaN → 0）
     * @param presentEdges 达标边数，会被裁剪到 [0,4]
     * @throws IllegalArgumentException 任一矩形为 null
     */
    public static DetectedTargetBox of(RoiRect rectInRoi, TargetBoundingBox rectInVideo,
                                       float confidence, float completeness,
                                       int presentEdges, boolean clippedByRoi) {
        if (rectInRoi == null) {
            throw new IllegalArgumentException("rectInRoi must not be null");
        }
        if (rectInVideo == null) {
            throw new IllegalArgumentException("rectInVideo must not be null");
        }
        return new DetectedTargetBox(rectInRoi, rectInVideo,
                clamp01(confidence), clamp01(completeness),
                Math.max(0, Math.min(4, presentEdges)), clippedByRoi);
    }

    /** 视频坐标下的宽度（像素）。 */
    public float videoWidth() {
        return rectInVideo.width();
    }

    /** 视频坐标下的高度（像素）。 */
    public float videoHeight() {
        return rectInVideo.height();
    }

    /**
     * 视频坐标下的面积（平方像素）。用于多候选选择里"面积更小者优先"的比较，
     * 也是把"点到小目标"与"点到大目标"区分开的依据。
     */
    public float videoArea() {
        return rectInVideo.width() * rectInVideo.height();
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "box=" + rectInVideo
                + " roi=" + rectInRoi
                + " conf=" + confidence
                + " complete=" + completeness
                + " edges=" + presentEdges
                + (clippedByRoi ? " clipped" : "");
    }

    @Override
    public String toString() {
        return "DetectedTargetBox{" + debugString() + "}";
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }
}
