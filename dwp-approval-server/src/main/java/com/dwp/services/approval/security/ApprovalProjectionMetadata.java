package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact projection metadata delegates to the corresponding immutable response contract. */
final class ApprovalProjectionMetadata {
    private ApprovalProjectionMetadata() { }

    static void validate(String routeKey, String profileKey, JsonNode projection) {
        Set<String> fields = new LinkedHashSet<>();
        projection.fieldNames().forEachRemaining(fields::add);
        Set<String> base = Set.of("apiBindingKey", "projectionPolicyKey", "responseSchemaKey");
        boolean work = ApprovalWorkProjectionSchemaContract.isWorkDataRoute(routeKey);
        boolean document = ApprovalDocumentProjectionSchemaContract.isDocumentDataRoute(routeKey);
        if (ApprovalRelease10ProjectionSchemaContract.isRelease10DataRoute(routeKey)) {
            require(ApprovalRelease10ProjectionSchemaContract.matches(routeKey, profileKey, projection),
                    "Approval release10 response projection schema metadata changed");
        } else if (ApprovalExtensionProjectionSchemaContract.isExtensionDataRoute(routeKey)) {
            require(ApprovalExtensionProjectionSchemaContract.matches(routeKey, profileKey, projection),
                    "Approval v9 extension projection schema metadata changed");
        } else if (ApprovalProjectionSchemaContract.isFieldMaskProfile(profileKey) || work || document) {
            Set<String> expected = new LinkedHashSet<>(base);
            expected.addAll(Set.of("schemaVersion", "openApiSchemaSha256", "additionalProperties"));
            require(fields.equals(expected), "Approval field-mask projection metadata fields changed");
            if (work) require(ApprovalWorkProjectionSchemaContract.matches(routeKey, profileKey, projection),
                    "Approval v7 work projection schema metadata changed");
            if (document) require(ApprovalDocumentProjectionSchemaContract.matches(routeKey, profileKey, projection),
                    "Approval v8 document/source projection schema metadata changed");
        } else require(fields.equals(base), "Projection schema metadata is forbidden for this Approval profile");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
