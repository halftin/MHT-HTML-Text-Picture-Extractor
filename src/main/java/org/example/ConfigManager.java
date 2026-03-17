package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_EXTENSION = ".json";
    private final ObjectMapper objectMapper;

    public ConfigManager() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // 创建配置目录
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
    }

    public void saveConfig(String configName, AppConfig config) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        objectMapper.writeValue(configFile, config);
    }

    public AppConfig loadConfig(String configName) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        if (!configFile.exists()) {
            throw new IOException("配置文件不存在: " + configFile.getAbsolutePath());
        }
        return objectMapper.readValue(configFile, AppConfig.class);
    }

    public List<String> getConfigNames() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            return new ArrayList<>();
        }

        List<String> configNames = new ArrayList<>();
        for (File file : configDir.listFiles()) {
            if (file.getName().endsWith(CONFIG_EXTENSION)) {
                configNames.add(file.getName().substring(0, file.getName().length() - CONFIG_EXTENSION.length()));
            }
        }
        return configNames;
    }

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