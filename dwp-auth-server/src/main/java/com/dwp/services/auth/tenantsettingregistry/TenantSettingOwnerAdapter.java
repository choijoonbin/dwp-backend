package com.dwp.services.auth.tenantsettingregistry;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Applies a published value to the service that actually owns the setting. */
public interface TenantSettingOwnerAdapter {

    String ownerKey();

    String settingKey();

    /** Stable UI contract. Unknown types deliberately fall back to OWNER_ONLY. */
    default String editorKind() {
        return "OWNER_ONLY";
    }

    /** Whether this adapter accepts a tenant override from the generic workflow. */
    default boolean tenantEditable() {
        return false;
    }

    JsonNode read(Long tenantId);

    Instant sourceUpdatedAt(Long tenantId);

    void validate(JsonNode value);

    void apply(Long tenantId, JsonNode value, Long actorId);
}
