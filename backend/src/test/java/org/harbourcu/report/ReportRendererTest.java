package org.harbourcu.report;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportRendererTest {
    @Test void multipagePdfRepeatsHeadersAndRetainsEveryRow() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(List.of("Member " + i, new BigDecimal("12345.67")));
        var report = new ReportRenderer.Report("Pagination check", "As at 2026-10-03", List.of(
            new ReportRenderer.Column("Member", 30, false), new ReportRenderer.Column("Equity", 20, true)),
            List.of(new ReportRenderer.Group("Balances", 0, 1)), rows, false);
        try (var pdf = Loader.loadPDF(new ReportRenderer().render(report, "pdf"))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                assertThat(stripper.getText(pdf)).contains("Pagination check", "Balances", "Equity", "Page " + page);
            }
            stripper.setStartPage(1); stripper.setEndPage(pdf.getNumberOfPages());
            assertThat(stripper.getText(pdf)).contains("Member 0", "Member 99");
        }
    }

    @Test void spreadsheetStoresFormulaLikeNamesAsTextAndPreservesLargeDecimals() throws Exception {
        var report = new ReportRenderer.Report("Exact values", "Test", List.of(new ReportRenderer.Column("Name", 30, false),
            new ReportRenderer.Column("Amount", 25, true)), List.of(),
            List.of(List.of("=HYPERLINK(\"bad\")", new BigDecimal("999999999999999.99"))), false);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(new ReportRenderer().render(report, "xlsx")))) {
            var row = book.getSheetAt(0).getRow(4);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("999999999999999.99");
        }
    }
}
