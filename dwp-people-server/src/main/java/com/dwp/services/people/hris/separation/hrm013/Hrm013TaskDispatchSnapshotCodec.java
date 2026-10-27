package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;

/** Decodes and validates the frozen task-dispatch snapshot fields. */
final class Hrm013TaskDispatchSnapshotCodec {

    static void requireSnapshot(JsonNode value) {
        java.util.Set<String> expected = java.util.Set.of(
                "bindingId", "ownerWorkloadId", "purpose", "taskType",
                "requestBodySha256", "payloadSha256",
                "dispatchTenantGrantRevision", "dispatchAuthorizationRevision",
                "callbackTenantGrantRevision", "callbackAuthorizationRevision");
        java.util.Set<String> actual = new java.util.HashSet<>();
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("dispatch fence snapshot is not an object");
        }
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)
                || value.path("dispatchTenantGrantRevision").longValue() < 1
                || value.path("dispatchAuthorizationRevision").longValue() < 1
                || value.path("callbackTenantGrantRevision").longValue() < 1
                || value.path("callbackAuthorizationRevision").longValue() < 1) {
            throw new IllegalStateException("dispatch fence snapshot shape is invalid");
        }
    }

    static Hrm013TaskDispatchV1.DispatchBody decodeStored(
            Hrm013TaskDispatchV1.Profile profile, long tenantId, JsonNode node) {
        try {
            Hrm013TaskDispatchV1.DispatchBody body;
            if (profile.equals(Hrm013TaskDispatchV1.IDENTITY)) {
                body = new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                        text(node, "schemaName"), exactInt(node, "schemaVersion"),
                        uuid(node, "dispatchEventId"), uuid(node, "casePublicId"),
                        uuid(node, "taskPublicId"), text(node, "taskType"),
                        positive(node, "taskOwnerRevision"),
                        positive(node, "taskVersion"), instant(node, "dispatchedAt"),
                        text(node, "payloadSha256"), uuid(node, "correlationId"),
                        uuid(node, "personPublicId"), text(node, "separationType"),
                        LocalDate.parse(text(node, "effectiveDate")));
            } else {
                body = new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        text(node, "schemaName"), exactInt(node, "schemaVersion"),
                        uuid(node, "dispatchEventId"), uuid(node, "casePublicId"),
                        uuid(node, "taskPublicId"), text(node, "taskType"),
                        positive(node, "taskOwnerRevision"),
                        positive(node, "taskVersion"), instant(node, "dispatchedAt"),
                        text(node, "payloadSha256"), uuid(node, "correlationId"),
                        uuid(node, "workerPublicId"),
                        uuid(node, "relationshipPublicId"),
                        text(node, "separationType"),
                        LocalDate.parse(text(node, "effectiveDate")),
                        LocalDate.parse(text(node, "lastWorkingDate")));
            }
            Hrm013TaskDispatchV1.requireProfileBinding(profile, tenantId, body);
            return body;
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("invalid stored dispatch payload", malformed);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.textValue();
    }

    static UUID uuid(JsonNode node, String field) {
        UUID value = UUID.fromString(text(node, field));
        if (!value.toString().equals(text(node, field))) throw new IllegalArgumentException(field);
        return value;
    }

    static long positive(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) throw new IllegalArgumentException(field);
        return value.longValue();
    }

    static int exactInt(JsonNode node, String field) {
        long value = positive(node, field);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(field);
        return (int) value;
    }

    static Instant instant(JsonNode node, String field) {
        Instant value = Instant.parse(text(node, field));
        if (!value.toString().equals(text(node, field))) throw new IllegalArgumentException(field);
        return value;
    }

    static Hrm013TaskDispatchV1.Profile profile(String eventType) {
        return Hrm013TaskDispatchV1.PROFILES.stream()
                .filter(value -> value.eventType().equals(eventType))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown dispatch event"));
    }
}
