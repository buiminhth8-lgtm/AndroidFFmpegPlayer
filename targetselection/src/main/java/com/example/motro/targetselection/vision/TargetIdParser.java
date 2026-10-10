package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「识别出来的文本 → 编号」这一步的<b>唯一</b>实现：规范化、按前缀匹配、
 * 在严格条件下回退到唯一数字串。播放器无关、纯 JVM、零第三方依赖。
 *
 * <h2>为什么单独一个类（而不是塞进 {@link TargetIdRecognizer} 的默认实现里）</h2>
 * 解析规则是这一层里<b>最容易出错、也最容易写测试</b>的部分：它处理的是字符串，
 * 输入可以穷举，不需要像素、不需要字形、不需要配置里的任何视觉参数。
 * 把它独立出来以后，「OCR 读到了什么」与「怎么从读到的文本里取号」就彻底分开了 ——
 * 前者靠真实样本 smoke test 验证，后者靠几十条纯字符串单测穷举。
 * 顺带的好处是识别实现可以被替换（Template / TFLite）而复用同一套解析规则：
 * 换实现时只有 {@code rawText} 的形态会变，取号规则不该跟着变。
 *
 * <h2>规则（按优先级）</h2>
 * <ol>
 *   <li><b>规范化</b>（{@link #normalize}）：trim、把所有空白（含换行/制表/全角空格）
 *       折成单个半角空格、去掉被识别出来的换行。目的只是消掉"排版噪声"，
 *       不做任何"纠正" —— 纠正会引入第二套规则，且无法单测。</li>
 *   <li><b>前缀优先</b>：用 {@link #ID_PREFIX} 匹配 {@code id} + 可选冒号 + 数字。
 *       冒号<b>可选</b>是刻意的：OCR 经常把 {@code :} 读丢、读成空格、
 *       或读成全角 {@code ：}，而 {@code id} 三个字母远比一个冒号稳定。
 *       大小写不敏感（{@code ID} / {@code Id} / {@code id} 都认）。</li>
 *   <li><b>唯一数字串回退</b>：只有当前缀匹配失败、且整段文本里<b>恰好</b>存在一个
 *       连续数字串时才采用它。理由：本方法的入参保证来自 {@link LabelRegionExtractor}
 *       裁出的标签 ROI（那一块已经被判过"像一行编号文字"），所以一个孤零零的数字串
 *       大概率就是编号。<b>多个数字串时一律不猜</b> —— 那时无法区分
 *       {@code "id"} 读丢了还是读错位了，返回 null 让上层报
 *       {@code LOW_CONFIDENCE}（有原始文本、可供人工或后续 Slice 处理）比猜一个错编号诚实得多。</li>
 *   <li><b>位数过滤</b>：数字串长度必须落在 {@code [idMinDigits, idMaxDigits]} 内。
 *       过短（{@code 0}、{@code 2}）多半是噪声笔画或单个字形被拆出来的碎片；
 *       过长（>{@code idMaxDigits}）几乎一定是把两行/两个编号连起来了。</li>
 * </ol>
 *
 * <h2>为什么返回的是「候选」而不是直接给编号</h2>
 * 因为调用方（{@link TargetIdRecognizer} 的默认实现）还要给这个结果配上置信度，
 * 再用 {@code minIdConfidence} 判一次。把"要不要接受"的判断留给调用方，
 * 解析器就只回答"文本里能不能取到一个数字串"，职责单一、可单独穷举。
 *
 * <p>线程约定：本类无状态（{@link Pattern} 是线程安全的），可跨线程并发调用。</p>
 */
public final class TargetIdParser {

    /**
     * 前缀规则：{@code id} + 可选冒号 + 数字。大小写不敏感。
     *
     * <p>{@code \s*} 允许 {@code id :1963} / {@code id 1963} 这类 OCR 把冒号读成空格的情况；
     * 冒号类同时接受半角 {@code :} 与全角 {@code ：}。</p>
     */
    private static final Pattern ID_PREFIX =
            Pattern.compile("id\\s*[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** 任意连续数字串，用于"唯一数字串回退"与"多数字串判定"。 */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    /** 所有空白字符（含不间断空格与全角空格），用于规范化。 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");

    /** 取号结果：{@link #digits} 非 null 表示成功。 */
    public static final class ParsedId {

        /** 规范化后的数字串（无前导零、长度已过滤）；未取到时为 null。 */
        public final String digits;

        /** 取号依据：{@code "prefix"} / {@code "unique-digits"} / null（未取到）。 */
        public final String via;

        private ParsedId(String digits, String via) {
            this.digits = digits;
            this.via = via;
        }
    }

    private static final ParsedId NONE = new ParsedId(null, null);

    private final int minDigits;
    private final int maxDigits;

    /**
     * @param config 默认策略，非 null；只读取 {@code idMinDigits} / {@code idMaxDigits}
     *               —— 解析器不认识任何视觉参数（那是识别实现的事）
     * @throws IllegalArgumentException config 为 null
     */
    public TargetIdParser(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.minDigits = config.idMinDigits;
        this.maxDigits = config.idMaxDigits;
    }

    /**
     * 显式给出位数区间的便捷构造（供单测与不持有配置的调用方使用）。
     *
     * @param minDigits 最小位数（&ge; 1）
     * @param maxDigits 最大位数（&ge; minDigits）
     * @throws IllegalArgumentException 区间非法
     */
    public TargetIdParser(int minDigits, int maxDigits) {
        if (minDigits < 1) {
            throw new IllegalArgumentException("minDigits must be >= 1 but was " + minDigits);
        }
        if (maxDigits < minDigits) {
            throw new IllegalArgumentException("maxDigits (" + maxDigits
                    + ") must be >= minDigits (" + minDigits + ")");
        }
        this.minDigits = minDigits;
        this.maxDigits = maxDigits;
    }

    /**
     * 从识别出的原始文本里取出编号。
     *
     * @param rawText 识别输出，可为 null（返回 {@link #NONE}）
     * @return 非 null；{@code digits == null} 表示没取到
     */
    public ParsedId parse(String rawText) {
        if (rawText == null) {
            return NONE;
        }
        final String text = normalize(rawText);
        if (text.isEmpty()) {
            return NONE;
        }

        // 1) 前缀优先：id[:：]?\d+
        final Matcher prefix = ID_PREFIX.matcher(text);
        while (prefix.find()) {
            final String accepted = accept(prefix.group(1));
            if (accepted != null) {
                return new ParsedId(accepted, "prefix");
            }
        }

        // 2) 回退：整段文本里恰好一个数字串时才采用
        final List<String> runs = digitRuns(text);
        if (runs.size() == 1) {
            final String accepted = accept(runs.get(0));
            if (accepted != null) {
                return new ParsedId(accepted, "unique-digits");
            }
        }
        // 多个数字串（或 0 个）：一律不猜
        return NONE;
    }

    /** 位数字符串是否落在配置区间内；在区间内则返回去前导零后的形式，否则 null。 */
    public boolean accepts(String digits) {
        return accept(digits) != null;
    }

    /** 规范化：trim + 空白折叠。可见性设为 public 便于单测直接断言这一步。 */
    public static String normalize(String rawText) {
        if (rawText == null) {
            return "";
        }
        return WHITESPACE.matcher(rawText).replaceAll(" ").trim();
    }

    private String accept(String digits) {
        if (digits == null || digits.isEmpty()) {
            return null;
        }
        if (digits.length() < minDigits || digits.length() > maxDigits) {
            return null;
        }
        return stripLeadingZeros(digits);
    }

    /** 去掉前导零（{@code "01963"} → {@code "1963"}）；全零保留一个 {@code "0"}。 */
    private static String stripLeadingZeros(String digits) {
        int first = 0;
        while (first < digits.length() - 1 && digits.charAt(first) == '0') {
            first++;
        }
        return digits.substring(first);
    }

    /** 全部连续数字串，按出现顺序。 */
    private static List<String> digitRuns(String text) {
        final List<String> runs = new ArrayList<>(4);
        final Matcher matcher = DIGIT_RUN.matcher(text);
        while (matcher.find()) {
            runs.add(matcher.group());
        }
        return runs;
    }

    /** 配置摘要，便于日志区分不同位数的解析规则。 */
    public String describe() {
        return "TargetIdParser{digits=[" + minDigits + "," + maxDigits + "]}";
    }

    @Override
    public String toString() {
        return describe();
    }

    /** 前缀规则的正则源码，供日志与单测断言"规则只有一份"。 */
    public static String prefixPatternSource() {
        return ID_PREFIX.pattern();
    }

    static {
        // 让"前缀规则只有一份"这件事在类装载时就被固定下来：如果有人改了正则却忘了
        // 同步注释与单测，这里的断言会立刻红掉。
        if (!ID_PREFIX.pattern().equalsIgnoreCase("id\\s*[:：]?\\s*(\\d+)")) {
            throw new IllegalStateException("ID_PREFIX pattern changed unexpectedly: "
                    + ID_PREFIX.pattern().toLowerCase(Locale.ROOT));
        }
    }
}
