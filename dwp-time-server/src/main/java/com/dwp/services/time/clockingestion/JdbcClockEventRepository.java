package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionPage;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter that commits receipt, immutable fact, and outbox envelope atomically. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class JdbcClockEventRepository implements ClockEventRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";
    private static final String SELECT_RECEIPT_SQL = """
            SELECT public_id, tenant_id, idempotency_key, command_type, actor_id,
                   subject_principal_public_id, population_public_id, population_revision,
                   grant_revision, membership_revision, population_scope_digest,
                   field_policy_revision, purpose_code, authorization_revision,
                   request_digest, result_event_public_id, result_digest, correlation_id, created_at
              FROM tme_clock_command_receipts
             WHERE tenant_id = ?
               AND subject_principal_public_id = ?
               AND command_type = ?
               AND idempotency_key = ?
             FOR UPDATE
            """;
    private static final String EVENT_COLUMNS = """
            event_order, public_id, tenant_id, source_public_id, source_event_key,
            source_sequence, source_type, device_public_id, capture_mode,
            offline_batch_public_id, worker_public_id, people_assignment_public_id,
            people_assignment_revision, event_type, occurred_at, received_at,
            source_local_datetime, source_time_zone, source_utc_offset_minutes,
            dst_resolution, instant_authority, tzdb_version, time_zone_rule_version,
            source_provenance_digest, payload_digest, trust_level,
            voids_event_public_id, correction_reason_code, correlation_id, recorded_by
            """;
    private static final String SNAPSHOT_FACTS_CTE = """
            WITH scoped AS (
                SELECT e.*,
                       MAX(e.source_sequence) OVER (
                           PARTITION BY e.source_public_id
                           ORDER BY e.event_order
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                       ) AS prior_source_sequence
                  FROM tme_clock_events e
                 WHERE e.tenant_id = :tenantId
                   AND e.worker_public_id IN (:workers)
                   AND e.event_order <= :snapshotHighWater
                   AND e.received_at <= :asOf
                   AND e.occurred_at <= :asOf
            ), facts_base AS (
                SELECT s.*,
                       (
                           SELECT v.public_id
                             FROM scoped v
                            WHERE v.event_type = 'VOID'
                              AND v.voids_event_public_id = s.public_id
                            ORDER BY v.event_order
                            LIMIT 1
                       ) AS resolution_void_id,
                       (s.source_sequence IS NOT NULL
                           AND s.prior_source_sequence IS NOT NULL
                           AND s.source_sequence > s.prior_source_sequence + 1) AS was_gap,
                       (s.source_sequence IS NOT NULL
                           AND s.prior_source_sequence IS NOT NULL
                           AND s.source_sequence < s.prior_source_sequence) AS out_of_order
                  FROM scoped s
            ), facts AS (
                SELECT b.*,
                       CASE
                           WHEN NOT b.was_gap THEN FALSE
                           ELSE EXISTS (
                               SELECT 1
                                 FROM generate_series(
                                     b.prior_source_sequence + 1,
                                     b.source_sequence - 1
                                 ) missing(sequence_value)
                                WHERE NOT EXISTS (
                                    SELECT 1
                                      FROM scoped present
                                     WHERE present.source_public_id = b.source_public_id
                                       AND present.source_sequence = missing.sequence_value
                                )
                           )
                       END AS gap_open
                  FROM facts_base b
            )
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public JdbcClockEventRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    @Override
    public ClockCommandReceipt append(ClockAppend append) {
        Objects.requireNonNull(append, "append must not be null");
        try {
            return inTenantTransaction(append.authority().tenantId(), () -> appendInTransaction(append));
        } catch (DataIntegrityViolationException conflict) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Clock event order, source identity, or corrective void conflicts.",
                    conflict);
        }
    }

    @Override
    public Optional<ClockEvent> find(long tenantId, UUID clockEventId) {
        requireTenant(tenantId);
        Objects.requireNonNull(clockEventId, "clockEventId must not be null");
        return inTenantTransaction(tenantId, () -> exact(jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM tme_clock_events "
                        + "WHERE tenant_id = ? AND public_id = ?",
                JdbcClockEventRepository::mapEvent,
                tenantId,
                clockEventId)));
    }

    @Override
    public ClockProjectionPage<ClockProjectionCandidate> queryClock(
            ClockProjectionQuery query) {
        validateSnapshotQuery(
                query.tenantId(), query.workerScope(), query.asOf(), query.limit());
        return inTenantTransaction(query.tenantId(), () -> queryClockInTransaction(query));
    }

    @Override
    public ClockProjectionPage<ClockExceptionCandidate> queryExceptions(
            ClockExceptionProjectionQuery query) {
        validateSnapshotQuery(
                query.tenantId(), query.workerScope(), query.asOf(), query.limit());
        return inTenantTransaction(query.tenantId(), () -> queryExceptionsInTransaction(query));
    }

    private ClockProjectionPage<ClockProjectionCandidate> queryClockInTransaction(
            ClockProjectionQuery query) {
        long highWater = snapshotHighWater(
                query.tenantId(), query.workerScope(), query.asOf(), query.snapshotHighWater());
        List<SortSpec> sort = clockSort(query.sort());
        MapSqlParameterSource parameters = snapshotParameters(
                query.tenantId(), query.workerScope(), query.asOf(), highWater, query.limit());
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        if (query.startFrom() != null) {
            where.append(" AND c.occurred_at >= :startFrom");
            parameters.addValue("startFrom", Timestamp.from(query.startFrom()));
        }
        if (query.startTo() != null) {
            where.append(" AND c.occurred_at <= :startTo");
            parameters.addValue("startTo", Timestamp.from(query.startTo()));
        }
        if (query.endFrom() != null || query.endTo() != null) where.append(" AND FALSE");
        if (query.effectiveOn() != null) {
            where.append(" AND (c.occurred_at AT TIME ZONE :timeZone)::date = :effectiveOn");
            parameters.addValue("timeZone", query.timeZone());
            parameters.addValue("effectiveOn", query.effectiveOn());
        }
        if (!query.ingestionCodes().isEmpty()) {
            where.append(" AND c.status_code IN (:ingestionCodes)");
            parameters.addValue("ingestionCodes", query.ingestionCodes());
        }
        if (query.minimumIngestionRevision() != null) {
            where.append(" AND c.event_order >= :minimumRevision");
            parameters.addValue("minimumRevision", query.minimumIngestionRevision());
        }
        if (!query.qualityCodes().isEmpty()) {
            where.append(" AND c.quality_code IN (:qualityCodes)");
            parameters.addValue("qualityCodes", query.qualityCodes());
        }
        if (query.evidenceDigest() != null) {
            where.append(" AND c.payload_digest = :evidenceDigest");
            parameters.addValue("evidenceDigest", query.evidenceDigest());
        }
        where.append(keysetPredicate(sort, query.afterKey(), parameters));
        String sql = SNAPSHOT_FACTS_CTE + """
                , clock_candidates AS (
                    SELECT f.*,
                           CASE
                               WHEN f.event_type = 'VOID' THEN 'CORRECTIVE_VOID'
                               WHEN f.resolution_void_id IS NOT NULL THEN 'VOIDED'
                               WHEN f.out_of_order THEN 'QUARANTINED'
                               ELSE 'ACCEPTED'
                           END AS status_code,
                           CASE
                               WHEN f.was_gap AND f.gap_open THEN 'SOURCE_SEQUENCE_GAP'
                               WHEN f.was_gap THEN 'SOURCE_SEQUENCE_GAP_RECOVERED'
                               WHEN f.out_of_order THEN 'OUT_OF_ORDER_OFFLINE_SEQUENCE'
                               WHEN f.trust_level = 'LOW' THEN 'LOW_TRUST_EVIDENCE'
                               WHEN f.capture_mode = 'OFFLINE' THEN 'OFFLINE_EVIDENCE_REVIEW'
                               ELSE 'EVIDENCE_VERIFIED'
                           END AS quality_code
                      FROM facts f
                )
                SELECT c.* FROM clock_candidates c
                """ + where + orderBy(sort) + " LIMIT :fetchLimit";
        List<ClockProjectionCandidate> rows = namedJdbc.query(
                sql,
                parameters,
                (result, row) -> new ClockProjectionCandidate(
                        mapEvent(result, row),
                        result.getString("status_code"),
                        result.getString("quality_code"),
                        result.getObject("resolution_void_id", UUID.class)));
        return page(highWater, rows, query.limit(), sort, JdbcClockEventRepository::clockKey);
    }

    private ClockProjectionPage<ClockExceptionCandidate> queryExceptionsInTransaction(
            ClockExceptionProjectionQuery query) {
        long highWater = snapshotHighWater(
                query.tenantId(), query.workerScope(), query.asOf(), query.snapshotHighWater());
        List<SortSpec> sort = exceptionSort(query.sort());
        MapSqlParameterSource parameters = snapshotParameters(
                query.tenantId(), query.workerScope(), query.asOf(), highWater, query.limit());
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        if (query.effectiveOn() != null) {
            where.append(" AND (c.occurred_at AT TIME ZONE :timeZone)::date = :effectiveOn");
            parameters.addValue("timeZone", query.timeZone());
            parameters.addValue("effectiveOn", query.effectiveOn());
        }
        if (!query.exceptionCodes().isEmpty()) {
            where.append(" AND c.exception_code IN (:exceptionCodes)");
            parameters.addValue("exceptionCodes", query.exceptionCodes());
        }
        if (query.minimumRevision() != null) {
            where.append(" AND c.event_order >= :minimumRevision");
            parameters.addValue("minimumRevision", query.minimumRevision());
        }
        if (!query.ownerCodes().isEmpty() && !query.ownerCodes().contains("CLOCK_EVENT")) {
            where.append(" AND FALSE");
        }
        if (!query.ownerPublicIds().isEmpty()) {
            where.append(" AND c.public_id::text IN (:ownerPublicIds)");
            parameters.addValue("ownerPublicIds", query.ownerPublicIds());
        }
        if (!query.metadataKeys().isEmpty()) {
            where.append(" AND c.recovery_key IN (:metadataKeys)");
            parameters.addValue("metadataKeys", query.metadataKeys());
        }
        if (!query.metadataVersions().isEmpty()) {
            where.append(" AND c.recovery_state IN (:metadataVersions)");
            parameters.addValue("metadataVersions", query.metadataVersions());
        }
        where.append(keysetPredicate(sort, query.afterKey(), parameters));
        String sql = SNAPSHOT_FACTS_CTE + """
                , exception_candidates AS (
                    SELECT f.*, x.exception_code, x.severity, x.recovery_state,
                           'clock.recovery.'
                               || lower(replace(x.exception_code, '_', '.')) AS recovery_key
                      FROM facts f
                      CROSS JOIN LATERAL (
                          SELECT 'SOURCE_SEQUENCE_GAP'::text AS exception_code,
                                 'ERROR'::text AS severity,
                                 CASE WHEN f.resolution_void_id IS NOT NULL OR NOT f.gap_open
                                      THEN 'RESOLVED' ELSE 'OPEN' END::text AS recovery_state
                           WHERE f.event_type <> 'VOID' AND f.was_gap
                          UNION ALL
                          SELECT 'OUT_OF_ORDER_OFFLINE_SEQUENCE', 'ERROR',
                                 CASE WHEN f.resolution_void_id IS NOT NULL
                                      THEN 'RESOLVED' ELSE 'QUARANTINED' END
                           WHERE f.event_type <> 'VOID' AND f.out_of_order
                          UNION ALL
                          SELECT 'LOW_TRUST_EVIDENCE', 'WARNING',
                                 CASE WHEN f.resolution_void_id IS NOT NULL
                                      THEN 'RESOLVED' ELSE 'OPEN' END
                           WHERE f.event_type <> 'VOID' AND f.trust_level = 'LOW'
                          UNION ALL
                          SELECT 'OFFLINE_EVIDENCE_REVIEW', 'WARNING',
                                 CASE WHEN f.resolution_void_id IS NOT NULL
                                      THEN 'RESOLVED' ELSE 'OPEN' END
                           WHERE f.event_type <> 'VOID' AND f.capture_mode = 'OFFLINE'
                      ) x
                )
                SELECT c.* FROM exception_candidates c
                """ + where + orderBy(sort) + " LIMIT :fetchLimit";
        List<ClockExceptionCandidate> rows = namedJdbc.query(
                sql,
                parameters,
                (result, row) -> new ClockExceptionCandidate(
                        mapEvent(result, row),
                        result.getString("exception_code"),
                        result.getString("severity"),
                        result.getString("recovery_state"),
                        result.getObject("resolution_void_id", UUID.class)));
        return page(
                highWater, rows, query.limit(), sort, JdbcClockEventRepository::exceptionKey);
    }

    private long snapshotHighWater(
            long tenantId,
            Set<UUID> workers,
            Instant asOf,
            Long requestedHighWater) {
        if (requestedHighWater != null) {
            if (requestedHighWater < 0) throw new BaseException(ErrorCode.FORBIDDEN);
            return requestedHighWater;
        }
        Long result = namedJdbc.queryForObject(
                """
                SELECT COALESCE(MAX(event_order), 0)
                  FROM tme_clock_events
                 WHERE tenant_id = :tenantId
                   AND worker_public_id IN (:workers)
                   AND received_at <= :asOf
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workers", workers)
                        .addValue("asOf", Timestamp.from(asOf)),
                Long.class);
        return result == null ? 0 : result;
    }

    private static MapSqlParameterSource snapshotParameters(
            long tenantId,
            Set<UUID> workers,
            Instant asOf,
            long highWater,
            int limit) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("workers", workers)
                .addValue("asOf", Timestamp.from(asOf))
                .addValue("snapshotHighWater", highWater)
                .addValue("fetchLimit", limit + 1);
    }

    private static void validateSnapshotQuery(
            long tenantId,
            Set<UUID> workerScope,
            Instant asOf,
            int limit) {
        requireTenant(tenantId);
        if (workerScope == null || workerScope.isEmpty() || workerScope.size() > 1_000) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (asOf == null || limit < 1 || limit > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private static List<SortSpec> clockSort(List<String> tokens) {
        List<SortSpec> result = new ArrayList<>();
        for (String token : tokens) {
            boolean descending = token.endsWith(":DESC");
            String field = token.substring(0, token.lastIndexOf(':'));
            result.add(switch (field) {
                case "clockEvent" -> new SortSpec(
                        field, "c.occurred_at", descending, SortType.INSTANT);
                case "ingestionStatus" -> new SortSpec(
                        field, "c.status_code", descending, SortType.TEXT);
                case "qualityFinding" -> new SortSpec(
                        field, "c.quality_code", descending, SortType.TEXT);
                case "itemPublicId" -> new SortSpec(
                        field, "c.public_id::text", descending, SortType.TEXT);
                default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            });
        }
        if (result.stream().noneMatch(spec -> spec.key().equals("itemPublicId"))) {
            result.add(new SortSpec(
                    "itemPublicId", "c.public_id::text", false, SortType.TEXT));
        }
        return List.copyOf(result);
    }

    private static List<SortSpec> exceptionSort(List<String> tokens) {
        List<SortSpec> result = new ArrayList<>();
        for (String token : tokens) {
            boolean descending = token.endsWith(":DESC");
            String field = token.substring(0, token.lastIndexOf(':'));
            result.add(switch (field) {
                case "exception" -> new SortSpec(
                        field, "c.exception_code", descending, SortType.TEXT);
                case "ownerDeepLink" -> new SortSpec(
                        field, "c.public_id::text", descending, SortType.TEXT);
                case "recoveryMetadata" -> new SortSpec(
                        field, "c.event_order", descending, SortType.LONG);
                case "itemPublicId" -> new SortSpec(
                        field,
                        "(c.public_id::text || '|' || c.exception_code)",
                        descending,
                        SortType.TEXT);
                default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            });
        }
        if (result.stream().noneMatch(spec -> spec.key().equals("itemPublicId"))) {
            result.add(new SortSpec(
                    "itemPublicId",
                    "(c.public_id::text || '|' || c.exception_code)",
                    false,
                    SortType.TEXT));
        }
        return List.copyOf(result);
    }

    private static String keysetPredicate(
            List<SortSpec> sort,
            List<String> afterKey,
            MapSqlParameterSource parameters) {
        if (afterKey == null || afterKey.isEmpty()) return "";
        if (afterKey.size() != sort.size()) throw new BaseException(ErrorCode.FORBIDDEN);
        StringBuilder predicate = new StringBuilder(" AND (");
        for (int index = 0; index < sort.size(); index++) {
            if (index > 0) predicate.append(" OR ");
            predicate.append('(');
            for (int equal = 0; equal < index; equal++) {
                predicate.append(sort.get(equal).column())
                        .append(" = ")
                        .append(cursorParameter(sort.get(equal), equal))
                        .append(" AND ");
            }
            SortSpec current = sort.get(index);
            predicate.append(current.column())
                    .append(current.descending() ? " < " : " > ")
                    .append(cursorParameter(current, index))
                    .append(')');
            parameters.addValue("after" + index, afterKey.get(index));
        }
        return predicate.append(')').toString();
    }

    private static String cursorParameter(SortSpec spec, int index) {
        return switch (spec.type()) {
            case TEXT -> ":after" + index;
            case INSTANT -> "CAST(:after" + index + " AS TIMESTAMPTZ)";
            case LONG -> "CAST(:after" + index + " AS BIGINT)";
        };
    }

    private static String orderBy(List<SortSpec> sort) {
        return " ORDER BY " + sort.stream()
                .map(spec -> spec.column() + (spec.descending() ? " DESC" : " ASC"))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static <T> ClockProjectionPage<T> page(
            long highWater,
            List<T> rows,
            int limit,
            List<SortSpec> sort,
            BiFunction<T, SortSpec, String> key) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        List<String> nextKey = hasMore
                ? sort.stream().map(spec -> key.apply(items.getLast(), spec)).toList()
                : List.of();
        return new ClockProjectionPage<>(highWater, items, hasMore, nextKey);
    }

    private static String clockKey(ClockProjectionCandidate candidate, SortSpec spec) {
        return switch (spec.key()) {
            case "clockEvent" -> candidate.event().occurredAt().toString();
            case "ingestionStatus" -> candidate.statusCode();
            case "qualityFinding" -> candidate.qualityCode();
            case "itemPublicId" -> candidate.event().clockEventId().toString();
            default -> throw new IllegalStateException("Unexpected clock keyset field");
        };
    }

    private static String exceptionKey(ClockExceptionCandidate candidate, SortSpec spec) {
        return switch (spec.key()) {
            case "exception" -> candidate.exceptionCode();
            case "ownerDeepLink" -> candidate.event().clockEventId().toString();
            case "recoveryMetadata" -> Long.toString(candidate.event().eventOrder());
            case "itemPublicId" -> candidate.event().clockEventId() + "|"
                    + candidate.exceptionCode();
            default -> throw new IllegalStateException("Unexpected exception keyset field");
        };
    }

    private ClockCommandReceipt appendInTransaction(ClockAppend append) {
        var authority = append.authority();
        ClockEvent event = append.event();
        UUID proposedReceiptId = UUID.randomUUID();
        Instant createdAt = event.receivedAt();
        int inserted = jdbc.update("""
                INSERT INTO tme_clock_command_receipts (
                    public_id, tenant_id, idempotency_key, command_type, actor_id,
                    subject_principal_public_id, population_public_id, population_revision,
                    grant_revision, membership_revision, population_scope_digest,
                    field_policy_revision, purpose_code, authorization_revision,
                    request_digest, result_event_public_id, result_digest,
                    status, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'SUCCEEDED', ?, ?)
                ON CONFLICT (
                    tenant_id, subject_principal_public_id, command_type, idempotency_key
                ) DO NOTHING
                """,
                proposedReceiptId,
                authority.tenantId(),
                append.idempotencyKey(),
                append.commandType(),
                authority.actorId(),
                authority.subjectPrincipalPublicId(),
                authority.populationPublicId(),
                authority.populationRevision(),
                authority.grantRevision(),
                authority.membershipRevision(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision(),
                append.requestDigest(),
                event.clockEventId(),
                event.payloadDigest(),
                authority.correlationId(),
                Timestamp.from(createdAt));

        StoredReceipt stored = exactlyOne(jdbc.query(
                SELECT_RECEIPT_SQL,
                JdbcClockEventRepository::mapReceipt,
                authority.tenantId(),
                authority.subjectPrincipalPublicId(),
                append.commandType(),
                append.idempotencyKey()));
        requireExactBinding(append, stored);
        if (inserted == 0) {
            return stored.toView(
                    true, eventVersion(stored.tenantId(), stored.eventId()));
        }
        if (!stored.receiptId().equals(proposedReceiptId)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        requireAndConsumeStepUpChallenge(append);

        Long eventOrder = jdbc.queryForObject("""
                INSERT INTO tme_clock_events (
                    public_id, tenant_id, command_receipt_public_id,
                    source_public_id, source_event_key, source_sequence, source_type,
                    device_public_id, capture_mode, offline_batch_public_id,
                    worker_public_id, people_assignment_public_id, people_assignment_revision,
                    event_type, occurred_at, received_at, source_local_datetime,
                    source_time_zone, source_utc_offset_minutes, dst_resolution,
                    instant_authority, tzdb_version, time_zone_rule_version,
                    source_provenance_digest, payload_digest, trust_level,
                    voids_event_public_id, correction_reason_code, correlation_id, recorded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?)
                RETURNING event_order
                """,
                Long.class,
                event.clockEventId(),
                event.tenantId(),
                proposedReceiptId,
                event.sourcePublicId(),
                event.sourceEventKey(),
                event.sourceSequence(),
                event.sourceType().name(),
                event.devicePublicId(),
                event.captureMode().name(),
                event.offlineBatchPublicId(),
                event.workerPublicId(),
                event.peopleAssignmentPublicId(),
                event.peopleAssignmentRevision(),
                event.eventType().name(),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.receivedAt()),
                event.sourceLocalDateTime(),
                event.sourceTimeZone(),
                event.sourceUtcOffsetMinutes(),
                event.dstResolution().name(),
                event.instantAuthority().name(),
                event.tzdbVersion(),
                event.timeZoneRuleVersion(),
                event.sourceProvenanceDigest(),
                event.payloadDigest(),
                event.trustLevel().name(),
                event.voidsClockEventId(),
                event.correctionReasonCode(),
                event.correlationId(),
                event.recordedBy());
        if (eventOrder == null || eventOrder <= 0) {
            throw new IllegalStateException("Clock event order was not allocated");
        }
        insertOutbox(event, eventOrder, proposedReceiptId);
        return stored.toView(false, eventOrder);
    }

    private void requireAndConsumeStepUpChallenge(ClockAppend append) {
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge =
                append.stepUpChallenge();
        if (!"CLOCK_VOID".equals(append.commandType())) {
            if (challenge != null) {
                throw new BaseException(
                        ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                        "Step-up proof is forbidden for this TIM clock command.");
            }
            return;
        }
        if (challenge == null || append.event().voidsClockEventId() == null) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "A verified TIM corrective-void challenge is required.");
        }

        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = challenge.binding();
        long targetVersion = lockedEventVersion(
                append.authority().tenantId(), append.event().voidsClockEventId());
        String expectedPath = AuthSignedClockStepUpVerifier.PUBLIC_PATH_PREFIX
                + append.event().voidsClockEventId() + "/voids";
        boolean exact = binding != null
                && binding.actorUserId() == append.authority().actorId()
                && binding.tenantId() == append.authority().tenantId()
                && AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY.equals(
                        binding.commandContractKey())
                && append.authority().contextKey().equals(binding.contextKey())
                && AuthSignedClockStepUpVerifier.ACTIVATION_POLICY.equals(
                        binding.activationPolicy())
                && AuthSignedClockStepUpVerifier.CAPABILITY_CONTRACT_KEY.equals(
                        binding.capabilityContractKey())
                && append.authority().gatewayScopeKey().equals(binding.scopeRef())
                && AuthSignedClockStepUpVerifier.TARGET_TYPE.equals(binding.targetType())
                && append.event().voidsClockEventId().toString().equals(binding.targetId())
                && binding.targetVersion() == targetVersion
                && AuthSignedClockStepUpVerifier.COMMAND_METHOD.equals(binding.commandMethod())
                && expectedPath.equals(binding.commandPath())
                && append.idempotencyKey().toString().equals(binding.idempotencyKey())
                && append.authority().decisionRevision().equals(binding.decisionRevision())
                && binding.payloadSha256() != null
                && binding.payloadSha256().matches("[0-9a-f]{64}")
                && challenge.challengeId() != null
                && !challenge.challengeId().isBlank()
                && challenge.nonce() != null
                && !challenge.nonce().isBlank()
                && challenge.issuer() != null
                && !challenge.issuer().isBlank()
                && challenge.expiresAt() != null;
        if (!exact) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Verified TIM step-up evidence does not match the current event.");
        }
        consumeStepUpChallenge(challenge);
    }

    private long lockedEventVersion(long tenantId, UUID eventId) {
        List<Long> versions = jdbc.query(
                "SELECT event_order FROM tme_clock_events "
                        + "WHERE tenant_id = ? AND public_id = ? FOR SHARE",
                (row, ignored) -> row.getLong("event_order"),
                tenantId,
                eventId);
        if (versions.size() != 1 || versions.getFirst() <= 0) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The TIM step-up target version is unavailable.");
        }
        return versions.getFirst();
    }

    private Long eventVersion(long tenantId, UUID eventId) {
        List<Long> versions = jdbc.query(
                "SELECT event_order FROM tme_clock_events "
                        + "WHERE tenant_id = ? AND public_id = ?",
                (row, ignored) -> row.getLong("event_order"),
                tenantId,
                eventId);
        if (versions.size() != 1 || versions.getFirst() <= 0) {
            throw new IllegalStateException("Durable clock event version was not recovered");
        }
        return versions.getFirst();
    }

    private void consumeStepUpChallenge(
            ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge) {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = challenge.binding();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO tme_clock_step_up_replay_ledger (
                        replay_id, tenant_id, actor_user_id, challenge_id, nonce,
                        command_contract_key, activation_policy, capability_contract_key,
                        context_key, scope_ref, target_type, target_id, target_version,
                        command_method, command_path, idempotency_key, payload_sha256,
                        decision_revision, issuer, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    binding.tenantId(),
                    binding.actorUserId(),
                    challenge.challengeId(),
                    challenge.nonce(),
                    binding.commandContractKey(),
                    binding.activationPolicy(),
                    binding.capabilityContractKey(),
                    binding.contextKey(),
                    binding.scopeRef(),
                    binding.targetType(),
                    binding.targetId(),
                    binding.targetVersion(),
                    binding.commandMethod(),
                    binding.commandPath(),
                    binding.idempotencyKey(),
                    binding.payloadSha256(),
                    binding.decisionRevision(),
                    challenge.issuer(),
                    Timestamp.from(challenge.expiresAt().plus(java.time.Duration.ofHours(24))));
            if (inserted != 1) throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The TIM step-up replay ledger did not consume the challenge.");
        } catch (DuplicateKeyException replay) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                    "The TIM step-up challenge nonce has already been consumed.",
                    replay);
        } catch (DataAccessException unavailable) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The TIM step-up replay ledger is unavailable.",
                    unavailable);
        }
    }

    private void insertOutbox(ClockEvent event, long eventOrder, UUID receiptId) {
        UUID outboxEventId = UUID.nameUUIDFromBytes(
                (event.clockEventId() + "|ClockEventRecorded.v1")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clockEventId", event.clockEventId());
        data.put("workerId", event.workerPublicId());
        data.put("eventType", event.eventType().name());
        data.put("occurredAt", event.occurredAt());
        data.put("sourceId", event.sourcePublicId());
        data.put("sourceOffsetMinutes", event.sourceUtcOffsetMinutes());
        data.put("payloadDigest", event.payloadDigest());
        data.put("sourceSequence", event.sourceSequence());
        data.put("sourceProvenanceDigest", event.sourceProvenanceDigest());
        data.put("voidsClockEventId", event.voidsClockEventId());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Clock outbox payload cannot be serialized", impossible);
        }
        jdbc.update("""
                INSERT INTO tme_clock_outbox_events (
                    tenant_id, event_id, aggregate_type, aggregate_public_id,
                    aggregate_sequence, schema_name, schema_version, payload,
                    payload_digest, correlation_id, causation_id, occurred_at,
                    publish_attempts
                ) VALUES (?, ?, 'ClockStream', ?, ?, 'ClockEventRecorded', 1,
                          CAST(? AS JSONB), ?, ?, ?, ?, 0)
                """,
                event.tenantId(),
                outboxEventId,
                event.workerPublicId(),
                eventOrder,
                payload,
                event.payloadDigest(),
                event.correlationId(),
                receiptId,
                Timestamp.from(event.receivedAt()));
    }

    private <T> T inTenantTransaction(long tenantId, Supplier<T> work) {
        requireTenant(tenantId);
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "transaction returned null");
    }

    private static void requireExactBinding(ClockAppend append, StoredReceipt stored) {
        var authority = append.authority();
        boolean exact = stored.tenantId() == authority.tenantId()
                && stored.idempotencyKey().equals(append.idempotencyKey())
                && stored.commandType().equals(append.commandType())
                && stored.actorId() == authority.actorId()
                && stored.subjectPrincipalPublicId().equals(authority.subjectPrincipalPublicId())
                && stored.populationPublicId().equals(authority.populationPublicId())
                && stored.populationRevision() == authority.populationRevision()
                && stored.grantRevision() == authority.grantRevision()
                && stored.membershipRevision() == authority.membershipRevision()
                && stored.populationScopeDigest().equals(authority.populationScopeDigest())
                && stored.fieldPolicyRevision() == authority.fieldPolicyRevision()
                && stored.purposeCode().equals(authority.purposeCode())
                && stored.authorizationRevision() == authority.authorizationRevision()
                && stored.requestDigest().equals(append.requestDigest())
                && stored.eventId().equals(append.event().clockEventId())
                && stored.correlationId().equals(authority.correlationId());
        if (!exact) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Idempotency-Key was already used for different clock evidence.");
        }
    }

    private static StoredReceipt mapReceipt(ResultSet row, int ignored) throws SQLException {
        return new StoredReceipt(
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("idempotency_key", UUID.class),
                row.getString("command_type"),
                row.getLong("actor_id"),
                row.getObject("subject_principal_public_id", UUID.class),
                row.getObject("population_public_id", UUID.class),
                row.getLong("population_revision"),
                row.getLong("grant_revision"),
                row.getLong("membership_revision"),
                row.getString("population_scope_digest").trim(),
                row.getLong("field_policy_revision"),
                row.getString("purpose_code"),
                row.getLong("authorization_revision"),
                row.getString("request_digest").trim(),
                row.getObject("result_event_public_id", UUID.class),
                row.getString("result_digest").trim(),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("created_at").toInstant());
    }

    private static ClockEvent mapEvent(ResultSet row, int ignored) throws SQLException {
        Object sourceSequence = row.getObject("source_sequence");
        return new ClockEvent(
                row.getObject("public_id", UUID.class),
                row.getLong("event_order"),
                row.getLong("tenant_id"),
                row.getObject("source_public_id", UUID.class),
                row.getString("source_event_key"),
                sourceSequence == null ? null : ((Number) sourceSequence).longValue(),
                ClockSourceType.valueOf(row.getString("source_type")),
                row.getObject("device_public_id", UUID.class),
                CaptureMode.valueOf(row.getString("capture_mode")),
                row.getObject("offline_batch_public_id", UUID.class),
                row.getObject("worker_public_id", UUID.class),
                row.getObject("people_assignment_public_id", UUID.class),
                row.getLong("people_assignment_revision"),
                ClockEventType.valueOf(row.getString("event_type")),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("received_at").toInstant(),
                row.getObject("source_local_datetime", java.time.LocalDateTime.class),
                row.getString("source_time_zone"),
                row.getInt("source_utc_offset_minutes"),
                DstResolution.valueOf(row.getString("dst_resolution")),
                InstantAuthority.valueOf(row.getString("instant_authority")),
                row.getString("tzdb_version"),
                row.getString("time_zone_rule_version"),
                row.getString("source_provenance_digest").trim(),
                row.getString("payload_digest").trim(),
                TrustLevel.valueOf(row.getString("trust_level")),
                row.getObject("voids_event_public_id", UUID.class),
                row.getString("correction_reason_code"),
                row.getObject("correlation_id", UUID.class),
                row.getLong("recorded_by"));
    }

    private static <T> Optional<T> exact(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Owner query returned multiple rows");
        return rows.stream().findFirst();
    }

    private static <T> T exactlyOne(List<T> rows) {
        if (rows.size() != 1) throw new IllegalStateException("Durable receipt was not recovered");
        return rows.getFirst();
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private record StoredReceipt(
            UUID receiptId,
            long tenantId,
            UUID idempotencyKey,
            String commandType,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            long membershipRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String requestDigest,
            UUID eventId,
            String resultDigest,
            UUID correlationId,
            Instant createdAt) {

        private ClockCommandReceipt toView(boolean replayed, Long eventVersion) {
            return new ClockCommandReceipt(
                    receiptId,
                    commandType,
                    commandType.equals("CLOCK_RECORD")
                            ? "TIME_CLOCK_RECORD"
                            : "TIME_CLOCK_CORRECT",
                    subjectPrincipalPublicId,
                    populationScopeDigest,
                    fieldPolicyRevision,
                    purposeCode,
                    authorizationRevision,
                    "SUCCEEDED",
                    correlationId,
                    requestDigest,
                    createdAt,
                    eventVersion,
                    eventId.toString(),
                    idempotencyKey,
                    eventId,
                    resultDigest,
                    replayed);
        }
    }

    private enum SortType {
        TEXT,
        INSTANT,
        LONG
    }

    private record SortSpec(
            String key,
            String column,
            boolean descending,
            SortType type) {}
}
