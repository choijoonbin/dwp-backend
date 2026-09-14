package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Composes normalization for locked server-owned pins without duplicating legacy validation. */
public final class ApprovalFormPayloadNormalizer implements ApprovalFormPayloadNormalization {
    @FunctionalInterface
    public interface LegacyNormalization {
        Map<String, Object> normalize(String immutableSchema, Map<String, Object> payload, boolean submitting);
    }

    private final ObjectMapper mapper;
    private final ApprovalFormReferenceNormalizer references;
    private final LegacyNormalization legacy;

    public ApprovalFormPayloadNormalizer(ObjectMapper mapper, ApprovalFormReferenceNormalizer references,
            LegacyNormalization legacy) {
        this.mapper = Objects.requireNonNull(mapper).copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.references = Objects.requireNonNull(references);
        this.legacy = Objects.requireNonNull(legacy);
    }

    @Override
    public Map<String, Object> normalize(Actor actor, UUID requestId, UUID formVersionId,
            String formSchemaSha256, String immutableSchema, Map<String, Object> merged,
            boolean submitting, long expectedRequestVersion) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || actor.tenantId() <= 0 || actor.userId() <= 0
                || requestId == null || formVersionId == null
                || formSchemaSha256 == null || !formSchemaSha256.matches("[a-f0-9]{64}") || immutableSchema == null
                || merged == null || expectedRequestVersion < 0 || expectedRequestVersion > 9_007_199_254_740_991L) {
            throw conflict();
        }
        Map<String, Object> definition = definition(immutableSchema);
        // Legacy hashes describe the original persisted bytes, not a JSONB rendering; the caller verifies their DB pins.
        if (!definition.containsKey("schemaContract")) return legacy.normalize(immutableSchema, merged, submitting);
        if (!ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Unknown approval form schema contract.");
        }
        ApprovalFormSchemaV2 schema = new ApprovalFormSchemaV2Compiler().compile(definition);
        if (!schema.sha256().equals(formSchemaSha256)) throw conflict();
        var summary = schema.scope.fields().get("summary");
        if (summary == null || !("TEXT".equals(summary.type()) || "TEXTAREA".equals(summary.type()))
                || summary.visibleWhen() != null) throw new BaseException(
                        ErrorCode.INVALID_STATE, "The immutable typed form must have an always-visible root summary.");
        return references.normalize(actor, requestId, formVersionId, immutableSchema, merged,
                submitting, expectedRequestVersion, false, false);
    }

    private Map<String, Object> definition(String schema) {
        try {
            Map<String, Object> result = mapper.readValue(schema, new TypeReference<>() { });
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Stored approval form schema is invalid.");
        }
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The locked immutable form snapshot does not match.");
    }
}
