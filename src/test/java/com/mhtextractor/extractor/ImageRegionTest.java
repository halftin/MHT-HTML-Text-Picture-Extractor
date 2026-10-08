package com.mhtextractor.extractor;

import com.mhtextractor.core.MimeParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageRegionTest {
    private static final String HTML = "<img src='cid:photo_a'>START<img src='cid:photo_b'>END<img src='cid:photo_c'>";

    @Test void priorityAndMarkerValidity() {
        assertEquals(ImageRegion.Rule.R1, ImageRegion.resolve(HTML, "START", "END").rule());
        for (String unset : new String[]{null, "", " ", "missing"}) {
            assertEquals(ImageRegion.Rule.R2, ImageRegion.resolve(HTML, unset, unset).rule());
            assertEquals(ImageRegion.Rule.R3, ImageRegion.resolve(HTML, "START", unset).rule());
            assertEquals(ImageRegion.Rule.R4, ImageRegion.resolve(HTML, unset, "END").rule());
        }
        assertEquals(ImageRegion.Rule.R2, ImageRegion.resolve("END START", "START", "END").rule());
        assertEquals("inside", ImageRegion.resolve("END STARTinsideENDlaterEND", "START", "END").html());
        assertEquals(ImageRegion.Rule.R2, ImageRegion.resolve("SAME", "SAME", "SAME").rule());
    }

    @Test void r1ToR5KeepDeclarationOrderAndOnlyWidenMembership() {
        var parsed = new MimeParser.ParsedMht();
        parsed.setHtmlContent(HTML);
        // Intentionally differs from HTML layout a,b,c: ordering is outside this batch.
        parsed.setImageInfoList(List.of(image("c"), image("b"), image("a")));
        String[][] markers = {{"START", "END"}, {"bad", "bad"}, {"START", "bad"}, {"bad", "END"},
                {"START", "<img"}, {"END", "START"}};
        List<List<String>> expected = List.of(List.of("b"), List.of("c", "b", "a"), List.of("c", "b"),
                List.of("b", "a"), List.of("c", "b", "a"), List.of("c", "b", "a"));
        for (int i = 0; i < markers.length; i++) {
            var ex = new ImageExtractor(parsed, null, null, markers[i][0], markers[i][1]);
            var ids = ex.extractImages().stream().map(img -> img.getContentId().substring(6)).toList();
            assertEquals(expected.get(i), ids, "case " + i);
            System.out.println("REGION case=" + i + " images=" + ids + " warnings=" + ex.getLastWarnings());
        }
    }

    @Test void emptyRelaxedRegionsAlwaysExplainNoImages() {
        var parsed = new MimeParser.ParsedMht();
        parsed.setHtmlContent("START END");
        parsed.setImageInfoList(List.of());
        for (var markers : List.of(List.of("bad", "bad"), List.of("START", "bad"),
                List.of("bad", "END"), List.of("START", "END"))) {
            var ex = new ImageExtractor(parsed, null, null, markers.get(0), markers.get(1));
            assertTrue(ex.extractImages().isEmpty());
            assertEquals("extract.region.noImages", ex.getLastWarnings().getLast().key());
        }
    }

    private static MimeParser.ImageInfo image(String cid) {
        return new MimeParser.ImageInfo("photo_" + cid, null, "photo_" + cid, new byte[]{1});
    }
}
