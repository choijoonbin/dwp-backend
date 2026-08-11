package com.dwp.services.platform.codecatalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

@Repository
public class SystemCodeCatalogRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SystemCodeCatalogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<SystemCodeCatalogDtos.CodeSetHealth> health() {
        return jdbc.query("""
                SELECT code_set_key, owner_service, contract_kind,
                       configuration_level, validation_source, runtime_visibility, value_count,
                       binding_count, enforced_binding_count, registration_state
                  FROM sys_code_catalog_health
                 ORDER BY owner_service, code_set_key
                """, (result, ignored) -> new SystemCodeCatalogDtos.CodeSetHealth(
                result.getString("code_set_key"),
                result.getString("owner_service"),
                result.getString("contract_kind"),
                result.getString("configuration_level"),
                result.getString("validation_source"),
                result.getString("runtime_visibility"),
                result.getLong("value_count"),
                result.getLong("binding_count"),
                result.getLong("enforced_binding_count"),
                result.getString("registration_state")));
    }

    public SystemCodeCatalogDtos.RuntimeCodeSet getRuntime(
            String rawCodeSetKey, String rawLocale) {
        String codeSetKey = normalizeKey(rawCodeSetKey);
        String locale = normalizeLocale(rawLocale);
        List<Integer> schemaVersions = jdbc.query("""
                SELECT code_set.schema_version
                  FROM sys_code_sets code_set
                 WHERE code_set.code_set_key = ?
                   AND code_set.lifecycle_state = 'ACTIVE'
                   AND code_set.runtime_visibility = 'RUNTIME'
                """, (result, ignored) -> result.getInt("schema_version"), codeSetKey);
        if (schemaVersions.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);

        return new SystemCodeCatalogDtos.RuntimeCodeSet(
                codeSetKey, schemaVersions.get(0), runtimeValues(codeSetKey, locale));
    }

    public SystemCodeCatalogDtos.CodeSet get(String rawCodeSetKey, String rawLocale) {
        String codeSetKey = normalizeKey(rawCodeSetKey);
        String locale = normalizeLocale(rawLocale);
        List<CodeSetRow> rows = jdbc.query("""
                SELECT code_set_key, owner_service, contract_kind, display_name,
                       description, configuration_level, validation_source,
                       source_reference, schema_version, runtime_visibility
                  FROM sys_code_sets
                 WHERE code_set_key = ? AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new CodeSetRow(
                result.getString("code_set_key"),
                result.getString("owner_service"),
                result.getString("contract_kind"),
                result.getString("display_name"),
                result.getString("description"),
                result.getString("configuration_level"),
                result.getString("validation_source"),
                result.getString("source_reference"),
                result.getInt("schema_version"),
                result.getString("runtime_visibility")), codeSetKey);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);

        List<SystemCodeCatalogDtos.CodeValue> values = values(codeSetKey, locale);

        List<SystemCodeCatalogDtos.CodeBinding> bindings = jdbc.query("""
                SELECT consumer_service, usage_type, source_reference, enforcement_type
                  FROM sys_code_bindings
                 WHERE code_set_key = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY consumer_service, usage_type, source_reference
                """, (result, ignored) -> new SystemCodeCatalogDtos.CodeBinding(
                result.getString("consumer_service"),
                result.getString("usage_type"),
                result.getString("source_reference"),
                result.getString("enforcement_type")), codeSetKey);

        CodeSetRow set = rows.get(0);
        return new SystemCodeCatalogDtos.CodeSet(
                set.codeSetKey(), set.ownerService(), set.contractKind(),
                set.displayName(), set.description(),
                set.configurationLevel(), set.validationSource(), set.sourceReference(),
                set.schemaVersion(), set.runtimeVisibility(),
                List.copyOf(values), List.copyOf(bindings));
    }

    private List<SystemCodeCatalogDtos.CodeValue> values(String codeSetKey, String locale) {
        return jdbc.query("""
                SELECT code,
                       COALESCE(label_i18n ->> ?, label_i18n ->> ?,
                                label_i18n ->> 'en', display_name) AS resolved_label,
                       display_name, sort_order, predefined, lifecycle_state,
                       behavior_metadata::text AS behavior_metadata
                  FROM sys_code_values
                 WHERE code_set_key = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order, code
                """, (result, ignored) -> new SystemCodeCatalogDtos.CodeValue(
                result.getString("code"),
                result.getString("resolved_label"),
                result.getString("display_name"),
                result.getInt("sort_order"),
                result.getBoolean("predefined"),
                result.getString("lifecycle_state"),
                json(result.getString("behavior_metadata"))),
                locale, baseLanguage(locale), codeSetKey);
    }

    private List<SystemCodeCatalogDtos.RuntimeCodeValue> runtimeValues(
            String codeSetKey, String locale) {
        return jdbc.query("""
                SELECT code,
                       COALESCE(label_i18n ->> ?, label_i18n ->> ?,
                                label_i18n ->> 'en', display_name) AS resolved_label
                  FROM sys_code_values
                 WHERE code_set_key = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order, code
                """, (result, ignored) -> new SystemCodeCatalogDtos.RuntimeCodeValue(
                result.getString("code"), result.getString("resolved_label")),
                locale, baseLanguage(locale), codeSetKey);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The system code metadata is invalid.",
                    exception);
        }
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) throw new BaseException(ErrorCode.INVALID_FORMAT);
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_.]{2,99}")) {
            throw new BaseException(ErrorCode.INVALID_FORMAT);
        }
        return normalized;
    }

    private String normalizeLocale(String value) {
        if (value == null || value.isBlank()) return "en";
        return value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String baseLanguage(String locale) {
        int separator = locale.indexOf('-');
        return separator < 0 ? locale : locale.substring(0, separator);
    }

    private record CodeSetRow(
            String codeSetKey,
            String ownerService,
            String contractKind,
            String displayName,
            String description,
            String configurationLevel,
            String validationSource,
            String sourceReference,
            int schemaVersion,
            String runtimeVisibility) {
    }
}
