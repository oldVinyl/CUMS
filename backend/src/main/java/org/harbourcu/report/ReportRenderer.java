package org.harbourcu.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/** Both formats consume the same authorized report and exact decimal values. */
@Component
public class ReportRenderer {
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public ReportRenderer() {}
    @org.springframework.beans.factory.annotation.Autowired
    public ReportRenderer(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Column(String label, int width, boolean numeric) {}
    public record Group(String label, int first, int last) {}
    public record Report(String title, String subtitle, List<Column> columns, List<Group> groups,
                         List<List<Object>> rows, boolean totals) {}
    private static final Color NAVY = new Color(0x08, 0x3a, 0x4f);
    private static final Color AQUA = new Color(0xc0, 0xd5, 0xd6);

    public byte[] render(Report report, String format) {
        try {
            return switch (format) {
                case "xlsx" -> xlsx(report);
                case "pdf" -> pdf(report);
                default -> throw new IllegalArgumentException("Choose xlsx or pdf");
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Report generation failed", exception);
        }
    }

    private byte[] xlsx(Report report) throws IOException {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Statement");
            CellStyle heading = style(book, true, IndexedColors.DARK_TEAL, IndexedColors.WHITE);
            CellStyle group = style(book, true, IndexedColors.PALE_BLUE, IndexedColors.BLACK);
            CellStyle text = style(book, false, null, IndexedColors.BLACK);
            CellStyle number = style(book, false, null, IndexedColors.BLACK);
            number.setDataFormat(book.createDataFormat().getFormat("#,##0.00;[Red](#,##0.00)"));
            CellStyle totalText = style(book, true, IndexedColors.GREY_25_PERCENT, IndexedColors.BLACK);
            CellStyle totalNumber = style(book, true, IndexedColors.GREY_25_PERCENT, IndexedColors.BLACK);
            totalNumber.setDataFormat(number.getDataFormat());
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(report.title());
            title.getCell(0).setCellStyle(heading);
            title.setHeightInPoints(28);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, report.columns().size() - 1));
            sheet.createRow(1).createCell(0).setCellValue(report.subtitle());
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, report.columns().size() - 1));
            Row groups = sheet.createRow(2);
            for (int i = 0; i < report.columns().size(); i++) groups.createCell(i).setCellStyle(group);
            for (Group g : report.groups()) {
                groups.getCell(g.first()).setCellValue(g.label());
                if (g.last() > g.first()) sheet.addMergedRegion(new CellRangeAddress(2, 2, g.first(), g.last()));
            }
            Row headers = sheet.createRow(3);
            headers.setHeightInPoints(32);
            for (int i = 0; i < report.columns().size(); i++) {
                headers.createCell(i).setCellValue(report.columns().get(i).label());
                headers.getCell(i).setCellStyle(heading);
                sheet.setColumnWidth(i, Math.min(60, report.columns().get(i).width()) * 256);
            }
            for (int r = 0; r < report.rows().size(); r++) {
                Row row = sheet.createRow(r + 4);
                int lineCount = 2;
                for (int c = 0; c < report.columns().size(); c++) {
                    String value = display(report.rows().get(r).get(c));
                    int lines = 0;
                    for (String part : value.split("\n", -1)) lines += Math.max(1, (part.length() + report.columns().get(c).width() - 1) / report.columns().get(c).width());
                    lineCount = Math.max(lineCount, lines);
                }
                row.setHeightInPoints(Math.min(409, 8 + lineCount * 14));
                boolean total = report.totals() && r == report.rows().size() - 1;
                for (int c = 0; c < report.columns().size(); c++) {
                    Cell cell = row.createCell(c);
                    Object value = report.rows().get(r).get(c);
                    if (value instanceof BigDecimal decimal) {
                        // Excel supports only 15 significant digits. Preserve larger values as exact text.
                        if (decimal.stripTrailingZeros().precision() > 15) cell.setCellValue(decimal.toPlainString());
                        else cell.setCellValue(decimal.doubleValue());
                        cell.setCellStyle(total ? totalNumber : number);
                    } else {
                        // setCellValue stores text; user names cannot become Excel formulas.
                        cell.setCellValue(value == null ? "" : value.toString());
                        cell.setCellStyle(total ? totalText : text);
                    }
                }
            }
            sheet.createFreezePane(2, 4);
            if (!report.rows().isEmpty()) sheet.setAutoFilter(new CellRangeAddress(3,
                3 + report.rows().size() - (report.totals() ? 1 : 0), 0, report.columns().size() - 1));
            sheet.setRepeatingRows(new CellRangeAddress(0, 3, -1, -1));
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.getFooter().setCenter("Page &P of &N");
            book.write(out);
            return out.toByteArray();
        }
    }

    private static CellStyle style(Workbook book, boolean bold, IndexedColors fill, IndexedColors color) {
        CellStyle style = book.createCellStyle();
        Font font = book.createFont(); font.setBold(bold); font.setColor(color.getIndex()); font.setFontHeightInPoints((short) 10);
        style.setFont(font); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true);
        if (fill != null) { style.setFillForegroundColor(fill.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); }
        return style;
    }

    private byte[] pdf(Report report) throws IOException {
        String organization=jdbc==null?"Harbour Credit Union":jdbc.queryForObject("SELECT organisation_name FROM organisation_settings WHERE singleton=true",String.class);
        organization=organization.replaceAll("[\\p{Cntrl}]", " ");
        if(organization.codePointCount(0,organization.length())>80)organization=organization.substring(0,organization.offsetByCodePoints(0,80))+"…";
        try (PDDocument document = new PDDocument(); var out = new ByteArrayOutputStream();
             var resource = ReportRenderer.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            PDFont font = PDType0Font.load(document, resource);
            PDRectangle size = report.columns().size() > 9 ? new PDRectangle(PDRectangle.A3.getHeight(), PDRectangle.A3.getWidth())
                : new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float usable = size.getWidth() - 64;
            int weight = report.columns().stream().mapToInt(Column::width).sum();
            float[] widths = new float[report.columns().size()];
            for (int c = 0; c < widths.length; c++) widths[c] = usable * report.columns().get(c).width() / weight;
            PDPageContentStream stream = null;
            float y = 0;
            int pageNumber = 0;
            try {
                for (int r = 0; r < Math.max(1, report.rows().size()); r++) {
                    List<Object> values = report.rows().isEmpty() ? java.util.Collections.nCopies(widths.length, "") : report.rows().get(r);
                    List<List<String>> lines = new ArrayList<>();
                    int maxLines = 1;
                    for (int c = 0; c < widths.length; c++) {
                        var wrapped = wrap(font, display(values.get(c)), 8, widths[c] - 12);
                        lines.add(wrapped); maxLines = Math.max(maxLines, wrapped.size());
                    }
                    float height = 12 + maxLines * 11;
                    if (stream == null || y - height < 44) {
                        if (stream != null) stream.close();
                        PDPage page = new PDPage(size); document.addPage(page);
                        stream = new PDPageContentStream(document, page); pageNumber++;
                        y = size.getHeight() - 40;
                        text(stream, font, report.title(), 32, y, 16, NAVY);
                        y -= 24;
                        for (String line : wrap(font, report.subtitle(), 8, usable)) {
                            text(stream, font, line, 32, y, 8, NAVY); y -= 11;
                        }
                        text(stream, font, organization + " | GHS | Page " + pageNumber, 32, 22, 8, NAVY);
                        y -= 8;
                        fill(stream, 32, y - 22, usable, 22, AQUA);
                        for (Group g : report.groups()) {
                            float x = 32; for (int c = 0; c < g.first(); c++) x += widths[c];
                            text(stream, font, g.label(), x + 6, y - 15, 8, NAVY);
                        }
                        y -= 22;
                        fill(stream, 32, y - 34, usable, 34, NAVY);
                        float x = 32;
                        for (int c = 0; c < widths.length; c++) {
                            var header = wrap(font, report.columns().get(c).label(), 8, widths[c] - 12);
                            for (int l = 0; l < header.size(); l++) text(stream, font, header.get(l), x + 6, y - 12 - l * 10, 8, Color.WHITE);
                            x += widths[c];
                        }
                        y -= 34;
                    }
                    boolean total = report.totals() && r == report.rows().size() - 1;
                    fill(stream, 32, y - height, usable, height, total ? AQUA : r % 2 == 0 ? new Color(0xf4, 0xf5, 0xf6) : Color.WHITE);
                    float x = 32;
                    for (int c = 0; c < widths.length; c++) {
                        for (int l = 0; l < lines.get(c).size(); l++) {
                            String line = lines.get(c).get(l);
                            float left = report.columns().get(c).numeric() ? x + widths[c] - 6 - font.getStringWidth(line) / 1000 * 8 : x + 6;
                            text(stream, font, line, left, y - 14 - l * 11, 8, NAVY);
                        }
                        x += widths[c];
                    }
                    y -= height;
                }
            } finally { if (stream != null) stream.close(); }
            document.getDocumentInformation().setTitle(report.title());
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String display(Object value) {
        return value instanceof BigDecimal amount ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : value == null ? "" : value.toString();
    }
    private static List<String> wrap(PDFont font, String value, float size, float width) throws IOException {
        value = value.replaceAll("[\\s\\p{Z}]+", " ");
        StringBuilder safe = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isISOControl(cp)) cp = ' ';
            String glyph = new String(Character.toChars(cp));
            try { font.encode(glyph); } catch (IllegalArgumentException exception) { glyph = "?"; }
            safe.append(glyph);
        }
        List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
        for (String word : safe.toString().trim().split(" +")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size <= width) {
                line.setLength(0); line.append(candidate); continue;
            }
            if (!line.isEmpty()) { lines.add(line.toString()); line.setLength(0); }
            // Preserve whole words; split only references or tokens wider than their column.
            for (int offset = 0; offset < word.length();) {
                int cp = word.codePointAt(offset); offset += Character.charCount(cp);
                String glyph = new String(Character.toChars(cp));
                if (!line.isEmpty() && font.getStringWidth(line + glyph) / 1000 * size > width) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(glyph);
            }
        }
        lines.add(line.toString()); return lines;
    }

    private static void fill(PDPageContentStream stream, float x, float y, float w, float h, Color color) throws IOException {
        stream.setNonStrokingColor(color); stream.addRect(x, y, w, h); stream.fill();
    }
    private static void text(PDPageContentStream stream, PDFont font, String value, float x, float y, float size, Color color) throws IOException {
        stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color); stream.newLineAtOffset(x, y); stream.showText(value); stream.endText();
    }
}
