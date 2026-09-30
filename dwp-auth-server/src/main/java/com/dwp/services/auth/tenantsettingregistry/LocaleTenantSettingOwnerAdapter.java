package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.regex.Pattern;

@Component
final class LocaleTenantSettingOwnerAdapter implements TenantSettingOwnerAdapter {

    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$");
    private final JdbcTemplate jdbc;

    LocaleTenantSettingOwnerAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ownerKey() {
        return "AUTH_TENANT_DIRECTORY";
    }

    @Override
    public String settingKey() {
        return "identity.defaultLocale";
    }

    @Override
    public String editorKind() {
        return "LOCALE";
    }

    @Override
    public boolean tenantEditable() {
        return true;
    }

    @Override
    public JsonNode read(Long tenantId) {
        return jdbc.query("SELECT default_locale FROM com_tenants WHERE tenant_id = ?",
                result -> result.next() ? TextNode.valueOf(result.getString(1)) : null,
                tenantId);
    }

    @Override
    public Instant sourceUpdatedAt(Long tenantId) {
        return jdbc.query("SELECT updated_at FROM com_tenants WHERE tenant_id = ?",
                result -> result.next() ? result.getTimestamp(1).toInstant() : null,
                tenantId);
    }

    @Override
    public void validate(JsonNode value) {
        if (value == null || !value.isTextual() || !LOCALE.matcher(value.textValue()).matches()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The locale must be a valid BCP 47 language tag.");
        }
    }

    @Override
    public void apply(Long tenantId, JsonNode value, Long actorId) {
        validate(value);
        int updated = jdbc.update("""
                UPDATE com_tenants
                   SET default_locale = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ?
                """, value.textValue(), actorId, tenantId);
        if (updated != 1) throw new BaseException(ErrorCode.NOT_FOUND);
    }
}
