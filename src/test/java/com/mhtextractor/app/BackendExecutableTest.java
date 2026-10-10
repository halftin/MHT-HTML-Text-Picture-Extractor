package com.mhtextractor.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P4-21 打包布局：后端在 {@code Backend/} 子文件夹，前端 exe + DLL 留在根目录。
 * 三个用例互为控制组（嵌套优先 / 仅嵌套 / 旧扁平兼容）。
 */
class BackendExecutableTest {

    @TempDir Path tmp;

    private Path nested() throws Exception {
        Path p = tmp.resolve(BackendExecutable.BACKEND_DIR).resolve(BackendExecutable.BACKEND_EXE);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "backend");
        return p;
    }

    @Test
    void nestedBackendFolderWinsOverFlatLayout() throws Exception {
        Path nested = nested();
        Files.writeString(tmp.resolve(BackendExecutable.BACKEND_EXE), "old-flat"); // 旧布局控制组
        assertEquals(nested, BackendExecutable.resolveIn(tmp),
                "Backend/ 布局必须优先于根目录旧布局");
    }

    @Test
    void nestedOnlyLayoutResolves() throws Exception {
        assertEquals(nested(), BackendExecutable.resolveIn(tmp));
    }

    @Test
    void flatLayoutStillResolvesForOldBuilds() throws Exception {
        Path flat = tmp.resolve(BackendExecutable.BACKEND_EXE);
        Files.writeString(flat, "old-flat");
        assertEquals(flat, BackendExecutable.resolveIn(tmp), "旧版扁平布局必须仍可定位（兼容）");
    }
}
