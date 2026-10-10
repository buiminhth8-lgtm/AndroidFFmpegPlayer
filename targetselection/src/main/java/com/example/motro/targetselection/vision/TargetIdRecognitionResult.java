package com.example.motro.targetselection.vision;

/**
 * 「标签区域 → 编号」这一步的产物，<b>不可变、播放器无关、纯 JVM</b>。
 *
 * <p><b>它是 Slice 7 的 {@link LabelExtractionResult} 的下一步，不是替代品</b>：
 * {@code LabelExtractionResult} 回答「框顶上哪一小块是编号文字」，
 * 本类型回答「那一块里的编号是几」。两者分开，于是「ROI 画偏了」与「数字读错了」
 * 在排查时是两条互不干扰的线索（前者看 ROI 矩形，后者看 {@link #rawText}）。</p>
 *
 * <p><b>{@link #status} 是唯一权威，其余字段按它解释（重要）</b>：</p>
 * <ul>
 *   <li>{@link TargetIdRecognitionStatus#SUCCESS}：{@link #targetId} 非 null，
 *       它已被 {@code idMinDigits}~{@code idMaxDigits} 与 {@code minIdConfidence} 过滤过。</li>
 *   <li>其余状态：{@link #targetId} <b>恒为 null</b>。这是刻意的 ——
 *       「识别不确定时也带一个猜测值返回」会让上层写出
 *       {@code if (result.targetId != null)} 这种分支，于是失败信息被悄悄丢掉。
 *       想看识别到底读出了什么，看 {@link #rawText}。</li>
 * </ul>
 *
 * <p><b>{@link #rawText} 是识别的原始输出，不是解析结果</b>：它可能包含
 * {@code id:} 之外的内容（例如 {@code "ID:1980"}、{@code "id 1963"}、
 * 甚至 OCR 的噪声字符）。把它单独留一份，是为了让「OCR 读错了」与
 * 「解析规则太严」这两类失败能被区分开 —— 只看 {@code targetId} 是区分不出来的。</p>
 *
 * <p><b>置信度语义（不许伪造，重要）</b>：{@link #confidence} 是 [0,1] 的
 * <b>规则置信度</b>，由 {@link TargetIdRecognizer} 的默认实现按下述规则给出，
 * <b>不是</b> OCR 引擎自报的概率（本模块不引入 OCR SDK，没有那种数字）：
 * <pre>
 *   字形匹配的平均相似度 ×（1 − 位置惩罚）  ... 对每个被识别出的字符
 * </pre>
 * 直观含义是「这次匹配有多像」而不是「这个编号正确的概率」。它只在
 * {@code idMinDigits}~{@code idMaxDigits} 尺寸区间内可比，跨尺寸比较无意义。
 * 因此 {@code minIdConfidence} 的门槛只能按本模块自己的量纲标定
 * （见 {@link com.example.motro.targetselection.api.TargetSelectionConfig#DEFAULT_MIN_ID_CONFIDENCE}）。
 * 若某个实现拿不到置信度，允许返回 {@code null} —— 本字段是 {@link Float} 而不是
 * {@code float}，正是为了让「真的不知道」与「算出来是 0」能被区分开。</p>
 *
 * <p><b>三段耗时分开记</b>：{@link #preprocessMs}（放大/增强/二值化）、
 * {@link #recognizeMs}（逐字符识别）、{@link #parseMs}（规范化 + 提取 id）、
 * {@link #processingMs}（本类覆盖的整段，含入参校验）。
 * 分开的理由与 Slice 4/5/7 一致：现场只能说「慢了」是不够的，
 * 要知道慢在预处理还是慢在识别 —— 前者调 {@code labelUpscaleFactor}，
 * 后者换识别实现。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetIdRecognitionResult {

    /** 结局。 */
    public final TargetIdRecognitionStatus status;

    /** 识别出的编号（十进制字符串，已去前导零）；未成功时<b>恒为 null</b>。 */
    public final String targetId;

    /** 识别出的编号的数值形式；未成功或超出 {@code long} 范围时为 null。 */
    public final Long targetIdValue;

    /** 识别的<b>原始</b>文本（未解析，可能含噪声）；识别没产出文本时为 null 或空串。 */
    public final String rawText;

    /**
     * 规则置信度 [0,1]；无法给出时为 null。语义见类注释 —— 它<b>不是</b>概率。
     */
    public final Float confidence;

    /** 预处理阶段耗时（毫秒）：放大、增强、二值化。 */
    public final long preprocessMs;

    /** 识别阶段耗时（毫秒）：逐字符匹配。 */
    public final long recognizeMs;

    /** 解析阶段耗时（毫秒）：规范化 + 提取编号。 */
    public final long parseMs;

    /** 本结果覆盖的总耗时（毫秒）。 */
    public final long processingMs;

    /** 失败/未命中原因（英文，供日志抓取）；正常时为 null。 */
    public final String debugReason;

    /** 识别实现的名字（例如 {@code "glyph-template-v1"}），便于日志区分实现。 */
    public final String recognizerName;

    private TargetIdRecognitionResult(TargetIdRecognitionStatus status,
                                      String targetId, String rawText,
                                      Float confidence,
                                      long preprocessMs, long recognizeMs, long parseMs, long processingMs,
                                      String debugReason, String recognizerName) {
        this.status = status;
        this.targetId = targetId;
        this.targetIdValue = parseLongOrNull(targetId);
        this.rawText = rawText;
        this.confidence = normalizeConfidence(confidence);
        this.preprocessMs = Math.max(0L, preprocessMs);
        this.recognizeMs = Math.max(0L, recognizeMs);
        this.parseMs = Math.max(0L, parseMs);
        this.processingMs = Math.max(0L, processingMs);
        this.debugReason = debugReason;
        this.recognizerName = recognizerName;
    }

    /**
     * 识别成功。
     *
     * @param targetId 规范化后的编号（非 null、非空、全为数字）
     * @throws IllegalArgumentException targetId 为 null/空/含非数字字符
     */
    public static TargetIdRecognitionResult success(String targetId, String rawText,
                                                    Float confidence,
                                                    long preprocessMs, long recognizeMs,
                                                    long parseMs, long processingMs,
                                                    String recognizerName) {
        if (targetId == null || targetId.isEmpty()) {
            throw new IllegalArgumentException("targetId must not be null or empty on SUCCESS");
        }
        for (int i = 0; i < targetId.length(); i++) {
            if (targetId.charAt(i) < '0' || targetId.charAt(i) > '9') {
                throw new IllegalArgumentException(
                        "targetId must contain only digits but was: " + targetId);
            }
        }
        return new TargetIdRecognitionResult(TargetIdRecognitionStatus.SUCCESS, targetId, rawText,
                confidence, preprocessMs, recognizeMs, parseMs, processingMs,
                null, recognizerName);
    }

    /** 标签可用、但里面没有可用的编号（无文本，或文本里没有数字）。 */
    public static TargetIdRecognitionResult noId(String rawText, Float confidence,
                                                 long preprocessMs, long recognizeMs, long parseMs,
                                                 long processingMs, String reason,
                                                 String recognizerName) {
        return new TargetIdRecognitionResult(TargetIdRecognitionStatus.NO_ID, null, rawText,
                confidence, preprocessMs, recognizeMs, parseMs, processingMs, reason, recognizerName);
    }

    /** 读出了编号但置信度不足。注意 {@code targetId} <b>仍然不带出来</b>（见类注释）。 */
    public static TargetIdRecognitionResult lowConfidence(String rawText, Float confidence,
                                                          long preprocessMs, long recognizeMs,
                                                          long parseMs, long processingMs,
                                                          String reason, String recognizerName) {
        return new TargetIdRecognitionResult(TargetIdRecognitionStatus.LOW_CONFIDENCE, null, rawText,
                confidence, preprocessMs, recognizeMs, parseMs, processingMs, reason, recognizerName);
    }

    /** 交进来的标签本身不可用。 */
    public static TargetIdRecognitionResult invalidLabel(String reason, long processingMs,
                                                         String recognizerName) {
        return new TargetIdRecognitionResult(TargetIdRecognitionStatus.INVALID_LABEL, null, null,
                null, 0L, 0L, 0L, processingMs, reason, recognizerName);
    }

    /** 识别过程本身失败。 */
    public static TargetIdRecognitionResult recognitionFailed(String rawText, long preprocessMs,
                                                              long recognizeMs, long processingMs,
                                                              String reason, String recognizerName) {
        return new TargetIdRecognitionResult(TargetIdRecognitionStatus.RECOGNITION_FAILED, null,
                rawText, null, preprocessMs, recognizeMs, 0L, processingMs, reason, recognizerName);
    }

    /** 是否读到了编号。 */
    public boolean hasId() {
        return status == TargetIdRecognitionStatus.SUCCESS && targetId != null;
    }

    /**
     * 是否「点到了目标、只是没读出编号」—— 上层据此报 {@code HIT_ID_UNKNOWN}
     * 而不是 {@code MISS}。语义见 {@link TargetIdRecognitionStatus#keepsHit()}。
     */
    public boolean isHitWithoutId() {
        return status.keepsHit() && !hasId();
    }

    /** 本结果覆盖的总耗时（毫秒）；与 {@code processingMs} 同值，提供更符合直觉的名字。 */
    public long processingMs() {
        return processingMs;
    }

    /** 单行摘要，供调试叠加层与真实样本验证脚本解析。 */
    public String debugString() {
        final StringBuilder b = new StringBuilder(160);
        b.append("targetId=").append(status)
                .append(" raw=").append(rawText == null ? "-" : '"' + rawText + '"')
                .append(" id=").append(targetId == null ? "-" : targetId)
                .append(" conf=").append(confidence == null ? "n/a" : confidence.toString())
                .append(" preMs=").append(preprocessMs)
                .append(" recMs=").append(recognizeMs)
                .append(" parseMs=").append(parseMs)
                .append(" totalMs=").append(processingMs);
        if (recognizerName != null) {
            b.append(" impl=").append(recognizerName);
        }
        if (debugReason != null) {
            b.append(" reason=").append(debugReason);
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "TargetIdRecognitionResult{" + debugString() + "}";
    }

    private static Float normalizeConfidence(Float value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static Long parseLongOrNull(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            // 位数超过 long 能表示的范围（idMaxDigits 允许到 18 位以内，但配置可能有边界情况）。
            // 这不算失败：targetId 仍是权威的字符串形式，只是没有数值形式可用。
            return null;
        }
    }
}
