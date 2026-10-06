package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.config.ConfigManager;

import java.io.IOException;
import java.util.List;

/**
 * 命名配置的保存 / 加载 / 删除 / 列表（P3-3b-8）：{@code gui.controller.ParamPanelActions} 对
 * {@code config.ConfigManager} 的四方法使用经本类 1:1 收口 —— 参数顺序、返回语义与异常传播
 * 与底层完全一致（只搬不改），gui 层不再直连 {@code config} 包。
 *
 * <p>实例语义与 {@code ParamPanelActions} 原字段一致：无参构造即建目录（失败仅告警）、
 * 持一个 {@link ConfigManager} 实例；调用方的捕获口径（{@code catch (Exception)}）
 * 与{@link IOException}传播路径不变。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class NamedConfigService {

    private final ConfigManager configManager = new ConfigManager();

    /** 保存命名配置：1:1 转发 {@link ConfigManager#saveConfig}，{@link IOException} 原样传播。 */
    public void save(String configName, AppConfig config) throws IOException {
        configManager.saveConfig(configName, config);
    }

    /** 加载命名配置：1:1 转发 {@link ConfigManager#loadConfig}，{@link IOException} 原样传播。 */
    public AppConfig load(String configName) throws IOException {
        return configManager.loadConfig(configName);
    }

    /** 删除命名配置：1:1 转发 {@link ConfigManager#deleteConfig}，{@link IOException} 原样传播。 */
    public void delete(String configName) throws IOException {
        configManager.deleteConfig(configName);
    }

    /** 全部命名配置名：1:1 转发 {@link ConfigManager#getConfigNames}。 */
    public List<String> names() {
        return configManager.getConfigNames();
    }
}
