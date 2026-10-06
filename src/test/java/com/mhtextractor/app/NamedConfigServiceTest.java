package com.mhtextractor.app;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NamedConfigService} 无头单测（P3-3b-8）：四方法包装与底层 {@link ConfigManager}
 * 同参同返（只搬不改），含 {@link IOException} 的异常语义对照。
 *
 * <p>落盘位置与运行期一致（工作目录下 {@code config/}，仓库 .gitignore 已锚定根）；
 * 本测试只用带唯一前缀的名字并在用例间清理，不触碰真实用户配置。</p>
 */
class NamedConfigServiceTest {

    private static final String NAME = "p3-3b8-test-" + System.nanoTime();

    private final NamedConfigService service = new NamedConfigService();
    private final ConfigManager underlying = new ConfigManager();

    @AfterEach
    void cleanup() {
        // 两个实例共用同一 config/ 目录；只删自己的名字（没写成功时删除抛 IOException，属预期）
        try {
            service.delete(NAME);
        } catch (IOException ignored) {
        }
    }

    @Test
    void saveLoadDeleteRoundTripMatchesUnderlying() throws IOException {
        AppConfig cfg = new AppConfig();
        cfg.setSeparateFiles(true);
        cfg.setHighSpeedMemoryMode(true);
        cfg.setEnableTitleExtraction(true);

        service.save(NAME, cfg);
        assertTrue(service.names().contains(NAME));
        assertTrue(underlying.getConfigNames().contains(NAME));

        AppConfig viaService = service.load(NAME);
        AppConfig viaUnderlying = underlying.loadConfig(NAME);
        // 同一份落盘 JSON：两个入口读回的内容逐字段相等
        assertEquals(viaUnderlying, viaService);
        assertTrue(viaService.isSeparateFiles());
        assertTrue(viaService.isHighSpeedMemoryMode());
        assertTrue(viaService.isEnableTitleExtraction());

        service.delete(NAME);
        assertFalse(service.names().contains(NAME));
        assertFalse(underlying.getConfigNames().contains(NAME));
    }

    @Test
    void missingNameThrowsSameIOExceptionAsUnderlying() {
        IOException fromUnderlying = assertThrows(IOException.class, () -> underlying.loadConfig(NAME));
        IOException fromService = assertThrows(IOException.class, () -> service.load(NAME));
        assertEquals(fromUnderlying.getMessage(), fromService.getMessage());

        IOException delUnderlying = assertThrows(IOException.class, () -> underlying.deleteConfig(NAME));
        IOException delService = assertThrows(IOException.class, () -> service.delete(NAME));
        assertEquals(delUnderlying.getMessage(), delService.getMessage());
    }

    @Test
    void namesListsSameSetAsUnderlying() {
        // 目录枚举顺序不保证稳定，按集合比对
        assertEquals(new TreeSet<>(underlying.getConfigNames()), new TreeSet<>(service.names()));
    }
}
