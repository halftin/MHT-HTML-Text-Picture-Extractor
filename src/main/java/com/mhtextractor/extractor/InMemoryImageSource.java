package com.mhtextractor.extractor;

import java.util.List;

/**
 * 内存图片数据源：包装现有提取链路的 {@code List<ImageExtractor.ImageData>}。
 */
public class InMemoryImageSource implements ImageSource {

    private final List<ImageExtractor.ImageData> images;

    public InMemoryImageSource(List<ImageExtractor.ImageData> images) {
        this.images = List.copyOf(images);
    }

    @Override
    public int size() {
        return images.size();
    }

    @Override
    public ImageExtractor.ImageData get(int index) {
        return images.get(index);
    }
}
