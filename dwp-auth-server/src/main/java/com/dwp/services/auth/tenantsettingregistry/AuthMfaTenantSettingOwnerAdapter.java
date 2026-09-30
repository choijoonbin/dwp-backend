package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class AuthMfaTenantSettingOwnerAdapter implements TenantSettingOwnerAdapter {

    private final JdbcTemplate jdbc;

    AuthMfaTenantSettingOwnerAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ownerKey() {
        return "AUTH_POLICY";
    }

    @Override
    public String settingKey() {
        return "authentication.requireMfa";
    }

    @Override
    public String editorKind() {
        return "BOOLEAN";
    }

    @Override
    public JsonNode read(Long tenantId) {
        return jdbc.query("SELECT require_mfa FROM sys_auth_policies WHERE tenant_id = ?",
                result -> result.next() ? BooleanNode.valueOf(result.getBoolean(1)) : null,
                tenantId);
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
                "The canonical authentication-policy workflow owns this setting.");
    }

    @Override
    public void apply(Long tenantId, JsonNode value, Long actorId) {
        validate(value);
    }
}
