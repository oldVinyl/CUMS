package org.harbourcu.report;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.harbourcu.investment.InvestmentService;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class FinancialReportIntegrationTest {
    @Autowired FinancialReportService reports;
    @Autowired InvestmentService investments;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    private final LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));

    @Test @Transactional
    void sheetBExportsGroupedNumericBalancesAndAuditsTheDownload() throws Exception {
        UUID actor = user("AUDITOR", null);
        UUID member = member("Ama Réconciliation");
        entry(member, "PRINCIPAL", "30000.00", today.minusYears(1), null);
        entry(member, "INCOME", "4796.87", today, null);
        var download = reports.dashboard(actor, today, "xlsx");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download.bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("Balance forward");
            assertThat(sheet.getRow(4).getCell(8).getNumericCellValue()).isEqualTo(30000);
            assertThat(sheet.getRow(4).getCell(10).getNumericCellValue()).isEqualTo(34796.87);
            assertThat(sheet.getRow(4).getCell(11).getNumericCellValue()).isEqualTo(15.99);
            assertThat(sheet.getRow(5).getCell(12).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(4).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        }
        var pdf = reports.dashboard(actor, today, "pdf");
        preview(pdf.bytes(), "sheet-b");
        try (var document = Loader.loadPDF(pdf.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Sheet B", "Ama Réconciliation", "34796.87", "Balance forward");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'REPORT_EXPORTED'", Integer.class, actor)).isEqualTo(2);
    }

    @Test @Transactional
    void sheetAExportsTheSuppliedCostAndParticipationDifference() throws Exception {
        UUID actor = user("TREASURER", null);
        for (int i = 0; i < 12; i++) {
            UUID id = member("Sheet A member " + i);
            entry(id, "PRINCIPAL", i == 0 ? "5000" : i == 1 ? "7000" : i == 2 ? "12000" : "2000", today, null);
        }
        UUID id = investments.create(actor, new InvestmentService.Create("91_DAY", null, "Fixture Bank", null,
            today, today.plusDays(91), "42271.26", "45435.00"));
        var download = reports.investment(actor, id, "xlsx");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download.bytes()))) {
            var total = book.getSheetAt(0).getRow(16);
            assertThat(total.getCell(2).getNumericCellValue()).isEqualTo(42000);
            assertThat(total.getCell(4).getNumericCellValue()).isEqualTo(42271.26);
            assertThat(total.getCell(5).getNumericCellValue()).isEqualTo(45435);
            assertThat(total.getCell(6).getNumericCellValue()).isEqualTo(3163.74);
            assertThat(total.getCell(7).getNumericCellValue()).isEqualTo(45163.74);
        }
        preview(reports.investment(actor, id, "pdf").bytes(), "sheet-a");
    }

    @Test @Transactional
    void statementPreservesOpeningBalancesLossesAndOwnDataBoundaries() throws Exception {
        UUID member = member("Statement owner");
        UUID other = member("Other private member");
        UUID actor = user("MEMBER", member);
        entry(member, "PRINCIPAL", "100.00", today.minusDays(1), null);
        entry(member, "PRINCIPAL", "20.00", today, null);
        entry(member, "INCOME", "-5.00", today, null);
        entry(other, "PRINCIPAL", "9999.00", today, null);
        var report = reports.statement(actor, member, today, today, "xlsx");
        preview(reports.statement(actor, member, today, today, "pdf").bytes(), "member-statement");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(report.bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(7).getCell(5).getNumericCellValue()).isEqualTo(120);
            assertThat(sheet.getRow(7).getCell(6).getNumericCellValue()).isEqualTo(-5);
            assertThat(sheet.getRow(7).getCell(7).getNumericCellValue()).isEqualTo(115);
        }
        assertThatThrownBy(() -> reports.statement(actor, other, today, today, "pdf")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> reports.dashboard(actor, today, "xlsx")).isInstanceOf(SecurityException.class);
        var tokens = sessions.create(actor);
        mvc.perform(get("/api/v1/reports/members/" + other + "/statement").param("from", today.toString()).param("to", today.toString())
            .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/members/" + member + "/statement").param("from", today.toString()).param("to", today.toString())
            .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test @Transactional
    void exportHttpRequiresSessionAndValidDatesAndFormats() throws Exception {
        mvc.perform(get("/api/v1/reports/dashboard").param("asAt", today.toString()).param("format", "pdf"))
            .andExpect(status().isUnauthorized());
        var tokens = sessions.create(user("AUDITOR", null));
        for (String date : new String[] { "bad-date", today.plusDays(1).toString() }) {
            mvc.perform(get("/api/v1/reports/dashboard").param("asAt", date).param("format", "pdf")
                .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/reports/dashboard").param("asAt", today.toString()).param("format", "exe")
            .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/dashboard").param("asAt", today.toString()).param("format", "pdf")
            .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test @Transactional
    void ledgerAndDirectoryExportsExcludeFutureJoinsAndOutOfRangeEntries() throws Exception {
        UUID actor = user("AUDITOR", null);
        UUID member = member("Directory fixture");
        entry(member, "PRINCIPAL", "100.00", today.minusDays(1), null);
        entry(member, "PRINCIPAL", "20.00", today, null);
        var ledger = reports.ledger(actor, today, today, "xlsx");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(ledger.bytes()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(book.getSheetAt(0).getRow(4).getCell(5).getNumericCellValue()).isEqualTo(20);
        }
        UUID future = member("Future join");
        jdbc.update("UPDATE members SET join_date = ? WHERE id = ?", today.plusDays(1), future);
        var directory = reports.members(actor, today, "xlsx");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(directory.bytes()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).isEqualTo("Directory fixture");
        }
        UUID ordinary = user("MEMBER", member);
        assertThatThrownBy(() -> reports.ledger(ordinary, today, today, "pdf")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> reports.members(ordinary, today, "xlsx")).isInstanceOf(SecurityException.class);
    }

    private void preview(byte[] bytes, String name) throws Exception {
        Path folder = Path.of("target", "report-previews"); Files.createDirectories(folder);
        Files.write(folder.resolve(name + ".pdf"), bytes);
        try (var doc = Loader.loadPDF(bytes)) {
            ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0, 100), "png", folder.resolve(name + ".png").toFile());
        }
    }
    private UUID member(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, ?, ?)", id, "R-" + id.toString().substring(0, 8), name, today.minusYears(2));
        return id;
    }
    private void entry(UUID member, String component, String amount, LocalDate date, UUID investment) {
        jdbc.update("INSERT INTO fiscal_years(year, start_date, end_date) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", date.getYear(), date.withDayOfYear(1), LocalDate.of(date.getYear(), 12, 31));
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date, fiscal_year, reference, investment_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, member, "INCOME".equals(component) ? "INCOME_ALLOCATION" : "SEED", component, new BigDecimal(amount), date, date.getYear(), "REPORT-" + UUID.randomUUID(), investment);
    }
    private UUID user(String role, UUID member) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, member_id) VALUES (?, ?, 'fixture-hash', ?)", id, id + "@invalid.example", member);
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) SELECT ?, id, ? FROM roles WHERE code = ?", id, today, role);
        return id;
    }
}
