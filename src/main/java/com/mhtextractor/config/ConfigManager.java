package com.mhtextractor.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器，负责加载、保存、列出和删除JSON格式的AppConfig
 */
@Slf4j
public class ConfigManager {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_EXTENSION = ".json";
    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 旧版配置含 imageMode/inputFolder/previewBeforeExtract 等已废弃字段，加载时忽略
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ConfigManager() {
        try {
            Files.createDirectories(Path.of(CONFIG_DIR));
        } catch (IOException e) {
            log.warn(I18n.msg("log.configmanager.01", CONFIG_DIR, e.getMessage()));
        }
    }

    public void saveConfig(String configName, AppConfig config) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        objectMapper.writeValue(configFile, config);

    }

    public AppConfig loadConfig(String configName) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configFile.getAbsolutePath());
        }
        return objectMapper.readValue(configFile, AppConfig.class);
    }

    public List<String> getConfigNames() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            return new ArrayList<>();
        }
        List<String> configNames = new ArrayList<>();
        File[] files = configDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(CONFIG_EXTENSION)) {
                    configNames.add(file.getName().substring(0, file.getName().length() - CONFIG_EXTENSION.length()));
                }
            }
        }
        return configNames;
    }

    public void deleteConfig(String configName) throws IOException {
        File configFile = new File(CONFIG_DIR, configName + CONFIG_EXTENSION);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configFile.getAbsolutePath());
        }
        if (!configFile.delete()) {
            throw new IOException("Failed to delete configuration file: " + configFile.getAbsolutePath());
        }
    }
}
