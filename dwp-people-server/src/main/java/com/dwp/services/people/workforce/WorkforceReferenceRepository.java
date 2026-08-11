package com.dwp.services.people.workforce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class WorkforceReferenceRepository {

    private static final TypeReference<Map<String, String>> LABELS_TYPE = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkforceReferenceRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<WorkforceReferenceDtos.ReferenceCatalog> catalogs(Long tenantId, String locale) {
        return List.of(
                catalog("ORGANIZATION_TYPE", "TENANT", true, organizationTypes(tenantId, locale)),
                catalog("JOB_GRADE", "TENANT", true, jobGrades(tenantId, locale)),
                catalog("ASSIGNMENT_REASON", "TENANT", true, assignmentReasons(tenantId, locale)),
                catalog("ORGANIZATION_ROLE", "TENANT", true, organizationRoles(tenantId, locale)),
                catalog("POSITION_TYPE", "PRODUCT", false, positionTypes(locale)),
                catalog("POSITION_CRITICALITY", "PRODUCT", false, positionCriticalities(locale)),
                catalog("APPROVAL_ROLE", "PRODUCT", false, approvalRoles(locale)));
    }

    public boolean update(
            Long tenantId,
            String catalogKey,
            String code,
            WorkforceReferenceDtos.UpdateReferenceValueRequest request,
            Long actorId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("code", code)
                .addValue("displayName", request.displayName().trim())
                .addValue("description", blankToNull(request.description()))
                .addValue("labels", labelsJson(request.labels()))
                .addValue("lifecycleState", request.lifecycleState())
                .addValue("version", request.version())
                .addValue("actorId", actorId);
        String sql = switch (catalogKey) {
            case "ORGANIZATION_TYPE" -> """
                    UPDATE ppl_organization_type_catalog
                       SET display_name = :displayName,
                           description = :description,
                           label_i18n = label_i18n || CAST(:labels AS jsonb),
                           lifecycle_state = :lifecycleState,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :actorId
                     WHERE tenant_id = :tenantId AND type_key = :code AND version = :version
                    """;
            case "JOB_GRADE" -> """
                    UPDATE ppl_job_grades
                       SET name = :displayName,
                           description = :description,
                           label_i18n = label_i18n || CAST(:labels AS jsonb),
                           lifecycle_state = :lifecycleState,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :actorId
                     WHERE tenant_id = :tenantId AND grade_key = :code AND version = :version
                    """;
            case "ASSIGNMENT_REASON" -> """
                    UPDATE ppl_assignment_change_reason_catalog
                       SET display_name = :displayName,
                           description = :description,
                           label_i18n = label_i18n || CAST(:labels AS jsonb),
                           lifecycle_state = :lifecycleState,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :actorId
                     WHERE tenant_id = :tenantId AND reason_code = :code AND version = :version
                    """;
            case "ORGANIZATION_ROLE" -> """
                    UPDATE ppl_organization_role_catalog
                       SET display_name = :displayName,
                           description = :description,
                           label_i18n = label_i18n || CAST(:labels AS jsonb),
                           lifecycle_state = :lifecycleState,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :actorId
                     WHERE tenant_id = :tenantId AND role_code = :code AND version = :version
                    """;
            default -> null;
        };
        return sql != null && jdbc.update(sql, parameters) == 1;
    }

    private WorkforceReferenceDtos.ReferenceCatalog catalog(
            String key,
            String ownership,
            boolean editable,
            List<WorkforceReferenceDtos.ReferenceValue> values) {
        return new WorkforceReferenceDtos.ReferenceCatalog(key, ownership, editable, values);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> organizationTypes(Long tenantId, String locale) {
        return query("""
                SELECT type_key AS code, display_name, description, label_i18n::text AS labels_json,
                       COALESCE(hierarchy_rank, 0) AS sort_order, lifecycle_state,
                       FALSE AS predefined,
                       CONCAT('rank=', COALESCE(hierarchy_rank::text, '-'),
                              '; root=', root_candidate,
                              '; worker=', worker_assignment_allowed) AS detail,
                       version
                  FROM ppl_organization_type_catalog
                 WHERE tenant_id = :tenantId
                 ORDER BY COALESCE(hierarchy_rank, 10000), display_name
                """, tenantParameters(tenantId), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> jobGrades(Long tenantId, String locale) {
        return query("""
                SELECT grade_key AS code, name AS display_name, description,
                       label_i18n::text AS labels_json, level_order AS sort_order, lifecycle_state,
                       FALSE AS predefined, CONCAT('track=', career_track) AS detail, version
                  FROM ppl_job_grades
                 WHERE tenant_id = :tenantId
                 ORDER BY level_order, name
                """, tenantParameters(tenantId), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> assignmentReasons(Long tenantId, String locale) {
        return query("""
                SELECT reason_code AS code, display_name, description, label_i18n::text AS labels_json,
                       sort_order, lifecycle_state, predefined,
                       CONCAT('effective=', effective_start_date, '..', COALESCE(effective_end_date::text, 'open')) AS detail,
                       version
                  FROM ppl_assignment_change_reason_catalog
                 WHERE tenant_id = :tenantId
                 ORDER BY sort_order, display_name
                """, tenantParameters(tenantId), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> organizationRoles(Long tenantId, String locale) {
        return query("""
                SELECT role_code AS code, display_name, description, label_i18n::text AS labels_json,
                       sort_order, lifecycle_state, predefined,
                       CONCAT('person=', allows_person_holder, '; position=', allows_position_holder) AS detail,
                       version
                  FROM ppl_organization_role_catalog
                 WHERE tenant_id = :tenantId
                 ORDER BY sort_order, display_name
                """, tenantParameters(tenantId), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> positionTypes(String locale) {
        return query("""
                SELECT position_type AS code, display_name, description, label_i18n::text AS labels_json,
                       sort_order, lifecycle_state, TRUE AS predefined,
                       CONCAT('multipleIncumbents=', allows_multiple_incumbents) AS detail, 0::bigint AS version
                  FROM ppl_position_type_catalog
                 ORDER BY sort_order, display_name
                """, new MapSqlParameterSource(), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> positionCriticalities(String locale) {
        return query("""
                SELECT criticality AS code, display_name, description, label_i18n::text AS labels_json,
                       sort_order, lifecycle_state, TRUE AS predefined,
                       CONCAT('decisionWeight=', decision_weight) AS detail, 0::bigint AS version
                  FROM ppl_position_criticality_catalog
                 ORDER BY sort_order, display_name
                """, new MapSqlParameterSource(), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> approvalRoles(String locale) {
        return query("""
                SELECT role_code AS code, display_name, description, label_i18n::text AS labels_json,
                       sort_order, lifecycle_state, TRUE AS predefined,
                       'scenario approval authority' AS detail, 0::bigint AS version
                  FROM ppl_approval_role_catalog
                 ORDER BY sort_order, display_name
                """, new MapSqlParameterSource(), locale);
    }

    private List<WorkforceReferenceDtos.ReferenceValue> query(
            String sql,
            MapSqlParameterSource parameters,
            String locale) {
        String normalizedLocale = normalizeLocale(locale);
        return jdbc.query(sql, parameters, (resultSet, rowNumber) -> value(resultSet, normalizedLocale));
    }

    private WorkforceReferenceDtos.ReferenceValue value(ResultSet resultSet, String locale)
            throws SQLException {
        Map<String, String> labels = labels(resultSet.getString("labels_json"));
        String displayName = resultSet.getString("display_name");
        String localized = labels.getOrDefault(locale,
                labels.getOrDefault(locale.split("-")[0], displayName));
        return new WorkforceReferenceDtos.ReferenceValue(
                resultSet.getString("code"),
                displayName,
                resultSet.getString("description"),
                labels,
                localized,
                resultSet.getInt("sort_order"),
                resultSet.getString("lifecycle_state"),
                resultSet.getBoolean("predefined"),
                resultSet.getString("detail"),
                resultSet.getLong("version"));
    }

    private MapSqlParameterSource tenantParameters(Long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private Map<String, String> labels(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return Map.copyOf(objectMapper.readValue(json, LABELS_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid workforce reference localization payload.", exception);
        }
    }

    private String labelsJson(Map<String, String> labels) {
        Map<String, String> normalized = new LinkedHashMap<>();
        labels.forEach((key, value) -> {
            if (key == null || value == null || value.isBlank()) return;
            normalized.put(normalizeLocale(key), value.trim());
        });
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workforce reference localization payload.", exception);
        }
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        return locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
