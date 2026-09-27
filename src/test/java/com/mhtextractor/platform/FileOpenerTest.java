package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileOpener} 的纯逻辑测试：<b>不打真实注册表、不启动任何进程</b>（否则会弹窗）。
 *
 * <p>覆盖三块最容易回归的东西：① 「照片」协议 URI 的编码（空格/盘符/反斜杠/中文）；
 * ② 两条命令行的形状（第 2 级刻意<b>不</b>带空标题——那是实测可用的形式）；
 * ③ 环境判定与解析（EnableLUA 注册表文本、打包应用 ProgId、扩展名归一）。</p>
 */
class FileOpenerTest {

    private static final String URI_PREFIX = "ms-photos:viewer?fileName=";

    @Test
    void photosUriEncodesWholePath() {
        String uri = FileOpener.photosViewerUri(Path.of("C:\\tmp\\a b\\图 1.webp"));

        assertTrue(uri.startsWith(URI_PREFIX), "URI 前缀固定，实际：" + uri);
        String encoded = uri.substring(URI_PREFIX.length());
        assertTrue(encoded.startsWith("C%3A%5C"), "盘符冒号与反斜杠都要编码，实际：" + encoded);
        assertFalse(encoded.contains(" "), "空格必须编码（+ 在协议参数里不通用），实际：" + encoded);
        assertTrue(encoded.contains("%20"), "空格应编码成 %20，实际：" + encoded);
        assertTrue(encoded.contains("%E5%9B%BE"), "中文应走 UTF-8 百分号编码，实际：" + encoded);
        assertTrue(encoded.endsWith(".webp"), "扩展名不应被改写，实际：" + encoded);
    }

    @Test
    void photosProtocolArgvHasNoEmptyTitle() {
        // 实测可用的形式：cmd /c start ms-photos:viewer?fileName=<enc>（不带空标题）
        String[] argv = FileOpener.photosProtocolArgv(Path.of("C:\\tmp\\x.webp"));

        assertEquals(4, argv.length);
        assertEquals("cmd", argv[0]);
        assertEquals("/c", argv[1]);
        assertEquals("start", argv[2]);
        assertTrue(argv[3].startsWith(URI_PREFIX), "第 4 个参数应是协议 URI，实际：" + argv[3]);
    }

    @Test
    void defaultViewerArgvKeepsEmptyTitleAndAbsolutePath() {
        // cmd /c start "" "<file>"：空标题是 start 的语法要求（否则第一个带引号的路径会被当成标题）
        String[] argv = FileOpener.defaultViewerArgv(Path.of("C:\\tmp\\x.webp"));

        assertEquals(5, argv.length);
        assertEquals("cmd", argv[0]);
        assertEquals("/c", argv[1]);
        assertEquals("start", argv[2]);
        assertEquals("", argv[3]);
        assertEquals(Path.of("C:\\tmp\\x.webp").toAbsolutePath().normalize().toString(), argv[4]);
    }

    @Test
    void argvUsesAbsolutePathForRelativeInput() {
        String expected = Path.of("rel\\x.webp").toAbsolutePath().normalize().toString();

        assertEquals(expected, FileOpener.defaultViewerArgv(Path.of("rel\\x.webp"))[4]);
        assertTrue(FileOpener.photosViewerUri(Path.of("rel\\x.webp"))
                .contains(java.net.URLEncoder.encode(expected, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20")));
    }

    @Test
    void parseEnableLuaDetectsDisabledUac() {
        String disabled = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\r\n"
                + "    EnableLUA    REG_DWORD    0x0\r\n";
        String enabled = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\r\n"
                + "    EnableLUA    REG_DWORD    0x1\r\n";

        assertTrue(FileOpener.parseEnableLua(disabled), "0x0 = UAC 关闭");
        assertFalse(FileOpener.parseEnableLua(enabled), "0x1 = UAC 开启");
    }

    @Test
    void parseEnableLuaIsConservativeOnUnreadableInput() {
        // 读不到就按"未关闭"处理：宁可不绕开第 1 级，也不要在正常机器上误用兜底
        assertFalse(FileOpener.parseEnableLua(null));
        assertFalse(FileOpener.parseEnableLua(""));
        assertFalse(FileOpener.parseEnableLua("ERROR: The system was unable to find the specified registry key"));
        assertFalse(FileOpener.parseEnableLua("    EnableLUA    REG_DWORD    0xZZ"));
    }

    @Test
    void packagedProgIdDetection() {
        assertTrue(FileOpener.isPackagedProgId("AppX4mntx4h978m1v9gtzv0ewksfd6pmwsre"));
        assertFalse(FileOpener.isPackagedProgId("pngfile"), "经典 ProgId 不该被判成打包应用");
        assertFalse(FileOpener.isPackagedProgId("Applications\\ImageGlass.exe"));
        assertFalse(FileOpener.isPackagedProgId(null));
    }

    @Test
    void extensionOfNormalizesCaseAndHandlesMissingDot() {
        assertEquals(".webp", FileOpener.extensionOf(Path.of("C:\\a\\B.WEBP")));
        assertEquals(".png", FileOpener.extensionOf(Path.of("C:\\a\\b.png")));
        assertEquals("", FileOpener.extensionOf(Path.of("C:\\a\\noext")));
        assertEquals("", FileOpener.extensionOf(null));
    }
}
