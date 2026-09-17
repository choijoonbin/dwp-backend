package com.dwp.services.platform.workplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;

@Component
public class WorkplaceSpacePlanningBoardReportRenderer {
    static final String PDF_MIME = "application/pdf";
    static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public RenderedDocument render(ReportFormat format, ReportSnapshot snapshot) {
        if (format == null || snapshot == null) {
            throw new IllegalArgumentException("A report format and aggregate snapshot are required.");
        }
        Map<String, String> fields = fields(snapshot);
        String stem = "workplace-space-planning-board-report-"
                + snapshot.scenarioId().toString().substring(0, 8);
        return switch (format) {
            case PDF -> new RenderedDocument(pdf(fields), PDF_MIME, stem + ".pdf");
            case XLSX -> new RenderedDocument(xlsx(fields), XLSX_MIME, stem + ".xlsx");
        };
    }

    private Map<String, String> fields(ReportSnapshot value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Report", "DWP Workplace Space Planning Board Report");
        fields.put("Scenario ID", text(value.scenarioId()));
        fields.put("Scenario version", Long.toString(value.scenarioVersion()));
        fields.put("Scenario name", text(value.scenarioName()));
        fields.put("Scenario state", text(value.scenarioState()));
        fields.put("Site code", text(value.siteCode()));
        fields.put("Site name", text(value.siteName()));
        fields.put("Floor", value.floorName() == null ? "All floors" : value.floorName());
        fields.put("Planning window start", text(value.windowStart()));
        fields.put("Planning window end", text(value.windowEnd()));
        fields.put("Current capacity", Integer.toString(value.currentCapacity()));
        fields.put("Proposed capacity", Integer.toString(value.proposedCapacity()));
        fields.put("Current room capacity", Integer.toString(value.currentRoomCapacity()));
        fields.put("Proposed room capacity", Integer.toString(value.proposedRoomCapacity()));
        fields.put("Current accessible resources",
                Integer.toString(value.currentAccessibleResourceCount()));
        fields.put("Proposed accessible resources",
                Integer.toString(value.proposedAccessibleResourceCount()));
        fields.put("Current utilization percent", text(value.currentUtilizationPercent()));
        fields.put("Proposed utilization percent", text(value.proposedUtilizationPercent()));
        fields.put("Forecast state", text(value.forecastState()));
        fields.put("Peak demand", text(value.peakDemand()));
        fields.put("Forecast confidence percent", text(value.forecastConfidencePercent()));
        fields.put("Calculation version", text(value.calculationVersion()));
        fields.put("Energy", quantity(value.energyValue(), value.energyUnit()));
        fields.put("CO2e", quantity(value.co2eValue(), value.co2eUnit()));
        fields.put("Emission factor version", text(value.emissionFactorVersion()));
        fields.put("Emission region", text(value.emissionRegionCode()));
        fields.put("Affected resource count", Integer.toString(value.affectedResourceCount()));
        fields.put("Impacted booking count", text(value.impactedBookingCount()));
        fields.put("Person-level rows", Integer.toString(value.personLevelRowCount()));
        fields.put("Person-level data included",
                Boolean.toString(value.personLevelDataIncluded()));
        fields.put("Snapshot captured at", text(value.capturedAt()));
        fields.put("Privacy", "Aggregate operational metrics only; no person or booking rows.");
        return fields;
    }

    private byte[] pdf(Map<String, String> fields) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 13);
                content.newLineAtOffset(42, 752);
                content.showText("DWP Workplace Space Planning Board Report");
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8.5f);
                content.setLeading(11);
                content.newLine();
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    if ("Report".equals(field.getKey())) continue;
                    for (String line : wrap(ascii(field.getKey() + ": " + field.getValue()), 98)) {
                        content.showText(line);
                        content.newLine();
                    }
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the Workplace board-report PDF.",
                    exception);
        }
    }

    private byte[] xlsx(Map<String, String> fields) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                      <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>
                    """);
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Board Report" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);
            entry(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <fonts count="2"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font></fonts>
                      <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                      <borders count="1"><border/></borders>
                      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                      <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
                    </styleSheet>
                    """);
            entry(zip, "xl/worksheets/sheet1.xml", worksheet(fields));
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the Workplace board-report XLSX.",
                    exception);
        }
    }

    private String worksheet(Map<String, String> fields) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <cols><col min="1" max="1" width="32" customWidth="1"/><col min="2" max="2" width="72" customWidth="1"/></cols>
                  <sheetData>
                """);
        int row = 1;
        xml.append(row(row++, "Field", "Value", true));
        for (Map.Entry<String, String> field : fields.entrySet()) {
            xml.append(row(row++, field.getKey(), field.getValue(), false));
        }
        xml.append("  </sheetData>\n</worksheet>\n");
        return xml.toString();
    }

    private String row(int number, String left, String right, boolean header) {
        int style = header ? 1 : 0;
        return "    <row r=\"" + number + "\"><c r=\"A" + number
                + "\" t=\"inlineStr\" s=\"" + style + "\"><is><t>" + xml(left)
                + "</t></is></c><c r=\"B" + number + "\" t=\"inlineStr\" s=\"" + style
                + "\"><is><t>" + xml(right) + "</t></is></c></row>\n";
    }

    private void entry(ZipOutputStream zip, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        for (int start = 0; start < value.length(); start += width) {
            lines.add(value.substring(start, Math.min(value.length(), start + width)));
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private static String ascii(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character >= 32 && character <= 126) result.append(character);
            else result.append(String.format("\\u%04x", (int) character));
        }
        return result.toString();
    }

    private static String xml(String value) {
        return text(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String quantity(Object value, String unit) {
        return value == null ? "Not available" : value + (unit == null ? "" : " " + unit);
    }

    private static String text(Object value) {
        return value == null ? "Not available" : value.toString();
    }

    public record RenderedDocument(byte[] payload, String mimeType, String fileName) {
        public RenderedDocument {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
