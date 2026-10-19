package com.mhtextractor.model;


/**
 * 图片数据在应用层的无头数据载体（P3-3b-11）：字段与
 * {@code extractor.ImageExtractor.ImageData} 对齐（originalName / sourceId / data），
 * 供 gui 层预览渲染路径携带图片字节，使 gui 不再直连 {@code extractor.ImageExtractor}；
 * 进入业务链路时经 {@code PreviewNamingService.imageFileName} 转回底层载体。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code model} 层。</p>
 */
public record PreviewImageData(String originalName, String sourceId, byte[] data) {

    /** 与 {@code ImageExtractor.ImageData#getData()} 同义的访问器，供现有调用面沿用。 */
    public byte[] getData() {
        return data();
    }
}
