package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.FreshnessState;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.RecoveryOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** JDBC adapter for the single tenant-isolated V14 projection table. */
public final class JdbcEmploymentAbsenceProjectionStore
        implements EmploymentAbsenceProjectionStore {

    private static final String SELECT_COLUMNS = """
            tenant_id, relationship_public_id, freshness_state,
            last_observed_event_sequence, required_relationship_revision,
            shared_offset_sequence, gap_expected_sequence, worker_public_id,
            owner_relationship_revision, worker_status, relationship_type,
            relationship_status, legal_entity_public_id,
            relationship_effective_from, relationship_effective_to,
            owner_effective_on, assignment_snapshots, owner_contract_id,
            owner_schema_version, owner_revision, owner_as_of, owner_valid_until,
            owner_content_sha256, owner_service, purpose_code, owner_scope_digest,
            owner_business_time_zone, owner_minimum_relationship_revision,
            tenant_grant_revision, authorization_revision, field_policy_revision,
            owner_issued_at, owner_evidence_digest, owner_key_id,
            last_invalidation_event_id, last_invalidation_change_type,
            last_invalidation_effective_date, last_invalidation_payload_sha256,
            last_correlation_id, recovery_outcome, last_recovery_error_code,
            last_recovery_at, projection_version, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEmploymentAbsenceProjectionStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void lockTuple(long tenantId, UUID relationshipPublicId) {
        EmploymentAbsenceContract.positive(tenantId, "tenantId");
        if (relationshipPublicId == null) {
            throw new IllegalArgumentException("relationshipPublicId is required");
        }
        Long locked = jdbc.queryForObject("""
                SELECT pg_catalog.hashtextextended(:lockKey, 0)
                  FROM pg_catalog.pg_advisory_xact_lock(
                      pg_catalog.hashtextextended(:lockKey, 0))
                """, new MapSqlParameterSource("lockKey",
                        tenantId + ":" + relationshipPublicId), Long.class);
        if (locked == null) {
            throw new IllegalStateException("relationship read fence was not acquired");
        }
    }

    @Override
    public Optional<EmploymentAbsenceProjection> find(
            long tenantId,
            UUID relationshipPublicId) {
        setTenant(tenantId);
        return jdbc.query("""
                SELECT %s
                  FROM abs_employment_relationship_projections
                 WHERE tenant_id = :tenantId
                   AND relationship_public_id = :relationshipPublicId
                """.formatted(SELECT_COLUMNS), key(tenantId, relationshipPublicId),
                (result, ignored) -> row(result)).stream().findFirst();
    }

    @Override
    public EvaluationRead readForEvaluation(
            long tenantId,
            UUID relationshipPublicId) {
        setTenant(tenantId);
        List<EvaluationRead> rows = jdbc.query("""
                SELECT %s
                  FROM abs_employment_relationship_projections
                 WHERE tenant_id = :tenantId
                   AND relationship_public_id = :relationshipPublicId
                """.formatted(SELECT_COLUMNS), key(tenantId, relationshipPublicId),
                (result, ignored) -> {
                    long version = result.getLong("projection_version");
                    try {
                        return new EvaluationRead.Available(row(result));
                    } catch (RuntimeException corrupt) {
                        return new EvaluationRead.Corrupt(version);
                    }
                });
        if (rows.size() > 1) {
            throw new IllegalStateException("projection tuple is not unique");
        }
        return rows.isEmpty() ? new EvaluationRead.Missing() : rows.get(0);
    }

    @Override
    public CorruptionQuarantine quarantineCorruptExpectedVersion(
            long tenantId,
            UUID relationshipPublicId,
            long expectedProjectionVersion,
            Instant at) {
        setTenant(tenantId);
        MapSqlParameterSource parameters = key(tenantId, relationshipPublicId)
                .addValue("expectedProjectionVersion", expectedProjectionVersion)
                .addValue("lastRecoveryAt", instant(at));
        List<Long> versions = jdbc.query("""
                UPDATE abs_employment_relationship_projections
                   SET worker_public_id = NULL,
                       freshness_state = 'QUARANTINED',
                       gap_expected_sequence = NULL,
                       owner_relationship_revision = NULL,
                       worker_status = NULL,
                       relationship_type = NULL,
                       relationship_status = NULL,
                       legal_entity_public_id = NULL,
                       relationship_effective_from = NULL,
                       relationship_effective_to = NULL,
                       owner_effective_on = NULL,
                       assignment_snapshots = '[]'::jsonb,
                       owner_contract_id = NULL,
                       owner_schema_version = NULL,
                       owner_revision = NULL,
                       owner_as_of = NULL,
                       owner_valid_until = NULL,
                       owner_content_sha256 = NULL,
                       owner_service = NULL,
                       purpose_code = NULL,
                       owner_scope_digest = NULL,
                       owner_business_time_zone = NULL,
                       owner_minimum_relationship_revision = NULL,
                       tenant_grant_revision = NULL,
                       authorization_revision = NULL,
                       field_policy_revision = NULL,
                       owner_issued_at = NULL,
                       owner_evidence_digest = NULL,
                       owner_key_id = NULL,
                       recovery_outcome = 'PERMANENT_FAILURE',
                       last_recovery_error_code =
                           'STORED_PROJECTION_INTEGRITY_FAILURE',
                       last_recovery_at = :lastRecoveryAt
                 WHERE tenant_id = :tenantId
                   AND relationship_public_id = :relationshipPublicId
                   AND projection_version = :expectedProjectionVersion
                   AND (freshness_state <> 'QUARANTINED'
                        OR recovery_outcome <> 'PERMANENT_FAILURE'
                        OR last_recovery_error_code IS DISTINCT FROM
                           'STORED_PROJECTION_INTEGRITY_FAILURE')
                RETURNING projection_version
                """, parameters,
                (result, ignored) -> result.getLong("projection_version"));
        if (versions.size() == 1) {
            return new CorruptionQuarantine(versions.get(0), true);
        }
        List<Long> unchanged = jdbc.query("""
                SELECT projection_version
                  FROM abs_employment_relationship_projections
                 WHERE tenant_id = :tenantId
                   AND relationship_public_id = :relationshipPublicId
                   AND projection_version = :expectedProjectionVersion
                   AND freshness_state = 'QUARANTINED'
                   AND recovery_outcome = 'PERMANENT_FAILURE'
                   AND last_recovery_error_code =
                       'STORED_PROJECTION_INTEGRITY_FAILURE'
                """, parameters,
                (result, ignored) -> result.getLong("projection_version"));
        if (unchanged.size() == 1) {
            return new CorruptionQuarantine(unchanged.get(0), false);
        }
        throw new ProjectionCasLost("corruption quarantine CAS lost");
    }

    @Override
    public EmploymentAbsenceProjection insertExpectedZero(
            EmploymentAbsenceProjection candidate) {
        if (candidate.projectionVersion() != 0) {
            throw new IllegalArgumentException("insert requires expected version zero");
        }
        setTenant(candidate.tenantId());
        int changed = jdbc.update("""
                INSERT INTO abs_employment_relationship_projections (
                    tenant_id, relationship_public_id, worker_public_id,
                    freshness_state, last_observed_event_sequence,
                    required_relationship_revision, shared_offset_sequence,
                    gap_expected_sequence, owner_relationship_revision,
                    worker_status, relationship_type, relationship_status,
                    legal_entity_public_id, relationship_effective_from,
                    relationship_effective_to, owner_effective_on,
                    assignment_snapshots, owner_contract_id, owner_schema_version,
                    owner_revision, owner_as_of, owner_valid_until,
                    owner_content_sha256, owner_service, purpose_code,
                    owner_scope_digest, owner_business_time_zone,
                    owner_minimum_relationship_revision, tenant_grant_revision,
                    authorization_revision, field_policy_revision, owner_issued_at,
                    owner_evidence_digest, owner_key_id,
                    last_invalidation_event_id, last_invalidation_change_type,
                    last_invalidation_effective_date,
                    last_invalidation_payload_sha256, last_correlation_id,
                    recovery_outcome, last_recovery_error_code, last_recovery_at)
                VALUES (
                    :tenantId, :relationshipPublicId, :workerPublicId,
                    :freshnessState, :lastObservedEventSequence,
                    :requiredRelationshipRevision, :sharedOffsetSequence,
                    :gapExpectedSequence, :ownerRelationshipRevision,
                    :workerStatus, :relationshipType, :relationshipStatus,
                    :legalEntityPublicId, :relationshipEffectiveFrom,
                    :relationshipEffectiveTo, :ownerEffectiveOn,
                    CAST(:assignmentSnapshots AS jsonb), :ownerContractId,
                    :ownerSchemaVersion, :ownerRevision, :ownerAsOf,
                    :ownerValidUntil, :ownerContentSha256, :ownerService,
                    :purposeCode, :ownerScopeDigest, :ownerBusinessTimeZone,
                    :ownerMinimumRelationshipRevision, :tenantGrantRevision,
                    :authorizationRevision, :fieldPolicyRevision, :ownerIssuedAt,
                    :ownerEvidenceDigest, :ownerKeyId, :lastInvalidationEventId,
                    :lastInvalidationChangeType, :lastInvalidationEffectiveDate,
                    :lastInvalidationPayloadSha256, :lastCorrelationId,
                    :recoveryOutcome, :lastRecoveryErrorCode, :lastRecoveryAt)
                ON CONFLICT (tenant_id, relationship_public_id) DO NOTHING
                """, parameters(candidate));
        if (changed != 1) throw new ProjectionCasLost("insert CAS lost");
        return find(candidate.tenantId(), candidate.relationshipPublicId())
                .orElseThrow(() -> new ProjectionCasLost("insert was not readable"));
    }

    @Override
    public EmploymentAbsenceProjection updateExpectedVersion(
            EmploymentAbsenceProjection candidate,
            long expectedProjectionVersion) {
        if (expectedProjectionVersion < 1) {
            throw new IllegalArgumentException("update expected version must be positive");
        }
        setTenant(candidate.tenantId());
        MapSqlParameterSource parameters = parameters(candidate)
                .addValue("expectedProjectionVersion", expectedProjectionVersion);
        int changed = jdbc.update("""
                UPDATE abs_employment_relationship_projections
                   SET worker_public_id = :workerPublicId,
                       freshness_state = :freshnessState,
                       last_observed_event_sequence = :lastObservedEventSequence,
                       required_relationship_revision = :requiredRelationshipRevision,
                       shared_offset_sequence = :sharedOffsetSequence,
                       gap_expected_sequence = :gapExpectedSequence,
                       owner_relationship_revision = :ownerRelationshipRevision,
                       worker_status = :workerStatus,
                       relationship_type = :relationshipType,
                       relationship_status = :relationshipStatus,
                       legal_entity_public_id = :legalEntityPublicId,
                       relationship_effective_from = :relationshipEffectiveFrom,
                       relationship_effective_to = :relationshipEffectiveTo,
                       owner_effective_on = :ownerEffectiveOn,
                       assignment_snapshots = CAST(:assignmentSnapshots AS jsonb),
                       owner_contract_id = :ownerContractId,
                       owner_schema_version = :ownerSchemaVersion,
                       owner_revision = :ownerRevision,
                       owner_as_of = :ownerAsOf,
                       owner_valid_until = :ownerValidUntil,
                       owner_content_sha256 = :ownerContentSha256,
                       owner_service = :ownerService,
                       purpose_code = :purposeCode,
                       owner_scope_digest = :ownerScopeDigest,
                       owner_business_time_zone = :ownerBusinessTimeZone,
                       owner_minimum_relationship_revision = :ownerMinimumRelationshipRevision,
                       tenant_grant_revision = :tenantGrantRevision,
                       authorization_revision = :authorizationRevision,
                       field_policy_revision = :fieldPolicyRevision,
                       owner_issued_at = :ownerIssuedAt,
                       owner_evidence_digest = :ownerEvidenceDigest,
                       owner_key_id = :ownerKeyId,
                       last_invalidation_event_id = :lastInvalidationEventId,
                       last_invalidation_change_type = :lastInvalidationChangeType,
                       last_invalidation_effective_date = :lastInvalidationEffectiveDate,
                       last_invalidation_payload_sha256 = :lastInvalidationPayloadSha256,
                       last_correlation_id = :lastCorrelationId,
                       recovery_outcome = :recoveryOutcome,
                       last_recovery_error_code = :lastRecoveryErrorCode,
                       last_recovery_at = :lastRecoveryAt
                 WHERE tenant_id = :tenantId
                   AND relationship_public_id = :relationshipPublicId
                   AND projection_version = :expectedProjectionVersion
                """, parameters);
        if (changed != 1) throw new ProjectionCasLost("update CAS lost");
        return find(candidate.tenantId(), candidate.relationshipPublicId())
                .orElseThrow(() -> new ProjectionCasLost("updated row was not readable"));
    }

    @Override
    public boolean hasUnresolvedInbox(long tenantId, UUID relationshipPublicId) {
        setTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND status IN ('RECEIVED', 'PROCESSING', 'FAILED', 'DEFERRED',
                                  'REPLAY_PENDING', 'DEAD')
                """, key(tenantId, relationshipPublicId)
                        .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                        .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                        .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                        .addValue("aggregateId", relationshipPublicId.toString()), Integer.class);
        return count == null || count != 0;
    }

    @Override
    public boolean hasAnyInbox(long tenantId, UUID relationshipPublicId) {
        setTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                """, key(tenantId, relationshipPublicId)
                        .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                        .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                        .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                        .addValue("aggregateId", relationshipPublicId.toString()), Integer.class);
        return count == null || count != 0;
    }

    @Override
    public boolean hasBlockingInbox(long tenantId, UUID relationshipPublicId) {
        setTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND (status = 'DEAD' OR last_error = :payloadConflictReason)
                """, key(tenantId, relationshipPublicId)
                        .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                        .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                        .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                        .addValue("aggregateId", relationshipPublicId.toString())
                        .addValue("payloadConflictReason",
                                com.dwp.core.event.DomainEventInboxStrictValidationSupport
                                        .PAYLOAD_CONFLICT_REASON), Integer.class);
        return count == null || count != 0;
    }

    @Override
    public OptionalLong sharedOffset(long tenantId, UUID relationshipPublicId) {
        setTenant(tenantId);
        List<Long> values = jdbc.query("""
                SELECT last_sequence
                  FROM sys_domain_event_offsets
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                 FOR UPDATE
                """, key(tenantId, relationshipPublicId)
                        .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                        .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                        .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                        .addValue("aggregateId", relationshipPublicId.toString()),
                (result, ignored) -> result.getLong("last_sequence"));
        if (values.size() > 1) {
            throw new IllegalStateException("shared offset tuple is not unique");
        }
        return values.isEmpty() ? OptionalLong.empty()
                : OptionalLong.of(values.get(0));
    }

    private void setTenant(long tenantId) {
        EmploymentAbsenceContract.positive(tenantId, "tenantId");
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)), String.class);
    }

    private MapSqlParameterSource parameters(EmploymentAbsenceProjection value) {
        var snapshot = value.ownerSnapshot();
        var evidence = value.ownerEvidence();
        var invalidation = value.lastInvalidation();
        return key(value.tenantId(), value.relationshipPublicId())
                .addValue("workerPublicId", snapshot == null ? null : snapshot.workerPublicId())
                .addValue("freshnessState", value.freshness().name())
                .addValue("lastObservedEventSequence", value.lastObservedEventSequence())
                .addValue("requiredRelationshipRevision", value.requiredRelationshipRevision())
                .addValue("sharedOffsetSequence", value.sharedOffsetSequence())
                .addValue("gapExpectedSequence", value.gapExpectedSequence())
                .addValue("ownerRelationshipRevision",
                        snapshot == null ? null : snapshot.relationshipRevision())
                .addValue("workerStatus", snapshot == null ? null : snapshot.workerStatus())
                .addValue("relationshipType", snapshot == null ? null : snapshot.relationshipType())
                .addValue("relationshipStatus", snapshot == null ? null : snapshot.relationshipStatus())
                .addValue("legalEntityPublicId", snapshot == null ? null : snapshot.legalEntityPublicId())
                .addValue("relationshipEffectiveFrom",
                        snapshot == null ? null : snapshot.relationshipEffectiveFrom())
                .addValue("relationshipEffectiveTo",
                        snapshot == null ? null : snapshot.relationshipEffectiveTo())
                .addValue("ownerEffectiveOn", snapshot == null ? null : snapshot.effectiveOn())
                .addValue("assignmentSnapshots", assignments(snapshot))
                .addValue("ownerContractId", snapshot == null ? null : snapshot.ownerContractId())
                .addValue("ownerSchemaVersion", snapshot == null ? null : 1)
                .addValue("ownerRevision", snapshot == null ? null : snapshot.ownerRevision())
                .addValue("ownerAsOf", instant(snapshot == null ? null : snapshot.asOf()))
                .addValue("ownerValidUntil", instant(snapshot == null ? null : snapshot.validUntil()))
                .addValue("ownerContentSha256", snapshot == null ? null : snapshot.contentSha256())
                .addValue("ownerService", snapshot == null ? null : snapshot.ownerService())
                .addValue("purposeCode", snapshot == null ? null : snapshot.purposeCode())
                .addValue("ownerScopeDigest", snapshot == null ? null : snapshot.scopeDigest())
                .addValue("ownerBusinessTimeZone", value.ownerBusinessTimeZone())
                .addValue("ownerMinimumRelationshipRevision",
                        snapshot == null ? null : value.ownerMinimumRelationshipRevision())
                .addValue("tenantGrantRevision", evidence == null ? null : evidence.tenantGrantRevision())
                .addValue("authorizationRevision", evidence == null ? null : evidence.authorizationRevision())
                .addValue("fieldPolicyRevision", evidence == null ? null : evidence.fieldPolicyRevision())
                .addValue("ownerIssuedAt", instant(evidence == null ? null : evidence.issuedAt()))
                .addValue("ownerEvidenceDigest", evidence == null ? null : evidence.evidenceDigest())
                .addValue("ownerKeyId", evidence == null ? null : evidence.keyId())
                .addValue("lastInvalidationEventId", invalidation == null ? null : invalidation.eventId())
                .addValue("lastInvalidationChangeType", invalidation == null ? null : invalidation.changeType())
                .addValue("lastInvalidationEffectiveDate", invalidation == null ? null : invalidation.effectiveDate())
                .addValue("lastInvalidationPayloadSha256", invalidation == null ? null : invalidation.payloadSha256())
                .addValue("lastCorrelationId", invalidation == null ? null : invalidation.correlationId())
                .addValue("recoveryOutcome", value.recoveryOutcome().name())
                .addValue("lastRecoveryErrorCode", value.lastRecoveryErrorCode())
                .addValue("lastRecoveryAt", instant(value.lastRecoveryAt()));
    }

    private EmploymentAbsenceProjection row(ResultSet result) throws SQLException {
        UUID relationshipId = result.getObject("relationship_public_id", UUID.class);
        UUID workerId = result.getObject("worker_public_id", UUID.class);
        Xcon027EmploymentAbsenceSnapshotV1 snapshot = null;
        EmploymentAbsenceProjection.StoredOwnerEvidence evidence = null;
        if (workerId != null) {
            List<Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment> assignments =
                    parseAssignments(result.getString("assignment_snapshots"));
            snapshot = new Xcon027EmploymentAbsenceSnapshotV1(
                    relationshipId, result.getLong("owner_relationship_revision"),
                    workerId, result.getString("worker_status"),
                    result.getString("relationship_type"),
                    result.getString("relationship_status"),
                    result.getObject("legal_entity_public_id", UUID.class),
                    result.getObject("relationship_effective_from", LocalDate.class),
                    result.getObject("relationship_effective_to", LocalDate.class),
                    result.getObject("owner_effective_on", LocalDate.class), assignments,
                    result.getLong("owner_revision"), instant(result, "owner_as_of"),
                    instant(result, "owner_valid_until"),
                    result.getString("owner_contract_id"), result.getString("owner_service"),
                    result.getString("purpose_code"), result.getString("owner_scope_digest"),
                    result.getString("owner_content_sha256"));
            evidence = new EmploymentAbsenceProjection.StoredOwnerEvidence(
                    result.getLong("tenant_id"),
                    result.getLong("tenant_grant_revision"),
                    result.getString("owner_business_time_zone"),
                    EmploymentAbsenceContract.PURPOSE,
                    EmploymentAbsenceContract.PROJECTION,
                    result.getLong("authorization_revision"),
                    result.getLong("field_policy_revision"),
                    instant(result, "owner_issued_at"),
                    instant(result, "owner_as_of"),
                    instant(result, "owner_valid_until"),
                    result.getString("owner_scope_digest"),
                    result.getString("owner_content_sha256"),
                    result.getString("owner_evidence_digest"),
                    result.getString("owner_key_id"));
        }
        long observed = result.getLong("last_observed_event_sequence");
        EmploymentAbsenceProjection.InvalidationEvidence invalidation = observed == 0
                ? null : new EmploymentAbsenceProjection.InvalidationEvidence(
                        observed, result.getObject("last_invalidation_event_id", UUID.class),
                        result.getString("last_invalidation_change_type"),
                        result.getObject("last_invalidation_effective_date", LocalDate.class),
                        result.getString("last_invalidation_payload_sha256"),
                        result.getString("last_correlation_id"));
        return new EmploymentAbsenceProjection(
                result.getLong("tenant_id"), relationshipId,
                FreshnessState.valueOf(result.getString("freshness_state")), observed,
                result.getLong("required_relationship_revision"),
                result.getLong("shared_offset_sequence"),
                nullableLong(result, "gap_expected_sequence"), snapshot, evidence,
                result.getString("owner_business_time_zone"),
                nullableLong(result, "owner_minimum_relationship_revision") == null
                        ? 0 : result.getLong("owner_minimum_relationship_revision"),
                invalidation,
                RecoveryOutcome.valueOf(result.getString("recovery_outcome")),
                result.getString("last_recovery_error_code"),
                instant(result, "last_recovery_at"), result.getLong("projection_version"),
                instant(result, "created_at"), instant(result, "updated_at"));
    }

    private String assignments(Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
        try {
            return mapper.writeValueAsString(snapshot == null ? List.of() : snapshot.assignments());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("assignment serialization failed", exception);
        }
    }

    private List<Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment>
            parseAssignments(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("assignment storage is corrupt", exception);
        }
    }

    private static MapSqlParameterSource key(long tenantId, UUID relationshipPublicId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("relationshipPublicId", relationshipPublicId);
    }

    private static OffsetDateTime instant(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public static final class ProjectionCasLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProjectionCasLost(String message) {
            super(message);
        }
    }
}
