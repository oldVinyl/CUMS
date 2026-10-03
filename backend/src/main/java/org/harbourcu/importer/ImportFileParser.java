package org.harbourcu.importer;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Bounded CSV and single-sheet XLSX inputs. Never evaluates workbook formulas. */
@Component
public class ImportFileParser {
    public List<Map<String,String>> parse(String filename, byte[] data, List<String> expected) {
        if(data==null || data.length==0 || data.length>5*1024*1024) throw new IllegalArgumentException("Choose a CSV or XLSX file up to 5 MB.");
        List<List<String>> cells;
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if(name.endsWith(".csv")) cells=csv(data);
        else if(name.endsWith(".xlsx")) cells=xlsx(data);
        else throw new IllegalArgumentException("Choose CSV or XLSX; legacy XLS files must be saved as XLSX.");
        if(cells.size()<2 || cells.size()>1002)throw new IllegalArgumentException("Include a header and 1–1,000 data rows, plus an optional totals row.");
        var header=cells.getFirst().stream().map(String::trim).toList();
        if(!header.equals(expected))throw new IllegalArgumentException("Column headings and order must match the downloaded template.");
        var rows=new ArrayList<Map<String,String>>();
        for(var row:cells.subList(1,cells.size())) {
            if(row.stream().allMatch(String::isBlank))continue;
            if(row.size()!=header.size())throw new IllegalArgumentException("Every row must have the same columns as the template.");
            var fields=new LinkedHashMap<String,String>();
            for(int i=0;i<header.size();i++){if(row.get(i).length()>2000)throw new IllegalArgumentException("A cell exceeds 2,000 characters.");fields.put(header.get(i),row.get(i).trim());}
            rows.add(fields);
        }
        if(rows.isEmpty())throw new IllegalArgumentException("The file contains no data rows.");
        return rows;
    }
    private List<List<String>> csv(byte[] data) {
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();}
        catch(CharacterCodingException e){throw new IllegalArgumentException("Save the CSV with UTF-8 encoding.");}
        if(text.startsWith("\ufeff"))text=text.substring(1);
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length() && text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);}
            else if(c=='"'){if(cell.length()!=0||closed)throw new IllegalArgumentException("CSV quotes must enclose a complete cell.");quoted=true;}
            else if(c==','||c=='\n'||c=='\r'){
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=','){rows.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            } else {if(closed)throw new IllegalArgumentException("Unexpected text after a quoted CSV cell.");cell.append(c);}
            if(cell.length()>2000||row.size()>20||rows.size()>1002)throw new IllegalArgumentException("The file exceeds the import limits.");
        }
        if(quoted)throw new IllegalArgumentException("A quoted CSV cell was not closed.");
        if(cell.length()>0||closed||!row.isEmpty()){row.add(cell.toString());rows.add(row);}
        return rows;
    }
    private List<List<String>> xlsx(byte[] data) {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(data))) {
            if(book.getNumberOfSheets()!=1)throw new IllegalArgumentException("Use one worksheet per import.");
            var sheet=book.getSheetAt(0);if(sheet.getLastRowNum()>1001)throw new IllegalArgumentException("The workbook exceeds 1,000 data rows.");
            var rows=new ArrayList<List<String>>();
            int columns=sheet.getRow(0)==null?0:sheet.getRow(0).getLastCellNum();
            if(columns<1||columns>20)throw new IllegalArgumentException("The workbook header is missing or has too many columns.");
            for(int n=0;n<=sheet.getLastRowNum();n++){
                var row=sheet.getRow(n);var values=new ArrayList<String>();
                if(row!=null&&row.getLastCellNum()>columns)throw new IllegalArgumentException("A workbook row contains extra columns.");
                for(int i=0;i<columns;i++){
                    var cell=row==null?null:row.getCell(i);
                    String value="";
                    if(cell!=null) value=switch(cell.getCellType()){
                        case STRING -> cell.getStringCellValue();
                        case NUMERIC -> DateUtil.isCellDateFormatted(cell)?cell.getLocalDateTimeCellValue().toLocalDate().toString():BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                        case BLANK -> "";
                        default -> throw new IllegalArgumentException("Replace formula, boolean and error cells with plain values before importing.");
                    };
                    values.add(value);
                }rows.add(values);
            }return rows;
        }catch(IOException e){throw new IllegalArgumentException("The XLSX file could not be read.");}
        catch(RuntimeException e){if(e instanceof IllegalArgumentException v)throw v;throw new IllegalArgumentException("The XLSX file could not be read.");}
    }
}
