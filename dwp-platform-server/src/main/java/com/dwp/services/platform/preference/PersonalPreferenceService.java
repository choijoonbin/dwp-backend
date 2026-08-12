package com.dwp.services.platform.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class PersonalPreferenceService {

    private static final int MAX_PAYLOAD_BYTES = 32_768;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 200;
    private static final Set<String> PATCH_NAMESPACES = Set.of("appearance", "accessibility", "regional");
    private static final Set<String> APPEARANCE_FIELDS = Set.of("mode", "density");
    private static final Set<String> ACCESSIBILITY_FIELDS = Set.of(
            "highContrast", "reduceMotion", "underlineLinks", "reduceTransparency");
    private static final Set<String> REGIONAL_FIELDS = Set.of(
            "timeZone", "dateFormat", "timeFormat", "firstDayOfWeek", "numberFormat");
    private static final Set<String> MODES = Set.of("system", "light", "dark");
    private static final Set<String> DENSITIES = Set.of("compact", "standard", "comfortable");
    private static final Set<String> DATE_FORMATS = Set.of("locale", "iso", "month_first", "day_first");
    private static final Set<String> TIME_FORMATS = Set.of("locale", "12_hour", "24_hour");
    private static final Set<String> FIRST_DAYS = Set.of("locale", "monday", "sunday");
    private static final Set<String> NUMBER_FORMATS = Set.of(
            "locale", "comma_decimal", "dot_decimal", "space_decimal");
    private static final PersonalPreferenceDtos.ManagedPreferencePolicy MANAGED_POLICY =
            new PersonalPreferenceDtos.ManagedPreferencePolicy(
                    "TENANT",
                    "TENANT_EXPERIENCE_POLICY",
                    "TENANT_ADMINISTRATOR",
                    List.of("appearance.fontFamily", "appearance.accentColor", "navigation.pattern"));

    private final PersonalPreferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformAuditService auditService;

    public PersonalPreferenceService(
            PersonalPreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PersonalPreferenceDtos.PersonalPreferenceResponse get(Long tenantId, Long userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId)
                .map(this::response)
                .orElseGet(this::defaultResponse);
    }

    @Transactional
    public PersonalPreferenceDtos.PersonalPreferenceResponse patch(
            Long tenantId,
            Long userId,
            String correlationId,
            PersonalPreferenceDtos.PatchPersonalPreferenceRequest request) {
        validatePatch(request.patch());
        PersonalPreference preference = repository.findByTenantIdAndUserId(tenantId, userId)
                .orElseGet(() -> create(tenantId, userId, request.version()));
        requireVersion(preference, request.version());

        Map<String, Object> before = snapshot(preference);
        ObjectNode merged = mergePatch(resolve(preference.getPreferencePayload()), request.patch());
        applyMissingDefaults(merged, defaultPreferences());
        validatePreferences(merged);

        preference.setSchemaVersion(PersonalPreferenceDtos.SCHEMA_VERSION);
        preference.setPreferencePayload(merged);
        PersonalPreference saved;
        try {
            saved = repository.saveAndFlush(preference);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }

        auditService.success(
                tenantId,
                userId,
                "personal-preference.updated",
                "PERSONAL_PREFERENCE",
                userId.toString(),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public PersonalPreferenceDtos.PersonalPreferenceResponse reset(
            Long tenantId,
            Long userId,
            String correlationId,
            Long version) {
        PersonalPreference preference = repository.findByTenantIdAndUserId(tenantId, userId)
                .orElse(null);
        if (preference == null) {
            if (version == null || version != 0L) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            return defaultResponse();
        }

        requireVersion(preference, version);
        Map<String, Object> before = snapshot(preference);
        repository.delete(preference);
        repository.flush();
        auditService.success(
                tenantId,
                userId,
                "personal-preference.reset",
                "PERSONAL_PREFERENCE",
                userId.toString(),
                correlationId,
                before,
                snapshot(null));
        return defaultResponse();
    }

    private PersonalPreference create(Long tenantId, Long userId, Long requestedVersion) {
        if (requestedVersion == null || requestedVersion != 0L) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return PersonalPreference.builder()
                .tenantId(tenantId)
                .userId(userId)
                .schemaVersion(PersonalPreferenceDtos.SCHEMA_VERSION)
                .preferencePayload(defaultPreferences())
                .build();
    }

    private void validatePatch(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw invalid("The personal preference patch must be a JSON object.");
        }
        rejectUnknownFields(patch, PATCH_NAMESPACES, "preference namespace");
        validatePatchNamespace(patch.get("appearance"), APPEARANCE_FIELDS, "appearance");
        validatePatchNamespace(patch.get("accessibility"), ACCESSIBILITY_FIELDS, "accessibility");
        validatePatchNamespace(patch.get("regional"), REGIONAL_FIELDS, "regional");
        validateDocumentLimits(patch);
    }

    private void validatePatchNamespace(JsonNode namespace, Set<String> fields, String name) {
        if (namespace == null || namespace.isNull()) return;
        if (!namespace.isObject()) {
            throw invalid("The " + name + " preference must be a JSON object.");
        }
        rejectUnknownFields(namespace, fields, name + " preference");
    }

    private void validatePreferences(JsonNode preferences) {
        JsonNode appearance = requireObject(preferences, "appearance");
        JsonNode accessibility = requireObject(preferences, "accessibility");
        JsonNode regional = requireObject(preferences, "regional");

        requireEnum(appearance, "mode", MODES);
        requireEnum(appearance, "density", DENSITIES);
        requireBoolean(accessibility, "highContrast");
        requireBoolean(accessibility, "reduceMotion");
        requireBoolean(accessibility, "underlineLinks");
        requireBoolean(accessibility, "reduceTransparency");
        requireTimeZone(regional, "timeZone");
        requireEnum(regional, "dateFormat", DATE_FORMATS);
        requireEnum(regional, "timeFormat", TIME_FORMATS);
        requireEnum(regional, "firstDayOfWeek", FIRST_DAYS);
        requireEnum(regional, "numberFormat", NUMBER_FORMATS);
        validateDocumentLimits(preferences);
    }

    private JsonNode requireObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid("The " + field + " preference is missing or invalid.");
        }
        return value;
    }

    private void requireEnum(JsonNode parent, String field, Set<String> values) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || !values.contains(value.asText())) {
            throw invalid("The " + field + " preference is invalid.");
        }
    }

    private void requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("The " + field + " preference is invalid.");
        }
    }

    private void requireTimeZone(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("The " + field + " preference is invalid.");
        }
        String timeZone = value.asText();
        if ("system".equals(timeZone)) return;
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw invalid("The " + field + " preference is invalid.");
        }
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String subject) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalid("The " + subject + " contains an unknown field.");
            }
        }
    }

    private void validateDocumentLimits(JsonNode node) {
        if (serializedSize(node) > MAX_PAYLOAD_BYTES
                || depth(node) > MAX_DEPTH
                || nodeCount(node) > MAX_NODES) {
            throw invalid("The personal preference document exceeds the configured limits.");
        }
    }

    private int serializedSize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The personal preference document could not be processed.");
        }
    }

    private int depth(JsonNode node) {
        if (!node.isContainerNode() || node.isEmpty()) return 1;
        int maximum = 0;
        for (JsonNode child : node) {
            maximum = Math.max(maximum, depth(child));
        }
        return maximum + 1;
    }

    private int nodeCount(JsonNode node) {
        int count = 1;
        for (JsonNode child : node) {
            count += nodeCount(child);
        }
        return count;
    }

    private ObjectNode resolve(JsonNode stored) {
        ObjectNode resolved = defaultPreferences();
        if (stored == null || !stored.isObject()) return resolved;
        mergeObject(resolved, stored);
        return resolved;
    }

    private ObjectNode mergePatch(JsonNode target, JsonNode patch) {
        ObjectNode result = target != null && target.isObject()
                ? ((ObjectNode) target).deepCopy()
                : objectMapper.createObjectNode();
        patch.fields().forEachRemaining(entry -> {
            JsonNode patchValue = entry.getValue();
            if (patchValue.isNull()) {
                result.remove(entry.getKey());
            } else if (patchValue.isObject()) {
                result.set(entry.getKey(), mergePatch(result.get(entry.getKey()), patchValue));
            } else {
                result.set(entry.getKey(), patchValue.deepCopy());
            }
        });
        return result;
    }

    private void applyMissingDefaults(ObjectNode target, ObjectNode defaults) {
        defaults.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            if (current == null) {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            } else if (current.isObject() && entry.getValue().isObject()) {
                applyMissingDefaults((ObjectNode) current, (ObjectNode) entry.getValue());
            }
        });
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (current != null && current.isObject() && value.isObject()) {
                mergeObject((ObjectNode) current, value);
            } else {
                target.set(entry.getKey(), value.deepCopy());
            }
        });
    }

    private ObjectNode defaultPreferences() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("appearance")
                .put("mode", "system")
                .put("density", "standard");
        root.putObject("accessibility")
                .put("highContrast", false)
                .put("reduceMotion", false)
                .put("underlineLinks", false)
                .put("reduceTransparency", false);
        root.putObject("regional")
                .put("timeZone", "system")
                .put("dateFormat", "locale")
                .put("timeFormat", "locale")
                .put("firstDayOfWeek", "locale")
                .put("numberFormat", "locale");
        return root;
    }

    private PersonalPreferenceDtos.PersonalPreferenceResponse response(PersonalPreference preference) {
        if (preference.getSchemaVersion() == null
                || preference.getSchemaVersion() < 1
                || preference.getSchemaVersion() > PersonalPreferenceDtos.SCHEMA_VERSION
                || preference.getPreferencePayload() == null
                || !preference.getPreferencePayload().isObject()) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "The stored personal preference is invalid.");
        }
        ObjectNode preferences = resolve(preference.getPreferencePayload());
        try {
            validatePreferences(preferences);
        } catch (BaseException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored personal preference is invalid.");
        }
        return new PersonalPreferenceDtos.PersonalPreferenceResponse(
                PersonalPreferenceDtos.SCHEMA_VERSION,
                true,
                preferences,
                MANAGED_POLICY,
                preference.getVersion() == null ? 0L : preference.getVersion(),
                preference.getUpdatedAt());
    }

    private PersonalPreferenceDtos.PersonalPreferenceResponse defaultResponse() {
        return new PersonalPreferenceDtos.PersonalPreferenceResponse(
                PersonalPreferenceDtos.SCHEMA_VERSION,
                false,
                defaultPreferences(),
                MANAGED_POLICY,
                0L,
                null);
    }

    private void requireVersion(PersonalPreference preference, Long requestedVersion) {
        long current = preference.getVersion() == null ? 0L : preference.getVersion();
        if (requestedVersion == null || current != requestedVersion) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private Map<String, Object> snapshot(PersonalPreference preference) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (preference == null) {
            value.put("customized", false);
            return value;
        }
        value.put("customized", true);
        value.put("schemaVersion", preference.getSchemaVersion());
        value.put("preferences", preference.getPreferencePayload());
        value.put("version", preference.getVersion() == null ? 0L : preference.getVersion());
        return value;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
