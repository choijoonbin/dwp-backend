package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class AuthDefaultLoginTypeTenantSettingOwnerAdapter implements TenantSettingOwnerAdapter {

    private final JdbcTemplate jdbc;

    AuthDefaultLoginTypeTenantSettingOwnerAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ownerKey() {
        return "AUTH_POLICY";
    }

    @Override
    public String settingKey() {
        return "authentication.defaultLoginType";
    }

    @Override
    public String editorKind() {
        return "LOGIN_TYPE";
    }

    @Override
    public JsonNode read(Long tenantId) {
        return jdbc.query("SELECT default_login_type FROM sys_auth_policies WHERE tenant_id = ?",
                result -> result.next() ? TextNode.valueOf(result.getString(1)) : null,
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
                "This authentication setting is managed by the policy owner.");
    }

    @Override
    public void apply(Long tenantId, JsonNode value, Long actorId) {
        validate(value);
    }
}
