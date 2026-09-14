package com.dwp.services.approval.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Collects canonical person references after typed normalization, without granting directory authority. */
public final class ApprovalFormUserReferences {

    private final ApprovalFormSchemaV2Evaluator evaluator = new ApprovalFormSchemaV2Evaluator();

    public Prepared prepare(ApprovalFormSchemaV2 schema, Map<String, Object> input, boolean submitting) {
        var evaluation = evaluator.evaluate(schema, input, submitting);
        List<Reference> references = new ArrayList<>();
        collect(schema.scope, evaluation.payload(), "", references);
        return new Prepared(evaluation, references);
    }

    /** Only root USER fields or direct USER children of a repeating group are selectable. */
    public String requireUserField(ApprovalFormSchemaV2 schema, String groupKey, String fieldKey) {
        if (schema == null || fieldKey == null || fieldKey.isBlank()) throw invalid("A typed USER field is required.");
        var scope = schema.scope;
        String prefix = "";
        if (groupKey != null) {
            var group = scope.fields().get(groupKey);
            if (group == null || !group.group()) throw invalid("The typed USER group is invalid.");
            scope = group.children();
            prefix = groupKey + ".";
        }
        var field = scope.fields().get(fieldKey);
        if (field == null || !"USER".equals(field.type())) throw invalid("The typed USER field is invalid.");
        return prefix + fieldKey;
    }

    private void collect(ApprovalFormSchemaV2.Scope scope, Map<?, ?> payload, String prefix,
            List<Reference> references) {
        for (var field : scope.order()) {
            Object value = payload.get(field.key());
            if (value == null) continue;
            String path = prefix + field.key();
            if ("USER".equals(field.type())) {
                references.add(new Reference(path, canonicalPersonId(value, path)));
            } else if (field.group()) {
                var rows = (List<?>) value;
                for (int index = 0; index < rows.size(); index++) {
                    collect(field.children(), (Map<?, ?>) rows.get(index), path + "[" + index + "].", references);
                }
            }
        }
    }

    private UUID canonicalPersonId(Object value, String path) {
        if (!(value instanceof String text)
                || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid("A canonical person identifier is required: " + path);
        }
        return UUID.fromString((String) value);
    }

    private com.dwp.core.exception.BaseException invalid(String message) {
        return ApprovalFormSchemaV2Compiler.invalid(message);
    }

    public record Reference(String fieldPath, UUID personPublicId) { }

    public record Prepared(ApprovalFormSchemaV2.Evaluation evaluation, List<Reference> references) {
        public Prepared { references = List.copyOf(references); }

        public List<UUID> distinctPersonIds() {
            return references.stream().map(Reference::personPublicId).distinct().toList();
        }
    }
}
