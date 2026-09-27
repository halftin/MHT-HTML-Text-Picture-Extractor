package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;

/**
 * 西文章节命名（会话 15C，架构设计 §8.1）：{@code Chapter N}，N = index + 1（1 起）。
 * 与中文策略一致：任何大小均用阿拉伯数字，无上限（中文策略 &gt;9999 的数字回退与之同形）。
 *
 * <p><b>为什么用 {@code core.chapter.westernPrefix} 而不是 {@code core.chapter.prefix}</b>：
 * 后者的 en 值是 {@code "Chapter "}、zh 值是 {@code "第"} —— 它属于"中文策略的前缀"。
 * 而本策略在**任何非 zh locale** 下都会被选中，其中包括**没有 bundle 的 locale**
 * （如 ja_JP：{@code ResourceBundle} 会回退到 JVM 默认语言的 bundle，本机为 zh_CN）。
 * 若借用 {@code core.chapter.prefix}，ja 用户会拿到 {@code "第1"}；
 * 独立的 westernPrefix 在两份 bundle 里都是 {@code "Chapter "}，
 * 保证"非 zh ⇒ Chapter N"这一 15C 以来的既有行为不因回退而破功。</p>
 */
public final class WesternChapterNamingStrategy implements ChapterNamingStrategy {

    @Override
    public String generateChapterName(int index) {
        if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        return I18n.get("core.chapter.westernPrefix") + (index + 1);
    }
}
