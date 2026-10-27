package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Snapshot and keyset queries, executed inside the repository's tenant transaction. */
final class JdbcClockEventQueries {

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

    private final NamedParameterJdbcTemplate namedJdbc;

    JdbcClockEventQueries(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    ClockProjectionPage<ClockProjectionCandidate> queryClockInTransaction(
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
        return page(highWater, rows, query.limit(), sort, JdbcClockEventQueries::clockKey);
    }

    ClockProjectionPage<ClockExceptionCandidate> queryExceptionsInTransaction(
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
                highWater, rows, query.limit(), sort, JdbcClockEventQueries::exceptionKey);
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

    static void validateSnapshotQuery(
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

    static ClockEvent mapEvent(ResultSet row, int ignored) throws SQLException {
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

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
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
