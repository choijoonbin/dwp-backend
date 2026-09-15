package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

final class SignatureProviderDiagnosticsRepository {
    private final SignatureProviderPersistence persistence;

    SignatureProviderDiagnosticsRepository(SignatureProviderPersistence persistence) {
        this.persistence = persistence;
    }

    void saveProviderProbe(SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.SourceSnapshot source, ProbeInput input,
            ProbeRun run) {
        insertRun(current, SignatureProviderOperation.PROBE, source, input,
                run.probeRunId(), run.state(), run.startedAt(), run.completedAt(),
                run.originalTargets(), run);
        for (ProbeResult result : run.providerResults()) {
            Check evidence = result.checks().stream().filter(check -> check.evidenceId() != null)
                    .findFirst().orElse(null);
            persistence.jdbc().update("""
                    INSERT INTO apr_signature_provider_probe_observations(
                        tenant_id,resource_set_key,probe_run_id,provider_id,provider_version,
                        provider_sha256,result,evidence_id,evidence_sha256,observed_at)
                    VALUES(:tenant,:scope,:run,:provider,:version,:sha,CAST(:result AS jsonb),
                        :evidence,:evidenceSha,:observed)
                    """, persistence.base(current).addValue("run", run.probeRunId())
                    .addValue("provider", result.providerId())
                    .addValue("version", result.originalTarget().expectedProviderVersion())
                    .addValue("sha", result.originalTarget().expectedProviderSha256())
                    .addValue("result", persistence.canonical().json(result))
                    .addValue("evidence", evidence == null ? null : evidence.evidenceId())
                    .addValue("evidenceSha", evidence == null ? null : evidence.evidenceSha256())
                    .addValue("observed", result.observedAt() == null ? null : Timestamp.from(result.observedAt())));
        }
    }

    void saveKms(SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.SourceSnapshot source, KmsProbeInput input,
            UUID runId, Instant started, Kms result) {
        insertRun(current, SignatureProviderOperation.KMS_PROBE, source, input, runId,
                ProbeState.COMPLETE, started, persistence.clock().instant(), List.of(input.target()), result);
        saveInspection(current, runId, input.target(), "KMS", result,
                result.evidenceId(), result.evidenceSha256(), result.checkedAt());
    }

    void saveWorm(SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.SourceSnapshot source, WormInspectionInput input,
            UUID runId, Instant started, Worm result) {
        insertRun(current, SignatureProviderOperation.WORM_INSPECTION, source, input, runId,
                ProbeState.COMPLETE, started, persistence.clock().instant(), List.of(input.target()), result);
        saveInspection(current, runId, input.target(), "WORM", result,
                result.evidenceId(), result.evidenceSha256(), result.checkedAt());
    }

    Kms latestKms(SignatureProviderCurrentAuthority.Current current,
                  List<SignatureProviderPersistence.Registration> registrations) {
        return latestInspection(current, "KMS", Kms.class, registrations);
    }

    Worm latestWorm(SignatureProviderCurrentAuthority.Current current,
                    List<SignatureProviderPersistence.Registration> registrations) {
        return latestInspection(current, "WORM", Worm.class, registrations);
    }

    Map<UUID, ProbeResult> latestProviderResults(SignatureProviderCurrentAuthority.Current current,
            List<SignatureProviderPersistence.Registration> registrations) {
        Map<UUID, SignatureProviderPersistence.Registration> expected = new HashMap<>();
        registrations.forEach(item -> expected.put(item.id(), item));
        var rows = persistence.jdbc().query("""
                SELECT DISTINCT ON(provider_id) provider_id,provider_version,provider_sha256,result::text
                  FROM apr_signature_provider_probe_observations
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY provider_id,observed_at DESC NULLS LAST,probe_run_id DESC
                """, persistence.base(current), (row, number) -> {
            UUID id = row.getObject("provider_id", UUID.class);
            var registration = expected.get(id);
            if (registration == null || registration.version() != row.getLong("provider_version")
                    || !registration.sha256().equals(row.getString("provider_sha256"))) return null;
            return persistence.canonical().read(row.getString("result"), ProbeResult.class);
        });
        var result = new HashMap<UUID, ProbeResult>();
        rows.stream().filter(Objects::nonNull).forEach(value -> result.put(value.providerId(), value));
        return Map.copyOf(result);
    }

    History history(SignatureProviderCurrentAuthority.Current current, Long beforeEpochMillis) {
        if (beforeEpochMillis != null && beforeEpochMillis < 0) throw SignatureProviderErrors.invalid();
        var rows = persistence.jdbc().query("""
                SELECT r.probe_run_id,r.source_revision,r.source_sha256,r.state,r.completed_at,
                       o.provider_id,o.result::text,o.evidence_id,o.evidence_sha256
                  FROM apr_signature_provider_probe_runs r
                  LEFT JOIN apr_signature_provider_probe_observations o
                    ON o.tenant_id=r.tenant_id AND o.resource_set_key=r.resource_set_key
                   AND o.probe_run_id=r.probe_run_id
                 WHERE r.tenant_id=:tenant AND r.resource_set_key=:scope
                   AND (CAST(:before AS bigint) IS NULL
                        OR extract(epoch FROM r.completed_at)*1000<:before)
                 ORDER BY r.completed_at DESC,r.probe_run_id DESC,o.provider_id LIMIT 51
                """, persistence.base(current).addValue("before", beforeEpochMillis), (row, number) -> {
            String state = row.getString("state");
            UUID provider = row.getObject("provider_id", UUID.class);
            List<String> reasons = row.getString("result") == null ? List.of()
                    : persistence.canonical().read(row.getString("result"), ProbeResult.class).reasonCodes();
            return new HistoryItem(row.getObject("probe_run_id", UUID.class), provider,
                    row.getString("source_revision"), row.getString("source_sha256"),
                    ProbeState.valueOf(state), row.getTimestamp("completed_at").toInstant(), reasons,
                    row.getObject("evidence_id", UUID.class), row.getString("evidence_sha256"));
        });
        boolean truncated = rows.size() > MAX_HISTORY;
        List<HistoryItem> items = List.copyOf(rows.subList(0, Math.min(MAX_HISTORY, rows.size())));
        String next = truncated ? Long.toString(items.getLast().occurredAt().toEpochMilli()) : null;
        return new History(persistence.source(current, false).scope(current, persistence.clock().instant()),
                items, next, truncated);
    }

    private void insertRun(SignatureProviderCurrentAuthority.Current current,
            SignatureProviderOperation operation, SignatureProviderPersistence.SourceSnapshot source,
            Object input, UUID runId, ProbeState state, Instant started, Instant completed,
            List<ProviderTarget> targets, Object result) {
        int inserted = persistence.jdbc().update("""
                INSERT INTO apr_signature_provider_probe_runs(
                    tenant_id,resource_set_key,probe_run_id,operation,actor_user_id,state,
                    source_revision,source_sha256,body_sha256,original_targets,result,started_at,completed_at)
                VALUES(:tenant,:scope,:run,:operation,:actor,:state,:sourceRevision,:sourceSha,:bodySha,
                    CAST(:targets AS jsonb),CAST(:result AS jsonb),:started,:completed)
                """, persistence.base(current).addValue("run", runId).addValue("operation", operation.name())
                .addValue("actor", current.actor().userId()).addValue("state", state.name())
                .addValue("sourceRevision", source.revision()).addValue("sourceSha", source.sha256())
                .addValue("bodySha", persistence.canonical().digest(input))
                .addValue("targets", persistence.canonical().json(targets))
                .addValue("result", persistence.canonical().json(result))
                .addValue("started", Timestamp.from(started)).addValue("completed", Timestamp.from(completed)));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
    }

    private void saveInspection(SignatureProviderCurrentAuthority.Current current, UUID run,
            ProviderTarget target, String kind, Object result, UUID evidence, String evidenceSha, Instant observed) {
        int inserted = persistence.jdbc().update("""
                INSERT INTO apr_signature_provider_inspections(
                    tenant_id,resource_set_key,probe_run_id,provider_id,provider_version,
                    provider_sha256,inspection_kind,result,evidence_id,evidence_sha256,observed_at)
                VALUES(:tenant,:scope,:run,:provider,:version,:sha,:kind,CAST(:result AS jsonb),
                    :evidence,:evidenceSha,:observed)
                """, persistence.base(current).addValue("run", run).addValue("provider", target.providerId())
                .addValue("version", target.expectedProviderVersion()).addValue("sha", target.expectedProviderSha256())
                .addValue("kind", kind).addValue("result", persistence.canonical().json(result))
                .addValue("evidence", evidence).addValue("evidenceSha", evidenceSha)
                .addValue("observed", observed == null ? null : Timestamp.from(observed)));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
    }

    private <T> T latestInspection(SignatureProviderCurrentAuthority.Current current, String kind,
            Class<T> type, List<SignatureProviderPersistence.Registration> registrations) {
        Map<UUID, SignatureProviderPersistence.Registration> expected = new HashMap<>();
        registrations.forEach(item -> expected.put(item.id(), item));
        var rows = persistence.jdbc().query("""
                SELECT provider_id,provider_version,provider_sha256,result::text
                  FROM apr_signature_provider_inspections
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND inspection_kind=:kind
                 ORDER BY observed_at DESC NULLS LAST,probe_run_id DESC LIMIT 1
                """, persistence.base(current).addValue("kind", kind), (row, number) -> {
            var registration = expected.get(row.getObject("provider_id", UUID.class));
            if (registration == null || registration.version() != row.getLong("provider_version")
                    || !registration.sha256().equals(row.getString("provider_sha256"))) return null;
            return persistence.canonical().read(row.getString("result"), type);
        });
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
