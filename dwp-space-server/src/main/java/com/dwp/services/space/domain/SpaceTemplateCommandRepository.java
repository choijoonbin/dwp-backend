package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class SpaceTemplateCommandRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SpaceTemplateCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID create(
            SpaceRequestContext.Subject subject,
            SpaceDtos.SaveTemplateRequest input) {
        UUID templateId = UUID.randomUUID();
        MapSqlParameterSource params = templateParams(subject, templateId, input);
        try {
            jdbc.update("""
                    INSERT INTO spc_templates (
                        template_id, tenant_id, template_key, name_ko, name_en,
                        description_ko, description_en, purpose_type, creation_mode,
                        default_visibility, default_data_classification,
                        allowed_content_types, default_apps, icon_key, accent_token,
                        lifecycle_state, created_by, updated_by)
                    VALUES (
                        :templateId, :tenantId, :templateKey, :nameKo, :nameEn,
                        :descriptionKo, :descriptionEn, :purposeType, :creationMode,
                        :visibility, :classification, CAST(:contentTypes AS jsonb),
                        CAST(:defaultApps AS jsonb), :iconKey, :accentToken,
                        :lifecycleState, :userId, :userId)
                    """, params);
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        return templateId;
    }

    public void update(
            SpaceRequestContext.Subject subject,
            UUID templateId,
            SpaceDtos.SaveTemplateRequest input) {
        if (input.expectedVersion() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Expected version is required when updating a Space template.");
        }
        MapSqlParameterSource params = templateParams(subject, templateId, input)
                .addValue("expectedVersion", input.expectedVersion());
        try {
            int updated = jdbc.update("""
                    UPDATE spc_templates
                       SET name_ko = :nameKo,
                           name_en = :nameEn,
                           description_ko = :descriptionKo,
                           description_en = :descriptionEn,
                           purpose_type = :purposeType,
                           creation_mode = :creationMode,
                           default_visibility = :visibility,
                           default_data_classification = :classification,
                           allowed_content_types = CAST(:contentTypes AS jsonb),
                           default_apps = CAST(:defaultApps AS jsonb),
                           icon_key = :iconKey,
                           accent_token = :accentToken,
                           lifecycle_state = :lifecycleState,
                           current_version = current_version + 1,
                           version = version + 1,
                           updated_by = :userId,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId
                       AND template_id = :templateId
                       AND template_key = :templateKey
                       AND version = :expectedVersion
                    """, params);
            if (updated == 0) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Space template changed in another session.");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
    }

    private MapSqlParameterSource templateParams(
            SpaceRequestContext.Subject subject,
            UUID templateId,
            SpaceDtos.SaveTemplateRequest input) {
        return new MapSqlParameterSource()
                .addValue("templateId", templateId)
                .addValue("tenantId", subject.tenantId())
                .addValue("userId", subject.userId())
                .addValue("templateKey", input.templateKey())
                .addValue("nameKo", input.nameKo().trim())
                .addValue("nameEn", input.nameEn().trim())
                .addValue("descriptionKo", input.descriptionKo().trim())
                .addValue("descriptionEn", input.descriptionEn().trim())
                .addValue("purposeType", input.purposeType())
                .addValue("creationMode", input.creationMode())
                .addValue("visibility", input.defaultVisibility())
                .addValue("classification", input.defaultDataClassification())
                .addValue("contentTypes", json(input.allowedContentTypes()))
                .addValue("defaultApps", json(input.defaultApps()))
                .addValue("iconKey", input.iconKey())
                .addValue("accentToken", input.accentToken())
                .addValue("lifecycleState", input.lifecycleState());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Space template payload could not be serialized.",
                    exception);
        }
    }

    private BaseException duplicate(DuplicateKeyException exception) {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "A Space template with this key already exists.",
                exception);
    }
}
