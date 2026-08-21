package com.xb.sgc.papererase.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Independent Word writer adapted from the old skill's merge concept: one exam page image per
 * Word page, preserving the caller-provided image order and without runtime references to old code.
 */
public class WordMergeComponent {
    public void merge(List<Path> imagePaths, Path output) throws IOException {
        if (imagePaths == null || output == null) {
            throw new IllegalArgumentException("imagePaths and output are required");
        }
        Files.createDirectories(output.getParent());
        try (OutputStream fileOut = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            write(zip, "[Content_Types].xml", contentTypes(imagePaths.size()));
            write(zip, "_rels/.rels", rootRelationships());
            write(zip, "word/_rels/document.xml.rels", documentRelationships(imagePaths));
            write(zip, "word/document.xml", documentXml(imagePaths));
            for (int i = 0; i < imagePaths.size(); i++) {
                String name = "word/media/image" + (i + 1) + ".png";
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(imagePaths.get(i), zip);
                zip.closeEntry();
            }
        }
    }

    private static String contentTypes(int imageCount) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        xml.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        xml.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        xml.append("<Default Extension=\"png\" ContentType=\"image/png\"/>");
        xml.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>");
        xml.append("</Types>");
        return xml.toString();
    }

    private static String rootRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String documentRelationships(List<Path> imagePaths) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < imagePaths.size(); i++) {
            xml.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                    .append(i + 1).append(".png\"/>");
        }
        xml.append("</Relationships>");
        return xml.toString();
    }

    private static String documentXml(List<Path> imagePaths) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ");
        xml.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ");
        xml.append("xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" ");
        xml.append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ");
        xml.append("xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">");
        xml.append("<w:body>");
        for (int i = 0; i < imagePaths.size(); i++) {
            String name = imagePaths.get(i).getFileName().toString();
            xml.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">");
            xml.append("<wp:extent cx=\"6000000\" cy=\"8000000\"/><wp:docPr id=\"").append(i + 1)
                    .append("\" name=\"").append(escape(name)).append("\" descr=\"").append(escape(name)).append("\"/>");
            xml.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">");
            xml.append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(i + 1).append("\" name=\"")
                    .append(escape(name)).append("\"/><pic:cNvPicPr/></pic:nvPicPr>");
            xml.append("<pic:blipFill><a:blip r:embed=\"rId").append(i + 1)
                    .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>");
            xml.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"6000000\" cy=\"8000000\"/></a:xfrm>");
            xml.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>");
            xml.append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
            if (i + 1 < imagePaths.size()) {
                xml.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
        }
        xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>");
        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void write(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
