package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「已选中的目标框 → 它的 {@code id:xxxx} 标签区域」这一步的产物，
 * <b>不可变、播放器无关、纯 JVM</b>。
 *
 * <p><b>它不含 id</b>：本 Slice 只负责「找到并裁出标签那一小块」，不识别文字内容。
 * 所以这里<b>没有</b> targetId 字段 —— 不设比设一个恒为 null 的字段更诚实，
 * 免得上层写出「读到了 null 就是识别失败」的误判逻辑。
 * 想拿编号的调用方应当把 {@link #labelArgb}（或 {@link #getLabelRectInVideo()}）
 * 交给后续的识别阶段。</p>
 *
 * <p><b>为什么同时给「选中标签」与「全部候选」</b>：生产路径只看前者，
 * 但调试路径必须看后者 —— 「为什么选的是这一块」这个问题的答案，
 * 全在被丢掉的候选里。两者的关系是 {@code selectedLabel == candidates.get(0)}，
 * 于是「选中的那个不在候选列表里」这种自相矛盾的状态在类型层面就不可能出现。</p>
 *
 * <p><b>三段耗时分开记</b>：{@link #labelDetectMs}（搜索区域构造 + 像素回读 + 候选搜索）、
 * {@link #labelCropMs}（裁剪选中的那一小块）、{@link #totalMs}（本类覆盖的整段，
 * 含入参校验）。注意 {@code totalMs} 只覆盖 <b>Label 提取</b>这一段 ——
 * 掩码、矩形恢复、命中与选择都在它之前，各自的耗时在 {@code TargetBoxDetectionResult} 里。</p>
 *
 * <p><b>像素所有权</b>：{@link #labelArgb} 由提取器每次调用<b>新分配</b>，可安全跨线程传递；
 * 调用方<b>不得</b>修改它（它是裁剪结果的唯一副本，改了就与 {@link #getLabelRectInVideo()}
 * 描述的区域对不上了）。它<b>不</b>引用原帧的任何缓冲，所以原帧释放之后仍然有效。</p>
 *
 * <p>线程约定：不可变对象（列表为不可修改视图，{@code labelArgb} 视为只读），
 * 可跨线程安全共享。</p>
 */
public final class LabelExtractionResult {

    /** 结局。 */
    public final LabelExtractionStatus status;

    /**
     * 本次实际搜索的区域（视频坐标，已夹进视频边界）；失败于入参校验时为 null。
     * 它是调试叠加层与误检排查的第一手依据：标签没找到时，先看这个框画在哪。
     */
    public final TargetBoundingBox labelSearchRegion;

    /** 全部标签候选，按 {@code LabelRegionExtractor} 的确定性顺序排列；无候选时为空列表。 */
    public final List<LabelCandidate> candidates;

    /** 最终选中的标签；无候选时为 null。它恒等于 {@code candidates.get(0)}（若有）。 */
    public final LabelCandidate selectedLabel;

    /** 选中标签在<b>分析帧</b>坐标下的裁剪矩形（含取整 padding）；未裁剪时为 null。 */
    public final RoiRect labelRectInFrame;

    /** 裁剪出的 ARGB_8888 像素（行优先，长度 = 宽×高）；未裁剪时为 null。 */
    public final int[] labelArgb;

    /** 裁剪宽度（像素）；未裁剪时为 0。 */
    public final int labelWidth;

    /** 裁剪高度（像素）；未裁剪时为 0。 */
    public final int labelHeight;

    /** 搜索阶段耗时（毫秒）：搜索区域构造 + 像素回读 + 候选筛选。 */
    public final long labelDetectMs;

    /** 裁剪阶段耗时（毫秒）；无候选未裁剪时为 0。 */
    public final long labelCropMs;

    /** 本结果覆盖的总耗时（毫秒）。 */
    public final long totalMs;

    /** 失败/未命中原因（英文，供日志抓取）；正常时为 null。 */
    public final String debugReason;

    private LabelExtractionResult(LabelExtractionStatus status,
                                  TargetBoundingBox labelSearchRegion,
                                  List<LabelCandidate> candidates,
                                  LabelCandidate selectedLabel,
                                  RoiRect labelRectInFrame,
                                  int[] labelArgb, int labelWidth, int labelHeight,
                                  long labelDetectMs, long labelCropMs, long totalMs,
                                  String debugReason) {
        this.status = status;
        this.labelSearchRegion = labelSearchRegion;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.selectedLabel = selectedLabel;
        this.labelRectInFrame = labelRectInFrame;
        this.labelArgb = labelArgb;
        this.labelWidth = Math.max(0, labelWidth);
        this.labelHeight = Math.max(0, labelHeight);
        this.labelDetectMs = Math.max(0L, labelDetectMs);
        this.labelCropMs = Math.max(0L, labelCropMs);
        this.totalMs = Math.max(0L, totalMs);
        this.debugReason = debugReason;
    }

    /** 入参/帧/框不可用，或搜索区域没有落到画面上。 */
    static LabelExtractionResult failure(LabelExtractionStatus status, String reason, long totalMs) {
        return new LabelExtractionResult(status, null, Collections.emptyList(), null, null,
                null, 0, 0, 0L, 0L, totalMs, reason);
    }

    /**
     * 框是好的、搜索也做完了，但没找到任何像标签的东西 —— 对应
     * {@link LabelExtractionStatus#NO_LABEL}（<b>不是</b> MISS，见该枚举的说明）。
     */
    static LabelExtractionResult noLabel(TargetBoundingBox searchRegion,
                                         long labelDetectMs, long totalMs, String reason) {
        return new LabelExtractionResult(LabelExtractionStatus.NO_LABEL, searchRegion,
                Collections.emptyList(), null, null, null, 0, 0,
                labelDetectMs, 0L, totalMs, reason);
    }

    /** 找到了标签并裁剪完毕。 */
    static LabelExtractionResult success(TargetBoundingBox searchRegion,
                                         List<LabelCandidate> candidates,
                                         LabelCandidate selectedLabel,
                                         RoiRect labelRectInFrame,
                                         int[] labelArgb, int labelWidth, int labelHeight,
                                         long labelDetectMs, long labelCropMs, long totalMs,
                                         String reason) {
        return new LabelExtractionResult(LabelExtractionStatus.SUCCESS, searchRegion,
                candidates, selectedLabel, labelRectInFrame, labelArgb, labelWidth, labelHeight,
                labelDetectMs, labelCropMs, totalMs, reason);
    }

    /** 是否找到了标签。 */
    public boolean hasLabel() {
        return status == LabelExtractionStatus.SUCCESS && selectedLabel != null;
    }

    /**
     * 是否「点到了目标、只是没读到编号」—— 上层据此报 {@code HIT_ID_UNKNOWN}
     * 而不是 {@code MISS}。
     */
    public boolean isHitWithoutLabel() {
        return status.isHitWithoutLabel();
    }

    /** 选中标签在视频坐标下的矩形；未找到时为 null。 */
    public TargetBoundingBox getLabelRectInVideo() {
        return selectedLabel == null ? null : selectedLabel.rectInVideo;
    }

    /** 候选总数。 */
    public int candidateCount() {
        return candidates.size();
    }

    /** 本结果覆盖的总耗时（毫秒）；与 {@link #totalMs} 同值，提供更符合直觉的名字。 */
    public long processingMs() {
        return totalMs;
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        final StringBuilder b = new StringBuilder(200);
        b.append("label=").append(status)
                .append(" search=").append(labelSearchRegion == null ? "-" : labelSearchRegion)
                .append(" candidates=").append(candidateCount())
                .append(" selected=").append(selectedLabel == null ? "-" : selectedLabel.rectInVideo)
                .append(" crop=").append(labelArgb == null ? "-" : labelWidth + "x" + labelHeight)
                .append(" detectMs=").append(labelDetectMs)
                .append(" cropMs=").append(labelCropMs)
                .append(" totalMs=").append(totalMs);
        if (selectedLabel != null) {
            b.append(" dist=").append(selectedLabel.distanceToBox)
                    .append(" align=").append(selectedLabel.alignmentScore)
                    .append(" text=").append(selectedLabel.textLikeScore)
                    .append(" conf=").append(selectedLabel.confidence);
        }
        if (debugReason != null) {
            b.append(" reason=").append(debugReason);
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "LabelExtractionResult{" + debugString() + "}";
    }
}
