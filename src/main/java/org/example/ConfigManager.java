package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器，负责加载、保存、列出和删除JSON格式的AppConfig。
 * 修改以支持AppConfig中新增的图片提取相关配置项。
 */
public class ConfigManager {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_EXTENSION = ".json";
    private final ObjectMapper objectMapper;

    public ConfigManager() {
        objectMapper = new ObjectMapper();
        // 注册Java 8时间模块，用于处理LocalDateTime等类型（虽然AppConfig里没有，但保留以防万一）
        objectMapper.registerModule(new JavaTimeModule());
        // 配置ObjectMapper，将日期写为字符串而非时间戳
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // 确保配置目录存在
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
    }

    /**
     * 将AppConfig对象保存到指定名称的JSON文件中。
     *
     * @param configName 配置文件的基础名称（不含.json扩展名）
     * @param config     要保存的AppConfig对象
     * @throws IOException 如果保存过程中发生I/O错误
     */
    public void saveConfig(String configName, AppConfig config) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        // 使用ObjectMapper将对象写入JSON文件
        objectMapper.writeValue(configFile, config);
    }

    /**
     * 从指定名称的JSON文件中加载AppConfig对象。
     *
     * @param configName 配置文件的基础名称（不含.json扩展名）
     * @return 加载的AppConfig对象
     * @throws IOException 如果文件不存在或加载过程中发生I/O错误
     */
    public AppConfig loadConfig(String configName) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        if (!configFile.exists()) {
            throw new IOException("配置文件不存在: " + configFile.getAbsolutePath());
        }
        // 使用ObjectMapper从JSON文件读取对象
        return objectMapper.readValue(configFile, AppConfig.class);
    }

    /**
     * 获取配置目录下所有配置文件的名称列表（不含.json扩展名）。
     *
     * @return 配置文件名称列表
     */
    public List<String> getConfigNames() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            return new ArrayList<>();
        }

        List<String> configNames = new ArrayList<>();
        File[] files = configDir.listFiles();
        if (files != null) { // 检查listFiles()返回值是否为null
            for (File file : files) {
                if (file.getName().endsWith(CONFIG_EXTENSION)) {
                    // 去掉".json"扩展名，获取基础名称
                    configNames.add(file.getName().substring(0, file.getName().length() - CONFIG_EXTENSION.length()));
                }
            }
        }
        return configNames;
    }

    /**
     * 删除指定名称的配置文件。
     *
     * @param configName 要删除的配置文件的基础名称（不含.json扩展名）
     * @throws IOException 如果文件不存在或删除过程中发生I/O错误
     */
    public void deleteConfig(String configName) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        if (!configFile.exists()) {
            throw new IOException("配置文件不存在: " + configFile.getAbsolutePath());
        }
        if (!configFile.delete()) {
            throw new IOException("无法删除配置文件: " + configFile.getAbsolutePath());
        }
    }
}