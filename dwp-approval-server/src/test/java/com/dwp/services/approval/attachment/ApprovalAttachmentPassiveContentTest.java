package com.dwp.services.approval.attachment;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalAttachmentPassiveContentTest {
    final ApprovalAttachmentPassiveContent parser=new ApprovalAttachmentPassiveContent();
    @Test void ordinaryPdfUsesActualParserAndNeverClaimsSanitization() throws Exception {
        var result=parser.inspect(pdf(false),"application/pdf");
        assertThat(result.allowed()).isTrue();assertThat(result.reason()).isEqualTo("PASSIVE_CONTENT_ALLOWED_NOT_SANITIZED");
    }
    @Test void actualPdfJavascriptIsRejectedIncludingDictionaryNameEscapes() throws Exception {
        assertThat(parser.inspect(pdf(true),"application/pdf").allowed()).isFalse();
    }
    @Test void malformedPdfCannotMasqueradeAsPlainPdf() {
        assertThat(parser.inspect("%PDF-1.7 malformed".getBytes(StandardCharsets.UTF_8),"application/pdf").allowed()).isFalse();
    }
    @Test void textCannotSmuggleMarkupBinaryOrMalformedUtf8() {
        for(byte[] bytes:new byte[][]{"<script>bad</script>".getBytes(StandardCharsets.UTF_8),{0},{(byte)0xff}})
            assertThat(parser.inspect(bytes,"text/plain").allowed()).isFalse();
        assertThat(parser.inspect("Actual plain text\n".getBytes(StandardCharsets.UTF_8),"text/plain").allowed()).isTrue();
    }
    @Test void ooxmlPassivePackageIsAllowedButExternalDdeAndDoctypeAreRejected() throws Exception {
        assertThat(parser.inspect(ooxml("<document/>","<Relationships/>"),word()).allowed()).isTrue();
        assertThat(parser.inspect(ooxml("<document/>","<Relationships><Relationship TargetMode=\"External\" Target=\"https://bad.invalid\"/></Relationships>"),word()).allowed()).isFalse();
        assertThat(parser.inspect(ooxml("<document><fldSimple/></document>","<Relationships/>"),word()).allowed()).isFalse();
        assertThat(parser.inspect(ooxml("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><document>&x;</document>","<Relationships/>"),word()).allowed()).isFalse();
    }
    @Test void unsupportedHtmlAndDigestMismatchRemainClosed() {
        assertThat(parser.inspect("<html/>".getBytes(StandardCharsets.UTF_8),"text/html").allowed()).isFalse();
        assertThatThrownBy(()->ApprovalAttachmentIntegrity.require(new byte[]{1},1,"a".repeat(64))).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void actualOpcSpreadsheetRejectsExternalAndOrdinaryFormulasWithoutNameHeuristics() throws Exception {
        for(String formula:new String[]{"WEBSERVICE(\"https://blocked.invalid/source\")","HYPERLINK(\"https://blocked.invalid/source\",\"Open\")","SUM(1,2)","_xlfn.UNKNOWN()"}) {
            var result=parser.inspect(xlsx("<c r=\"A1\"><f>"+formula+"</f><v>0</v></c>"),sheet());
            assertThat(result.allowed()).as(formula).isFalse();
        }
    }
    @Test void actualOpcSpreadsheetAllowsStaticCellsButRejectsNamedAndCalculatedExpressions() throws Exception {
        assertThat(parser.inspect(xlsx("<c r=\"A1\"><v>123.4567890123456789012345678</v></c>"),sheet()).allowed()).isTrue();
        for(String expression:new String[]{"<definedName>WEBSERVICE()</definedName>","<formula1>1+1</formula1>","<hyperlink ref=\"A1\" location=\"https://blocked.invalid/\"/>"})
            assertThat(parser.inspect(xlsx(expression),sheet()).allowed()).isFalse();
    }
    private byte[] pdf(boolean active) throws Exception {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if(active) {var action=new COSDictionary();action.setName(COSName.S,"JavaScript");action.setString(COSName.getPDFName("JS"),"app.alert('unsafe')");document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("OpenAction"),action);}
            document.save(output);return output.toByteArray();
        }
    }
    private String word() {return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
    private String sheet() {return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
    private byte[] xlsx(String cell) throws Exception {
        String packageNs="http://schemas.openxmlformats.org/package/2006/relationships",main="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String[][] parts={{"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"},
                {"_rels/.rels","<Relationships xmlns=\""+packageNs+"\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"},
                {"xl/workbook.xml","<workbook xmlns=\""+main+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"},
                {"xl/_rels/workbook.xml.rels","<Relationships xmlns=\""+packageNs+"\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"},
                {"xl/worksheets/sheet1.xml","<worksheet xmlns=\""+main+"\"><sheetData><row r=\"1\">"+cell+"</row></sheetData></worksheet>"}};
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var part:parts){zip.putNextEntry(new ZipEntry(part[0]));zip.write(part[1].getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return output.toByteArray();
    }
    private byte[] ooxml(String body,String relationships) throws Exception {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            String[][] parts={{"[Content_Types].xml","<Types/>"},{"_rels/.rels",relationships},{"word/document.xml",body}};
            for(var part:parts) {zip.putNextEntry(new ZipEntry(part[0]));zip.write(part[1].getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        return output.toByteArray();
    }
}
