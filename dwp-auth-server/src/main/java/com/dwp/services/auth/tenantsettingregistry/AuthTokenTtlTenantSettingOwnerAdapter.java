package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class AuthTokenTtlTenantSettingOwnerAdapter implements TenantSettingOwnerAdapter {

    private final JdbcTemplate jdbc;

    AuthTokenTtlTenantSettingOwnerAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ownerKey() {
        return "AUTH_POLICY";
    }

    @Override
    public String settingKey() {
        return "authentication.tokenTtlSec";
    }

    @Override
    public String editorKind() {
        return "DURATION_SECONDS";
    }

    @Override
    public JsonNode read(Long tenantId) {
        return jdbc.query("SELECT token_ttl_sec FROM sys_auth_policies WHERE tenant_id = ?",
                result -> {
                    if (!result.next()) return null;
                    Integer value = (Integer) result.getObject(1);
                    return value == null ? NullNode.instance : IntNode.valueOf(value);
                }, tenantId);
    }

    @Override
    public Instant sourceUpdatedAt(Long tenantId) {
        return jdbc.query("SELECT updated_at FROM sys_auth_policies WHERE tenant_id = ?",
                result -> result.next() ? result.getTimestamp(1).toInstant() : null,
                tenantId);
    }

    @Override
    public void validate(JsonNode value) {
        throw new BaseException(ErrorCode.FORBIDDEN,
                "This authentication setting is managed by the policy owner.");
    }

    @Override
    public void apply(Long tenantId, JsonNode value, Long actorId) {
        validate(value);
    }
}
