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
}
