package com.mhtextractor.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 预览缓存纯存储层（需求 2 P6/P7/P8/P9/P11）：目录布局、原子写入、读取、按 key 删除、启动扫孤儿。
 * 无 GUI/业务逻辑，不含 AppConfig 引用；根目录由构造函数注入（P8，禁止硬编码、禁止依赖 CWD）。
 *
 * <p>目录布局（P6，位于 {@code <java.io.tmpdir>/mht-extractor-preview/} 下，由调用方注入）：
 * <pre>{@code
 * <root>/
 *   <cacheKey>/            cacheKey = PreviewCacheKey.toDirName()（SHA-256 hex）
 *     meta.json
 *     images/0001.png ...
 *     .complete            最后写入；存在即视为可复用（P11）
 *   <cacheKey>.tmp/        写入中的临时目录；cleanupOrphans 无条件删除
 * }</pre>
 */
@Slf4j
public record PreviewCacheStore(Path root) {

    public static final String META_FILE_NAME = "meta.json";
    public static final String IMAGES_DIR_NAME = "images";
    public static final String COMPLETE_MARKER = ".complete";
    public static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // 向前兼容：旧版/新版多出的字段加载时忽略
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 写图片内容的回调：把全部图片写入 imagesDir，文件名必须与 meta.images[].file 一致 */
    @FunctionalInterface
    public interface ImagesWriter {
        void write(Path imagesDir) throws IOException;
    }

    /** 紧凑构造器：注入的根目录统一规范化为绝对路径（P8） */
    public PreviewCacheStore {
        root = root.toAbsolutePath().normalize();
    }

    /** 某 key 对应的缓存目录（不保证存在） */
    public Path dirFor(PreviewCacheKey key) {
        return root.resolve(key.toDirName());
    }

    /**
     * 缓存 meta 清单里第 {@code index} 张图片的真实路径（<b>不保证存在</b>；meta 为 null 或下标越界返回 null）。
     * key 由 meta 自身四要素反推（与 {@link #listKeys()} 同法）——{@code load} 命中判定已要求
     * {@code meta.matches(key)}，故反推目录必然是这些图片的来源，与"当前参数是否已变"无关。
     * 供调用方（如预览区的"点击查看"）在不了解目录布局的前提下取到图片文件。
     */
    public Path imageFileFor(PreviewCacheMeta meta, int index) {
        if (meta == null || index < 0 || index >= meta.images().size()) {
            return null;
        }
        PreviewCacheKey key = new PreviewCacheKey(meta.source().absolutePath(), meta.source().size(),
                meta.source().mtime(), meta.paramFingerprint(), meta.source().sidecarFingerprint(),
                meta.formatVersion());
        return dirFor(key).resolve(meta.images().get(index).file());
    }

    /**
     * 原子写入（P11）：内容与 meta 全部写入 {@code <key>.tmp/} → 改名为 {@code <key>/} → 最后写 .complete。
     * 半成品永远不叫正式名；崩溃残留由 {@link #cleanupOrphans()} 兜底清理。返回最终缓存目录。
     * 旧缓存只在提交（改名）前一刻删除，写入失败时旧缓存保持可用。
     */
    public Path write(PreviewCacheKey key, PreviewCacheMeta meta, ImagesWriter imagesWriter)
            throws IOException {
        if (!meta.matches(key)) {
            throw new IllegalArgumentException("cache meta does not match key: " + key);
        }
        Path target = dirFor(key);
        Path tmp = root.resolve(key.toDirName() + TMP_SUFFIX);

        Files.createDirectories(root);
        deleteRecursivelyIfExists(tmp);
        Files.createDirectories(tmp);
        Path imagesDir = tmp.resolve(IMAGES_DIR_NAME);
        Files.createDirectories(imagesDir);
        imagesWriter.write(imagesDir);
        MAPPER.writeValue(tmp.resolve(META_FILE_NAME).toFile(), meta);

        // 提交：删旧缓存 → 改名 → 最后写完成标记
        deleteRecursivelyIfExists(target);
        Files.move(tmp, target);
        Files.createFile(target.resolve(COMPLETE_MARKER));
        log.debug(I18n.msg("log.previewcachestore.01", target));
        return target;
    }

    /**
     * 读取缓存（P9 命中判定）：目录存在 + .complete 存在 + meta 与 key 四要素全等 + 全部图片文件存在。
     * 任一不满足 → empty（不命中）。meta.json 损坏/不可读 → 抛 IOException（显式失败，由调用方处置）。
     */
    public Optional<PreviewCacheMeta> load(PreviewCacheKey key) throws IOException {
        Path dir = dirFor(key);
        if (!Files.isDirectory(dir) || !Files.exists(dir.resolve(COMPLETE_MARKER))) {
            return Optional.empty();
        }
        PreviewCacheMeta meta = MAPPER.readValue(dir.resolve(META_FILE_NAME).toFile(), PreviewCacheMeta.class);
        if (!meta.matches(key)) {
            return Optional.empty();
        }
        for (PreviewCacheMeta.ImageEntry entry : meta.images()) {
            if (!Files.isRegularFile(dir.resolve(entry.file()))) {
                log.warn(I18n.msg("log.previewcachestore.02", dir.resolve(entry.file())));
                return Optional.empty();
            }
        }
        return Optional.of(meta);
    }

    /**
     * 当前缓存根目录的真实磁盘占用（字节）：遍历 root 下全部常规文件求和（会话 12 占用提醒用）。
     * 口径：<b>含</b>写入中的 {@code *.tmp} 目录与孤儿残留——它们同样真实占用磁盘，提醒的是占用本身，
     * 不区分「有效缓存」与「残留」；不计目录项自身（占比极小，忽略）。root 不存在返回 0。
     * 并发写入/删除期间的遍历可能抛 IOException（如 NoSuchFileException），由调用方决定处置。
     */
    public long totalBytes() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                total += Files.size(p);
            }
        }
        return total;
    }

    /** 删除某 key 的缓存目录；返回是否真的删掉了东西 */
    public boolean delete(PreviewCacheKey key) throws IOException {
        return deleteRecursivelyIfExists(dirFor(key));
    }

    /** 清空整个缓存根目录下所有条目（含孤儿与 .tmp 残留）；返回删除的条目数。根目录本身保留 */
    public int deleteAll() throws IOException {
        int count = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path entry : stream.toList()) {
                if (deleteRecursivelyIfExists(entry)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 列出所有「完整缓存」的键：解析各目录 meta.json，由 source + 指纹 + 格式版本重建 key。
     * 无 .complete 的目录与 .tmp 目录被跳过（它们是孤儿，不属于有效键）。
     */
    public List<PreviewCacheKey> listKeys() throws IOException {
        List<PreviewCacheKey> keys = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return keys;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                if (dir.getFileName().toString().endsWith(TMP_SUFFIX)
                        || !Files.exists(dir.resolve(COMPLETE_MARKER))) {
                    continue;
                }
                PreviewCacheMeta meta = MAPPER.readValue(dir.resolve(META_FILE_NAME).toFile(), PreviewCacheMeta.class);
                keys.add(new PreviewCacheKey(meta.source().absolutePath(), meta.source().size(),
                        meta.source().mtime(), meta.paramFingerprint(),
                        meta.source().sidecarFingerprint(), meta.formatVersion()));
            }
        }
        return keys;
    }

    /**
     * 启动时清理孤儿（P11/P7 崩溃恢复）：删除所有 {@code *.tmp} 目录、所有无 .complete 的目录，
     * 以及根目录下的散落文件。返回删除的条目数。除「目录正被并发写入」外的一切残留均可处理。
     */
    public int cleanupOrphans() throws IOException {
        int count = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path entry : stream.toList()) {
                if (entry.getFileName().toString().endsWith(TMP_SUFFIX)) {
                    if (deleteRecursivelyIfExists(entry)) {
                        count++;
                    }
                } else if (Files.isDirectory(entry)) {
                    if (!Files.exists(entry.resolve(COMPLETE_MARKER))) {
                        if (deleteRecursivelyIfExists(entry)) {
                            count++;
                        }
                    }
                } else {
                    Files.deleteIfExists(entry);
                    count++;
                }
            }
        }
        if (count > 0) {
            log.info(I18n.msg("log.previewcachestore.03", count, root));
        }
        return count;
    }

    /** 递归删除；不存在返回 false */
    private static boolean deleteRecursivelyIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
        return true;
    }
}
