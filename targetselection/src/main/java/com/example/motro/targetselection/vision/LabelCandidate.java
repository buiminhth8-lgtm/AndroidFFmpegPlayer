package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

/**
 * 一个<b>标签候选</b>：搜索区域里看起来像 {@code id:xxxx} 文字的那一小块，不可变。
 *
 * <p><b>同时带两套坐标是有意的</b>：{@link #rectInVideo} 是对外交流与调试叠加层用的
 * 视频像素矩形；{@link #rectInLocal} 是搜索区域<b>局部</b>像素矩形（闭区间），
 * 裁剪像素时只能用后者 —— 它直接对应刚才读进来的那块 ARGB 缓冲的下标。
 * 两者由同一次换算得出（{@link RoiRect#toVideoRect}），不存在第二份公式。</p>
 *
 * <p><b>四个评分字段各自回答什么（排序就是照这个顺序逐级比较的）</b>：</p>
 * <ul>
 *   <li>{@link #distanceToBox}：候选到 {@code selectedBox.top} 的<b>绝对</b>垂直距离
 *       （视频像素）。真实推流端把编号文字直接压在框顶边之上，所以这个值通常只有几像素；
 *       它是排序的第一优先级 —— 同一个框里的编号，离框顶最近的那个最可能是它自己的。</li>
 *   <li>{@link #signedGapToBox}：带符号的同一个量（正 = 在框<b>上方</b>，负 = 压进框顶）。
 *       单独留一个带符号版本是因为「上方 3px」与「压进框里 3px」在形态上是不同的两种排版，
 *       排查误检时必须分得开。</li>
 *   <li>{@link #alignmentScore}：候选宽度里有多大比例落在 {@code selectedBox} 的
 *       <b>水平跨度</b>内，[0,1]。它同时覆盖「与框左对齐」和「与框中心对齐」两种排版：
 *       两种情况下候选都完整落在框跨度内，也都是 1.0。它的真正用途是
 *       <b>把相邻目标框的编号推开</b> —— 隔壁那个框的编号多半落在本框跨度之外。</li>
 *   <li>{@link #textLikeScore}：这一块到底像不像「一行文字」，[0,1]。
 *       判据是行内多段性（每行有几段互不相连的笔画）、填充率（文字是镂空的，
 *       实心色块与长直线填充率接近 1）、以及长宽比（文字是 2:1~12:1 的横条）。
 *       框的一条边线在这三项上全面落败 —— 详见 {@code LabelRegionExtractor}。</li>
 *   <li>{@link #confidence}：综合量，[0,1]，只用于<b>展示与同分兜底</b>。
 *       排序不靠它定胜负：把三项权重压成一个浮点数以后，"谁该排前面"就变成不可读的
 *       系数游戏了；逐级比较（见 {@code LabelRegionExtractor} 的比较器）才能一眼看出
 *       某个候选是因为哪一条胜出的。</li>
 * </ul>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class LabelCandidate {

    /** 视频像素坐标下的候选矩形。 */
    public final TargetBoundingBox rectInVideo;

    /** 搜索区域局部像素坐标下的候选矩形（闭区间），供裁剪像素使用。 */
    public final RoiRect rectInLocal;

    /** 到 {@code selectedBox.top} 的绝对垂直距离（视频像素，&ge; 0）。 */
    public final float distanceToBox;

    /** 带符号的垂直间隙（视频像素）：{@code box.top - rect.bottom}，正 = 在框上方。 */
    public final float signedGapToBox;

    /** 与 {@code selectedBox} 的水平对齐程度 [0,1]，见类注释。 */
    public final float alignmentScore;

    /** 文本形态置信度 [0,1]，见类注释。 */
    public final float textLikeScore;

    /** 综合置信度 [0,1]，见类注释。 */
    public final float confidence;

    private LabelCandidate(TargetBoundingBox rectInVideo, RoiRect rectInLocal,
                           float distanceToBox, float signedGapToBox,
                           float alignmentScore, float textLikeScore, float confidence) {
        this.rectInVideo = rectInVideo;
        this.rectInLocal = rectInLocal;
        this.distanceToBox = Math.max(0f, distanceToBox);
        this.signedGapToBox = signedGapToBox;
        this.alignmentScore = clamp01(alignmentScore);
        this.textLikeScore = clamp01(textLikeScore);
        this.confidence = clamp01(confidence);
    }

    /**
     * @param rectInVideo    视频坐标矩形，非 null
     * @param rectInLocal    搜索区域局部矩形，非 null
     * @param distanceToBox  到框顶的绝对垂直距离（&lt; 0 会被置 0）
     * @param signedGapToBox 带符号垂直间隙
     * @param alignmentScore 水平对齐度，会被夹到 [0,1]
     * @param textLikeScore  文本形态置信度，会被夹到 [0,1]
     * @param confidence     综合置信度，会被夹到 [0,1]
     * @throws IllegalArgumentException 任一矩形为 null
     */
    public static LabelCandidate of(TargetBoundingBox rectInVideo, RoiRect rectInLocal,
                                    float distanceToBox, float signedGapToBox,
                                    float alignmentScore, float textLikeScore, float confidence) {
        if (rectInVideo == null) {
            throw new IllegalArgumentException("rectInVideo must not be null");
        }
        if (rectInLocal == null) {
            throw new IllegalArgumentException("rectInLocal must not be null");
        }
        return new LabelCandidate(rectInVideo, rectInLocal, distanceToBox, signedGapToBox,
                alignmentScore, textLikeScore, confidence);
    }

    /** 视频坐标下的宽度（像素）。 */
    public float width() {
        return rectInVideo.width();
    }

    /** 视频坐标下的高度（像素）。 */
    public float height() {
        return rectInVideo.height();
    }

    /** 视频坐标下的面积（平方像素）。 */
    public float area() {
        return rectInVideo.width() * rectInVideo.height();
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        return "label=" + rectInVideo
                + " local=" + rectInLocal
                + " dist=" + distanceToBox
                + " gap=" + signedGapToBox
                + " align=" + alignmentScore
                + " text=" + textLikeScore
                + " conf=" + confidence;
    }

    @Override
    public String toString() {
        return "LabelCandidate{" + debugString() + "}";
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }
}
