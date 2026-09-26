package com.mhtextractor.core;

/**
 * 西文章节命名（会话 15C，架构设计 §8.1）：{@code Chapter N}，N = index + 1（1 起）。
 * 与中文策略一致：任何大小均用阿拉伯数字，无上限（中文策略 &gt;9999 的数字回退与之同形）。
 */
public final class WesternChapterNamingStrategy implements ChapterNamingStrategy {

    @Override
    public String generateChapterName(int index) {
        if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        return "Chapter " + (index + 1);
    }
}
