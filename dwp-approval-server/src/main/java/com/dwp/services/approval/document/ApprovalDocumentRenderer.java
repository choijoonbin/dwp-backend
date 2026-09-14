package com.dwp.services.approval.document;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;

@Component
public class ApprovalDocumentRenderer {
    private final ApprovalDocumentCanonical canonical;
    public ApprovalDocumentRenderer(ApprovalDocumentCanonical canonical) { this.canonical = canonical; }

    public List<DocumentField> fields(String payload, List<FieldRule> rules) {
        JsonNode tree = canonical.read(payload, JsonNode.class);
        if (!tree.isObject()) throw ApprovalDocumentCanonical.forbidden();
        return fields(tree, rules, 0);
    }
    public List<DocumentField> fields(String payload, String schema, List<FieldRule> rules) {
        JsonNode definition = canonical.read(schema, JsonNode.class);
        schema(definition.get("fields"), rules, definition.path("schemaVersion").asInt() == 2, 0);
        return fields(payload, rules);
    }
    private void schema(JsonNode fields, List<FieldRule> rules, boolean typed, int depth) {
        if (rules.isEmpty()) return;
        if (fields == null || !fields.isArray() || depth > 4) throw ApprovalDocumentCanonical.forbidden();
        var known = new java.util.HashMap<String, JsonNode>();
        for (var field : fields) {
            if (!field.isObject() || !field.path("key").isTextual() || known.put(field.path("key").asText(), field) != null) throw ApprovalDocumentCanonical.forbidden();
        }
        for (var rule : rules) {
            var field = known.get(rule.key()); if (field == null) throw ApprovalDocumentCanonical.forbidden();
            String type = field.path("type").asText();
            boolean compatible = switch (rule.type()) {
                case STRING -> List.of("TEXT", "TEXTAREA", "DATE", "SELECT", "USER").contains(type);
                case DECIMAL_STRING -> typed && List.of("NUMBER", "CALCULATED_NUMBER").contains(type);
                case NUMBER -> !typed && "NUMBER".equals(type);
                case OBJECT_LIST -> typed && "REPEATING_GROUP".equals(type)
                        && rule.maxRows() != null && rule.maxRows() <= field.path("maxRows").asInt(20);
                case BOOLEAN -> "BOOLEAN".equals(type);
                case STRING_LIST -> "MULTI_SELECT".equals(type);
                case OBJECT -> "OBJECT".equals(type);
            };
            if (!compatible) throw ApprovalDocumentCanonical.forbidden();
            if (rule.type() == FieldType.OBJECT_LIST || rule.type() == FieldType.OBJECT) schema(field.get("fields"), rule.children(), typed, depth + 1);
        }
    }
    private List<DocumentField> fields(JsonNode tree, List<FieldRule> rules, int depth) {
        if (depth > 5) throw ApprovalDocumentCanonical.forbidden();
        var result = new ArrayList<DocumentField>();
        for (var rule : rules) {
            JsonNode value = tree.get(rule.key());
            if (value == null || value.isNull()) continue;
            String text = null; BigDecimal number = null; Boolean flag = null;
            List<String> strings = List.of(); List<DocumentField> children = List.of();
            List<DocumentRow> rows = List.of();
            switch (rule.type()) {
                case STRING -> { if (!value.isTextual()) throw ApprovalDocumentCanonical.forbidden(); text = bounded(value.textValue(), rule.maxLength()); }
                case NUMBER -> {
                    if (!value.isIntegralNumber() || value.asText().length() > rule.maxLength()) throw ApprovalDocumentCanonical.forbidden();
                    try { number = new BigDecimal(value.asText()); } catch (NumberFormatException e) { throw ApprovalDocumentCanonical.forbidden(); }
                    if (number.abs().compareTo(new BigDecimal("9007199254740991")) > 0) throw ApprovalDocumentCanonical.forbidden();
                }
                case DECIMAL_STRING -> {
                    if (!value.isTextual()) throw ApprovalDocumentCanonical.forbidden();
                    text = bounded(value.textValue(), rule.maxLength());
                    if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw ApprovalDocumentCanonical.forbidden();
                    BigDecimal decimal = new BigDecimal(text);
                    if (decimal.precision() > 28 || decimal.scale() > 8) throw ApprovalDocumentCanonical.forbidden();
                }
                case BOOLEAN -> { if (!value.isBoolean()) throw ApprovalDocumentCanonical.forbidden(); flag = value.booleanValue(); }
                case STRING_LIST -> {
                    if (!value.isArray() || value.size() > 100) throw ApprovalDocumentCanonical.forbidden();
                    var list = new ArrayList<String>();
                    for (var item : value) { if (!item.isTextual()) throw ApprovalDocumentCanonical.forbidden(); list.add(bounded(item.textValue(), rule.maxLength())); }
                    strings = List.copyOf(list);
                }
                case OBJECT -> { if (!value.isObject()) throw ApprovalDocumentCanonical.forbidden(); children = fields(value, rule.children(), depth + 1); }
                case OBJECT_LIST -> {
                    if (!value.isArray() || rule.maxRows() == null || value.size() > rule.maxRows() || value.size() > 50) throw ApprovalDocumentCanonical.forbidden();
                    var list = new ArrayList<DocumentRow>(); int row = 0;
                    for (var item : value) { if (!item.isObject()) throw ApprovalDocumentCanonical.forbidden(); list.add(new DocumentRow(++row, fields(item, rule.children(), depth + 1))); }
                    rows = List.copyOf(list);
                }
            }
            result.add(new DocumentField(rule.key(), rule.type(), text, number, flag, strings, children, rows));
        }
        return List.copyOf(result);
    }

    public String render(List<Document> documents, Intent intent, Instant generatedAt) {
        if (intent == Intent.DOWNLOAD) return canonical.json(new FileContent(1, "JSON", generatedAt, documents));
        var html = new StringBuilder("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>Approval document</title><style>body{font:14px sans-serif;color:#111;margin:24px}article{break-after:page}h1{font-size:20px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #aaa;padding:8px;text-align:left;overflow-wrap:anywhere}pre{white-space:pre-wrap}footer{font-size:11px}</style></head><body>");
        for (var doc : documents) {
            html.append("<article><h1>").append(escape(doc.title())).append("</h1><p>")
                    .append(escape(doc.requestNumber())).append(" / ").append(escape(doc.classification()))
                    .append(" / ").append(escape(doc.status())).append("</p><p>").append(escape(doc.summary()))
                    .append("</p><table><tbody>");
            htmlFields(html, doc.fields(), "");
            html.append("</tbody></table><section>");
            for (var comment : doc.comments()) html.append("<p>").append(comment.sequence()).append(". ")
                    .append(escape(comment.text())).append("</p>");
            for (var event : doc.evidence()) html.append("<p>").append(escape(event.eventType())).append(" ")
                    .append(escape(event.message())).append("</p>");
            html.append("</section><footer>Payload revision ").append(doc.payloadRevision()).append(" / ")
                    .append(escape(doc.payloadSha256())).append(" / ").append(generatedAt).append("</footer></article>");
        }
        return html.append("</body></html>").toString();
    }
    private void htmlFields(StringBuilder html, List<DocumentField> fields, String prefix) {
        for (var field : fields) {
            String key = prefix + field.key();
            if (field.type() == FieldType.OBJECT) { htmlFields(html, field.children(), key + "."); continue; }
            if (field.type() == FieldType.OBJECT_LIST) {
                for (var row : field.rows()) htmlFields(html, row.fields(), key + "[" + row.rowNumber() + "].");
                continue;
            }
            String value = switch (field.type()) {
                case STRING, DECIMAL_STRING -> field.stringValue(); case NUMBER -> field.numberValue().toPlainString();
                case BOOLEAN -> field.booleanValue().toString(); case STRING_LIST -> String.join(", ", field.stringValues());
                case OBJECT, OBJECT_LIST -> "";
            };
            html.append("<tr><th>").append(escape(key)).append("</th><td>").append(escape(value)).append("</td></tr>");
        }
    }
    private String bounded(String text, int maximum) {
        if (text.length() > maximum || text.indexOf('\u0000') >= 0) throw ApprovalDocumentCanonical.forbidden();
        return text;
    }
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private record FileContent(int schemaVersion, String format, Instant generatedAt, List<Document> documents) { }
}
