package com.dwp.services.platform.auditcontrol;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.auditcontrol.HcmAuditEvidenceDtos.Evidence;

@Repository
public class HcmAuditEvidenceRepository {

    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";
    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";
    private static final String CODE_PATTERN = "^[A-Z][A-Z0-9._-]{0,79}$";
    private static final String REFERENCE_PATTERN =
            "^[A-Za-z0-9][A-Za-z0-9:/._-]*$";
    private static final String SERVICE_PATTERN = "^[a-z][a-z0-9._-]{2,127}$";
    private static final String REQUIRED_METADATA = """
            ARRAY['aggregateRef','aggregateRevision','effectiveCapability','purposeCode',
                  'scopeRevision','fieldPolicyRevision','evidenceDigest','classification',
                  'causationId']::text[]
            """;
    private static final String ALLOWED_METADATA = """
            ARRAY['aggregateRef','aggregateRevision','effectiveCapability','purposeCode',
                  'scopeRevision','fieldPolicyRevision','evidenceDigest','classification',
                  'causationId','delegationRef','supportSessionRef','stepUpReceiptRef',
                  'beforeDigest','afterDigest']::text[]
            """;
    private static final String SELECT = """
            SELECT event_id, occurred_at, category, action, outcome, severity, actor_type,
                   source_service, source_module, target_type AS aggregate_type,
                   (metadata ->> 'aggregateRevision')::bigint AS aggregate_revision,
                   metadata ->> 'effectiveCapability' AS effective_capability,
                   metadata ->> 'purposeCode' AS purpose_code,
                   policy_id AS authorization_decision_ref,
                   (metadata ->> 'scopeRevision')::bigint AS scope_revision,
                   (metadata ->> 'fieldPolicyRevision')::bigint AS field_policy_revision,
                   approval_id AS approval_receipt_id,
                   reason AS reason_code,
                   metadata ->> 'beforeDigest' AS before_digest,
                   metadata ->> 'afterDigest' AS after_digest,
                   metadata ->> 'evidenceDigest' AS evidence_digest,
                   metadata ->> 'classification' AS classification,
                   retention_class,
                   metadata ->> 'causationId' AS causation_id,
                   correlation_id,
                   record_hash
              FROM sys_audit_events
            """;
    private static final String CANONICAL_FINGERPRINT = """
               AND source_module ~ '^hris\\.[a-z0-9._-]+$'
               AND source_service ~ '%s'
               AND action ~ '%s'
               AND target_type ~ '%s'
               AND target_id ~ '%s'
               AND char_length(target_id) <= 512
               AND actor_type IN ('USER','SERVICE','SYSTEM','AGENT')
               AND actor_id ~ '%s'
               AND char_length(actor_id) <= 512
               AND policy_id ~ '%s'
               AND char_length(policy_id) <= 512
               AND (reason IS NULL OR reason ~ '%s')
               AND ((outcome = 'DENIED' AND category = 'POLICY_DENIED')
                    OR (outcome IN ('SUCCESS','FAILED') AND category = 'ADMIN_CHANGE'))
               AND ((outcome = 'DENIED' AND policy_decision = 'DENY')
                    OR (outcome IN ('SUCCESS','FAILED') AND policy_decision = 'ALLOW'))
               AND ((outcome = 'SUCCESS' AND severity = 'INFO')
                    OR (outcome = 'FAILED' AND severity = 'HIGH')
                    OR (outcome = 'DENIED' AND severity IN ('HIGH','CRITICAL')))
               AND before_state = '{}'::jsonb
               AND after_state = '{}'::jsonb
               AND cardinality(changed_fields) = 0
               AND jsonb_exists_all(metadata, %s)
               AND metadata - %s = '{}'::jsonb
               AND metadata ->> 'aggregateRevision' ~ '^[1-9][0-9]*$'
               AND metadata ->> 'aggregateRef' ~ '%s'
               AND char_length(metadata ->> 'aggregateRef') <= 512
               AND metadata ->> 'effectiveCapability' ~ '%s'
               AND metadata ->> 'purposeCode' ~ '%s'
               AND metadata ->> 'scopeRevision' ~ '^[1-9][0-9]*$'
               AND metadata ->> 'fieldPolicyRevision' ~ '^[1-9][0-9]*$'
               AND metadata ->> 'evidenceDigest' ~ '%s'
               AND (metadata ->> 'beforeDigest' IS NULL
                    OR metadata ->> 'beforeDigest' ~ '%s')
               AND (metadata ->> 'afterDigest' IS NULL
                    OR metadata ->> 'afterDigest' ~ '%s')
               AND metadata ->> 'classification' IN ('INTERNAL','CONFIDENTIAL','RESTRICTED')
               AND metadata ->> 'causationId' ~ '%s'
               AND (metadata ->> 'delegationRef' IS NULL
                    OR (metadata ->> 'delegationRef' ~ '%s'
                    AND char_length(metadata ->> 'delegationRef') <= 512))
               AND (metadata ->> 'supportSessionRef' IS NULL
                    OR (metadata ->> 'supportSessionRef' ~ '%s'
                    AND char_length(metadata ->> 'supportSessionRef') <= 512))
               AND (metadata ->> 'stepUpReceiptRef' IS NULL
                    OR (metadata ->> 'stepUpReceiptRef' ~ '%s'
                    AND char_length(metadata ->> 'stepUpReceiptRef') <= 512))
               AND correlation_id ~ '%s'
               AND policy_id IS NOT NULL AND btrim(policy_id) <> ''
               AND source_service IS NOT NULL AND btrim(source_service) <> ''
               AND target_type IS NOT NULL AND btrim(target_type) <> ''
               AND target_id IS NOT NULL AND btrim(target_id) <> ''
               AND actor_type IS NOT NULL AND btrim(actor_type) <> ''
               AND actor_id IS NOT NULL AND btrim(actor_id) <> ''
               AND (approval_id IS NULL OR approval_id ~ '%s')
               AND (outcome NOT IN ('DENIED','FAILED')
                    OR (reason IS NOT NULL AND btrim(reason) <> ''))
            """.formatted(
            SERVICE_PATTERN, CODE_PATTERN, CODE_PATTERN,
            REFERENCE_PATTERN, REFERENCE_PATTERN, REFERENCE_PATTERN, CODE_PATTERN,
            REQUIRED_METADATA, ALLOWED_METADATA,
            REFERENCE_PATTERN, CODE_PATTERN, CODE_PATTERN,
            SHA256_PATTERN, SHA256_PATTERN, SHA256_PATTERN,
            UUID_PATTERN,
            REFERENCE_PATTERN, REFERENCE_PATTERN, REFERENCE_PATTERN,
            UUID_PATTERN, UUID_PATTERN);

    private static final RowMapper<Evidence> EVIDENCE_MAPPER =
            HcmAuditEvidenceRepository::mapEvidence;

    private final NamedParameterJdbcTemplate jdbc;

    public HcmAuditEvidenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Evidence> list(
            HcmAuditEvidenceCriteria criteria,
            HcmAuditEvidenceCursorCodec.CursorPosition cursor,
            int limit) {
        MapSqlParameterSource parameters = baseParameters(
                criteria.tenantId(), criteria.snapshotAt())
                .addValue("from", Timestamp.from(criteria.from()))
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE tenant_id = :tenantId")
                .append(" AND occurred_at >= :from")
                .append(" AND occurred_at <= :snapshotAt")
                .append(" AND ingested_at <= :snapshotAt")
                .append(CANONICAL_FINGERPRINT);
        filters(sql, parameters, criteria);
        if (cursor != null) {
            sql.append(" AND (occurred_at < :cursorOccurredAt OR ")
                    .append("(occurred_at = :cursorOccurredAt AND event_id < :cursorEventId))");
            parameters
                    .addValue("cursorOccurredAt", Timestamp.from(cursor.occurredAt()))
                    .addValue("cursorEventId", cursor.eventId());
        }
        sql.append(" ORDER BY occurred_at DESC, event_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), parameters, EVIDENCE_MAPPER);
    }

    Optional<Evidence> findById(Long tenantId, UUID eventId, Instant snapshotAt) {
        String sql = SELECT
                + " WHERE tenant_id = :tenantId"
                + " AND event_id = :eventId"
                + " AND occurred_at <= :snapshotAt"
                + " AND ingested_at <= :snapshotAt"
                + CANONICAL_FINGERPRINT
                + " ORDER BY occurred_at DESC, event_id DESC LIMIT 1";
        List<Evidence> rows = jdbc.query(
                sql,
                baseParameters(tenantId, snapshotAt).addValue("eventId", eventId),
                EVIDENCE_MAPPER);
        return rows.stream().findFirst();
    }

    private static void filters(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            HcmAuditEvidenceCriteria criteria) {
        if (!"ALL".equals(criteria.category())) {
            sql.append(" AND category = :category");
            parameters.addValue("category", criteria.category());
        }
        if (!"ALL".equals(criteria.severity())) {
            sql.append(" AND severity = :severity");
            parameters.addValue("severity", criteria.severity());
        }
        if (!"ALL".equals(criteria.outcome())) {
            sql.append(" AND outcome = :outcome");
            parameters.addValue("outcome", criteria.outcome());
        }
        if (criteria.sourceService() != null) {
            sql.append(" AND source_service = :sourceService");
            parameters.addValue("sourceService", criteria.sourceService());
        }
        if (criteria.query() != null) {
            sql.append(" AND (action ILIKE :query ESCAPE '\\\\'")
                    .append(" OR source_service ILIKE :query ESCAPE '\\\\'")
                    .append(" OR source_module ILIKE :query ESCAPE '\\\\'")
                    .append(" OR metadata ->> 'effectiveCapability' ILIKE :query ESCAPE '\\\\'")
                    .append(" OR metadata ->> 'purposeCode' ILIKE :query ESCAPE '\\\\'")
                    .append(" OR reason ILIKE :query ESCAPE '\\\\')");
            parameters.addValue("query", "%" + escapeLike(criteria.query()) + "%");
        }
    }

    private static MapSqlParameterSource baseParameters(
            Long tenantId, Instant snapshotAt) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotAt", Timestamp.from(snapshotAt));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static Evidence mapEvidence(ResultSet result, int rowNumber) throws SQLException {
        return new Evidence(
                result.getObject("event_id", UUID.class),
                instant(result, "occurred_at"),
                result.getString("category"),
                result.getString("action"),
                result.getString("outcome"),
                result.getString("severity"),
                result.getString("actor_type"),
                result.getString("source_service"),
                result.getString("source_module"),
                result.getString("aggregate_type"),
                result.getLong("aggregate_revision"),
                result.getString("effective_capability"),
                result.getString("purpose_code"),
                result.getString("authorization_decision_ref"),
                result.getLong("scope_revision"),
                result.getLong("field_policy_revision"),
                uuid(result.getString("approval_receipt_id")),
                result.getString("reason_code"),
                result.getString("before_digest"),
                result.getString("after_digest"),
                result.getString("evidence_digest"),
                result.getString("classification"),
                result.getString("retention_class"),
                UUID.fromString(result.getString("causation_id")),
                UUID.fromString(result.getString("correlation_id")),
                result.getString("record_hash"));
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
