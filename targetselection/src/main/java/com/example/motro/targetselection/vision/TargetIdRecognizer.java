package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;

/**
 * 「标签区域 → 编号」的识别契约，<b>播放器无关、纯 JVM</b>。
 *
 * <h2>为什么是接口 + 默认实现</h2>
 * 识别是这一层里唯一"可能被换掉"的组件：今天用字形模板匹配，将来可能换成
 * Template Matching、TFLite 或某个 OCR SDK。业务层（{@code TargetProvider} 的实现、
 * 调试探针、离线验证）只应该依赖本接口，于是换实现时它们一行都不用改。
 *
 * <p><b>第三方 SDK 的隔离要求（若将来引入）</b>：SDK 类型不得出现在本接口签名里 ——
 * 入参只有 {@link LabelExtractionResult} 与 {@link TargetSelectionConfig}（都是本模块
 * 自己的类型），出参只有 {@link TargetIdRecognitionResult}。SDK 的初始化/释放放在
 * 实现类的生命周期里，并且<b>不得每次调用重建</b>：识别器通常持有重量级资源
 * （模型、缓存表），每次点击重建会直接把几十毫秒变成几百毫秒。
 * 默认实现（{@link GlyphTemplateTargetIdRecognizer}）是无状态的，因此它天然满足这一条，
 * 但它<b>仍然提供一个 {@code close()} 钩子</b> —— 让"需要释放资源"的替代实现
 * 不必改变接口。</p>
 *
 * <h2>输入契约</h2>
 * 入参必须是 {@link LabelExtractionResult}（或等效的 label ARGB + 宽高），
 * <b>不得</b>是整帧：本模块明确不做全帧 OCR（那是另一个量级的代价，且会引入
 * "在画面里到处找编号"这种与产品语义不符的行为）。调用方应当在
 * Slice 7 找到标签区域<b>之后</b>才调用本接口。</p>
 *
 * <h2>线程与调用时机</h2>
 * <ul>
 *   <li>只在<b>用户点击</b>时调用一次，绝不每帧调用；</li>
 *   <li>必须跑在<b>工作线程</b>上 —— 内部有逐像素的放大与匹配，放主线程会掉帧；</li>
 *   <li>不要放解码/渲染线程。</li>
 * </ul>
 *
 * <p><b>失败语义（关键）</b>：任何失败都必须以
 * {@link TargetIdRecognitionResult#status} 的形式返回，<b>不得抛异常</b>（入参为 null 除外）。
 * 理由是识别只是命中判定之后的附加步骤，它的失败不该把整条管线打断成崩溃 ——
 * 上层要的是「点中了框、编号没读出来」（{@code HIT_ID_UNKNOWN}），
 * 而不是一个异常。</p>
 */
public interface TargetIdRecognizer {

    /**
     * 识别标签区域里的编号。
     *
     * @param label  标签提取结果，非 null（其内部可能没有标签 → 返回
     *               {@link TargetIdRecognitionStatus#INVALID_LABEL}）
     * @param config 默认策略，非 null；实现只读取自己那一组参数
     * @return 非 null 的结果；永不为 null，也绝不抛出识别相关的异常
     * @throws IllegalArgumentException label 或 config 为 null
     */
    TargetIdRecognitionResult recognize(LabelExtractionResult label, TargetSelectionConfig config);

    /**
     * 释放实现持有的资源（模型、缓存、native 句柄）。默认无操作 ——
     * 无状态的实现不必覆写。此方法应是幂等的。
     */
    default void close() {
    }

    /** 实现名，用于日志区分（不同实现的 {@code rawText} 形态差异很大）。 */
    String name();
}
