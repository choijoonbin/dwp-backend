package com.dwp.services.platform.navigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class NavigationStudioRepository {

    private static final TypeReference<List<NavigationDtos.AdminNode>> TREE_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NavigationStudioRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<StoredRevision> latestPublished(Long tenantId) {
        return queryOne("""
                SELECT *
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ? AND lifecycle_state = 'PUBLISHED'
                 ORDER BY revision_number DESC
                 LIMIT 1
                """, tenantId);
    }

    public Optional<List<NavigationDtos.AdminNode>> latestPublishedTree(Long tenantId) {
        return latestPublished(tenantId).map(StoredRevision::tree);
    }

    public Optional<StoredRevision> draft(Long tenantId) {
        return queryOne("""
                SELECT *
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ? AND lifecycle_state = 'DRAFT'
                 LIMIT 1
                """, tenantId);
    }

    public StoredRevision requireDraft(Long tenantId, UUID revisionId) {
        return queryOne("""
                SELECT *
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ? AND navigation_revision_id = ?
                   AND lifecycle_state = 'DRAFT'
                """, tenantId, revisionId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public StoredRevision requireRevision(Long tenantId, UUID revisionId) {
        return queryOne("""
                SELECT *
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ? AND navigation_revision_id = ?
                """, tenantId, revisionId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<StoredRevision> history(Long tenantId, int limit) {
        return jdbc.query("""
                SELECT *
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ? AND lifecycle_state <> 'DRAFT'
                 ORDER BY revision_number DESC
                 LIMIT ?
                """, this::map, tenantId, limit);
    }

    public StoredRevision createBaseline(
            Long tenantId,
            Long actorId,
            String treeHash,
            List<NavigationDtos.AdminNode> tree,
            NavigationStudioDtos.ValidationReport validation) {
        lockTenant(tenantId);
        Optional<StoredRevision> existing = latestPublished(tenantId);
        if (existing.isPresent()) return existing.get();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO adm_navigation_revisions (
                    navigation_revision_id, tenant_id, revision_number, lifecycle_state,
                    baseline_tree_hash, tree_payload, validation_payload, change_summary,
                    published_at, published_by, created_by, updated_by)
                VALUES (?, ?, 1, 'PUBLISHED', ?, ?::jsonb, ?::jsonb, ?,
                        CURRENT_TIMESTAMP, ?, ?, ?)
                """, id, tenantId, treeHash, json(tree), json(validation),
                "Initial runtime navigation baseline", actorId, actorId, actorId);
        return requireRevision(tenantId, id);
    }

    public StoredRevision createDraft(
            Long tenantId,
            Long actorId,
            StoredRevision baseline,
            String treeHash,
            List<NavigationDtos.AdminNode> tree,
            NavigationStudioDtos.ValidationReport validation,
            String changeSummary) {
        lockTenant(tenantId);
        if (draft(tenantId).isPresent()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "A navigation draft already exists.");
        }
        long revisionNumber = nextRevisionNumber(tenantId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO adm_navigation_revisions (
                        navigation_revision_id, tenant_id, revision_number, lifecycle_state,
                        baseline_revision_id, baseline_tree_hash, tree_payload,
                        validation_payload, change_summary, created_by, updated_by)
                    VALUES (?, ?, ?, 'DRAFT', ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """, id, tenantId, revisionNumber, baseline.navigationRevisionId(),
                    treeHash, json(tree), json(validation), trimToNull(changeSummary), actorId, actorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "A navigation draft already exists.", exception);
        }
        return requireDraft(tenantId, id);
    }

    public StoredRevision updateDraft(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            long version,
            List<NavigationDtos.AdminNode> tree,
            NavigationStudioDtos.ValidationReport validation,
            String changeSummary) {
        int updated = jdbc.update("""
                UPDATE adm_navigation_revisions
                   SET tree_payload = ?::jsonb, validation_payload = ?::jsonb,
                       change_summary = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND navigation_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, json(tree), json(validation), trimToNull(changeSummary), actorId,
                tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        return requireDraft(tenantId, revisionId);
    }

    public StoredRevision publish(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            long version,
            String treeHash,
            List<NavigationDtos.AdminNode> tree,
            NavigationStudioDtos.ValidationReport validation) {
        lockTenant(tenantId);
        jdbc.update("""
                UPDATE adm_navigation_revisions
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND lifecycle_state = 'PUBLISHED'
                """, actorId, tenantId);
        int updated = jdbc.update("""
                UPDATE adm_navigation_revisions
                   SET lifecycle_state = 'PUBLISHED', baseline_tree_hash = ?,
                       tree_payload = ?::jsonb, validation_payload = ?::jsonb,
                       published_at = CURRENT_TIMESTAMP, published_by = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND navigation_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, treeHash, json(tree), json(validation), actorId, actorId,
                tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        return requireRevision(tenantId, revisionId);
    }

    public StoredRevision cancel(
            Long tenantId, Long actorId, UUID revisionId, long version) {
        int updated = jdbc.update("""
                UPDATE adm_navigation_revisions
                   SET lifecycle_state = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND navigation_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        return requireRevision(tenantId, revisionId);
    }

    public Set<String> activeAppRegistryKeys(Long tenantId) {
        return Set.copyOf(jdbc.query("""
                SELECT DISTINCT entry_key
                  FROM adm_registry_entries
                 WHERE tenant_id = ? AND registry_type = 'APP' AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> result.getString("entry_key"), tenantId));
    }

    private Optional<StoredRevision> queryOne(String sql, Object... arguments) {
        List<StoredRevision> rows = jdbc.query(sql, this::map, arguments);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private StoredRevision map(ResultSet result, int ignored) throws SQLException {
        return new StoredRevision(
                result.getObject("navigation_revision_id", UUID.class),
                result.getLong("tenant_id"), result.getLong("revision_number"),
                result.getString("lifecycle_state"),
                result.getObject("baseline_revision_id", UUID.class),
                result.getString("baseline_tree_hash"),
                tree(result.getString("tree_payload")),
                validation(result.getString("validation_payload")),
                result.getString("change_summary"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                nullableLong(result, "created_by"),
                result.getObject("updated_at", OffsetDateTime.class),
                result.getObject("published_at", OffsetDateTime.class),
                nullableLong(result, "published_by"));
    }

    private List<NavigationDtos.AdminNode> tree(String value) {
        try {
            return objectMapper.readValue(value, TREE_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Stored navigation revision is invalid.", exception);
        }
    }

    private NavigationStudioDtos.ValidationReport validation(String value) {
        try {
            return objectMapper.readValue(value, NavigationStudioDtos.ValidationReport.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Stored navigation validation is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Navigation revision could not be serialized.", exception);
        }
    }

    private long nextRevisionNumber(Long tenantId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM adm_navigation_revisions
                 WHERE tenant_id = ?
                """, Long.class, tenantId);
        return value == null ? 1 : value;
    }

    private void lockTenant(Long tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> { }, tenantId);
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Navigation draft changed after it was loaded. Refresh and try again.");
    }

    public record StoredRevision(
            UUID navigationRevisionId,
            Long tenantId,
            long revisionNumber,
            String lifecycleState,
            UUID baselineRevisionId,
            String baselineTreeHash,
            List<NavigationDtos.AdminNode> tree,
            NavigationStudioDtos.ValidationReport validation,
            String changeSummary,
            long version,
            OffsetDateTime createdAt,
            Long createdBy,
            OffsetDateTime updatedAt,
            OffsetDateTime publishedAt,
            Long publishedBy) {
    }
}
