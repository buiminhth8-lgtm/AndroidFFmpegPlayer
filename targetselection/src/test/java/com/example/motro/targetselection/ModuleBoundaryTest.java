package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 模块边界测试 —— 把架构约束钉成可执行的断言，防止后续被顺手破坏：
 *
 * <ol>
 *   <li>targetselection 源码不得依赖 ffmpegplayer 包</li>
 *   <li>targetselection 的 dependencies 不得引用 :ffmpegplayer</li>
 *   <li>targetselection 不得引入 OpenCV / ML Kit / TFLite 等识别依赖</li>
 *   <li>ffmpegplayer 不得反向依赖 targetselection</li>
 *   <li>settings.gradle / app 的接线正确</li>
 * </ol>
 *
 * <p>注意：只检查 **包路径与 dependencies 块**，不检查注释散文 —— 注释里提到
 * “FFmpegPlayer”“OpenCV”只是说明复用场景，不构成依赖。</p>
 */
public class ModuleBoundaryTest {

    private static final String MODULE_PACKAGE = "com.example.motro.targetselection";
    private static final String FFMPEG_PACKAGE = "com.example.motro.ffmpeg";

    @Test
    public void targetSelectionSourcesDoNotDependOnFfmpegPlayerPackage() throws IOException {
        List<File> sources = javaSourcesIn(moduleDir("targetselection") + "/src/main/java");
        assertTrue("未找到任何源文件，说明目录定位失败", sources.size() >= 10);

        List<String> offenders = new ArrayList<>();
        for (File source : sources) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.contains(FFMPEG_PACKAGE)) {
                    offenders.add(source.getName() + ":" + lineNumber + " -> " + line.trim());
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("targetselection 源码不得引用 " + FFMPEG_PACKAGE + "，但发现：\n" + String.join("\n", offenders));
        }
    }

    @Test
    public void targetSelectionDependenciesAreFreeOfPlayerAndVisionLibraries() throws IOException {
        String deps = dependenciesBlock(read(new File(moduleDir("targetselection"), "build.gradle")));
        String lower = deps.toLowerCase(Locale.ROOT);

        assertNoDependency(deps, "ffmpegplayer");
        assertNoDependency(lower, "opencv");
        assertNoDependency(lower, "mlkit");
        assertNoDependency(lower, "ml-kit");
        assertNoDependency(lower, "tensorflow");
        assertNoDependency(lower, "tflite");
        assertNoDependency(lower, "tflite-gpu");
        assertTrue("JUnit 必须保留，否则本测试自身跑不起来", lower.contains("junit"));
    }

    @Test
    public void ffmpegPlayerNeverDependsOnTargetSelection() throws IOException {
        String deps = dependenciesBlock(read(new File(moduleDir("ffmpegplayer"), "build.gradle")));
        assertNoDependency(deps, "targetselection");

        List<File> sources = javaSourcesIn(moduleDir("ffmpegplayer") + "/src/main/java");
        List<String> offenders = new ArrayList<>();
        for (File source : sources) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.contains(MODULE_PACKAGE)) {
                    offenders.add(source.getName() + ":" + lineNumber + " -> " + line.trim());
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("ffmpegplayer 不得反向依赖 " + MODULE_PACKAGE + "，但发现：\n" + String.join("\n", offenders));
        }
    }

    @Test
    public void settingsGradleIncludesTheModule() throws IOException {
        String settings = read(new File(projectRoot(), "settings.gradle"));
        assertTrue("settings.gradle 必须 include ':targetselection'",
                settings.contains(":targetselection"));
    }

    @Test
    public void appModuleIsWiredToTargetSelection() throws IOException {
        String deps = dependenciesBlock(read(new File(projectRoot(), "app/build.gradle")));
        assertTrue("app 需要接入 :targetselection（组装职责在 app 层）",
                deps.contains(":targetselection"));
        assertTrue("app 仍需保留 :ffmpegplayer", deps.contains(":ffmpegplayer"));
    }

    /**
     * 取帧实现不得引用任何具体的解码/原生帧类型 —— 这正是「播放器无关」的判据：
     * 一旦某个实现开始引用它们，复用就断了（换个播放器就得改这个模块）。
     *
     * <p>检查范围刻意收窄到「真实代码」：{@code android.media.} 这类全限定串只可能出现在
     * 代码里；原生帧类型只可能出现在 import 里。这样注释中“不得暴露 AVFrame”这类
     * 边界声明不会被误判成违规（否则测试会逼着人删掉有价值的文档）。</p>
     */
    @Test
    public void frameSourcesDoNotReferenceNativeOrCodecFrameTypes() throws IOException {
        File frameDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/frame");
        List<File> sources = javaSourcesIn(frameDir.toString());
        assertTrue("未找到 frame 包源码", sources.size() >= 8);

        String[] forbiddenImports = {"avcodec", "avformat", "avutil", "AVFrame", "libav", "SurfaceTexture"};
        List<String> offenders = new ArrayList<>();
        for (File source : sources) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.contains("android.media.")) {
                    offenders.add(source.getName() + ":" + lineNumber + " -> 引用 android.media.* " + line.trim());
                }
                if (!line.trim().startsWith("import ")) {
                    continue;
                }
                for (String needle : forbiddenImports) {
                    if (line.contains(needle)) {
                        offenders.add(source.getName() + ":" + lineNumber
                                + " -> 引入原生帧类型 [" + needle + "] " + line.trim());
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("取帧实现不得引用解码/原生帧类型，但发现：\n" + String.join("\n", offenders));
        }
    }

    /** 两个通用取帧实现必须各自只认自己那种 View，别粘在一起。 */
    @Test
    public void eachViewFrameSourceStaysBoundToOneViewType() throws IOException {
        File frameDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/frame");

        String surfaceSource = read(new File(frameDir, "SurfaceViewFrameSource.java"));
        assertTrue("SurfaceView 实现不得依赖 TextureView",
                !surfaceSource.contains("android.view.TextureView"));

        String textureSource = read(new File(frameDir, "TextureViewFrameSource.java"));
        assertTrue("TextureView 实现不得依赖 SurfaceView / Surface / SurfaceHolder",
                !textureSource.contains("android.view.SurfaceView")
                        && !textureSource.contains("android.view.SurfaceHolder")
                        && !textureSource.contains("android.view.Surface;"));
    }

    /** 两个通用取帧实现必须共用同一个 API 契约，否则 app 层换承载 View 时要改调用代码。 */
    @Test
    public void bothViewFrameSourcesShareTheSameContract() throws IOException {
        File frameDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/frame");
        for (String name : new String[] {"SurfaceViewFrameSource.java", "TextureViewFrameSource.java"}) {
            String source = read(new File(frameDir, name));
            assertTrue(name + " 必须继承共享骨架 AbstractFrameSource",
                    source.contains("extends AbstractFrameSource"));
        }
        String base = read(new File(frameDir, "AbstractFrameSource.java"));
        assertTrue("共享骨架必须实现 VideoFrameSource", base.contains("implements VideoFrameSource"));
    }

    /**
     * 坐标换算公式只允许存在一份 —— {@code VideoCoordinateMapper}。
     *
     * <p>判据取「旋转反解开关」：{@code case 90:} 与 {@code case 270:} 必须同时出现在
     * 同一个文件里，且只能有一个这样的文件。若哪天有人在别处再写一份坐标公式，
     * 这里会立刻红掉 —— 两份公式最典型的坏结果是 rotation / mirror 顺序只在一份里写对。</p>
     */
    @Test
    public void coordinateFormulaLivesOnlyInTheMapper() throws IOException {
        File geometryDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/geometry");

        List<String> holders = new ArrayList<>();
        for (File source : javaSourcesIn(geometryDir.toString())) {
            String text = read(source);
            if (text.contains("case 90:") && text.contains("case 270:")) {
                holders.add(source.getName());
            }
        }
        assertEquals("旋转反解开关只应出现在 VideoCoordinateMapper，实际：" + holders,
                Collections.singletonList("VideoCoordinateMapper.java"), holders);

        // VideoGeometry 只做委托，不再自持公式
        String geometry = read(new File(geometryDir, "VideoGeometry.java"));
        assertTrue("VideoGeometry 必须把坐标系换算委托给 VideoCoordinateMapper.map(...)",
                geometry.contains("VideoCoordinateMapper.map("));
        assertFalse("VideoGeometry 不得再自带一份旋转反解实现",
                geometry.contains("case 90:") || geometry.contains("case 270:"));
    }

    /** 控制器必须走同一个 mapper（不许另抄一份映射），否则两条路径的语义会分叉。 */
    @Test
    public void controllerDelegatesMappingToTheMapper() throws IOException {
        String controller = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/api/TargetSelectionController.java"));
        assertTrue("控制器必须调用 VideoCoordinateMapper.map(...)",
                controller.contains("VideoCoordinateMapper.map("));
        assertFalse("控制器不得绕过 mapper 自行反解坐标",
                controller.contains("case 90:") || controller.contains("case 270:"));
    }

    /**
     * 调试可视化层不得自带坐标公式 —— 这是本 Slice 最容易踩的坑。
     *
     * <p>叠加层要把「映射后的视频点」画出来，直觉做法是在里面写
     * {@code viewX = videoX * getWidth() / videoWidth}。一旦这么写，调试层就和正式映射算法
     * 变成两套逻辑：调试图"看起来对"而实际命中判定是错的（黑边、旋转、镜像任一项都会让它偏），
     * 而调试图的全部价值恰恰是"帮我判断正式算法对不对" —— 逻辑一分为二，这个价值就没了。
     * 所以这里断言：视频坐标必须经 {@link com.example.motro.targetselection.geometry.VideoCoordinateMapper}
     * 正推回 View，debug 包里不许出现任何旋转开关或按 View 尺寸的缩放式。</p>
     */
    @Test
    public void debugOverlayNeverReimplementsTheCoordinateFormula() throws IOException {
        File debugDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/debug");
        List<File> sources = javaSourcesIn(debugDir.toString());
        assertTrue("未找到 debug 包源码", sources.size() >= 2);

        for (File source : sources) {
            String text = read(source);
            assertFalse(source.getName() + " 不得自带旋转公式（只允许 VideoCoordinateMapper 持有）",
                    text.contains("case 90:") || text.contains("case 270:"));
            assertFalse(source.getName() + " 不得直接按 View 尺寸缩放视频坐标",
                    text.contains("* viewWidth") || text.contains("/ viewWidth")
                            || text.contains("* viewHeight") || text.contains("/ viewHeight"));
        }

        String overlay = read(new File(debugDir, "TargetDebugOverlayView.java"));
        assertTrue("叠加层必须经 VideoCoordinateMapper.mapVideoToView(...) 正推回 View 坐标",
                overlay.contains("VideoCoordinateMapper.mapVideoToView("));
        assertFalse("叠加层不该自行反解（反解只属于点击路径）",
                overlay.contains("VideoCoordinateMapper.map("));
        assertFalse("调试层不得持有 Bitmap（只画矢量标记）",
                overlay.contains("import android.graphics.Bitmap"));

        String state = read(new File(debugDir, "TargetDebugState.java"));
        assertTrue("状态对象也必须经 mapper 做回投影", state.contains("VideoCoordinateMapper.mapVideoToView("));
        assertFalse("TargetDebugState 必须保持纯 JVM（否则状态推导无法单测）",
                state.contains("import android."));
    }

    /**
     * 视觉层必须保持<b>纯 JVM</b>：ROI 裁剪与颜色分析是这个模块里最需要被穷举测试的部分
     * （边界裁剪、阈值、去噪），一旦引用 {@code android.graphics} 就只能靠真机验证，
     * 覆盖率和回归速度都会塌掉。像素经 {@code FrameImage} 抽象进来，
     * 因此这里可以断言"vision 包一行 android 都不引"。
     */
    @Test
    public void visionLayerStaysPureJvm() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        List<File> sources = javaSourcesIn(visionDir.toString());
        assertTrue("未找到 vision 包源码", sources.size() >= 8);

        List<String> offenders = new ArrayList<>();
        for (File source : sources) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.trim().startsWith("import android.")) {
                    offenders.add(source.getName() + ":" + lineNumber + " -> " + line.trim());
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("vision 包必须保持纯 JVM（不得 import android.*），但发现：\n"
                    + String.join("\n", offenders));
        }
    }

    /**
     * 像素回读只允许有一条落点：{@link com.example.motro.targetselection.frame.BitmapFrameImage}
     * 里的 {@code Bitmap.getPixels}。
     *
     * <p>判据取 {@code getPixels} 这个平台 API 名 —— 它只可能出现在真实代码里。
     * 一旦有人在别处再写一次逐像素回读，就会出现第二份缓冲管理/回收责任，
     * 而"谁负责 recycle"正是这类代码最容易泄漏的地方。</p>
     */
    @Test
    public void pixelReadoutHasASinglePlatformCallSite() throws IOException {
        List<File> sources = javaSourcesIn(moduleDir("targetselection") + "/src/main/java");
        List<String> holders = new ArrayList<>();
        for (File source : sources) {
            if (read(source).contains("getPixels(")) {
                holders.add(source.getName());
            }
        }
        assertEquals("Bitmap.getPixels 只应出现在 BitmapFrameImage",
                Collections.singletonList("BitmapFrameImage.java"), holders);

        // 抽象层必须显式声明像素回读契约，否则 ROI 裁剪只能靠 instanceof 猜
        String frameImage = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/frame/FrameImage.java"));
        assertTrue("FrameImage 必须声明 readArgbRegion 契约",
                frameImage.contains("readArgbRegion"));

        String extractor = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision/TouchRoiExtractor.java"));
        assertTrue("ROI 裁剪必须经 FrameImage.readArgbRegion 取像素",
                extractor.contains("readArgbRegion"));
    }

    /**
     * 蓝色阈值必须全部来自 {@code TargetSelectionConfig}，不许在算法里留下实验魔数。
     *
     * <p>现场调参（换推流端、换码率、换光照）只应改配置对象；如果阈值散落在检测器里，
     * 调参就会变成"改代码 + 重新编译 + 重新验证"三件事。</p>
     */
    @Test
    public void blueThresholdsAreOwnedByTheConfigObject() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String analyzer = read(new File(visionDir, "BlueMaskAnalyzer.java"));

        for (String knob : new String[]{
                "roiRadiusX", "roiRadiusY",
                "blueHueMinDegrees", "blueHueMaxDegrees",
                "minSaturation", "minValue",
                "minBluePixelCount", "maskDenoiseMinNeighbors", "debugMaskEnabled"}) {
            assertTrue("BlueMaskAnalyzer 必须从配置读取 " + knob, analyzer.contains("config." + knob));
        }

        // 默认值只有一份定义：配置类只引用 BlueColorThresholds 的常量，不再抄一遍数字
        String config = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/api/TargetSelectionConfig.java"));
        assertTrue("色调/饱和度/明度默认值必须来自 BlueColorThresholds",
                config.contains("BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES")
                        && config.contains("BlueColorThresholds.DEFAULT_MIN_SATURATION"));

        String thresholds = read(new File(visionDir, "BlueColorThresholds.java"));
        assertTrue("BlueColorThresholds 必须提供默认值常量",
                thresholds.contains("DEFAULT_HUE_MIN_DEGREES")
                        && thresholds.contains("DEFAULT_HUE_MAX_DEGREES")
                        && thresholds.contains("DEFAULT_MIN_SATURATION")
                        && thresholds.contains("DEFAULT_MIN_VALUE"));
    }

    /**
     * 视觉实现不得引入 OpenCV / ML Kit / TFLite —— 本 Slice 刻意只用轻量位运算。
     *
     * <p>判据只看 <b>import 行</b>：注释里提到 OpenCV（例如解释"本模块的色调是角度制，
     * 与 OpenCV 的 [0,180] 不同"）是<b>有价值的文档</b>，把它判成违规只会逼人删掉说明 ——
     * 这与本类开头写的"不检查注释散文"是同一条原则。</p>
     */
    @Test
    public void visionLayerAvoidsHeavyVisionDependencies() throws IOException {
        List<File> sources = javaSourcesIn(moduleDir("targetselection")
                + "/src/main/java/com/example/motro/targetselection/vision");
        List<String> banned = Arrays.asList("org.opencv", "com.google.mlkit",
                "org.tensorflow", "tflite", "opencv");
        List<String> offenders = new ArrayList<>();
        for (File source : sources) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source.toPath(), StandardCharsets.UTF_8)) {
                lineNumber++;
                final String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String needle : banned) {
                    if (trimmed.toLowerCase(Locale.ROOT).contains(needle)) {
                        offenders.add(source.getName() + ":" + lineNumber + " -> " + trimmed);
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("vision 包不得依赖重型视觉库：\n" + String.join("\n", offenders));
        }
    }

    /**
     * Slice 5 的矩形恢复阈值也必须全部来自 {@code TargetSelectionConfig}。
     *
     * <p>矩形检测比蓝色阈值更难调：边线厚度、最小框高、边覆盖率这几个量互相牵制
     * （放宽覆盖率就会把"两条平行线"放进来，压紧又会漏掉 HEVC 断边）。一旦它们散落成
     * 算法里的字面量，现场调参就退化成"改代码 → 重编 → 重跑真机"。</p>
     */
    @Test
    public void targetBoxThresholdsAreOwnedByTheConfigObject() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String detector = read(new File(visionDir, "TargetBoxDetector.java"));

        for (String knob : new String[]{
                "minBoxWidth", "minBoxHeight", "maxBoxWidth", "maxBoxHeight",
                "maxBoxAspectRatio", "minEdgeCoverage", "minPresentEdges",
                "maxEdgeThicknessPx", "minEdgeSolidity", "minBoxConfidence",
                "maxCandidateCount"}) {
            assertTrue("TargetBoxDetector 必须从配置读取 " + knob, detector.contains("config." + knob));
        }

        String hitTester = read(new File(visionDir, "TargetHitTester.java"));
        assertTrue("TargetHitTester 必须从配置读取 touchTolerancePx",
                hitTester.contains("config.touchTolerancePx"));
    }

    /**
     * ROI 局部坐标 → 视频坐标的换算只允许有一条通道：{@code RoiRect.toVideoRect}。
     *
     * <p>这是 Slice 5 最隐蔽的一处坑。帧被 {@code maxFrameLongSidePx} 缩小后，ROI 的局部像素
     * 与视频像素之间差的是一个<b>缩放</b>，不是单纯的 {@code +left/+top} 偏移；两者在 1:1 时
     * 恰好退化成一回事，所以「漏掉缩放」在默认配置下能跑对，只在帧被缩小时静默判错 ——
     * 不崩、不报错，只是点到框上判 MISS。把公式钉在唯一一处，这种错误就无处可藏。</p>
     *
     * <p>判据刻意只看「公式本体」与「检测器是否绕开它自建视频矩形」，不限制
     * {@code * scaleX} 这种<b>尺寸</b>缩放 —— 检测器按视频尺度做最小尺寸过滤时会用到它，
     * 那是另一件事（缩放边长 vs 换算坐标原点），不构成第二份坐标公式。</p>
     */
    @Test
    public void roiLocalToVideoConversionHasASingleChannel() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");

        List<String> holders = new ArrayList<>();
        for (File source : javaSourcesIn(visionDir.toString())) {
            if (read(source).contains("roiRectInVideo.left +")) {
                holders.add(source.getName());
            }
        }
        assertEquals("ROI 局部→视频的换算公式只应出现在 RoiRect，实际：" + holders,
                Collections.singletonList("RoiRect.java"), holders);

        String detector = read(new File(visionDir, "TargetBoxDetector.java"));
        assertTrue("TargetBoxDetector 必须经 RoiRect.toVideoRect(...) 落到视频坐标",
                detector.contains(".toVideoRect("));
        assertFalse("TargetBoxDetector 不得自建视频矩形 —— 那等于把带缩放的换算公式抄了第二份",
                detector.contains("TargetBoundingBox.of("));
    }

    /**
     * Slice 6 的多目标选择策略：权重与开关必须全部来自 {@code TargetSelectionConfig}，
     * 而且<b>只允许 {@code TargetCandidateSelector} 读它们</b>。
     *
     * <p>第二条比第一条更重要。选权重这类东西一旦在两个地方各读一份，
     * 就会出现"改了配置但结果没变"或"只变了一半"——排查时几乎无从下手。
     * 所以这里同时断言过滤层（{@code TargetHitTester}）里<b>不存在</b>任何评分痕迹：
     * 它只做几何过滤，把"哪个更好"完整地留给选择器。</p>
     */
    @Test
    public void selectionPolicyLivesOnlyInTheCandidateSelector() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String selector = read(new File(visionDir, "TargetCandidateSelector.java"));

        for (String knob : new String[]{
                "touchTolerancePx", "exactHitWeight", "centerDistanceWeight", "areaWeight",
                "confidenceWeight", "completenessWeight", "preferSmallerBox",
                "maxRankedCandidates"}) {
            assertTrue("TargetCandidateSelector 必须从配置读取 " + knob,
                    selector.contains("config." + knob));
        }
        // 排序必须用显式比较器（逐级 tie-break），而不是靠排序稳定性
        assertTrue("TargetCandidateSelector 必须自带比较器以固定名次",
                selector.contains("compare(") && selector.contains("hitType.ordinal()"));

        String hitTester = read(new File(visionDir, "TargetHitTester.java"));
        assertTrue("TargetHitTester 只做几何过滤：必须用容差扩张后的框判定",
                hitTester.contains("inflated("));
        for (String scoring : new String[]{"totalScore", "score", "Comparator", "hitType.ordinal()",
                "centerDistanceWeight", "areaWeight", "preferSmallerBox"}) {
            assertFalse("TargetHitTester 不得掺入评分/排序逻辑（发现 " + scoring + "）",
                    hitTester.contains(scoring));
        }
    }

    /**
     * 一次点击的四个耗时阶段必须各自有独立出口，否则现场只能知道"慢了"而不知"哪里慢"。
     */
    @Test
    public void everyStageKeepsItsOwnTiming() throws IOException {
        String result = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision/TargetBoxDetectionResult.java"));
        for (String timing : new String[]{"detectMs", "hitTestMs", "selectionMs", "totalMs"}) {
            assertTrue("TargetBoxDetectionResult 必须暴露 " + timing, result.contains(timing));
        }

        // Slice 7 的标签提取同理：搜索与裁剪各自要能被单独看到
        String label = read(new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision/LabelExtractionResult.java"));
        for (String timing : new String[]{"labelDetectMs", "labelCropMs", "totalMs"}) {
            assertTrue("LabelExtractionResult 必须暴露 " + timing, label.contains(timing));
        }
    }

    /**
     * Slice 7 的标签提取策略：参数必须全部来自 {@code TargetSelectionConfig}，
     * 而且<b>只允许 {@code LabelRegionExtractor} 读它们</b>。
     *
     * <p>同时钉住两件更重要的结构性约束：</p>
     * <ol>
     *   <li><b>蓝阈值只有一份</b>：标签提取必须复用 {@code BlueColorThresholds}
     *       （以及配置里同一组 {@code blueHueMinDegrees} / {@code blueHueMaxDegrees} /
     *       {@code minSaturation} / {@code minValue}），
     *       不许为了"文字细、压缩后掉色更狠"另起一套阈值 ——
     *       两套阈值意味着现场调参要调两处，且永远不知道哪一处才是生效的那个。</li>
     *   <li><b>排序只有一处</b>：候选名次必须由 {@code LabelRegionExtractor} 里的显式比较器
     *       逐级 tie-break 给出，不能靠集合遍历顺序或排序稳定性 ——
     *       后者的结果会随输入规模与实现细节漂移，而"选错标签"是肉眼几乎发现不了的缺陷。</li>
     * </ol>
     */
    @Test
    public void labelExtractionPolicyLivesOnlyInTheLabelRegionExtractor() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String extractor = read(new File(visionDir, "LabelRegionExtractor.java"));

        for (String knob : new String[]{
                "labelSearchHeightPx", "labelHorizontalPaddingPx", "labelVerticalPaddingPx",
                "labelMaxDistanceFromBoxPx", "minLabelCandidateWidth", "minLabelCandidateHeight",
                "maxLabelCandidateCount"}) {
            assertTrue("LabelRegionExtractor 必须从配置读取 " + knob,
                    extractor.contains("config." + knob));
        }
        assertTrue("LabelRegionExtractor 必须复用 BlueColorThresholds（不得新建第二套蓝阈值）",
                extractor.contains("BlueColorThresholds.of("));
        assertTrue("LabelRegionExtractor 必须自带比较器以固定名次",
                extractor.contains("Comparator<LabelCandidate>") && extractor.contains(".sort(RANKING)"));

        // 其它视觉阶段不得掺入标签参数：否则"改了配置但行为没变"或"只变了一半"
        for (String other : new String[]{"TargetBoxDetector.java", "TargetHitTester.java",
                "TargetCandidateSelector.java", "BlueMaskDetector.java"}) {
            String text = read(new File(visionDir, other));
            for (String knob : new String[]{"labelSearchHeightPx", "labelMaxDistanceFromBoxPx",
                    "labelHorizontalPaddingPx", "labelVerticalPaddingPx",
                    "minLabelCandidate", "maxLabelCandidateCount"}) {
                assertFalse(other + " 不得掺入标签提取参数（发现 " + knob + "）", text.contains(knob));
            }
        }
    }

    /**
     * Slice 8 的编号识别策略：放大倍数、置信度门槛、位数区间必须全部来自
     * {@code TargetSelectionConfig}，而且<b>只允许识别实现与解析器读它们</b>。
     *
     * <p>同时钉住两条本 Slice 最重要的结构性约束：</p>
     * <ol>
     *   <li><b>蓝色阈值仍然只有一份</b>：识别阶段的"什么是文字像素"必须复用
     *       {@link com.example.motro.targetselection.vision.BlueColorThresholds}
     *       与配置里同一组 {@code blueHue*} / {@code minSaturation} / {@code minValue}，
     *       不许因为"文字细、压缩后掉色狠"另起一套 —— 两套阈值意味着现场调参要调两处。</li>
     *   <li><b>逐字符模板与阈值不外泄</b>：模板点阵与匹配相似度下限是识别实现的
     *       内部细节，不构成配置项（它们不随画面变化，只随实现变化）。</li>
     * </ol>
     *
     * <p>另外断言识别层的<b>纯 JVM</b>：{@code GlyphTemplateTargetIdRecognizer} 与
     * {@code TargetIdParser} 都不得 import {@code android.*}（后者已由
     * {@link #visionLayerStaysPureJvm()} 覆盖整个 vision 包，这里额外确认
     * 识别实现没有"为了一个 Bitmap 就破例"）。</p>
     */
    @Test
    public void targetIdRecognitionPolicyLivesOnlyInTheRecognizer() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String recognizer = read(new File(visionDir, "GlyphTemplateTargetIdRecognizer.java"));

        for (String knob : new String[]{
                "labelUpscaleFactor", "minIdConfidence", "idMinDigits", "idMaxDigits"}) {
            assertTrue("GlyphTemplateTargetIdRecognizer 必须从配置读取 " + knob,
                    recognizer.contains("config." + knob));
        }
        assertTrue("识别实现必须复用 BlueColorThresholds（不得新建第二套蓝阈值）",
                recognizer.contains("BlueColorThresholds.of("));

        // 解析规则与识别实现分开：解析器只读位数区间，不碰任何视觉参数。
        // 只看<b>代码行</b>（跳过注释与 Javadoc）：本类开头就写明"注释散文不构成违规"，
        // 而解析器的 Javadoc 恰好要提到 minIdConfidence 才能把分工解释清楚 ——
        // 那是有价值的文档，不该被判成违规。
        final String parserCode = codeOnly(new File(visionDir, "TargetIdParser.java"));
        assertTrue("TargetIdParser 必须从配置读取 idMinDigits", parserCode.contains("config.idMinDigits"));
        assertTrue("TargetIdParser 必须从配置读取 idMaxDigits", parserCode.contains("config.idMaxDigits"));
        for (String visual : new String[]{"labelUpscaleFactor", "minIdConfidence",
                "blueHueMinDegrees", "minSaturation", "labelSearchHeightPx"}) {
            assertFalse("TargetIdParser 不得在代码里读取视觉参数（发现 " + visual + "）",
                    parserCode.contains(visual));
        }

        // 识别参数不得掺进其它视觉阶段：否则"改了配置但行为没变"或"只变了一半"
        for (String other : new String[]{"TargetBoxDetector.java", "TargetHitTester.java",
                "TargetCandidateSelector.java", "BlueMaskDetector.java", "LabelRegionExtractor.java"}) {
            String text = read(new File(visionDir, other));
            for (String knob : new String[]{"labelUpscaleFactor", "minIdConfidence",
                    "idMinDigits", "idMaxDigits"}) {
                assertFalse(other + " 不得掺入编号识别参数（发现 " + knob + "）", text.contains(knob));
            }
        }

        // 识别接口必须只依赖本模块自己的类型（不得漏出 SDK 类型到公开 API）
        String contract = read(new File(visionDir, "TargetIdRecognizer.java"));
        assertTrue("TargetIdRecognizer 必须以 LabelExtractionResult 为入参",
                contract.contains("LabelExtractionResult"));
        assertTrue("TargetIdRecognizer 必须以 TargetIdRecognitionResult 为出参",
                contract.contains("TargetIdRecognitionResult"));
    }

    /**
     * 识别失败不得把"已经确认的 bbox 命中"倒退成 MISS —— 这条语义必须由
     * {@code TargetIdRecognitionStatus.keepsHit()} 一处给出，而不是散落在各调用点的
     * 枚举比较里（那样迟早会漏掉一个分支）。
     */
    @Test
    public void recognitionFailureNeverDowngradesAHit() throws IOException {
        File visionDir = new File(moduleDir("targetselection"),
                "src/main/java/com/example/motro/targetselection/vision");
        String status = read(new File(visionDir, "TargetIdRecognitionStatus.java"));
        assertTrue("TargetIdRecognitionStatus 必须提供 keepsHit() 作为唯一的命中语义出口",
                status.contains("public boolean keepsHit()"));
        assertTrue("keepsHit() 必须恒为 true（识别是命中的附加步骤，失败不影响命中）",
                status.contains("return true;"));

        String result = read(new File(visionDir, "TargetIdRecognitionResult.java"));
        assertTrue("TargetIdRecognitionResult 必须提供 isHitWithoutId()",
                result.contains("isHitWithoutId()"));
        // 非成功状态下 targetId 必须恒为 null（否则上层会写出 targetId != null 的模糊分支）
        for (String factory : new String[]{"noId(", "lowConfidence(", "invalidLabel(",
                "recognitionFailed("}) {
            assertTrue("TargetIdRecognitionResult 必须提供 " + factory + " 工厂",
                    result.contains(factory));
        }
    }

    // ---------------------------------------------------------------- utils

    private static void assertNoDependency(String deps, String needle) {
        if (deps.contains(needle)) {
            fail("dependencies 中不应出现 " + needle + "，实际依赖块：\n" + deps);
        }
    }

    /**
     * 只取 {@code dependencies { ... }} 内容，避免把注释里的说明误判成依赖。
     * 模块若根本没有依赖块（如 :ffmpegplayer），返回空串 —— 即“未声明任何依赖”。
     */
    private static String dependenciesBlock(String gradleText) {
        int start = gradleText.indexOf("dependencies {");
        return start < 0 ? "" : gradleText.substring(start);
    }

    private static List<File> javaSourcesIn(String dirPath) throws IOException {
        File dir = new File(dirPath);
        assertTrue("目录不存在：" + dir.getAbsolutePath(), dir.isDirectory());

        List<File> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> result.add(path.toFile()));
        }
        return result;
    }

    private static File moduleDir(String module) {
        return new File(projectRoot(), module);
    }

    /**
     * 定位工程根目录（含 settings.gradle）。单测的工作目录可能是模块目录也可能是根目录，
     * 因此从 user.dir 逐级向上找，避免测试对环境敏感。
     */
    private static File projectRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "settings.gradle").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        fail("无法从 user.dir 定位工程根目录：" + System.getProperty("user.dir"));
        return null;
    }

    private static String read(File file) throws IOException {
        assertNotNull(file);
        assertTrue("文件不存在：" + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 只保留<b>代码行</b>：丢掉纯注释行（{@code //}、{@code *}、{@code /*}）。
     *
     * <p>用于"某个标识符不得出现在代码里"这类断言 —— 本类开头明确写着
     * 「注释里提到某个名字只是说明，不构成依赖」，若把注释也算进来，
     * 就会逼着人删掉有价值的文档。</p>
     */
    private static String codeOnly(File file) throws IOException {
        final StringBuilder code = new StringBuilder(4096);
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            code.append(line).append('\n');
        }
        return code.toString();
    }
}
