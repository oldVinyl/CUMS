package org.harbourcu.importer;
import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
class ImportFileParserTest {
 private final ImportFileParser parser=new ImportFileParser();
 @Test void csvHandlesBomEscapedQuotesCommasAndNewlinesWithoutAcceptingMalformedQuotes(){
  var rows=parser.parse("test.csv","\ufeffa,b\r\n\"name, \"\"quoted\"\"\",\"two\nlines\"\r\n".getBytes(StandardCharsets.UTF_8),List.of("a","b"));
  assertThat(rows.getFirst().get("a")).isEqualTo("name, \"quoted\"");assertThat(rows.getFirst().get("b")).isEqualTo("two\nlines");
  assertThatThrownBy(()->parser.parse("test.csv","a,b\n\"unfinished,b".getBytes(StandardCharsets.UTF_8),List.of("a","b"))).hasMessageContaining("not closed");
 }
 @Test void xlsxReadsPlainValuesButRejectsFormulasAndMultipleSheets()throws Exception {
  try(var workbook=new XSSFWorkbook()){
   var sheet=workbook.createSheet("Import");sheet.createRow(0).createCell(0).setCellValue("amount");sheet.createRow(1).createCell(0).setCellValue(12.25);
   assertThat(parser.parse("test.xlsx",bytes(workbook),List.of("amount")).getFirst().get("amount")).isEqualTo("12.25");
   sheet.getRow(1).getCell(0).setCellFormula("1+1");assertThatThrownBy(()->parser.parse("test.xlsx",bytes(workbook),List.of("amount"))).hasMessageContaining("formula");
   workbook.createSheet("Extra");assertThatThrownBy(()->parser.parse("test.xlsx",bytes(workbook),List.of("amount"))).hasMessageContaining("one worksheet");
  }
 }
 private byte[] bytes(XSSFWorkbook book)throws Exception {try(var out=new ByteArrayOutputStream()){book.write(out);return out.toByteArray();}}
}
