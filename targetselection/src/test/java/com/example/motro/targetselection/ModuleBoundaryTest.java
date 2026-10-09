package com.example.motro.targetselection;

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
