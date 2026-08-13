package com.dwp.services.platform.localization;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LocalizationRepository {

    private static final TypeReference<Map<String, String>> ENTRY_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalizationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<StoredBundle> bundles(Long tenantId) {
        return jdbc.query("""
                SELECT bundle.*, published.revision_number AS published_revision_number,
                       open_revision.localization_revision_id AS open_revision_id,
                       open_revision.revision_number AS open_revision_number,
                       open_revision.lifecycle_state AS open_revision_state
                  FROM adm_localization_bundles bundle
                  LEFT JOIN adm_localization_revisions published
                    ON published.localization_revision_id = bundle.current_published_revision_id
                  LEFT JOIN LATERAL (
                       SELECT revision.localization_revision_id, revision.revision_number,
                              revision.lifecycle_state
                         FROM adm_localization_revisions revision
                        WHERE revision.localization_bundle_id = bundle.localization_bundle_id
                          AND revision.lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
                        ORDER BY revision.revision_number DESC
                        LIMIT 1
                  ) open_revision ON TRUE
                 WHERE bundle.tenant_id = ?
                 ORDER BY bundle.bundle_key, bundle.target_locale
                """, this::mapBundle, tenantId);
    }

    public StoredBundle requireBundle(Long tenantId, UUID bundleId) {
        List<StoredBundle> rows = jdbc.query("""
                SELECT bundle.*, published.revision_number AS published_revision_number,
                       open_revision.localization_revision_id AS open_revision_id,
                       open_revision.revision_number AS open_revision_number,
                       open_revision.lifecycle_state AS open_revision_state
                  FROM adm_localization_bundles bundle
                  LEFT JOIN adm_localization_revisions published
                    ON published.localization_revision_id = bundle.current_published_revision_id
                  LEFT JOIN LATERAL (
                       SELECT revision.localization_revision_id, revision.revision_number,
                              revision.lifecycle_state
                         FROM adm_localization_revisions revision
                        WHERE revision.localization_bundle_id = bundle.localization_bundle_id
                          AND revision.lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
                        ORDER BY revision.revision_number DESC
                        LIMIT 1
                  ) open_revision ON TRUE
                 WHERE bundle.tenant_id = ? AND bundle.localization_bundle_id = ?
                """, this::mapBundle, tenantId, bundleId);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    public List<StoredRevision> revisions(Long tenantId, UUID bundleId) {
        requireBundle(tenantId, bundleId);
        return jdbc.query(REVISION_QUERY + """
                 WHERE revision.tenant_id = ? AND revision.localization_bundle_id = ?
                 ORDER BY revision.revision_number DESC
                """, this::mapRevision, tenantId, bundleId);
    }

    public StoredRevision requireRevision(Long tenantId, UUID revisionId) {
        List<StoredRevision> rows = jdbc.query(REVISION_QUERY + """
                 WHERE revision.tenant_id = ? AND revision.localization_revision_id = ?
                """, this::mapRevision, tenantId, revisionId);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    public StoredBundle createBundle(
            Long tenantId,
            Long actorId,
            String bundleKey,
            String sourceLocale,
            String targetLocale,
            Map<String, String> sourceEntries,
            Map<String, String> entries,
            String changeSummary,
            String hash) {
        lockTenant(tenantId);
        UUID bundleId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO adm_localization_bundles (
                        localization_bundle_id, tenant_id, bundle_key, source_locale,
                        target_locale, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, bundleId, tenantId, bundleKey, sourceLocale, targetLocale, actorId, actorId);
            jdbc.update("""
                    INSERT INTO adm_localization_revisions (
                        localization_revision_id, localization_bundle_id, tenant_id,
                        revision_number, source_entries, entries, lifecycle_state,
                        change_summary, content_sha256, created_by, updated_by)
                    VALUES (?, ?, ?, 1, ?::jsonb, ?::jsonb, 'DRAFT', ?, ?, ?, ?)
                    """, revisionId, bundleId, tenantId, json(sourceEntries), json(entries),
                    changeSummary, hash, actorId, actorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A localization bundle already exists for this key and target locale.",
                    exception);
        }
        return requireBundle(tenantId, bundleId);
    }

    public StoredRevision updateDraft(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            long version,
            Map<String, String> sourceEntries,
            Map<String, String> entries,
            String changeSummary,
            String hash) {
        int updated = jdbc.update("""
                UPDATE adm_localization_revisions
                   SET source_entries = ?::jsonb, entries = ?::jsonb,
                       change_summary = ?, content_sha256 = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, json(sourceEntries), json(entries), changeSummary, hash,
                actorId, tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        return requireRevision(tenantId, revisionId);
    }

    public StoredRevision createDraft(
            Long tenantId,
            Long actorId,
            StoredRevision source,
            String changeSummary,
            String hash) {
        return insertDraft(tenantId, actorId, source, changeSummary, hash, false);
    }

    public StoredRevision submit(
            Long tenantId, Long actorId, UUID revisionId, long version, String reason) {
        StoredRevision before = requireRevision(tenantId, revisionId);
        int updated = jdbc.update("""
                UPDATE adm_localization_revisions
                   SET lifecycle_state = 'IN_REVIEW', submitted_by = ?,
                       submitted_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, actorId, tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        appendDecision(tenantId, revisionId, before.lifecycleState(), "SUBMITTED", reason, actorId);
        return requireRevision(tenantId, revisionId);
    }

    public StoredRevision decide(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            long version,
            String decision,
            String reason) {
        StoredRevision before = requireRevision(tenantId, revisionId);
        int updated = jdbc.update("""
                UPDATE adm_localization_revisions
                   SET lifecycle_state = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_revision_id = ?
                   AND lifecycle_state = 'IN_REVIEW' AND version = ?
                """, decision, actorId, actorId, tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        appendDecision(tenantId, revisionId, before.lifecycleState(), decision, reason, actorId);
        return requireRevision(tenantId, revisionId);
    }

    public StoredRevision publish(
            Long tenantId, Long actorId, UUID revisionId, long version, String reason) {
        StoredRevision before = requireRevision(tenantId, revisionId);
        lockBundle(before.bundleId());
        int updated = jdbc.update("""
                UPDATE adm_localization_revisions
                   SET lifecycle_state = 'PUBLISHED', published_by = ?,
                       published_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_revision_id = ?
                   AND lifecycle_state = 'APPROVED' AND version = ?
                """, actorId, actorId, tenantId, revisionId, version);
        if (updated != 1) throw conflict();
        jdbc.update("""
                UPDATE adm_localization_revisions
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_bundle_id = ?
                   AND lifecycle_state = 'PUBLISHED'
                   AND localization_revision_id <> ?
                """, actorId, tenantId, before.bundleId(), revisionId);
        jdbc.update("""
                UPDATE adm_localization_bundles
                   SET current_published_revision_id = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND localization_bundle_id = ?
                """, revisionId, actorId, tenantId, before.bundleId());
        appendDecision(tenantId, revisionId, before.lifecycleState(), "PUBLISHED", reason, actorId);
        return requireRevision(tenantId, revisionId);
    }

    public StoredRevision restore(
            Long tenantId,
            Long actorId,
            StoredRevision source,
            String changeSummary,
            String hash) {
        return insertDraft(tenantId, actorId, source, changeSummary, hash, true);
    }

    public List<LocalizationDtos.Decision> decisions(Long tenantId, UUID revisionId) {
        return jdbc.query("""
                SELECT localization_revision_decision_id, previous_state, decision,
                       reason, actor_id, decided_at
                  FROM adm_localization_revision_decisions
                 WHERE tenant_id = ? AND localization_revision_id = ?
                 ORDER BY decided_at, localization_revision_decision_id
                """, (row, ignored) -> new LocalizationDtos.Decision(
                row.getObject("localization_revision_decision_id", UUID.class),
                row.getString("previous_state"), row.getString("decision"),
                row.getString("reason"), row.getLong("actor_id"),
                row.getObject("decided_at", OffsetDateTime.class)), tenantId, revisionId);
    }

    private StoredRevision insertDraft(
            Long tenantId,
            Long actorId,
            StoredRevision source,
            String changeSummary,
            String hash,
            boolean restored) {
        lockBundle(source.bundleId());
        StoredBundle bundle = requireBundle(tenantId, source.bundleId());
        if (bundle.openRevisionId() != null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Resolve the existing open localization revision before creating another draft.");
        }
        Long revisionNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM adm_localization_revisions
                 WHERE tenant_id = ? AND localization_bundle_id = ?
                """, Long.class, tenantId, source.bundleId());
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO adm_localization_revisions (
                    localization_revision_id, localization_bundle_id, tenant_id,
                    revision_number, based_on_revision_id, source_entries, entries,
                    lifecycle_state, change_summary, content_sha256, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'DRAFT', ?, ?, ?, ?)
                """, revisionId, source.bundleId(), tenantId, revisionNumber,
                source.revisionId(), json(source.sourceEntries()), json(source.entries()),
                changeSummary, hash, actorId, actorId);
        if (restored) {
            appendDecision(
                    tenantId, revisionId, source.lifecycleState(), "RESTORED", changeSummary, actorId);
        }
        return requireRevision(tenantId, revisionId);
    }

    private void appendDecision(
            Long tenantId,
            UUID revisionId,
            String previousState,
            String decision,
            String reason,
            Long actorId) {
        jdbc.update("""
                INSERT INTO adm_localization_revision_decisions (
                    localization_revision_id, tenant_id, previous_state,
                    decision, reason, actor_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, revisionId, tenantId, previousState, decision, reason, actorId);
    }

    private StoredBundle mapBundle(ResultSet row, int ignored) throws SQLException {
        return new StoredBundle(
                row.getObject("localization_bundle_id", UUID.class),
                row.getLong("tenant_id"), row.getString("bundle_key"),
                row.getString("source_locale"), row.getString("target_locale"),
                row.getString("lifecycle_state"),
                row.getObject("current_published_revision_id", UUID.class),
                row.getObject("published_revision_number", Long.class),
                row.getObject("open_revision_id", UUID.class),
                row.getObject("open_revision_number", Long.class),
                row.getString("open_revision_state"), row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class));
    }

    private StoredRevision mapRevision(ResultSet row, int ignored) throws SQLException {
        return new StoredRevision(
                row.getObject("localization_revision_id", UUID.class),
                row.getObject("localization_bundle_id", UUID.class),
                row.getLong("tenant_id"), row.getString("bundle_key"),
                row.getString("source_locale"), row.getString("target_locale"),
                row.getLong("revision_number"),
                row.getObject("based_on_revision_id", UUID.class),
                entries(row.getString("source_entries")), entries(row.getString("entries")),
                row.getString("lifecycle_state"), row.getString("change_summary"),
                row.getString("content_sha256"), row.getObject("submitted_by", Long.class),
                row.getObject("submitted_at", OffsetDateTime.class),
                row.getObject("decided_by", Long.class),
                row.getObject("decided_at", OffsetDateTime.class),
                row.getObject("published_by", Long.class),
                row.getObject("published_at", OffsetDateTime.class), row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("created_by", Long.class),
                row.getObject("updated_at", OffsetDateTime.class));
    }

    private Map<String, String> entries(String value) {
        try {
            return objectMapper.readValue(value, ENTRY_MAP);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored localization content is invalid.", exception);
        }
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Localization content could not be serialized.", exception);
        }
    }

    private void lockTenant(Long tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", row -> { }, tenantId);
    }

    private void lockBundle(UUID bundleId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", row -> { }, bundleId.toString());
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The localization revision changed after it was loaded. Refresh and try again.");
    }

    public record StoredBundle(
            UUID bundleId,
            Long tenantId,
            String bundleKey,
            String sourceLocale,
            String targetLocale,
            String lifecycleState,
            UUID currentPublishedRevisionId,
            Long currentPublishedRevisionNumber,
            UUID openRevisionId,
            Long openRevisionNumber,
            String openRevisionState,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record StoredRevision(
            UUID revisionId,
            UUID bundleId,
            Long tenantId,
            String bundleKey,
            String sourceLocale,
            String targetLocale,
            long revisionNumber,
            UUID basedOnRevisionId,
            Map<String, String> sourceEntries,
            Map<String, String> entries,
            String lifecycleState,
            String changeSummary,
            String contentSha256,
            Long submittedBy,
            OffsetDateTime submittedAt,
            Long decidedBy,
            OffsetDateTime decidedAt,
            Long publishedBy,
            OffsetDateTime publishedAt,
            long version,
            OffsetDateTime createdAt,
            Long createdBy,
            OffsetDateTime updatedAt) {
    }

    private static final String REVISION_QUERY = """
            SELECT revision.*, bundle.bundle_key, bundle.source_locale, bundle.target_locale
              FROM adm_localization_revisions revision
              JOIN adm_localization_bundles bundle
                ON bundle.localization_bundle_id = revision.localization_bundle_id
            """;
}
