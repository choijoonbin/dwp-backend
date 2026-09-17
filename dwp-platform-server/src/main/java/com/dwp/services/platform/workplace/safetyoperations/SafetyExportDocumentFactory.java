package com.dwp.services.platform.workplace.safetyoperations;

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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Component
class SafetyExportDocumentFactory {
    byte[] create(ExportFormat format, IncidentRow incident, PostIncidentReport report,
                  ExportEvidence evidence) {
        Map<String, String> fields = fields(incident, report, evidence);
        return format == ExportFormat.CSV ? csv(fields) : pdf(fields);
    }

    private Map<String, String> fields(IncidentRow incident, PostIncidentReport report,
                                       ExportEvidence evidence) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("incidentNumber", incident.number());
        values.put("incidentState", incident.state().name());
        values.put("purpose", evidence.purpose());
        values.put("reason", evidence.reason());
        values.put("requestedBy", Long.toString(evidence.requestedBy()));
        values.put("stepUpDecision", evidence.stepUpEvidence());
        values.put("correlationId", evidence.correlationId());
        values.put("generatedAt", evidence.generatedAt().toString());
        values.put("reportGeneratedAt", report.generatedAt().toString());
        values.put("reportSummary", report.summary().toString());
        values.put("signatureEvidence", "actor=" + evidence.requestedBy()
                + ";stepUp=" + evidence.stepUpEvidence()
                + ";correlation=" + evidence.correlationId()
                + ";generatedAt=" + evidence.generatedAt());
        return values;
    }

    private byte[] csv(Map<String, String> fields) {
        StringBuilder csv = new StringBuilder("field,value\r\n");
        fields.forEach((key, value) -> csv.append(escapeCsv(key)).append(',')
                .append(escapeCsv(value)).append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pdf(Map<String, String> fields) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                content.newLineAtOffset(42, 750);
                content.setLeading(13);
                content.showText("Workplace Safety Incident Export");
                content.newLine();
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    for (String line : wrap(ascii(field.getKey() + ": " + field.getValue()), 90)) {
                        content.showText(line);
                        content.newLine();
                    }
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the safety PDF export.", exception);
        }
    }

    private static String escapeCsv(String value) {
        String bounded = value == null ? "" : value;
        return '"' + bounded.replace("\"", "\"\"") + '"';
    }

    private static java.util.List<String> wrap(String value, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
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

    record ExportEvidence(String purpose, String reason, long requestedBy,
                          String correlationId, String stepUpEvidence,
                          OffsetDateTime generatedAt) { }
}
