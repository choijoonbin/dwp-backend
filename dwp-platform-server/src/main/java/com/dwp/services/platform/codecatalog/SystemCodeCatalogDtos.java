package com.dwp.services.platform.codecatalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class SystemCodeCatalogDtos {

    private SystemCodeCatalogDtos() {
    }

    public record CodeValue(
            String code,
            String label,
            String displayName,
            int sortOrder,
            boolean predefined,
            String lifecycleState,
            JsonNode behaviorMetadata) {
    }

    public record CodeBinding(
            String consumerService,
            String usageType,
            String sourceReference,
            String enforcementType) {
    }

    public record CodeSet(
            String codeSetKey,
            String ownerService,
            String contractKind,
            String displayName,
            String description,
            String configurationLevel,
            String validationSource,
            String sourceReference,
            int schemaVersion,
            String runtimeVisibility,
            List<CodeValue> values,
            List<CodeBinding> bindings) {
    }

    public record RuntimeCodeSet(
            String codeSetKey,
            int schemaVersion,
            List<RuntimeCodeValue> values) {
    }

    public record RuntimeCodeValue(
            String code,
            String label) {
    }

    public record CatalogSnapshot(
            String catalogScope,
            String changePolicy,
            List<CodeSetHealth> codeSets) {
    }

    public record CodeSetHealth(
            String codeSetKey,
            String displayName,
            String ownerService,
            String contractKind,
            String configurationLevel,
            String validationSource,
            String runtimeVisibility,
            int schemaVersion,
            long valueCount,
            long bindingCount,
            long enforcedBindingCount,
            String registrationState) {
    }
}
