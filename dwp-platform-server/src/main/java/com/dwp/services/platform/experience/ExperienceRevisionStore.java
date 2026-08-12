package com.dwp.services.platform.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ExperienceRevisionStore {

    public static final String BRANDING = "BRANDING";
    public static final String HOME = "HOME";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ExperienceRevisionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void ensureBaseline(
            Long tenantId,
            String experienceType,
            Long sourceVersion,
            Map<String, Object> snapshot,
            Long actorId,
            String correlationId) {
        jdbc.update(
                """
                INSERT INTO adm_experience_revisions (
                    tenant_id, experience_type, source_version, change_type,
                    snapshot_payload, correlation_id, created_by)
                VALUES (?, ?, ?, 'BASELINE', CAST(? AS jsonb), ?, ?)
                ON CONFLICT DO NOTHING
                """,
                tenantId,
                experienceType,
                sourceVersion,
                json(snapshot),
                correlationId,
                actorId);
    }

    public void append(
            Long tenantId,
            String experienceType,
            Long sourceVersion,
            String changeType,
            Map<String, Object> snapshot,
            Long actorId,
            String correlationId) {
        jdbc.update(
                """
                INSERT INTO adm_experience_revisions (
                    tenant_id, experience_type, source_version, change_type,
                    snapshot_payload, correlation_id, created_by)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                tenantId,
                experienceType,
                sourceVersion,
                changeType,
                json(snapshot),
                correlationId,
                actorId);
    }

    public List<ExperienceRevision> list(Long tenantId, String experienceType, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return jdbc.query(
                """
                SELECT revision_id, tenant_id, experience_type, source_version, change_type,
                       snapshot_payload, correlation_id, created_at, created_by
                  FROM adm_experience_revisions
                 WHERE tenant_id = ?
                   AND experience_type = ?
                 ORDER BY revision_id DESC
                 LIMIT ?
                """,
                this::map,
                tenantId,
                experienceType,
                limit);
    }

    public ExperienceRevision require(Long tenantId, String experienceType, Long revisionId) {
        List<ExperienceRevision> rows = jdbc.query(
                """
                SELECT revision_id, tenant_id, experience_type, source_version, change_type,
                       snapshot_payload, correlation_id, created_at, created_by
                  FROM adm_experience_revisions
                 WHERE tenant_id = ?
                   AND experience_type = ?
                   AND revision_id = ?
                """,
                this::map,
                tenantId,
                experienceType,
                revisionId);
        if (rows.isEmpty()) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Experience revision was not found.");
        }
        return rows.getFirst();
    }

    private ExperienceRevision map(ResultSet rs, int rowNumber) throws SQLException {
        try {
            return new ExperienceRevision(
                    rs.getLong("revision_id"),
                    rs.getLong("tenant_id"),
                    rs.getString("experience_type"),
                    rs.getLong("source_version"),
                    rs.getString("change_type"),
                    objectMapper.readTree(rs.getString("snapshot_payload")),
                    rs.getString("correlation_id"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("created_by", Long.class));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored experience revision is invalid.", exception);
        }
    }

    private String json(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Experience revision could not be serialized.",
                    exception);
        }
    }

    public record ExperienceRevision(
            Long revisionId,
            Long tenantId,
            String experienceType,
            Long sourceVersion,
            String changeType,
            JsonNode snapshot,
            String correlationId,
            OffsetDateTime createdAt,
            Long createdBy) {
    }
}
