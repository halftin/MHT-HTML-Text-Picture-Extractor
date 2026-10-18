package com.mhtextractor.gui.controller;

import com.mhtextractor.i18n.Texts;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.util.ThumbnailFxBridge;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.platform.FileOpener;
import com.mhtextractor.uiport.PortEvent;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 预览渲染协作者（C1b）—— 承载文本预览、缩略图呈现与"单击缩略图打开缓存图片"这一组渲染动作，
 * 从 {@code MainController} 原样外迁：{@code renderTextPreview} / {@code resetImageArea} /
 * {@code renderThumbnails} / {@code appendThumb} / {@code buildThumbBox} / {@code openCachedImage} /
 * 图片名称与格式直接读取端口事实；对宿主的全部能力需求收窄为 {@link Host}。
 *
 * <p>文案键、日志键（{@code log.maincontroller.64/65/66}）、线程名（{@code open-cached-image}）、
 * 缩略图下标与缓存清单 {@code meta.images()} 的 1:1 语义、{@link FileOpener} 的两级兜底
 * （系统默认查看器 → 「照片」协议）、以及端口提供的合并输出和批量重命名显示名
 * 全部保持不变。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层；业务侧不得反向依赖它。</p>
 */
@Slf4j
public final class PreviewRenderer {

    /** 宿主能力窄接口：本类不持有任何控件字段，全部经此接口驱动预览区。 */
    public interface Host {
        /** 文本预览区（搬迁前是 {@code refs.textPreviewArea()}）。 */
        TextArea textPreviewArea();

        /** 图片区提示标签（搬迁前是 {@code refs.imageHintLabel()}）。 */
        Label imageHintLabel();

        /** 图片滚动容器（搬迁前是 {@code refs.imageScrollPane()}）。 */
        ScrollPane imageScrollPane();

        /** 缩略图流式容器（搬迁前是 {@code refs.thumbnailPane()}）。 */
        FlowPane thumbnailPane();

        /** 按需加载按钮（搬迁前是 {@code refs.previewEffectButton()}）。 */
        Button previewEffectButton();

        /** 条目/组生效参数（搬迁前是 {@code effectiveForObject(Object)}）。 */
        AppConfig effectiveParams(Object item);

        /** 可见文件数（搬迁前是 {@code visibleCount()}）。 */
        int visibleCount();

        /** 条目在输出单元规划中的序号（搬迁前是 {@code unitIndex(PreviewRow)}）。 */
        int unitIndex(Object item);

    }

    private final Host host;

    public PreviewRenderer(Host host) {
        this.host = host;
    }

    public void renderTextPreview(PreviewRow item) {
        AppConfig params = host.effectiveParams(item);
        if (params == null) {
            params = new AppConfig();
        }
        String startTemplate = params.isEnableTemplate() ? params.getStartTemplate() : "";
        String endTemplate = params.isEnableTemplate() ? params.getEndTemplate() : "";
        String head = item.previewHead;
        String tail = item.previewTail;
        String text = item.cachedText == null ? "" : item.cachedText;
        if (text.length() <= 10000) {
            host.textPreviewArea().setText(head + text + tail);
        } else {
            host.textPreviewArea().setText(head
                    + text.substring(0, 5000)
                    + Texts.get("preview.extractedContentDivider")
                    + text.substring(text.length() - 5000)
                    + tail);
        }
    }

    /** 重置图片区：hint 为 null 时显示默认按需预览说明 */
    public void resetImageArea(String hint, boolean showPreviewButton) {
        host.imageHintLabel().setText(hint != null ? hint : Texts.get("preview.imageLazyHint"));
        host.imageHintLabel().setVisible(true);
        host.previewEffectButton().setVisible(showPreviewButton);
        host.previewEffectButton().setDisable(false);
        host.imageScrollPane().setVisible(false);
        host.imageScrollPane().setManaged(false);
        host.thumbnailPane().getChildren().clear();
    }

    public void renderThumbnails(PreviewRow item) {
        List<ThumbnailData> thumbs = item.cachedThumbs;
        host.thumbnailPane().getChildren().clear();
        if (thumbs == null || thumbs.isEmpty()) {
            host.imageHintLabel().setText(Texts.get("preview.noImages"));
            host.imageHintLabel().setVisible(true);
            host.previewEffectButton().setVisible(false);
            host.imageScrollPane().setVisible(false);
            host.imageScrollPane().setManaged(false);
            return;
        }
        host.imageHintLabel().setVisible(false);
        host.previewEffectButton().setVisible(false);
        host.imageScrollPane().setVisible(true);
        host.imageScrollPane().setManaged(true);

        // 下标 = 缓存清单 meta.images() 的序号（cachedThumbs 与 meta 同序 1:1，见 openCachedImage 的索引语义）
        for (int i = 0; i < thumbs.size(); i++) {
            host.thumbnailPane().getChildren().add(buildThumbBox(item, thumbs.get(i), i));
        }
    }

    public void appendThumb(PreviewRow item, ThumbnailData entry, int index) {
        appendThumb(item, entry, index, null);
    }

    public void appendThumb(PreviewRow item, ThumbnailData entry, int index, PortEvent.ImageFile file) {
        host.imageHintLabel().setVisible(false);
        host.previewEffectButton().setVisible(false);
        host.imageScrollPane().setVisible(true);
        host.imageScrollPane().setManaged(true);
        host.thumbnailPane().getChildren().add(buildThumbBox(item, entry, index, file));
    }

    public ThumbnailData decodeFile(PortEvent.ImageFile file) {
        try {
            return decode(Files.readAllBytes(Path.of(file.imagePath() == null ? file.path() : file.imagePath())),
                    file.extension(), file.coreName());
        } catch(java.io.IOException error) {
            return new ThumbnailData(file.coreName(),0,0,null);
        }
    }

    private VBox buildThumbBox(PreviewRow item, ThumbnailData entry, int index) {
        var file = item.cachedImages == null || index >= item.cachedImages.size()
                ? null : item.cachedImages.get(index);
        return buildThumbBox(item, entry, index, file);
    }

    private VBox buildThumbBox(PreviewRow item, ThumbnailData entry, int index, PortEvent.ImageFile file) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        // E1.4：模型只存像素；JavaFX Image 在此（渲染层）按需构造。构造失败等价于原 image == null
        Image fxImage = ThumbnailFxBridge.toFxImage(entry);
        if (fxImage == null) {
            box.getChildren().add(new Label(Texts.get("preview.loadFailed")));
        } else {
            ImageView iv = new ImageView(fxImage);
            iv.setFitWidth(150);
            iv.setFitHeight(150);
            iv.setPreserveRatio(true);
            box.getChildren().add(iv);
        }
        Label nameLabel = new Label(file == null || file.displayName() == null ? entry.coreName() : file.displayName());
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(150);
        nameLabel.setStyle("-fx-font-size: 0.85em;");
        box.getChildren().add(nameLabel);
        // 点击查看（需求：预览区图片可点击查看）：单击缩略图 → 系统默认图片查看器打开缓存目录里对应的那张图
        box.setCursor(Cursor.HAND);
        Tooltip.install(box, new Tooltip(Texts.get("preview.thumbClickHint")));
        box.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) openCachedImage(item, index); });
        return box;
    }

    /** 单击缩略图：用系统默认图片查看器打开缓存目录里对应的图片（非 AWT；启动/校验/环境兜底见 {@link FileOpener}）。 */
    private void openCachedImage(PreviewRow item, int index) {
        // E1.2：cachedMeta 为 null（参数变更后清空 / 从未落盘）时安全返回，warn 复用既有
        // file == null 分支（同一日志键 log.maincontroller.65，不新增日志调用点）。
        Path file = item.cachedMeta == null || index >= item.cachedMeta.images().size() ? null
                : Path.of(item.cachedMeta.images().get(index).path());
        if (file == null || !Files.isRegularFile(file)) {
            log.warn(Texts.msg("log.maincontroller.65", item.name));
            return;
        }
        // index 语义见 renderThumbnails 注释；打开最多等 3 秒（校验退出码）再兜底 → 后台线程，避免冻结 FX 线程
        Thread t = new Thread(() -> {
            FileOpener.Route route = FileOpener.open(file);
            if (route == FileOpener.Route.FAILED) {
                log.warn(Texts.msg("log.maincontroller.65", item.name));
            } else {
                log.info(Texts.msg(route == FileOpener.Route.PHOTOS_PROTOCOL
                        ? "log.maincontroller.66" : "log.maincontroller.64", file));
            }
        }, "open-cached-image");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 解码单张缩略图：WebP 走 JWebP 缩放解码（150 边界），其余走 JavaFX 原生缩放解码。
     *
     * <p>E1.4：返回值由"持有 JavaFX {@code Image}"改为"持有纯像素"（{@link ThumbnailData}），
     * 使该数据可脱离 UI 框架存在。解码仍在<b>后台线程</b>完成（缩放边界与算法不变），
     * JavaFX {@code Image} 的构造下沉到渲染层的
     * {@link com.mhtextractor.gui.util.ThumbnailFxBridge#toFxImage(ThumbnailData)}。失败时 pixels=null，
     * 渲染层显示"无法加载"占位 —— 与原 {@code image == null} 语义一致。</p>
     */
    public ThumbnailData decode(byte[] data, String extension, String coreName) {
        Image img = null;
        try {
            if ("webp".equals(extension)) {
                WebPImage webp = WebPImage.read(new ByteArrayInputStream(data),
                        WebPImageLoadOptions.builder()
                                .requestedWidth(150).requestedHeight(150)
                                .preserveRatio(true).smooth(true)
                                .build());
                img = new WebPFXImage(webp);
            } else {
                img = new Image(new ByteArrayInputStream(data), 150, 150, true, true);
            }
        } catch (Exception e) {
            // img 保持 null → 占位"无法加载"
        }
        return ThumbnailFxBridge.toThumbnailData(img, coreName);
    }
}
