package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.vision.TargetIdParser;

import org.junit.Test;

/**
 * {@link TargetIdParser} 的穷举单测 —— 解析是这一层里唯一能"用字符串穷举"的部分，
 * 所以覆盖度刻意做满：需求里逐条点名的规范化、前缀变体、回退条件、多数字串不猜、
 * 位数边界，全部落在具体的输入输出对上。
 *
 * <p>为什么这些用例值得写得这么细：解析规则错了的后果是"识别成功率莫名偏低"
 * 或"读到别的框的编号"—— 两者都不会崩、不会报错，只会让现场觉得"这个功能不太准"。
 * 把每条规则钉成一个断言，回归才有意义。</p>
 */
public class TargetIdParserTest {

    private static TargetIdParser parser() {
        return new TargetIdParser(TargetSelectionConfig.defaults());
    }

    private static TargetIdParser parser(int minDigits, int maxDigits) {
        return new TargetIdParser(minDigits, maxDigits);
    }

    // ---------------------------------------------------------------- 前缀形式

    @Test
    public void canonicalIdIsParsed() {
        assertEquals("1963", parser().parse("id:1963").digits);
        assertEquals("1963", parser().parse("id:1963").digits);
        assertEquals("prefix", parser().parse("id:1963").via);
    }

    @Test
    public void uppercasePrefixIsAccepted() {
        assertEquals("1963", parser().parse("ID:1963").digits);
        assertEquals("1963", parser().parse("Id:1963").digits);
        assertEquals("1963", parser().parse("iD:1963").digits);
    }

    @Test
    public void spacedPrefixVariantsAreAccepted() {
        // OCR 常把冒号读丢、读成空格、或读成全角
        assertEquals("1963", parser().parse("id 1963").digits);
        assertEquals("1963", parser().parse("id1963").digits);
        assertEquals("1963", parser().parse("id :1963").digits);
        assertEquals("1963", parser().parse("id: 1963").digits);
        assertEquals("1963", parser().parse("id ：1963").digits);
        assertEquals("1963", parser().parse("id：1963").digits);
    }

    @Test
    public void leadingAndTrailingNoiseIsIgnored() {
        assertEquals("1963", parser().parse("  id:1963  ").digits);
        assertEquals("1963", parser().parse("xx id:1963 yy").digits);
        assertEquals("1963", parser().parse("|id:1963|").digits);
    }

    @Test
    public void newlinesAndTabsAreNormalized() {
        assertEquals("1963", parser().parse("id:\n1963").digits);
        assertEquals("1963", parser().parse("id\r\n1963").digits);
        assertEquals("1963", parser().parse("id\t:\t1963").digits);
        // 多个空白折成一个
        assertEquals("1 9 6 3".replace(" ", ""), parser().parse("id:   1963").digits);
    }

    @Test
    public void fullWidthSpacesAreNormalized() {
        assertEquals("1963", parser().parse("id\u30001963").digits);
        assertEquals("1963", parser().parse("id\u00A0:1963").digits);
    }

    // ---------------------------------------------------------------- 唯一数字串回退

    @Test
    public void uniqueDigitRunIsUsedWhenPrefixIsMissing() {
        // 前缀被 OCR 读坏，但整段只有一个数字串 → 采用
        assertEquals("1963", parser().parse("l d:1963".replace(":", " ")).digits);
        assertEquals("1963", parser().parse("jd 1963").digits);
        assertEquals("1963", parser().parse("1963").digits);
        assertEquals("1963", parser().parse("【1963】").digits);
        assertEquals("unique-digits", parser().parse("jd 1963").via);
    }

    @Test
    public void multipleDigitRunsAreNeverGuessed() {
        // 没有 id 前缀、又有两个数字串 → 一律不猜
        // （返回 null，让上层报 LOW_CONFIDENCE 并带上 rawText 供人工/后续 Slice 处理）
        assertNull(parser().parse("12 34").digits);
        assertNull(parser().parse("1963 1980").digits);
        assertNull(parser().parse("a1 b2").digits);
        assertNull(parser().parse("x12 y34").digits);
    }

    @Test
    public void prefixAfterGarbageStillMatchesThePrefix() {
        // "id 12 and 34" 里前缀是明确的定位信息，所以按前缀取 12 ——
        // 这正是「多数字串不猜」这条规则的例外，必须在测试里写清楚，
        // 否则日后有人会以为解析器"猜了"第一个数字串。
        assertEquals("12", parser().parse("id 12 and 34").digits);
        assertEquals("prefix", parser().parse("id 12 and 34").via);
    }

    @Test
    public void prefixWinsEvenWhenOtherDigitRunsExist() {
        // 有前缀时以它为准，其余数字串不参与（这是"多数字串不猜"的例外：
        // 前缀本身就是明确的定位信息）
        assertEquals("1963", parser().parse("id:1963 1980").digits);
        assertEquals("prefix", parser().parse("id:1963 1980").via);
    }

    @Test
    public void firstMatchingPrefixWins() {
        // 两个 id 前缀：取第一个 —— 顺序是唯一的、可复现的
        assertEquals("1963", parser().parse("id:1963 id:1980").digits);
    }

    // ---------------------------------------------------------------- 无数字

    @Test
    public void textWithoutDigitsYieldsNoId() {
        assertNull(parser().parse("id:").digits);
        assertNull(parser().parse("id").digits);
        assertNull(parser().parse("no text here").digits);
        assertNull(parser().parse("...").digits);
        assertNull(parser().parse("").digits);
        assertNull(parser().parse("   ").digits);
        assertNull(parser().parse(null).digits);
    }

    // ---------------------------------------------------------------- 位数边界

    @Test
    public void shortIdIsRejectedWhenBelowMinimum() {
        final TargetIdParser strict = parser(3, 6);
        assertNull(strict.parse("id:19").digits);
        assertEquals("196", strict.parse("id:196").digits);
    }

    @Test
    public void longIdIsRejectedWhenAboveMaximum() {
        final TargetIdParser strict = parser(1, 6);
        assertEquals("123456", strict.parse("id:123456").digits);
        assertNull(strict.parse("id:1234567").digits);
        // 连起来的两个编号（典型错误）会被位数上限挡掉
        assertNull(strict.parse("id:19631980").digits);
    }

    @Test
    public void singleDigitIdIsAcceptedByDefault() {
        // 默认 1..6 位：id:1 是合法目标，不该被当成噪声
        assertEquals("1", parser().parse("id:1").digits);
    }

    @Test
    public void leadingZerosAreStripped() {
        assertEquals("1963", parser().parse("id:01963").digits);
        assertEquals("0", parser().parse("id:0").digits);
        assertEquals("0", parser().parse("id:000").digits);
    }

    @Test
    public void fallbackRespectsDigitBoundsToo() {
        final TargetIdParser strict = parser(2, 4);
        assertEquals("196", strict.parse("196").digits);   // 3 位落在区间内
        assertNull(strict.parse("1").digits);              // 1 位 < 下限
        assertNull(strict.parse("19631").digits);          // 5 位 > 上限
    }

    // ---------------------------------------------------------------- 规范化本身

    @Test
    public void normalizeCollapsesWhitespace() {
        assertEquals("id:1963", TargetIdParser.normalize("  id:1963  "));
        assertEquals("a b c", TargetIdParser.normalize("a\n\t b   c"));
        assertEquals("", TargetIdParser.normalize(null));
        assertEquals("", TargetIdParser.normalize("   "));
    }

    // ---------------------------------------------------------------- 确定性与构造校验

    @Test
    public void parsingIsDeterministic() {
        final TargetIdParser parser = parser();
        final String[] inputs = {"id:1963", "ID 1963", "jd1963", "12 34", "", "id:19631980"};
        for (String input : inputs) {
            final String first = parser.parse(input).digits;
            for (int i = 0; i < 20; i++) {
                assertEquals("输入 " + input + " 的解析结果必须稳定", first, parser.parse(input).digits);
            }
        }
    }

    @Test
    public void acceptsReportsDigitBounds() {
        final TargetIdParser strict = parser(3, 5);
        assertTrue(strict.accepts("1963"));
        assertFalse(strict.accepts("19"));
        assertFalse(strict.accepts("196319"));
    }

    @Test
    public void constructorValidatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TargetIdParser(0, 6));
        assertThrows(IllegalArgumentException.class, () -> new TargetIdParser(4, 3));
        assertThrows(IllegalArgumentException.class, () -> new TargetIdParser((TargetSelectionConfig) null));
    }

    @Test
    public void configDrivenBoundsAreHonored() {
        final TargetSelectionConfig narrow = TargetSelectionConfig.defaults().toBuilder()
                .idMinDigits(4).idMaxDigits(4).build();
        final TargetIdParser parser = new TargetIdParser(narrow);
        assertEquals("1963", parser.parse("id:1963").digits);
        assertNull(parser.parse("id:196").digits);
        assertNull(parser.parse("id:19631").digits);
    }

    @Test
    public void describeIncludesDigitBounds() {
        assertTrue(parser(2, 5).describe().contains("[2,5]"));
    }

    @Test
    public void prefixPatternIsTheDocumentedOne() {
        assertEquals("id\\s*[:：]?\\s*(\\d+)", TargetIdParser.prefixPatternSource());
    }
}
