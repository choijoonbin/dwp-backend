package com.dwp.services.platform.hrisconfiguration.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcHrisOperationalExceptionRepository
        implements HrisOperationalExceptionRepository {

    private static final int MAXIMUM_GAP_ATTEMPTS = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate admissionTransactions;
    private final TransactionTemplate projectionTransactions;

    JdbcHrisOperationalExceptionRepository(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.admissionTransactions = requiresNew(transactionManager);
        this.projectionTransactions = requiresNew(transactionManager);
    }

    @Override
    public IngressResult apply(ValidatedEvent event) {
        Admission admission = admissionTransactions.execute(
                status -> admit(event));
        if (admission == null) {
            throw new IllegalStateException("SYS-EVT-009 admission returned no result");
        }
        if (admission.result() != null) return admission.result();
        IngressResult result = projectionTransactions.execute(
                status -> applyContiguous(event));
        if (result == null) {
            throw new IllegalStateException("SYS-EVT-009 projection returned no result");
        }
        return result;
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private Admission admit(ValidatedEvent event) {
        MapSqlParameterSource parameters = eventParameters(event);
        int inserted = jdbc.update("""
                INSERT INTO sys_domain_event_inbox (
                    consumer_name, event_id, event_source, event_type, schema_version,
                    tenant_id, aggregate_type, aggregate_id, aggregate_sequence,
                    correlation_id, causation_id, trace_parent, payload, payload_sha256,
                    status, attempt_count, available_at, created_at, updated_at)
                VALUES (
                    :consumerName, :eventId, :eventSource, :eventType, 1,
                    :tenantId, :aggregateType, :aggregateId, :aggregateSequence,
                    :correlationId, :causationId, :traceParent,
                    CAST(:payload AS jsonb), :payloadHash, 'RECEIVED', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, parameters);
        InboxRow row = lockInbox(event.envelope().id());
        if (!event.eventDigest().equals(row.payloadHash())) {
            quarantine(event.envelope().id(),
                    "SYS-EVT-009 event id payload conflict");
            return new Admission(new IngressResult(
                    IngressState.QUARANTINED, row.attemptCount(),
                    "EVENT_ID_PAYLOAD_CONFLICT"));
        }
        if (inserted == 0 && Set.of("SUCCEEDED", "DUPLICATE")
                .contains(row.status())) {
            return new Admission(new IngressResult(
                    IngressState.DUPLICATE, row.attemptCount(), null));
        }
        if ("DEAD".equals(row.status())) {
            return new Admission(new IngressResult(
                    IngressState.QUARANTINED, row.attemptCount(),
                    "EVENT_ALREADY_QUARANTINED"));
        }
        return new Admission(null);
    }

    private IngressResult applyContiguous(ValidatedEvent event) {
        bindTenant(event.envelope().tenantId());
        InboxRow inbox = lockInbox(event.envelope().id());
        if (!event.eventDigest().equals(inbox.payloadHash())) {
            quarantine(event.envelope().id(),
                    "SYS-EVT-009 event id payload conflict");
            return new IngressResult(
                    IngressState.QUARANTINED, inbox.attemptCount(),
                    "EVENT_ID_PAYLOAD_CONFLICT");
        }
        if (Set.of("SUCCEEDED", "DUPLICATE").contains(inbox.status())) {
            return new IngressResult(
                    IngressState.DUPLICATE, inbox.attemptCount(), null);
        }
        if ("DEAD".equals(inbox.status())) {
            return new IngressResult(
                    IngressState.QUARANTINED, inbox.attemptCount(),
                    "EVENT_ALREADY_QUARANTINED");
        }

        MapSqlParameterSource offset = offsetParameters(event);
        jdbc.update("""
                INSERT INTO sys_domain_event_offsets (
                    consumer_name, tenant_id, event_source, aggregate_type,
                    aggregate_id, last_sequence, updated_at)
                VALUES (
                    :consumerName, :tenantId, :eventSource, :aggregateType,
                    :aggregateId, 0, CURRENT_TIMESTAMP)
                ON CONFLICT (
                    consumer_name, tenant_id, event_source, aggregate_type,
                    aggregate_id) DO NOTHING
                """, offset);
        Long lastSequence = jdbc.queryForObject("""
                SELECT last_sequence
                  FROM sys_domain_event_offsets
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                 FOR UPDATE
                """, offset, Long.class);
        if (lastSequence == null) {
            throw new IllegalStateException("SYS-EVT-009 offset is unavailable");
        }

        long incoming = event.sourceRevision();
        if (incoming <= lastSequence) {
            String priorDigest = priorDigest(event, incoming);
            if (event.eventDigest().equals(priorDigest)) {
                markDuplicate(event.envelope().id(), lastSequence);
                return new IngressResult(
                        IngressState.DUPLICATE, inbox.attemptCount(), null);
            }
            quarantine(event.envelope().id(),
                    "SYS-EVT-009 owner revision conflicts with accepted digest");
            return new IngressResult(
                    IngressState.QUARANTINED, inbox.attemptCount(),
                    "OWNER_REVISION_CONFLICT");
        }
        if (incoming > lastSequence + 1) {
            int attempt = inbox.attemptCount() + 1;
            boolean dead = attempt >= MAXIMUM_GAP_ATTEMPTS;
            jdbc.update("""
                    UPDATE sys_domain_event_inbox
                       SET status = :status,
                           attempt_count = :attempt,
                           available_at = CURRENT_TIMESTAMP + INTERVAL '5 seconds',
                           dead_lettered_at = CASE WHEN :dead
                               THEN CURRENT_TIMESTAMP ELSE NULL END,
                           last_error = :reason,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE consumer_name = :consumerName AND event_id = :eventId
                    """, new MapSqlParameterSource()
                    .addValue("status", dead ? "DEAD" : "DEFERRED")
                    .addValue("attempt", attempt)
                    .addValue("dead", dead)
                    .addValue("reason", "Waiting for aggregate sequence "
                            + (lastSequence + 1))
                    .addValue("consumerName",
                            HrisOperationalExceptionEventContract.CONSUMER)
                    .addValue("eventId", event.envelope().id()));
            return new IngressResult(
                    dead ? IngressState.DEAD_LETTER_REQUIRED
                            : IngressState.DEFERRED,
                    attempt, dead ? "SEQUENCE_GAP_EXHAUSTED" : "SEQUENCE_GAP");
        }

        MapSqlParameterSource projection = projectionParameters(event)
                .addValue("expectedSourceRevision", incoming - 1);
        int changed;
        if (incoming == 1) {
            changed = jdbc.update("""
                    INSERT INTO hris_operational_exception_projection (
                        tenant_id, exception_public_id, event_source, owner_service,
                        owner_module, source_ref, source_revision, exception_type,
                        severity, internal_state, code, summary_key, owner_route_key,
                        evidence_refs, correlation_id, event_digest,
                        first_observed_at, last_observed_at, occurrence_count, version)
                    VALUES (
                        :tenantId, :exceptionPublicId, :eventSource, :ownerService,
                        :ownerModule, :sourceRef, :sourceRevision, :exceptionType,
                        :severity, :internalState, :code, :summaryKey,
                        :ownerRouteKey,
                        string_to_array(:evidenceRefs, chr(31)), :correlationId,
                        :eventDigest, :occurredAt, :occurredAt, 1, 1)
                    ON CONFLICT (tenant_id, event_source, exception_public_id)
                    DO NOTHING
                    """, projection);
        } else {
            changed = jdbc.update("""
                    UPDATE hris_operational_exception_projection
                       SET source_ref = :sourceRef,
                           source_revision = :sourceRevision,
                           exception_type = :exceptionType,
                           severity = :severity,
                           internal_state = :internalState,
                           code = :code,
                           summary_key = :summaryKey,
                           owner_route_key = :ownerRouteKey,
                           evidence_refs = string_to_array(:evidenceRefs, chr(31)),
                           correlation_id = :correlationId,
                           event_digest = :eventDigest,
                           last_observed_at = :occurredAt,
                           occurrence_count = occurrence_count + 1,
                           version = version + 1
                     WHERE tenant_id = :tenantId
                       AND event_source = :eventSource
                       AND exception_public_id = :exceptionPublicId
                       AND source_revision = :expectedSourceRevision
                    """, projection);
        }
        if (changed != 1) {
            quarantine(event.envelope().id(),
                    "SYS-EVT-009 projection CAS conflict");
            return new IngressResult(
                    IngressState.QUARANTINED, inbox.attemptCount(),
                    "PROJECTION_CAS_CONFLICT");
        }
        int offsetChanged = jdbc.update("""
                UPDATE sys_domain_event_offsets
                   SET last_sequence = :aggregateSequence,
                       last_event_id = :eventId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND last_sequence = :expectedPredecessor
                """, offset
                .addValue("aggregateSequence", incoming)
                .addValue("eventId", event.envelope().id())
                .addValue("expectedPredecessor", incoming - 1));
        if (offsetChanged != 1) {
            throw new IllegalStateException(
                    "SYS-EVT-009 offset CAS changed during projection");
        }
        int inboxChanged = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'SUCCEEDED', processed_at = CURRENT_TIMESTAMP,
                       last_error = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("eventId", event.envelope().id()));
        if (inboxChanged != 1) {
            throw new IllegalStateException(
                    "SYS-EVT-009 inbox completion changed during projection");
        }
        return new IngressResult(
                IngressState.APPLIED, inbox.attemptCount(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectionPage list(
            long tenantId,
            Set<OwnerModule> ownerModules,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state,
            OffsetDateTime asOf,
            ProjectionPosition after,
            int limit) {
        bindTenant(tenantId);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ownerModules", ownerModules.stream()
                        .map(Enum::name).toList())
                .addValue("asOf", asOf)
                .addValue("limit", limit + 1);
        String cursor = "";
        String filters = "";
        if (ownerModule != null) {
            filters += " AND owner_module = :ownerModule";
            parameters.addValue("ownerModule", ownerModule.name());
        }
        if (severity != null) {
            filters += " AND severity = :severity";
            parameters.addValue("severity", severity.name());
        }
        if (state != null) {
            if (state == PublicState.OPEN) {
                filters += " AND internal_state IN ('OPEN', 'RECOVERING', 'SUPPRESSED')";
            } else {
                filters += " AND internal_state = :internalState";
                parameters.addValue("internalState", state.name());
            }
        }
        if (after != null) {
            cursor = """
                     AND (last_observed_at, exception_public_id)
                         < (:afterObservedAt, :afterExceptionId)
                    """;
            parameters.addValue("afterObservedAt", after.lastObservedAt())
                    .addValue("afterExceptionId", after.exceptionPublicId());
        }
        List<Projection> rows = jdbc.query("""
                SELECT tenant_id, exception_public_id, event_source, owner_service,
                       owner_module, source_ref, source_revision, exception_type,
                       severity, internal_state, code, summary_key, owner_route_key,
                       evidence_refs, correlation_id, event_digest,
                       first_observed_at, last_observed_at, occurrence_count, version
                  FROM hris_operational_exception_projection
                 WHERE tenant_id = :tenantId
                   AND owner_module IN (:ownerModules)
                   AND last_observed_at <= :asOf
                """ + filters + cursor + """
                 ORDER BY last_observed_at DESC, exception_public_id DESC
                 LIMIT :limit
                """, parameters, this::projection);
        boolean more = rows.size() > limit;
        List<Projection> page = more
                ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        Projection last = more ? page.getLast() : null;
        return new ProjectionPage(page, last == null ? null
                : new ProjectionPosition(
                last.lastObservedAt(), last.exceptionPublicId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Projection> find(
            long tenantId,
            Set<OwnerModule> ownerModules,
            UUID exceptionPublicId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, exception_public_id, event_source, owner_service,
                       owner_module, source_ref, source_revision, exception_type,
                       severity, internal_state, code, summary_key, owner_route_key,
                       evidence_refs, correlation_id, event_digest,
                       first_observed_at, last_observed_at, occurrence_count, version
                  FROM hris_operational_exception_projection
                 WHERE tenant_id = :tenantId
                   AND owner_module IN (:ownerModules)
                   AND exception_public_id = :exceptionPublicId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ownerModules", ownerModules.stream()
                        .map(Enum::name).toList())
                .addValue("exceptionPublicId", exceptionPublicId),
                this::projection).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectionSummary summary(long tenantId, OffsetDateTime asOf) {
        bindTenant(tenantId);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("asOf", asOf);
        SummaryCounts counts = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE internal_state IN
                           ('OPEN', 'RECOVERING', 'SUPPRESSED')) AS open_count,
                       COUNT(*) FILTER (WHERE internal_state = 'ACKNOWLEDGED')
                           AS acknowledged_count,
                       COUNT(*) FILTER (WHERE internal_state = 'RESOLVED')
                           AS resolved_count,
                       COUNT(*) FILTER (WHERE severity IN ('HIGH', 'CRITICAL'))
                           AS high_impact,
                       MAX(last_observed_at) AS last_refresh
                  FROM hris_operational_exception_projection
                 WHERE tenant_id = :tenantId AND last_observed_at <= :asOf
                """, parameters, (rs, row) -> new SummaryCounts(
                rs.getLong("total"), rs.getLong("open_count"),
                rs.getLong("acknowledged_count"), rs.getLong("resolved_count"),
                rs.getLong("high_impact"),
                rs.getObject("last_refresh", OffsetDateTime.class)));
        if (counts == null) throw new IllegalStateException(
                "SYS-002 projection summary is unavailable");
        EnumMap<OwnerModule, OffsetDateTime> latest =
                new EnumMap<>(OwnerModule.class);
        jdbc.query("""
                SELECT owner_module, MAX(last_observed_at) AS last_refresh
                  FROM hris_operational_exception_projection
                 WHERE tenant_id = :tenantId AND last_observed_at <= :asOf
                 GROUP BY owner_module
                """, parameters, (rs, row) -> Map.entry(
                OwnerModule.valueOf(rs.getString("owner_module")),
                rs.getObject("last_refresh", OffsetDateTime.class)))
                .forEach(entry -> latest.put(entry.getKey(), entry.getValue()));
        return new ProjectionSummary(
                asOf, counts.lastRefresh(), latest, counts.total(), counts.open(),
                counts.acknowledged(), counts.resolved(), counts.highImpact());
    }

    private InboxRow lockInbox(UUID eventId) {
        return jdbc.query("""
                SELECT status, payload_sha256, attempt_count
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("eventId", eventId),
                (rs, row) -> new InboxRow(
                        rs.getString("status"),
                        rs.getString("payload_sha256"),
                        rs.getInt("attempt_count")))
                .stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "SYS-EVT-009 inbox admission is unavailable"));
    }

    private String priorDigest(ValidatedEvent event, long revision) {
        List<String> values = jdbc.query("""
                SELECT payload_sha256
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND aggregate_sequence = :aggregateSequence
                   AND status IN ('SUCCEEDED', 'DUPLICATE')
                 ORDER BY processed_at NULLS LAST, created_at
                 LIMIT 1
                """, offsetParameters(event).addValue("aggregateSequence", revision),
                (rs, row) -> rs.getString("payload_sha256"));
        return values.isEmpty() ? null : values.getFirst();
    }

    private void markDuplicate(UUID eventId, long lastSequence) {
        jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DUPLICATE',
                       last_error = :reason,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("reason", "Aggregate sequence already applied: "
                        + lastSequence)
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("eventId", eventId));
    }

    private void quarantine(UUID eventId, String reason) {
        jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DEAD', dead_lettered_at = CURRENT_TIMESTAMP,
                       last_error = :reason, updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("reason", reason)
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("eventId", eventId));
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', :tenant, true)",
                new MapSqlParameterSource("tenant", Long.toString(tenantId)),
                String.class);
    }

    private MapSqlParameterSource eventParameters(ValidatedEvent event) {
        return new MapSqlParameterSource()
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("eventId", event.envelope().id())
                .addValue("eventSource", event.envelope().source())
                .addValue("eventType", event.envelope().type())
                .addValue("tenantId", event.envelope().tenantId())
                .addValue("aggregateType", event.envelope().aggregateType())
                .addValue("aggregateId", event.envelope().aggregateId())
                .addValue("aggregateSequence", event.envelope().aggregateSequence())
                .addValue("correlationId", event.envelope().correlationId())
                .addValue("causationId", event.envelope().causationId())
                .addValue("traceParent", event.envelope().traceParent())
                .addValue("payload", event.canonicalPayload())
                .addValue("payloadHash", event.eventDigest());
    }

    private MapSqlParameterSource offsetParameters(ValidatedEvent event) {
        return new MapSqlParameterSource()
                .addValue("consumerName",
                        HrisOperationalExceptionEventContract.CONSUMER)
                .addValue("tenantId", event.envelope().tenantId())
                .addValue("eventSource", event.envelope().source())
                .addValue("aggregateType", event.envelope().aggregateType())
                .addValue("aggregateId", event.envelope().aggregateId());
    }

    private MapSqlParameterSource projectionParameters(ValidatedEvent event) {
        return new MapSqlParameterSource()
                .addValue("tenantId", event.envelope().tenantId())
                .addValue("exceptionPublicId", event.exceptionPublicId())
                .addValue("eventSource", event.envelope().source())
                .addValue("ownerService", event.ownerService())
                .addValue("ownerModule", event.ownerModule().name())
                .addValue("sourceRef", event.sourceRef())
                .addValue("sourceRevision", event.sourceRevision())
                .addValue("exceptionType", event.exceptionType())
                .addValue("severity", event.severity().name())
                .addValue("internalState", event.internalState().name())
                .addValue("code", event.code())
                .addValue("summaryKey", event.summaryKey())
                .addValue("ownerRouteKey", event.ownerRouteKey())
                .addValue("evidenceRefs", String.join("\u001f", event.evidenceRefs()))
                .addValue("correlationId", event.correlationId())
                .addValue("eventDigest", event.eventDigest())
                .addValue("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC));
    }

    private Projection projection(ResultSet rs, int row) throws SQLException {
        return new Projection(
                rs.getLong("tenant_id"),
                rs.getObject("exception_public_id", UUID.class),
                rs.getString("event_source"),
                rs.getString("owner_service"),
                OwnerModule.valueOf(rs.getString("owner_module")),
                rs.getString("source_ref"),
                rs.getLong("source_revision"),
                rs.getString("exception_type"),
                Severity.valueOf(rs.getString("severity")),
                InternalState.valueOf(rs.getString("internal_state")),
                rs.getString("code"),
                rs.getString("summary_key"),
                rs.getString("owner_route_key"),
                strings(rs.getArray("evidence_refs")),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("event_digest"),
                rs.getObject("first_observed_at", OffsetDateTime.class),
                rs.getObject("last_observed_at", OffsetDateTime.class),
                rs.getLong("occurrence_count"),
                rs.getLong("version"));
    }

    private List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (value instanceof String[] strings) return List.of(strings);
        Object[] values = (Object[]) value;
        List<String> result = new ArrayList<>();
        for (Object item : values) result.add(String.valueOf(item));
        return List.copyOf(result);
    }

    private record Admission(IngressResult result) {
    }

    private record InboxRow(String status, String payloadHash, int attemptCount) {
    }

    private record SummaryCounts(
            long total,
            long open,
            long acknowledged,
            long resolved,
            long highImpact,
            OffsetDateTime lastRefresh) {
    }
}
