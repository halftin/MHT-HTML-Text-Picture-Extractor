package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出单元计划构建（F5 6.4/6.8，阶段 8）：数据模型 → 输出单元列表。
 *
 * <p>单元划分：未分组项目按"来源目录 + 生效参数内容"分桶（条目独立参数需求拍板）——参数内容
 * 相同（如均继承全局）的同来源条目合并为一个输出，输出名 = 来源目录名（同源多桶时第二个起追加序号）；
 * 单独输出模式的桶直写会话目录（输出名 null）。每个组为一个独立单元（组名经
 * {@link SanitizeUtils#sanitize} 清洗后命名输出；空组跳过）。
 * 单元参数解析沿用 {@link ParamScope#effectiveParams} 三层覆盖（组 &gt; 条目 &gt; 全局；
 * 均未确认时回退默认参数实例）。单元按"首个构成条目在数据序中的位置"排序。</p>
 *
 * <p>本类不感知 GUI 状态，全部方法为纯函数，供 MainController 与单元测试共用。</p>
 */
public final class OutputUnitPlanner {

    private OutputUnitPlanner() {
    }

    /** 项目信息视图：将任意项目类型映射为规划所需字段 */
    public interface ItemView<I> {
        String path(I item);

        String sourceFolder(I item);

        boolean removed(I item);

        /** 条目级文本参数（null = 未设置；条目独立参数需求） */
        AppConfig textItemParams(I item);

        /** 条目级图片参数（null = 未设置；条目独立参数需求） */
        AppConfig imageItemParams(I item);
    }

    /** 输出单元：一组按数据序排列的文件 + 生效参数 + 输出路径 */
    public static final class OutputUnit {
        /** sanitize 后的输出名（合并模式的输出文件/目录名、组单独模式的子文件夹名）；未分组单独单元为 null */
        private final String outputName;
        /** 是否组单元（false = 未分组来源单元） */
        private final boolean group;
        /** 生效参数（已回退默认实例，调用方补充运行时 GUI 字段） */
        private final AppConfig params;
        /** 单元内文件路径（保持数据序） */
        private final List<String> paths;
        /** 输出路径：合并文本 = 文件路径；其余 = 目录路径 */
        private final String outputPath;

        OutputUnit(String outputName, boolean group, AppConfig params, List<String> paths,
                   String outputPath) {
            this.outputName = outputName;
            this.group = group;
            this.params = params;
            this.paths = List.copyOf(paths);
            this.outputPath = outputPath;
        }

        public String getOutputName() {
            return outputName;
        }

        public boolean isGroup() {
            return group;
        }

        public AppConfig getParams() {
            return params;
        }

        public List<String> getPaths() {
            return paths;
        }

        public String getOutputPath() {
            return outputPath;
        }

        public int fileCount() {
            return paths.size();
        }

        /** 日志/展示用单元名：未分组单独单元显示"未分组" */
        public String label() {
            return outputName != null ? outputName : "未分组";
        }
    }

    /**
     * 构建输出单元计划。
     *
     * @param dataOrder    数据序列表（I 与 GroupItem&lt;I&gt; 混合）
     * @param view         项目字段视图
     * @param imageMode    当前提取模式（文本/图片）
     * @param textGlobal   全局文本参数（null = 未确认）
     * @param imageGlobal  全局图片参数（null = 未确认）
     * @param sessionDir   会话输出目录
     */
    public static <I> List<OutputUnit> plan(List<?> dataOrder, ItemView<I> view,
                                            boolean imageMode, AppConfig textGlobal,
                                            AppConfig imageGlobal, String sessionDir) {
        List<UnitEntry> entries = new ArrayList<>();
        // 未分组项目按"来源+生效参数内容"分桶（条目独立参数需求拍板；LinkedHashMap 保持首次出现序）
        Map<BucketKey, List<I>> ungroupedByKey = new LinkedHashMap<>();
        Map<BucketKey, Integer> keyFirstPos = new HashMap<>();
        boolean anyUngrouped = false;
        int pos = 0;

        for (Object o : dataOrder) {
            if (o instanceof GroupItem<?> g) {
                OutputUnit groupUnit = groupUnit(g, view, imageMode, textGlobal, imageGlobal, sessionDir);
                if (groupUnit != null) {
                    entries.add(new UnitEntry(pos, groupUnit));
                }
            } else {
                @SuppressWarnings("unchecked")
                I item = (I) o;
                if (!view.removed(item)) {
                    anyUngrouped = true;
                    AppConfig itemParams = resolveItem(item, view, imageMode, textGlobal, imageGlobal);
                    boolean separate = imageMode ? !itemParams.isSingleOutputMode() : itemParams.isSeparateFiles();
                    // 单独输出桶不区分来源（直写会话目录，跨来源同参数合一单元，与改造前一致）
                    BucketKey key = new BucketKey(separate ? "" : sourceKey(view.sourceFolder(item)), itemParams);
                    keyFirstPos.putIfAbsent(key, pos);
                    ungroupedByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
                }
            }
            pos++;
        }

        if (anyUngrouped) {
            // 同名（同来源）合并桶计数：第二个起追加序号（小说A.txt、小说A(2).txt）
            Map<String, Integer> usedNames = new HashMap<>();
            for (Map.Entry<BucketKey, List<I>> e : ungroupedByKey.entrySet()) {
                AppConfig params = e.getKey().params();
                boolean separate = imageMode ? !params.isSingleOutputMode() : params.isSeparateFiles();
                String name;
                String outputPath;
                if (separate) {
                    // 单独输出：各单元直写会话目录（输出名 null，成员文件名互不冲突）
                    name = null;
                    outputPath = sessionDir;
                } else {
                    String base = sourceOutputName(e.getKey().source(), imageMode);
                    int n = usedNames.merge(base, 1, Integer::sum);
                    name = n == 1 ? base : base + "(" + n + ")";
                    outputPath = imageMode
                            ? new File(sessionDir, name).getPath()
                            : new File(sessionDir, name + ".txt").getPath();
                }
                entries.add(new UnitEntry(keyFirstPos.get(e.getKey()),
                        new OutputUnit(name, false, params, pathsOf(e.getValue(), view), outputPath)));
            }
        }

        entries.sort(java.util.Comparator.comparingInt(e -> e.position));
        return entries.stream().map(e -> e.unit).toList();
    }

    /** 未分组分桶键：来源目录 + 生效参数（AppConfig 按内容 equals，参数内容相同即同桶） */
    private record BucketKey(String source, AppConfig params) {
    }

    /** 单元 + 首个构成条目在数据序中的位置（用于按首次出现排序） */
    private static final class UnitEntry {
        final int position;
        final OutputUnit unit;

        UnitEntry(int position, OutputUnit unit) {
            this.position = position;
            this.unit = unit;
        }
    }

    /** 组单元：成员全部移除（空组）时返回 null */
    private static <I> OutputUnit groupUnit(GroupItem<?> g, ItemView<I> view, boolean imageMode,
                                            AppConfig textGlobal, AppConfig imageGlobal,
                                            String sessionDir) {
        List<I> members = new ArrayList<>();
        for (Object m : g.getMembers()) {
            @SuppressWarnings("unchecked")
            I item = (I) m;
            if (!view.removed(item)) {
                members.add(item);
            }
        }
        if (members.isEmpty()) {
            return null;
        }

        AppConfig params = resolve(g, null, null, imageMode, textGlobal, imageGlobal);
        boolean separate = imageMode ? !params.isSingleOutputMode() : params.isSeparateFiles();
        String name = sanitizedGroupName(g);
        String outputPath = (!imageMode && !separate)
                ? new File(sessionDir, name + ".txt").getPath()
                : new File(sessionDir, name).getPath();
        return new OutputUnit(name, true, params, pathsOf(members, view), outputPath);
    }

    private static String sanitizedGroupName(GroupItem<?> g) {
        String s = SanitizeUtils.sanitize(g.getName());
        if (s.isEmpty()) {
            return I18n.get("core.groupDefault") + g.getGroupNo();
        }
        return s;
    }

    private static String sourceOutputName(String sourceFolder, boolean imageMode) {
        String dirName = sourceFolder == null || sourceFolder.isEmpty()
                ? "" : new File(sourceFolder).getName();
        String s = SanitizeUtils.sanitize(dirName);
        if (s.isEmpty()) {
            return imageMode ? "All_Images" : "mht_output";
        }
        return s;
    }

    private static String sourceKey(String sourceFolder) {
        return sourceFolder == null ? "" : sourceFolder;
    }

    private static AppConfig resolve(GroupItem<?> parentGroup, AppConfig itemText, AppConfig itemImage,
                                     boolean imageMode, AppConfig textGlobal, AppConfig imageGlobal) {
        AppConfig p = ParamScope.effectiveParams(parentGroup, itemText, itemImage,
                imageMode, textGlobal, imageGlobal);
        return p != null ? p : new AppConfig();
    }

    /** 未分组条目的生效参数（组参数恒为 null，条目参数取 ItemView 快照） */
    private static <I> AppConfig resolveItem(I item, ItemView<I> view, boolean imageMode,
                                             AppConfig textGlobal, AppConfig imageGlobal) {
        return resolve(null, view.textItemParams(item), view.imageItemParams(item),
                imageMode, textGlobal, imageGlobal);
    }

    private static <I> List<String> pathsOf(List<I> items, ItemView<I> view) {
        List<String> out = new ArrayList<>(items.size());
        for (I it : items) {
            out.add(view.path(it));
        }
        return out;
    }
}
