package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 「蓝色掩码 → 目标框候选」：从 {@link BinaryMask} 里恢复出推流端画的<b>细蓝色矩形框</b>。
 * 播放器无关、零第三方依赖（没有 OpenCV / ML Kit / TFLite）。
 *
 * <h2>为什么不用「找连通域 → 要求闭合轮廓」这条常规路</h2>
 * 那套做法默认输入是干净的：轮廓连续、四角完整。而推流端细蓝线经过 HEVC + 4:2:0 色度子采样后
 * 会<b>断线、掉色、缺角</b>，还经常和旁边的 {@code id:xxxx} 文字<b>连成一体</b> ——
 * 一旦要求闭合 contour，这些情况全都会漏检，而它们恰恰是真实画面的常态。
 *
 * <h2>本实现的路子：行/列投影取"边带" + 互相配对</h2>
 * <ol>
 *   <li><b>投影</b>：统计每行 / 每列的蓝像素数（列统计在<b>转置掩码</b>上做，
 *       于是两个方向共用同一段代码，不必写两套扫描）。</li>
 *   <li><b>取边带</b>：把"连续若干行、每行蓝像素数都超过阈值"的区间取成一条<b>水平边带</b>；
 *       垂直边带同理。阈值来自
 *       {@code minBoxWidth/minBoxHeight × minEdgeCoverage}，不是拍脑袋的常数。
 *       边带的空间范围只统计<b>本方向的长度超过线宽上限</b>的那些段 ——
 *       横穿它的另一条框线只留下很短的一段，不能算成它的范围（见 {@code extractBands}）。</li>
 *   <li><b>细线判据</b>：边带厚度超过 {@code maxEdgeThicknessPx} 的直接丢掉 ——
 *       这一步把"一整块蓝色物体"（蓝色车身、蓝屏、天空）和"几像素宽的框线"区分开。
 *       实心色块在投影上表现为几十上百行都满覆盖，形成的边带极厚，一击即中。
 *       另一条判据是实心度（{@code minEdgeSolidity}）：跨度范围内像素太稀疏就不算线。</li>
 *   <li><b>配对</b>：两条水平边带配出上/下（横跨范围取交集），两条垂直边带配出左/右
 *       （纵向范围取交集）。<b>两个方向都做</b>，因为断裂通常只破坏其中一个方向：
 *       上边线断成两截时水平配对会出错，但左右两条竖线还在，垂直配对依然能给出正确的框。</li>
 *   <li><b>打分与过滤</b>：四条边各算蓝色覆盖率 → {@code completeness} =
 *       四边均值，{@code confidence} = {@code completeness × (达标边数/4)}；
 *       再按尺寸、长宽比、达标边数、最低置信度过滤。</li>
 *   <li><b>去重与限量</b>：两个方向会给出同一个框的近似结果，按 IoU 去重；
 *       候选总数封顶 {@code maxCandidateCount}，防异常掩码组合爆炸。</li>
 * </ol>
 *
 * <p><b>覆盖率用"窗口内去重列数"而不是"蓝像素数除以边长"</b>：后者在粗线上会得出
 * 大于 1 的值（一条 4px 粗的边每列有 4 个蓝像素），必须再除以厚度才勉强对；
 * 前者问的是"这条边上每一个位置<b>是否</b>有蓝"，天生落在 [0,1]，而且对厚度不敏感。</p>
 *
 * <p><b>耗时</b>：所有扫描都只用 {@code runs()}（行内连续段），复杂度与<b>段数</b>而非
 * 像素数同阶；覆盖率计算只在通过尺寸过滤的少量候选上做，窗口高度被
 * {@code maxEdgeThicknessPx} 卡死。因此不存在 O(宽×高×候选数) 的实现。</p>
 *
 * <p><b>不做的事（本 Slice 的边界）</b>：不识别 {@code id:xxxx}、不做复杂重叠消歧、
 * 不看整帧（只吃 Slice 4 的 ROI 掩码）。</p>
 *
 * <p>线程约定：{@link #detect} 是 {@code synchronized} 的（内部复用扫描缓冲，避免每次点击
 * 都重新分配几十 KB），可被多线程调用；单次调用应完整跑在一个工作线程上。</p>
 */
public final class TargetBoxDetector {

    /**
     * 判定两个候选"其实是同一个框"的 IoU 阈值。
     *
     * <p>为什么放在算法内部而不是配置里：这是<b>实现细节</b>（水平配对与垂直配对给出的
     * 同一个框天然只差取整误差，IoU 通常 &gt; 0.95），不是需要现场调的感知阈值。
     * 真正需要按画面调的是尺寸、覆盖率、置信度那几项，它们都在
     * {@link TargetSelectionConfig} 里。</p>
     */
    private static final float DUPLICATE_IOU = 0.7f;

    /** 候选排序：置信度降序 → 面积升序 → 左上角优先。最后两项只为让结果<b>确定</b>。 */
    private static final Comparator<DetectedTargetBox> BY_QUALITY = (a, b) -> {
        int result = Float.compare(b.confidence, a.confidence);
        if (result != 0) {
            return result;
        }
        result = Float.compare(a.videoArea(), b.videoArea());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.rectInRoi.top, b.rectInRoi.top);
        if (result != 0) {
            return result;
        }
        return Integer.compare(a.rectInRoi.left, b.rectInRoi.left);
    };

    private final TargetSelectionConfig config;

    /** 转置掩码的复用缓冲（与 {@link #detect} 的同步保持一致）。 */
    private final BitSet transposeBits = new BitSet();

    /** 覆盖率去重用的"戳"数组：省掉每次清零，见 {@link Analysis#coveredColumns}。 */
    private int[] marks = new int[0];
    private int markStamp;

    /**
     * @param config 默认策略，非 null；尺寸/覆盖率/厚度/置信度/候选上限都从它读取
     * @throws IllegalArgumentException config 为 null
     */
    public TargetBoxDetector(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    /**
     * 从一次点击的蓝色掩码里恢复候选目标框。
     *
     * @param maskResult Slice 4 的产物，可为 null（→ {@link TargetBoxDetectionStatus#INVALID_INPUT}）
     * @return 非 null 的检测结果；本方法只填检测部分，命中部分由
     *         {@link TargetBoxDetectionResult#withHitTest} 后续挂上
     */
    public synchronized TargetBoxDetectionResult detect(BlueMaskResult maskResult) {
        final long startNs = System.nanoTime();

        if (maskResult == null) {
            return TargetBoxDetectionResult.failure(
                    TargetBoxDetectionStatus.INVALID_INPUT, "mask result is null");
        }
        if (!maskResult.hasRoi()) {
            return TargetBoxDetectionResult.failure(TargetBoxDetectionStatus.INVALID_MASK,
                    "no usable roi: status=" + maskResult.status
                            + (maskResult.debugReason == null ? "" : " reason=" + maskResult.debugReason));
        }
        final BinaryMask mask = maskResult.mask;
        final int roiWidth = maskResult.roiWidth;
        final int roiHeight = maskResult.roiHeight;
        final TargetBoundingBox roiVideo = maskResult.roiRectInVideo;
        if (mask == null || roiWidth <= 0 || roiHeight <= 0 || roiVideo == null
                || roiVideo.width() <= 0f || roiVideo.height() <= 0f) {
            return TargetBoxDetectionResult.failure(TargetBoxDetectionStatus.INVALID_MASK,
                    "degenerate roi or mask: roi=" + roiWidth + "x" + roiHeight
                            + " mask=" + (mask == null ? "null" : mask.width() + "x" + mask.height()));
        }
        // 掩码必须就是这个 ROI 的产物。不一致说明上游把两个尺度的东西配到了一起 ——
        // 这种错误不会崩，只会让所有坐标静默错位，所以在这里硬性拦住。
        if (mask.width() != roiWidth || mask.height() != roiHeight) {
            return TargetBoxDetectionResult.failure(TargetBoxDetectionStatus.INVALID_MASK,
                    "mask size " + mask.width() + "x" + mask.height()
                            + " does not match roi " + roiWidth + "x" + roiHeight);
        }
        if (mask.isEmpty()) {
            return TargetBoxDetectionResult.detected(Collections.emptyList(), elapsedMs(startNs));
        }

        final List<DetectedTargetBox> found = new Analysis(mask, roiWidth, roiHeight, roiVideo).run();
        return TargetBoxDetectionResult.detected(found, elapsedMs(startNs));
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 一次检测的全部派生状态

    /**
     * 把一次检测需要的派生量（两套索引、两套阈值、覆盖率缓冲）收在一起，
     * 免得十几个参数在每个 helper 之间传来传去。
     */
    private final class Analysis {

        private final BinaryMask mask;
        private final int roiWidth;
        private final int roiHeight;
        private final TargetBoundingBox roiVideo;

        /** 原掩码的按行索引（用于水平边）。 */
        private final RunIndex rows;

        /** 转置掩码的按行索引 —— 转置后"行"就是原掩码的"列"，用于垂直边。 */
        private final RunIndex cols;

        /** 帧像素 → 视频像素。 */
        private final float scaleX;
        private final float scaleY;

        /** 最小框边长在 ROI 局部尺度下的值（用于边带阈值与配对下限）。 */
        private final int localMinWidth;
        private final int localMinHeight;

        /** 边带厚度上限（转成局部尺度）：水平边看行数，垂直边看列数。 */
        private final int maxEdgeRows;
        private final int maxEdgeCols;

        Analysis(BinaryMask mask, int roiWidth, int roiHeight, TargetBoundingBox roiVideo) {
            this.mask = mask;
            this.roiWidth = roiWidth;
            this.roiHeight = roiHeight;
            this.roiVideo = roiVideo;
            this.scaleX = roiVideo.width() / (float) roiWidth;
            this.scaleY = roiVideo.height() / (float) roiHeight;
            this.localMinWidth = Math.max(1, Math.round(config.minBoxWidth / scaleX));
            this.localMinHeight = Math.max(1, Math.round(config.minBoxHeight / scaleY));
            this.maxEdgeRows = Math.max(1, Math.round(config.maxEdgeThicknessPx / scaleY));
            this.maxEdgeCols = Math.max(1, Math.round(config.maxEdgeThicknessPx / scaleX));
            this.rows = new RunIndex(mask);
            this.cols = new RunIndex(transpose(mask));
            ensureMarks(Math.max(roiWidth, roiHeight));
        }

        List<DetectedTargetBox> run() {
            // 边带数量上限直接复用候选上限：两者都是"防异常掩码"的同一件事
            final int bandLimit = Math.max(2, config.maxCandidateCount);
            final int horizontalMin = Math.max(2, Math.round(localMinWidth * config.minEdgeCoverage));
            final int verticalMin = Math.max(2, Math.round(localMinHeight * config.minEdgeCoverage));

            final List<Band> horizontal = extractBands(rows, horizontalMin, maxEdgeRows, bandLimit);
            final List<Band> vertical = extractBands(cols, verticalMin, maxEdgeCols, bandLimit);

            final List<DetectedTargetBox> found = new ArrayList<>();
            collectPairs(horizontal, true, found);
            collectPairs(vertical, false, found);

            Collections.sort(found, BY_QUALITY);
            final List<DetectedTargetBox> kept = new ArrayList<>();
            for (DetectedTargetBox candidate : found) {
                if (kept.size() >= config.maxCandidateCount) {
                    break;
                }
                if (!isDuplicateOfAny(candidate, kept)) {
                    kept.add(candidate);
                }
            }
            return kept;
        }

        // ------------------------------------------------------------ 边带提取

        /**
         * 取"连续若干行、每行蓝像素数都 &ge; {@code minCount}"的区间作为一条边带。
         *
         * <p>边带的空间范围取<b>该边自己的像素</b>的并集（"够长的段"，见下），因此一条中间
         * 断裂的边线仍然是一条完整边带 —— 断裂只体现在覆盖率上，不体现在几何上。</p>
         *
         * <p><b>为什么跨度只统计"够长的段"（关键）</b>：一行里出现的蓝像素不全是这条边的 ——
         * 任何<b>横穿</b>它的另一条框线也会在这一行留下几个像素，而那些像素在本方向上的长度
         * 约等于对方那条线的<b>线宽</b>（&le; {@code maxThickness}）。把它们算进跨度，会让本边
         * 的外接盒被撑到"跨到对面那条线"那么宽，实心度随之腰斩：
         * 「大框套小框」时内层框的边就是这样被误判成假边丢掉的（实测 110px 的真边，
         * 跨度被外框的两条竖边撑成 270px，实心度 1.0 → 0.44 &lt; {@code minEdgeSolidity}），
         * 于是内层框永远不会成为候选，用户点它只会选中外面的大框。
         * 只计入"比最粗允许线宽还长"的段以后，跨度还原成"这条边自己的范围"，
         * 实心度也就回到了它本来的含义：<b>这条边的范围内有多实</b>（挡住点状/虚线的假边）。</p>
         */
        private List<Band> extractBands(RunIndex index, int minCount, int maxThickness, int limit) {
            final int height = index.mask.height();
            final int[] counts = new int[height];
            for (int i = 0; i < index.runs.length; i += 3) {
                counts[index.runs[i]] += index.runs[i + 2] - index.runs[i + 1] + 1;
            }

            // 横穿本方向的另一条框线，其"段长"约等于它自己的线宽；超过线宽上限的段
            // 不可能是横穿线留下的，才算这条边自己的像素。
            final int minSpanRun = maxThickness + 1;

            final List<Band> bands = new ArrayList<>();
            int y = 0;
            while (y < height) {
                if (counts[y] < minCount) {
                    y++;
                    continue;
                }
                final Band band = new Band();
                band.start = y;
                band.spanStart = Integer.MAX_VALUE;
                band.spanEnd = Integer.MIN_VALUE;
                while (y < height && counts[y] >= minCount) {
                    for (int r = index.rowHead[y]; r < index.rowHead[y + 1]; r++) {
                        final int start = index.runs[r * 3 + 1];
                        final int end = index.runs[r * 3 + 2];
                        if (end - start + 1 < minSpanRun) {
                            continue;   // 横穿线留下的短段：不参与跨度，也不参与实心度
                        }
                        if (start < band.spanStart) {
                            band.spanStart = start;
                        }
                        if (end > band.spanEnd) {
                            band.spanEnd = end;
                        }
                        band.pixelCount += end - start + 1;
                    }
                    y++;
                }
                band.end = y - 1;
                // 一整条边带里没有一段"够长的段"：它不是一个线状结构（例如一排点、
                // 或纯粹由横穿线拼出来的行），直接丢掉，避免后面用未初始化的跨度算实心度。
                if (band.spanStart == Integer.MAX_VALUE) {
                    continue;
                }
                // 太厚 = 实心色块，不是细线框。这是区分"蓝色物体"与"蓝色框"的关键一步。
                if (band.thickness() > maxThickness) {
                    continue;
                }
                // 太不实心 = 这一段在自身范围内是稀疏的（点状、虚线、断续的碎片），
                // 不是一条连续的线。
                if (band.solidity() < config.minEdgeSolidity) {
                    continue;
                }
                bands.add(band);
            }

            if (bands.size() > limit) {
                Collections.sort(bands, (a, b) -> Integer.compare(b.pixelCount, a.pixelCount));
                return new ArrayList<>(bands.subList(0, limit));
            }
            return bands;
        }

        // ------------------------------------------------------------ 配对

        /**
         * 两两配对同类边带生成候选框。
         *
         * @param horizontal true = 这批是水平边带（配出上下边），false = 垂直边带（配出左右边）
         */
        private void collectPairs(List<Band> bands, boolean horizontal, List<DetectedTargetBox> out) {
            for (int i = 0; i < bands.size(); i++) {
                for (int j = i + 1; j < bands.size(); j++) {
                    final Band first = bands.get(i);
                    final Band second = bands.get(j);
                    // 必须真正分离：两条边带重叠在同一批行/列上，说明是同一条线被拆成了两段
                    if (first.end >= second.start) {
                        continue;
                    }
                    final int overlapStart = Math.max(first.spanStart, second.spanStart);
                    final int overlapEnd = Math.min(first.spanEnd, second.spanEnd);
                    if (overlapEnd < overlapStart) {
                        continue;
                    }
                    final int overlap = overlapEnd - overlapStart + 1;
                    // 两条边必须"认同"同一段范围：重叠部分要占到较长那条的 minEdgeCoverage。
                    // 少了这一步，一条断成两截的长边会与另一条完整边配出若干个小框。
                    final int longer = Math.max(first.spanLength(), second.spanLength());
                    if (overlap < Math.round(longer * config.minEdgeCoverage)) {
                        continue;
                    }
                    final int minSpan = horizontal ? localMinWidth : localMinHeight;
                    if (overlap < minSpan) {
                        continue;
                    }
                    final RoiRect rect = horizontal
                            ? RoiRect.of(overlapStart, first.start, overlapEnd, second.end)
                            : RoiRect.of(first.start, overlapStart, second.end, overlapEnd);
                    addCandidate(rect, out);
                }
            }
        }

        /** 尺寸 / 长宽比 / 边覆盖率 / 置信度四道过滤，全过才成为候选。 */
        private void addCandidate(RoiRect rect, List<DetectedTargetBox> out) {
            // 1) 尺寸：一律用视频像素判断，避免"局部取整 → 再换算"的二次误差
            final float videoWidth = rect.width() * scaleX;
            final float videoHeight = rect.height() * scaleY;
            if (videoWidth < config.minBoxWidth || videoHeight < config.minBoxHeight) {
                return;
            }
            if (config.maxBoxWidth != TargetSelectionConfig.NO_BOX_SIZE_LIMIT
                    && videoWidth > config.maxBoxWidth) {
                return;
            }
            if (config.maxBoxHeight != TargetSelectionConfig.NO_BOX_SIZE_LIMIT
                    && videoHeight > config.maxBoxHeight) {
                return;
            }
            final float aspect = Math.max(videoWidth, videoHeight) / Math.min(videoWidth, videoHeight);
            if (aspect > config.maxBoxAspectRatio) {
                return;
            }

            // 2) 四条边的蓝色覆盖率
            final float topCoverage = horizontalCoverage(
                    rect.top, Math.min(rect.top + maxEdgeRows - 1, rect.bottom), rect.left, rect.right);
            final float bottomCoverage = horizontalCoverage(
                    Math.max(rect.bottom - maxEdgeRows + 1, rect.top), rect.bottom, rect.left, rect.right);
            final float leftCoverage = verticalCoverage(
                    rect.left, Math.min(rect.left + maxEdgeCols - 1, rect.right), rect.top, rect.bottom);
            final float rightCoverage = verticalCoverage(
                    Math.max(rect.right - maxEdgeCols + 1, rect.left), rect.right, rect.top, rect.bottom);

            int presentEdges = 0;
            if (topCoverage >= config.minEdgeCoverage) {
                presentEdges++;
            }
            if (bottomCoverage >= config.minEdgeCoverage) {
                presentEdges++;
            }
            if (leftCoverage >= config.minEdgeCoverage) {
                presentEdges++;
            }
            if (rightCoverage >= config.minEdgeCoverage) {
                presentEdges++;
            }
            if (presentEdges < config.minPresentEdges) {
                return;
            }

            final float completeness = (topCoverage + bottomCoverage + leftCoverage + rightCoverage) * 0.25f;
            final float confidence = completeness * (presentEdges / 4f);
            if (confidence < config.minBoxConfidence) {
                return;
            }

            out.add(DetectedTargetBox.of(rect, rect.toVideoRect(roiVideo, roiWidth, roiHeight),
                    confidence, completeness, presentEdges,
                    rect.touchesBoundary(roiWidth, roiHeight)));
        }

        // ------------------------------------------------------------ 覆盖率

        /** 水平边覆盖率：在 rows∈[y0,y1] × cols∈[x0,x1] 里，有多少比例的列存在蓝像素。 */
        private float horizontalCoverage(int y0, int y1, int x0, int x1) {
            final int height = rows.mask.height();
            final int width = rows.mask.width();
            final int cy0 = clamp(y0, 0, height - 1);
            final int cy1 = clamp(y1, cy0, height - 1);
            final int cx0 = clamp(x0, 0, width - 1);
            final int cx1 = clamp(x1, cx0, width - 1);
            return coveredColumns(rows, cy0, cy1, cx0, cx1) / (float) (cx1 - cx0 + 1);
        }

        /**
         * 垂直边覆盖率。转置掩码把"原掩码的列"变成了"自己的行"，
         * 于是这里只是把坐标对调后复用同一段扫描 —— 不必写第二套垂直扫描。
         */
        private float verticalCoverage(int x0, int x1, int y0, int y1) {
            final int height = cols.mask.height();
            final int width = cols.mask.width();
            final int cy0 = clamp(x0, 0, height - 1);
            final int cy1 = clamp(x1, cy0, height - 1);
            final int cx0 = clamp(y0, 0, width - 1);
            final int cx1 = clamp(y1, cx0, width - 1);
            return coveredColumns(cols, cy0, cy1, cx0, cx1) / (float) (cx1 - cx0 + 1);
        }

        /**
         * 统计 rows∈[y0,y1] × cols∈[x0,x1] 里"至少有一个蓝像素的列数"。
         *
         * <p>利用 {@code marks} 数组 + 单调递增的"戳"来去重：命中过的列标记成当前戳，
         * 于是<b>不需要每次清零</b>（清零本身是 O(宽度)，在候选多的时候反而是主要开销）。
         * 戳只在溢出时才整体重置，实际不可能触发。</p>
         */
        private int coveredColumns(RunIndex index, int y0, int y1, int x0, int x1) {
            final int stamp = nextStamp();
            final int[] marksLocal = marks;
            int covered = 0;
            for (int y = y0; y <= y1; y++) {
                for (int r = index.rowHead[y]; r < index.rowHead[y + 1]; r++) {
                    final int start = Math.max(index.runs[r * 3 + 1], x0);
                    final int end = Math.min(index.runs[r * 3 + 2], x1);
                    for (int x = start; x <= end; x++) {
                        if (marksLocal[x] != stamp) {
                            marksLocal[x] = stamp;
                            covered++;
                        }
                    }
                }
            }
            return covered;
        }

        private int nextStamp() {
            if (++markStamp == Integer.MAX_VALUE) {
                java.util.Arrays.fill(marks, 0);
                markStamp = 1;
            }
            return markStamp;
        }

        private boolean isDuplicateOfAny(DetectedTargetBox candidate, List<DetectedTargetBox> kept) {
            for (int i = 0; i < kept.size(); i++) {
                if (iou(candidate.rectInRoi, kept.get(i).rectInRoi) >= DUPLICATE_IOU) {
                    return true;
                }
            }
            return false;
        }

        private int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            return value > max ? max : value;
        }
    }

    // ------------------------------------------------------------------ 辅助类型

    /** 一条边带：{@code [start, end]} 是它在本方向"行"轴上的范围，{@code [spanStart, spanEnd]} 是另一轴上的并集范围。 */
    private static final class Band {
        int start;
        int end;
        int spanStart;
        int spanEnd;
        int pixelCount;

        int thickness() {
            return end - start + 1;
        }

        int spanLength() {
            return spanEnd - spanStart + 1;
        }

        /**
         * 实心度 = "这条边自己的像素数" / 边带外接盒面积，落在 (0,1]。
         *
         * <p>真实的一段框线是<b>实心</b>的，实心度≈1（一条 4px 线就是 4×长度 个像素）。
         * 反过来，若这个范围内只有稀稀落落的点（点状噪声、虚线碎片），外接盒里绝大部分
         * 是空的，实心度会明显低于 0.5 —— 这正是本判据要挡的东西。</p>
         *
         * <p><b>它挡不住、也不该由它挡的东西</b>：横穿这条边的另一条框线留下的几个像素。
         * 那些像素在<b>本方向</b>上只是很短的一段，会让跨度虚增、实心度无缘无故腰斩 ——
         * 但本方向的长度本来就分辨不出"这是我自己的边"还是"别人路过"。
         * 这件事已经由 {@code extractBands} 在统计跨度时就处理掉了（只计入够长的段），
         * 所以到这里时 {@link #pixelCount} 与 {@link #spanLength()} 描述的都是<b>同一条边</b>。
         * 若没有那一步，「大框套小框」里内层框那条 110px 的真边会被算成
         * 4×270 的外接盒、实心度 0.44，从而被误当成假边丢掉。</p>
         */
        float solidity() {
            final int box = thickness() * spanLength();
            return box <= 0 ? 0f : pixelCount / (float) box;
        }
    }

    /** 把掩码的行内连续段按行建索引，让"某行的段"可以 O(1) 取到（而不是每次线性扫描 runs）。 */
    private static final class RunIndex {
        final BinaryMask mask;
        final int[] runs;
        final int[] rowHead;

        RunIndex(BinaryMask mask) {
            this.mask = mask;
            this.runs = mask.runs();
            final int height = mask.height();
            this.rowHead = new int[height + 1];
            int run = 0;
            for (int y = 0; y < height; y++) {
                rowHead[y] = run;
                while (run * 3 < runs.length && runs[run * 3] == y) {
                    run++;
                }
            }
            rowHead[height] = run;
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 掩码转置：位 {@code (x, y)} → {@code (y, x)}。
     *
     * <p>代价只与<b>蓝像素数</b>同阶（遍历 {@code runs()} 里的段，而不是整张掩码），
     * 一条 400×400 ROI 的框线只有几千个蓝像素，转置是微秒级。</p>
     */
    private BinaryMask transpose(BinaryMask mask) {
        final int width = mask.width();
        final int height = mask.height();
        transposeBits.clear();
        final int[] runs = mask.runs();
        for (int i = 0; i < runs.length; i += 3) {
            final int y = runs[i];
            final int start = runs[i + 1];
            final int end = runs[i + 2];
            for (int x = start; x <= end; x++) {
                transposeBits.set(x * height + y);
            }
        }
        return BinaryMask.of(transposeBits, height, width);
    }

    private void ensureMarks(int size) {
        if (marks.length < size) {
            marks = new int[size];
            markStamp = 0;
        }
    }

    private static float iou(RoiRect a, RoiRect b) {
        final int left = Math.max(a.left, b.left);
        final int right = Math.min(a.right, b.right);
        final int top = Math.max(a.top, b.top);
        final int bottom = Math.min(a.bottom, b.bottom);
        if (right < left || bottom < top) {
            return 0f;
        }
        final long intersection = (long) (right - left + 1) * (bottom - top + 1);
        final long union = a.area() + b.area() - intersection;
        return union <= 0L ? 0f : intersection / (float) union;
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }
}
