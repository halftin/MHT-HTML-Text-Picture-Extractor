package com.mhtextractor.extractor;

import java.util.List;

/**
 * 图片数据源的统一抽象：写入层只依赖本接口，不关心图片来自内存还是磁盘。
 */
public interface ImageSource {

    /** 图片总数 */
    int size();

    /** 第 index 张图片（0 起） */
    ImageExtractor.ImageData get(int index);

    /** 数据源自身的警告信息（默认无） */
    default List<String> warnings() {
        return List.of();
    }
}
