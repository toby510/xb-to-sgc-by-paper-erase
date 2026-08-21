package com.xb.sgc.papererase.output;

import org.docx4j.XmlUtils;
import org.docx4j.dml.CTBlipFillProperties;
import org.docx4j.dml.CTNonVisualDrawingProps;
import org.docx4j.dml.CTNonVisualGraphicFrameProperties;
import org.docx4j.dml.CTPoint2D;
import org.docx4j.dml.CTPositiveSize2D;
import org.docx4j.dml.CTShapeProperties;
import org.docx4j.dml.CTTransform2D;
import org.docx4j.dml.Graphic;
import org.docx4j.dml.GraphicData;
import org.docx4j.dml.picture.CTPictureNonVisual;
import org.docx4j.dml.picture.Pic;
import org.docx4j.dml.wordprocessingDrawing.Anchor;
import org.docx4j.dml.wordprocessingDrawing.CTEffectExtent;
import org.docx4j.dml.wordprocessingDrawing.CTPosH;
import org.docx4j.dml.wordprocessingDrawing.CTPosV;
import org.docx4j.dml.wordprocessingDrawing.CTWrapNone;
import org.docx4j.dml.wordprocessingDrawing.CTWrapSquare;
import org.docx4j.dml.wordprocessingDrawing.STRelFromH;
import org.docx4j.dml.wordprocessingDrawing.STRelFromV;
import org.docx4j.dml.wordprocessingDrawing.STWrapText;
import org.docx4j.openpackaging.contenttype.ContentType;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPartAbstractImage;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.ImageJpegPart;
import org.docx4j.openpackaging.parts.WordprocessingML.ImagePngPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.relationships.Relationship;
import org.docx4j.wml.Br;
import org.docx4j.wml.Document;
import org.docx4j.wml.Drawing;
import org.docx4j.wml.Ftr;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.R;
import org.docx4j.wml.STBrType;
import org.docx4j.wml.Text;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 按卷合并 Word（独立拷贝成熟 merge_word.py 视觉输出逻辑）。
 *
 * 关键复刻点（与 cc python-docx 一致）：
 *   - PLACEHOLDER_LINES=5 钉第一行坐标
 *   - 图浮动：wp:anchor + behindDoc=1 + wrapNone + relativeHeight=251659264
 *   - positionH/V relativeFrom=margin posOffset=0（贴容器左上角）
 *   - 删除模板自带空段（避免首页空白）
 *   - 清空模板 footer page 字段
 *   - 等比缩放：min(cw/imgW, ch/imgH)
 */
public class WordMergeComponent {

    private static final int PLACEHOLDER_LINES = 5;
    private static final int CALIBRATED_BREAK_COUNT = 59;
    private static final long RELATIVE_HEIGHT_BEHIND = 251659264L;
    private static final int PX_TO_EMU = 9525;
    /** line=240 twips = 12pt = 152400 EMU（OOXML 1pt=12700 EMU） */
    private static final long LINE_HEIGHT_EMU = 152400L;

    private final String templatePath;
    private final long containerWidthEmu;
    private final long containerHeightEmu;
    // 新方法 mergeOneAligned 用：页面坐标系下的"页眉下沿"和"页脚上沿"绝对偏移 + 页面宽
    // 0 表示走老路径（mergeOne + 容器内坐标）
    private final long pageWidthEmu;
    private final long headerOffsetEmu;
    private final long bottomOffsetEmu;

    public WordMergeComponent() {
        this(defaultTemplatePath(), 6_120_130L, 9_180_195L,
                7_560_310L, 791_845L, 9_972_040L);
    }

    /** 老构造器（mergeOne 用，容器内坐标 = 旧行为）。 */
    public WordMergeComponent(String templatePath, long containerWidthEmu, long containerHeightEmu) {
        this(templatePath, containerWidthEmu, containerHeightEmu, 0L, 0L, 0L);
    }

    /**
     * 新构造器（mergeOneAligned 用）。
     *
     * @param pageWidthEmu    A4 页面宽（如 11906 twips * 635 = 7560310 EMU = 21cm）
     * @param headerOffsetEmu 页眉下沿 y 偏移（页面顶 = 0，page-relative，如 margin_top = 1247 twips = 791845 EMU）
     * @param bottomOffsetEmu 页脚上沿 y 偏移（页面顶 = 0，page-relative，如 16838-1134 = 15704 twips = 9972040 EMU）
     */
    public WordMergeComponent(String templatePath, long containerWidthEmu, long containerHeightEmu,
                              long pageWidthEmu, long headerOffsetEmu, long bottomOffsetEmu) {
        this.templatePath = templatePath;
        this.containerWidthEmu = containerWidthEmu;
        this.containerHeightEmu = containerHeightEmu;
        this.pageWidthEmu = pageWidthEmu;
        this.headerOffsetEmu = headerOffsetEmu;
        this.bottomOffsetEmu = bottomOffsetEmu;
    }

    public void merge(List<Path> imagePaths, Path output) throws Exception {
        List<String[]> pages = new ArrayList<String[]>();
        for (Path imagePath : imagePaths) {
            pages.add(new String[]{"unused", imagePath.toAbsolutePath().toString()});
        }
        mergeOneAlignedTemplateCalibrated(pages, output.toAbsolutePath().toString(), CALIBRATED_BREAK_COUNT);
    }

    private static String defaultTemplatePath() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path template = current.resolve("resources").resolve("word-template.docx");
            if (template.toFile().isFile()) {
                return template.toString();
            }
            current = current.getParent();
        }
        return Paths.get("resources", "word-template.docx").toAbsolutePath().toString();
    }

    public void mergeOne(List<String[]> pages, String outPath) throws Exception {
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(new File(templatePath));
        MainDocumentPart mainPart = wordMLPackage.getMainDocumentPart();
        stripTemplateEmptyParagraphs(mainPart);
        clearPageNumbers(wordMLPackage);

        int n = pages.size();
        for (int i = 0; i < n; i++) {
            String imagePath = pages.get(i)[1];
            boolean isLast = (i == n - 1);
            File imgFile = new File(imagePath);
            if (!imgFile.exists()) {
                addPlaceholder(mainPart, "图片缺失: " + imagePath, isLast);
                continue;
            }
            BufferedImage img = ImageIO.read(imgFile);
            long[] fit = computeFit(img.getWidth(), img.getHeight(), containerWidthEmu, containerHeightEmu);
            addAntiDriftPicture(mainPart, imagePath, fit[0], fit[1], isLast);
        }

        new File(outPath).getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(outPath)) {
            wordMLPackage.save(os);
        }
    }

    public static long[] computeFit(int imgW, int imgH, long contWEmu, long contHEmu) {
        long imgWEmu = (long) imgW * PX_TO_EMU;
        long imgHEmu = (long) imgH * PX_TO_EMU;
        double scale = Math.min((double) contWEmu / imgWEmu, (double) contHEmu / imgHEmu);
        return new long[]{(long) (imgWEmu * scale), (long) (imgHEmu * scale)};
    }

    private void addPlaceholderLines(MainDocumentPart mainPart) {
        for (int i = 0; i < PLACEHOLDER_LINES; i++) {
            mainPart.addObject(newP());
        }
    }

    /**
     * 计算每页图片锚点后的空段数。
     *
     * <p>对齐合成不写入显式分页符：图片锚点段先留在当前页，再追加足以超过正文容器高度的
     * 空段，Word 才会把下一张图片的锚点自然排到下一页。必须向上取整；向下取整会让下一页
     * 的锚点仍落在当前页，造成两张图叠在同一页。</p>
     */
    private int computeAlignedPageFillers() {
        long contentHeight = bottomOffsetEmu - headerOffsetEmu;
        return (int) Math.max(1L, (contentHeight + LINE_HEIGHT_EMU - 1) / LINE_HEIGHT_EMU);
    }

    private void addFloatingPicture(MainDocumentPart mainPart, String imagePath,
                                    long displayW, long displayH, boolean isLast) throws Exception {
        String relId = addImageAndGetRelId(mainPart, imagePath);
        long docPrId = nextDocPrId();

        Anchor anchor = new Anchor();
        anchor.setDistT(0L);
        anchor.setDistB(0L);
        anchor.setDistL(0L);
        anchor.setDistR(0L);
        anchor.setSimplePosAttr(Boolean.FALSE);
        anchor.setRelativeHeight(RELATIVE_HEIGHT_BEHIND);
        anchor.setBehindDoc(true);
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(false);

        CTPoint2D simplePos = new CTPoint2D();
        simplePos.setX(0L);
        simplePos.setY(0L);
        anchor.setSimplePos(simplePos);

        CTPosH posH = new CTPosH();
        posH.setRelativeFrom(org.docx4j.dml.wordprocessingDrawing.STRelFromH.MARGIN);
        posH.setPosOffset(0);
        anchor.setPositionH(posH);

        CTPosV posV = new CTPosV();
        posV.setRelativeFrom(org.docx4j.dml.wordprocessingDrawing.STRelFromV.MARGIN);
        posV.setPosOffset(0);
        anchor.setPositionV(posV);

        CTPositiveSize2D extent = new CTPositiveSize2D();
        extent.setCx(displayW);
        extent.setCy(displayH);
        anchor.setExtent(extent);

        CTEffectExtent effectExtent = new CTEffectExtent();
        effectExtent.setL(0L); effectExtent.setT(0L);
        effectExtent.setR(0L); effectExtent.setB(0L);
        anchor.setEffectExtent(effectExtent);

        anchor.setWrapNone(new CTWrapNone());

        CTNonVisualDrawingProps docPr = new CTNonVisualDrawingProps();
        docPr.setId(docPrId);
        docPr.setName("Picture " + docPrId);
        anchor.setDocPr(docPr);
        anchor.setCNvGraphicFramePr(new CTNonVisualGraphicFrameProperties());

        // pic
        org.docx4j.dml.picture.ObjectFactory picOF = new org.docx4j.dml.picture.ObjectFactory();
        Pic pic = picOF.createPic();
        CTPictureNonVisual nvPicPr = picOF.createCTPictureNonVisual();
        CTNonVisualDrawingProps cNvPr = new CTNonVisualDrawingProps();
        cNvPr.setId(docPrId);
        cNvPr.setName("Picture " + docPrId);
        nvPicPr.setCNvPr(cNvPr);
        nvPicPr.setCNvPicPr(new org.docx4j.dml.CTNonVisualPictureProperties());
        pic.setNvPicPr(nvPicPr);

        // blipFill
        org.docx4j.dml.ObjectFactory dmlOF = new org.docx4j.dml.ObjectFactory();
        CTBlipFillProperties blipFill = dmlOF.createCTBlipFillProperties();
        org.docx4j.dml.CTBlip blip = dmlOF.createCTBlip();
        blip.setEmbed(relId);
        blipFill.setBlip(blip);
        org.docx4j.dml.CTStretchInfoProperties stretch = dmlOF.createCTStretchInfoProperties();
        stretch.setFillRect(dmlOF.createCTRelativeRect());
        blipFill.setStretch(stretch);
        pic.setBlipFill(blipFill);

        // spPr
        CTShapeProperties spPr = new CTShapeProperties();
        CTTransform2D xfrm = new CTTransform2D();
        CTPoint2D off = new CTPoint2D();
        off.setX(0L); off.setY(0L);
        xfrm.setOff(off);
        xfrm.setExt(extent);
        spPr.setXfrm(xfrm);
        spPr.setPrstGeom(dmlOF.createCTPresetGeometry2D());
        spPr.getPrstGeom().setPrst(org.docx4j.dml.STShapeType.fromValue("rect"));
        spPr.getPrstGeom().setAvLst(dmlOF.createCTGeomGuideList());
        pic.setSpPr(spPr);

        GraphicData graphicData = new GraphicData();
        graphicData.setUri("http://schemas.openxmlformats.org/drawingml/2006/picture");
        graphicData.getAny().add(pic);
        Graphic graphic = new Graphic();
        graphic.setGraphicData(graphicData);
        anchor.setGraphic(graphic);

        Drawing drawing = new Drawing();
        drawing.getAnchorOrInline().add(anchor);

        P p = newP();
        R r = new R();
        r.getRunContent().add(drawing);
        p.getParagraphContent().add(r);
        mainPart.addObject(p);

        if (!isLast) {
            P pbP = newP();
            R pbR = new R();
            Br br = new Br();
            br.setType(STBrType.PAGE);
            pbR.getRunContent().add(br);
            pbP.getParagraphContent().add(pbR);
            mainPart.addObject(pbP);
        }
    }

    private void addAntiDriftPicture(MainDocumentPart mainPart, String imagePath,
                                     long displayW, long displayH, boolean isLast) throws Exception {
        addPlaceholderLines(mainPart);
        addFloatingPicture(mainPart, imagePath, displayW, displayH, isLast);
    }

    // ==================== 页面绝对坐标合成 ====================

    /**
     * 按“先锚图、再占位、自然换页”的顺序合成整卷。
     *
     * <p>浮动图片属于其所在的锚点段；若先写占位段，锚点会被推到下一页，导致第一页只剩空白。
     * 因此每页必须先写图片锚点，再写满空段。空段越过正文容器后，下一页的锚点自然进入下一页，
     * 禁止额外写 {@code w:br type="page"}，否则会多出空白页。</p>
     */
    public void mergeOneAligned(List<String[]> pages, String outPath) throws Exception {
        validateAlignedGeometry();
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(new File(templatePath));
        MainDocumentPart mainPart = wordMLPackage.getMainDocumentPart();
        stripTemplateEmptyParagraphs(mainPart);
        clearPageNumbers(wordMLPackage);

        int pageFillers = computeAlignedPageFillers();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String[] page = pages.get(pageIndex);
            boolean hasNextPage = pageIndex < pages.size() - 1;
            String imagePath = page[1];
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                appendAlignedMissingImagePage(mainPart, imagePath, pageFillers, hasNextPage);
                continue;
            }

            BufferedImage image = ImageIO.read(imageFile);
            long[] displaySize = computeAlignedDisplaySize(image);
            long horizontalOffset = (pageWidthEmu - displaySize[0]) / 2;
            appendAlignedImagePage(mainPart, imagePath, displaySize[0], displaySize[1],
                    horizontalOffset, pageFillers, hasNextPage);
        }

        File targetFile = new File(outPath);
        targetFile.getParentFile().mkdirs();
        try (OutputStream output = new FileOutputStream(targetFile)) {
            wordMLPackage.save(output);
        }
    }

    /**
     * 固定模板的校准换行方案。
     *
     * <p>保留 {@link #mergeOneAligned(List, String)} 的“图片锚点 + 普通换行”语义，
     * 但不再按通用公式猜测换行数。调用方将针对唯一模板标定出的换行数显式传入；所有占位段
     * 使用固定行高，避免继承模板的字体或文档网格后逐页累积漂移。</p>
     *
     * <h3>页面组织规则</h3>
     * <ol>
     *   <li>先创建图片所属的锚点段落。浮动图片的页码由该段落决定，因此绝不能先写换行，
     *       否则第一张图片会被推到下一页。</li>
     *   <li>图片以页面绝对坐标定位：横向居中，纵向固定在页眉下沿；图片缩放范围受页面宽度和
     *       页眉/页脚之间可用高度共同约束，不能改变原图比例。</li>
     *   <li>再在同一段落追加普通 {@code w:br}。非末页使用完整校准值，把下一页锚点自然推入
     *       新页；这里禁止使用 {@code w:br type="page"}，避免额外分页导致空白页。</li>
     *   <li>末页仍保留占位换行，但比完整校准值少一条。锚点段自身已经占一行，少一条可以让末页
     *       视觉上占满，又不会创建没有下一张图片的尾部空白页。</li>
     * </ol>
     *
     * <p>当前固定模板已实测：调用方传入 {@code 59}。因此非末页为 59 条换行，末页为 58 条。
     * 这个数值是模板版式参数，不应与其他模板混用；模板页边距、正文行距或页面网格变化后，需要
     * 重新校准。</p>
     *
     * <p>这是新增入口，旧方法及其历史调用均保持不变。</p>
     */
    public void mergeOneAlignedTemplateCalibrated(List<String[]> pages, String outPath, int calibratedBreakCount)
            throws Exception {
        validateAlignedGeometry();
        if (calibratedBreakCount <= 0) {
            throw new IllegalArgumentException("calibratedBreakCount 必须大于 0");
        }

        // 每次调用都从模板重新加载独立文档包：同一 JVM 连续合并多份试卷时，前一份的正文、图片
        // 和关系对象不会进入后一份，确保后写入任务不影响已生成 Word。
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(new File(templatePath));
        MainDocumentPart mainPart = wordMLPackage.getMainDocumentPart();
        // 模板可能带有初始空段或页码。空段会改变第一个锚点的归属页，页码会与图片页码混淆，
        // 所以在添加任何图片前统一清理。
        stripTemplateEmptyParagraphs(mainPart);
        clearPageNumbers(wordMLPackage);

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            // pages 的第二列约定为图片绝对路径；第一页的顺序即 Word 页序，不能自行排序或跳号。
            String imagePath = pages.get(pageIndex)[1];
            boolean hasNextPage = pageIndex < pages.size() - 1;
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                // 缺图也必须占用一页，否则后续图片会提前，导致图片页序与 JSON/原始页序不再对应。
                appendCalibratedMissingImagePage(mainPart, imagePath, calibratedBreakCount, hasNextPage);
                continue;
            }

            BufferedImage image = ImageIO.read(imageFile);
            // 这里只计算展示尺寸和 X 偏移；Y 坐标始终使用模板测量得到的页眉下沿。
            long[] displaySize = computeAlignedDisplaySize(image);
            long horizontalOffset = (pageWidthEmu - displaySize[0]) / 2;
            appendCalibratedImagePage(mainPart, imagePath, displaySize[0], displaySize[1], horizontalOffset,
                    calibratedBreakCount, hasNextPage);
        }
        savePackage(wordMLPackage, outPath);
    }

    /**
     * 固定页容器实验方案：每张图放进一个无边框、固定行高且不允许拆分的单行表格。
     *
     * <p>表格的高度由模板页眉下沿和页脚上沿推导，后一张表格自然进入下一页，不写入 PAGE
     * 类型分页符，也不依赖换行数量。该方法只供对照验收，旧合成方法不会调用它。</p>
     */
    public void mergeOneAlignedFixedFrame(List<String[]> pages, String outPath) throws Exception {
        validateAlignedGeometry();
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(new File(templatePath));
        MainDocumentPart mainPart = wordMLPackage.getMainDocumentPart();
        stripTemplateEmptyParagraphs(mainPart);
        clearPageNumbers(wordMLPackage);

        for (String[] page : pages) {
            String imagePath = page[1];
            P frameParagraph = newExactLineP();
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                appendMissingImageNotice(frameParagraph, imagePath);
            } else {
                BufferedImage image = ImageIO.read(imageFile);
                long[] displaySize = computeAlignedDisplaySize(image);
                long horizontalOffset = (pageWidthEmu - displaySize[0]) / 2;
                R imageRun = new R();
                imageRun.getRunContent().add(createAlignedPictureAnchor(mainPart, imagePath, displaySize[0],
                        displaySize[1], horizontalOffset, headerOffsetEmu));
                frameParagraph.getParagraphContent().add(imageRun);
            }
            mainPart.addObject(createFixedPageFrame(frameParagraph));
        }
        // 仅在所有页面组织完成后一次性落盘，避免中间状态覆盖此前已经生成的独立 Word。
        savePackage(wordMLPackage, outPath);
    }

    /**
     * 追加一张正常图片页。图片锚点和占位换行必须位于同一个段落：Word 根据锚点段落决定图片
     * 所在页；若拆成“换行段落 + 图片段落”，图片段落会被换行推到下一页，首张图前就会出现空白页。
     */
    private void appendCalibratedImagePage(MainDocumentPart mainPart, String imagePath,
                                           long displayWidth, long displayHeight, long horizontalOffset,
                                           int calibratedBreakCount, boolean hasNextPage) throws Exception {
        P pageParagraph = newExactLineP();
        R imageRun = new R();
        imageRun.getRunContent().add(createAlignedPictureAnchor(mainPart, imagePath, displayWidth, displayHeight,
                horizontalOffset, headerOffsetEmu));
        pageParagraph.getParagraphContent().add(imageRun);
        // 普通换行只承担“占满当前页”的职责，不承担显式分页职责。
        appendLineBreakFillers(pageParagraph, pageBreakCount(calibratedBreakCount, hasNextPage));
        mainPart.addObject(pageParagraph);
    }

    /**
     * 追加缺图页。使用与正常图片页完全相同的换行规则，保证缺图不会改变后续任一图片的页码。
     */
    private void appendCalibratedMissingImagePage(MainDocumentPart mainPart, String imagePath,
                                                  int calibratedBreakCount, boolean hasNextPage) {
        P notice = newExactLineP();
        appendMissingImageNotice(notice, imagePath);
        appendLineBreakFillers(notice, pageBreakCount(calibratedBreakCount, hasNextPage));
        mainPart.addObject(notice);
    }

    /**
     * 非末页需要完整校准值把下一张锚点推入新页；末页不需要再推动下一页，因此少写一行。
     * 图片锚点段自身仍占一行，故末页视觉占满但不会多产生一张空白页。
     */
    private int pageBreakCount(int calibratedBreakCount, boolean hasNextPage) {
        return hasNextPage ? calibratedBreakCount : Math.max(0, calibratedBreakCount - 1);
    }

    private void appendMissingImageNotice(P paragraph, String imagePath) {
        R run = new R();
        Text text = new Text();
        text.setValue("【此页裁剪审核未通过，已占位】原因：图片缺失: " + imagePath);
        run.getRunContent().add(text);
        paragraph.getParagraphContent().add(run);
    }

    /** 构造一个占满模板正文高度的单行单列无边框表格。 */
    private org.docx4j.wml.Tbl createFixedPageFrame(P frameParagraph) {
        org.docx4j.wml.ObjectFactory factory = new org.docx4j.wml.ObjectFactory();
        long contentHeightTwips = Math.max(1L, (bottomOffsetEmu - headerOffsetEmu) / 635L - 1L);
        long contentWidthTwips = Math.max(1L, containerWidthEmu / 635L);

        org.docx4j.wml.Tbl table = factory.createTbl();
        org.docx4j.wml.TblPr tableProperties = factory.createTblPr();
        org.docx4j.wml.TblWidth tableWidth = factory.createTblWidth();
        tableWidth.setW(BigInteger.valueOf(contentWidthTwips));
        tableWidth.setType("dxa");
        tableProperties.setTblW(tableWidth);
        org.docx4j.wml.CTTblLayoutType layout = factory.createCTTblLayoutType();
        layout.setType(org.docx4j.wml.STTblLayoutType.FIXED);
        tableProperties.setTblLayout(layout);
        table.setTblPr(tableProperties);

        org.docx4j.wml.TblGrid grid = factory.createTblGrid();
        org.docx4j.wml.TblGridCol column = factory.createTblGridCol();
        column.setW(BigInteger.valueOf(contentWidthTwips));
        grid.getGridCol().add(column);
        table.setTblGrid(grid);

        org.docx4j.wml.Tr row = factory.createTr();
        org.docx4j.wml.TrPr rowProperties = factory.createTrPr();
        org.docx4j.wml.CTHeight rowHeight = factory.createCTHeight();
        rowHeight.setVal(BigInteger.valueOf(contentHeightTwips));
        rowHeight.setHRule(org.docx4j.wml.STHeightRule.EXACT);
        rowProperties.getCnfStyleOrDivIdOrGridBefore().add(factory.createCTTrPrBaseTrHeight(rowHeight));
        org.docx4j.wml.BooleanDefaultTrue doNotSplit = factory.createBooleanDefaultTrue();
        doNotSplit.setVal(Boolean.TRUE);
        rowProperties.getCnfStyleOrDivIdOrGridBefore().add(factory.createCTTrPrBaseCantSplit(doNotSplit));
        row.setTrPr(rowProperties);

        org.docx4j.wml.Tc cell = factory.createTc();
        org.docx4j.wml.TcPr cellProperties = factory.createTcPr();
        org.docx4j.wml.TblWidth cellWidth = factory.createTblWidth();
        cellWidth.setW(BigInteger.valueOf(contentWidthTwips));
        cellWidth.setType("dxa");
        cellProperties.setTcW(cellWidth);
        org.docx4j.wml.BooleanDefaultTrue hideEndMark = factory.createBooleanDefaultTrue();
        hideEndMark.setVal(Boolean.TRUE);
        cellProperties.setHideMark(hideEndMark);
        cell.setTcPr(cellProperties);
        cell.getContent().add(frameParagraph);
        row.getContent().add(cell);
        table.getContent().add(row);
        return table;
    }

    private void savePackage(WordprocessingMLPackage wordMLPackage, String outPath) throws Exception {
        File targetFile = new File(outPath);
        targetFile.getParentFile().mkdirs();
        try (OutputStream output = new FileOutputStream(targetFile)) {
            wordMLPackage.save(output);
        }
    }

    private void validateAlignedGeometry() {
        if (pageWidthEmu <= 0L || headerOffsetEmu <= 0L || bottomOffsetEmu <= headerOffsetEmu) {
            throw new IllegalStateException(
                    "mergeOneAligned 需要有效的页面宽度、页眉下沿和页脚上沿（且页脚上沿必须低于页眉下沿）");
        }
    }

    /** 在页面宽度与页眉/页脚之间的可用高度内，按比例缩放图片。 */
    private long[] computeAlignedDisplaySize(BufferedImage image) {
        long imageWidth = (long) image.getWidth() * PX_TO_EMU;
        long imageHeight = (long) image.getHeight() * PX_TO_EMU;
        long contentHeight = bottomOffsetEmu - headerOffsetEmu;
        double scale = Math.min((double) contentHeight / imageHeight, (double) pageWidthEmu / imageWidth);
        return new long[]{(long) (imageWidth * scale), (long) (imageHeight * scale)};
    }

    /**
     * 用同一个段落承载图片锚点和换行占位。
     *
     * <p>浮动图会按其所在段落决定归属页。若锚点与占位段拆成两个段落，排版器可能先把占位段
     * 填满第一页，再把锚点移动到第二页，形成“空白页 + 图片页”。因此图片锚点必须先写入该页的
     * 段落，再在同一段落追加普通换行，撑满当前页并让下一页自然开始。</p>
     */
    private void appendAlignedImagePage(MainDocumentPart mainPart, String imagePath,
                                        long displayWidth, long displayHeight,
                                        long horizontalOffset, int pageFillers,
                                        boolean hasNextPage) throws Exception {
        P pageParagraph = newP();
        R imageRun = new R();
        imageRun.getRunContent().add(createAlignedPictureAnchor(mainPart, imagePath, displayWidth, displayHeight,
                horizontalOffset, headerOffsetEmu));
        pageParagraph.getParagraphContent().add(imageRun);
        if (hasNextPage) appendLineBreakFillers(pageParagraph, pageFillers);
        mainPart.addObject(pageParagraph);
    }

    /** 缺图页也保持相同的自然分页机制，避免破坏后续页面的锚点页码。 */
    private void appendAlignedMissingImagePage(MainDocumentPart mainPart, String imagePath,
                                               int pageFillers, boolean hasNextPage) {
        P notice = newP();
        R run = new R();
        Text text = new Text();
        text.setValue("【此页裁剪审核未通过，已占位】原因：图片缺失: " + imagePath);
        run.getRunContent().add(text);
        notice.getParagraphContent().add(run);
        if (hasNextPage) appendLineBreakFillers(notice, pageFillers);
        mainPart.addObject(notice);
    }

    /** 在页面段落内追加普通换行，不使用会产生额外页面的 PAGE 类型分页符。 */
    private void appendLineBreakFillers(P pageParagraph, int count) {
        R fillerRun = new R();
        for (int i = 0; i < count; i++) {
            fillerRun.getRunContent().add(new Br());
        }
        pageParagraph.getParagraphContent().add(fillerRun);
    }

    /** 构造页面绝对坐标的浮动图片 Drawing；由调用方放入对应页的锚点段落。 */
    private Drawing createAlignedPictureAnchor(MainDocumentPart mainPart, String imagePath,
                                               long displayW, long displayH,
                                               long xOffset, long yOffset) throws Exception {
        String relId = addImageAndGetRelId(mainPart, imagePath);
        long docPrId = nextDocPrId();

        Anchor anchor = new Anchor();
        anchor.setDistT(0L);
        anchor.setDistB(0L);
        anchor.setDistL(0L);
        anchor.setDistR(0L);
        anchor.setSimplePosAttr(Boolean.FALSE);
        anchor.setRelativeHeight(RELATIVE_HEIGHT_BEHIND);
        anchor.setBehindDoc(true);            // 衬于文字下方（恢复旧版环绕）
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(false);

        CTPoint2D simplePos = new CTPoint2D();
        simplePos.setX(0L);
        simplePos.setY(0L);
        anchor.setSimplePos(simplePos);

        // 固定在页面上：相对 PAGE（非 MARGIN），坐标是页面绝对偏移
        CTPosH posH = new CTPosH();
        posH.setRelativeFrom(STRelFromH.PAGE);
        posH.setPosOffset((int) xOffset);
        anchor.setPositionH(posH);

        CTPosV posV = new CTPosV();
        posV.setRelativeFrom(STRelFromV.PAGE);
        posV.setPosOffset((int) yOffset);
        anchor.setPositionV(posV);

        CTPositiveSize2D extent = new CTPositiveSize2D();
        extent.setCx(displayW);
        extent.setCy(displayH);
        anchor.setExtent(extent);

        CTEffectExtent effectExtent = new CTEffectExtent();
        effectExtent.setL(0L); effectExtent.setT(0L);
        effectExtent.setR(0L); effectExtent.setB(0L);
        anchor.setEffectExtent(effectExtent);

        // 旧版环绕：衬于文字下方 + 无环绕
        anchor.setWrapNone(new CTWrapNone());

        CTNonVisualDrawingProps docPr = new CTNonVisualDrawingProps();
        docPr.setId(docPrId);
        docPr.setName("Picture " + docPrId);
        anchor.setDocPr(docPr);
        anchor.setCNvGraphicFramePr(new CTNonVisualGraphicFrameProperties());

        // pic
        org.docx4j.dml.picture.ObjectFactory picOF = new org.docx4j.dml.picture.ObjectFactory();
        Pic pic = picOF.createPic();
        CTPictureNonVisual nvPicPr = picOF.createCTPictureNonVisual();
        CTNonVisualDrawingProps cNvPr = new CTNonVisualDrawingProps();
        cNvPr.setId(docPrId);
        cNvPr.setName("Picture " + docPrId);
        nvPicPr.setCNvPr(cNvPr);
        nvPicPr.setCNvPicPr(new org.docx4j.dml.CTNonVisualPictureProperties());
        pic.setNvPicPr(nvPicPr);

        // blipFill
        org.docx4j.dml.ObjectFactory dmlOF = new org.docx4j.dml.ObjectFactory();
        CTBlipFillProperties blipFill = dmlOF.createCTBlipFillProperties();
        org.docx4j.dml.CTBlip blip = dmlOF.createCTBlip();
        blip.setEmbed(relId);
        blipFill.setBlip(blip);
        org.docx4j.dml.CTStretchInfoProperties stretch = dmlOF.createCTStretchInfoProperties();
        stretch.setFillRect(dmlOF.createCTRelativeRect());
        blipFill.setStretch(stretch);
        pic.setBlipFill(blipFill);

        // spPr
        CTShapeProperties spPr = new CTShapeProperties();
        CTTransform2D xfrm = new CTTransform2D();
        CTPoint2D off = new CTPoint2D();
        off.setX(0L); off.setY(0L);
        xfrm.setOff(off);
        xfrm.setExt(extent);
        spPr.setXfrm(xfrm);
        spPr.setPrstGeom(dmlOF.createCTPresetGeometry2D());
        spPr.getPrstGeom().setPrst(org.docx4j.dml.STShapeType.fromValue("rect"));
        spPr.getPrstGeom().setAvLst(dmlOF.createCTGeomGuideList());
        pic.setSpPr(spPr);

        GraphicData graphicData = new GraphicData();
        graphicData.setUri("http://schemas.openxmlformats.org/drawingml/2006/picture");
        graphicData.getAny().add(pic);
        Graphic graphic = new Graphic();
        graphic.setGraphicData(graphicData);
        anchor.setGraphic(graphic);

        Drawing drawing = new Drawing();
        drawing.getAnchorOrInline().add(anchor);
        return drawing;
    }

    private void addPlaceholder(MainDocumentPart mainPart, String reason, boolean isLast) {
        addPlaceholderLines(mainPart);
        P p = newP();
        R r = new R();
        Text t = new Text();
        t.setValue("【此页裁剪审核未通过，已占位】原因：" + reason);
        r.getRunContent().add(t);
        p.getParagraphContent().add(r);
        mainPart.addObject(p);
        if (!isLast) {
            P pbP = newP();
            R pbR = new R();
            Br br = new Br();
            br.setType(STBrType.PAGE);
            pbR.getRunContent().add(br);
            pbP.getParagraphContent().add(pbR);
            mainPart.addObject(pbP);
        }
    }

    private void stripTemplateEmptyParagraphs(MainDocumentPart mainPart) {
        Document doc = (Document) mainPart.getJaxbElement();
        if (doc == null || doc.getBody() == null) return;
        List<Object> bodyChildren = doc.getBody().getContent();
        List<Object> toRemove = new ArrayList<Object>();
        for (Object child : bodyChildren) {
            if (child instanceof P) {
                P p = (P) child;
                boolean hasRun = false;
                if (p.getParagraphContent() != null) {
                    for (Object c : p.getParagraphContent()) {
                        if (c instanceof R) {
                            R rr = (R) c;
                            if (rr.getRunContent() != null && !rr.getRunContent().isEmpty()) {
                                hasRun = true;
                                break;
                            }
                        }
                    }
                }
                if (!hasRun) toRemove.add(child);
            }
        }
        bodyChildren.removeAll(toRemove);
    }

    /** 清空所有 FooterPart 段落内容 + 删 sectPr 里的 footerReference（避免 Word 仍渲染页脚/页码）。 */
    private void clearPageNumbers(WordprocessingMLPackage wordMLPackage) throws Exception {
        for (Part p : new ArrayList<Part>(wordMLPackage.getParts().getParts().values())) {
            if (p instanceof FooterPart) {
                FooterPart fp = (FooterPart) p;
                Ftr ftr = fp.getContents();
                if (ftr == null) continue;
                for (Object child : new ArrayList<Object>(ftr.getContent())) {
                    if (child instanceof P) {
                        P pp = (P) child;
                        for (Object c : new ArrayList<Object>(pp.getParagraphContent())) {
                            if (c instanceof R) {
                                ((R) c).getRunContent().clear();
                            }
                        }
                    }
                }
            }
        }
        // 删 sectPr 的 footerReference，让 Word 完全不渲染页脚（页码随之消失）
        Document doc = (Document) wordMLPackage.getMainDocumentPart().getJaxbElement();
        if (doc == null || doc.getBody() == null) return;
        org.docx4j.wml.SectPr sectPr = doc.getBody().getSectPr();
        if (sectPr == null) return;
        List<org.docx4j.wml.CTRel> refs = sectPr.getEGHdrFtrReferences();
        List<org.docx4j.wml.CTRel> toRemove = new ArrayList<org.docx4j.wml.CTRel>();
        for (org.docx4j.wml.CTRel r : refs) {
            if (r instanceof org.docx4j.wml.FooterReference
                    || r instanceof org.docx4j.wml.HeaderReference) toRemove.add(r);
        }
        refs.removeAll(toRemove);
    }

    private static long docPrSeq = 1;
    private static synchronized long nextDocPrId() {
        return docPrSeq++;
    }

    private static int imageSeq = 1;
    private static synchronized String nextImagePartName(String ext) {
        return "/word/media/image" + (imageSeq++) + "." + ext;
    }

    /** docx4j 6.1.2：构造具体 ImagePngPart / ImageJpegPart + addTargetPart，返回 relId。 */
    private static String addImageAndGetRelId(MainDocumentPart mainPart, String imagePath) throws Exception {
        byte[] imageBytes = readFile(imagePath);
        boolean isPng = imagePath.toLowerCase().endsWith(".png");
        String ext = isPng ? "png" : "jpg";
        String contentTypeStr = isPng ? "image/png" : "image/jpeg";
        PartName partName = new PartName(nextImagePartName(ext));
        BinaryPartAbstractImage imagePart = isPng ? new ImagePngPart(partName) : new ImageJpegPart(partName);
        imagePart.setBinaryData(imageBytes);
        imagePart.setContentType(new ContentType(contentTypeStr));
        Relationship rel = mainPart.addTargetPart(imagePart);
        return rel.getId();
    }

    private P newP() {
        P p = new P();
        PPr ppr = new PPr();
        // line spacing = 240 (single line)；cc 用 line_spacing=1.0
        org.docx4j.wml.PPrBase.Spacing sp = new org.docx4j.wml.PPrBase.Spacing();
        sp.setBefore(BigInteger.ZERO);
        sp.setAfter(BigInteger.ZERO);
        sp.setLine(BigInteger.valueOf(240));
        ppr.setSpacing(sp);
        p.setPPr(ppr);
        return p;
    }

    /**
     * 校准方案专用段落：固定 12pt 行高，杜绝从模板样式继承“最小行距”后发生逐页漂移。
     * 旧路径继续使用 {@link #newP()}，以确保历史版式完全不变。
     */
    private P newExactLineP() {
        P p = new P();
        PPr ppr = new PPr();
        org.docx4j.wml.PPrBase.Spacing spacing = new org.docx4j.wml.PPrBase.Spacing();
        spacing.setBefore(BigInteger.ZERO);
        spacing.setAfter(BigInteger.ZERO);
        spacing.setLine(BigInteger.valueOf(240));
        spacing.setLineRule(org.docx4j.wml.STLineSpacingRule.EXACT);
        ppr.setSpacing(spacing);
        p.setPPr(ppr);
        return p;
    }

    private static byte[] readFile(String path) throws IOException {
        FileInputStream fis = new FileInputStream(path);
        byte[] buf = new byte[(int) new File(path).length()];
        int read = 0;
        while (read < buf.length) {
            int r = fis.read(buf, read, buf.length - read);
            if (r < 0) break;
            read += r;
        }
        fis.close();
        return buf;
    }
}
