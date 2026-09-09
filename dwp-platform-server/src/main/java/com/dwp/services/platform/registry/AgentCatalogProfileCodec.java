package com.dwp.services.platform.registry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AgentCatalogProfileCodec {

    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final int MAX_PROFILE_BYTES = 64 * 1024;
    private static final String SOURCE_PATTERN = "[A-Z][A-Z0-9_]{0,63}";
    private static final String PERMISSION_PATTERN =
            "[A-Z][A-Z0-9_.-]{0,99}:[A-Z][A-Z0-9_.-]{0,39}";

    private final ObjectMapper objectMapper;

    public AgentCatalogProfileCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RegistryDtos.AgentCatalogProfile decode(JsonNode stored) {
        if (stored == null || stored.isNull()) return null;
        try {
            if (!stored.isObject() || objectMapper.writeValueAsBytes(stored).length > MAX_PROFILE_BYTES) {
                throw invalidProfile();
            }
            RegistryDtos.AgentCatalogProfile profile = objectMapper.treeToValue(
                    stored,
                    RegistryDtos.AgentCatalogProfile.class);
            validate(profile);
            return profile;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored agent catalog profile is invalid.",
                    exception);
        }
    }

    void validate(RegistryDtos.AgentCatalogProfile profile) {
        if (profile == null
                || !Integer.valueOf(RegistryDtos.AGENT_CATALOG_SCHEMA_VERSION)
                        .equals(profile.schemaVersion())
                || profile.category() == null
                || !bounded(profile.capabilities(), 1, 8)
                || !bounded(profile.boundaries(), 1, 8)
                || !bounded(profile.sources(), 1, 8)
                || !bounded(profile.starterPrompts(), 1, 6)) {
            throw invalidProfile();
        }
        requireLocalized(profile.displayName());
        requireLocalized(profile.description());
        requireLocalized(profile.safetySummary());
        profile.capabilities().forEach(this::requireLocalized);
        profile.boundaries().forEach(this::requireLocalized);
        profile.starterPrompts().forEach(this::requireLocalized);

        Set<String> sourceSystems = new HashSet<>();
        for (RegistryDtos.AgentCatalogSource source : profile.sources()) {
            if (source == null
                    || !text(source.sourceSystem(), 64)
                    || !source.sourceSystem().matches(SOURCE_PATTERN)
                    || !sourceSystems.add(source.sourceSystem())
                    || source.permissionMatch() != RegistryDtos.AgentSourcePermissionMatch.ANY_OF
                    || source.accessMode() != RegistryDtos.AgentSourceAccessMode.READ_ONLY
                    || !bounded(source.requiredPermissions(), 1, 8)
                    || source.requiredPermissions().stream().anyMatch(permission ->
                            !text(permission, 140) || !permission.matches(PERMISSION_PATTERN))) {
                throw invalidProfile();
            }
            requireLocalized(source.displayName());
        }
    }

    private void requireLocalized(RegistryDtos.LocalizedCatalogText value) {
        if (value == null || !text(value.ko(), MAX_TEXT_LENGTH) || !text(value.en(), MAX_TEXT_LENGTH)) {
            throw invalidProfile();
        }
    }

    private boolean bounded(List<?> values, int min, int max) {
        return values != null && values.size() >= min && values.size() <= max;
    }

    private boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private BaseException invalidProfile() {
        return new BaseException(
                ErrorCode.INTERNAL_SERVER_ERROR,
                "The stored agent catalog profile is invalid.");
    }
}
