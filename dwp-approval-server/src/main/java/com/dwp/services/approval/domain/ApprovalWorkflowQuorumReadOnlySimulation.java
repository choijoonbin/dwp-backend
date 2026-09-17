package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-only preflight of a bound draft, using database canonical pins and current candidate authority. */
public final class ApprovalWorkflowQuorumReadOnlySimulation {
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final TransactionTemplate readOnly;
    private final ApprovalWorkflowQuorumAuthority authority;

    public ApprovalWorkflowQuorumReadOnlySimulation(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            TransactionTemplate transactions, ApprovalWorkflowQuorumAuthority authority) {
        store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
        readOnly = new TransactionTemplate(java.util.Objects.requireNonNull(transactions.getTransactionManager()));
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.authority = java.util.Objects.requireNonNull(authority);
    }

    public ApprovalWorkflowQuorumSimulation.Result simulate(long tenant, UUID request, ApprovalWorkflowQuorumSimulation.Input input) {
        return simulate(tenant, request, input, () -> { });
    }

    ApprovalWorkflowQuorumSimulation.Result simulate(long tenant, UUID request, ApprovalWorkflowQuorumSimulation.Input input,
            Runnable ownerValidation) {
        return evaluate(tenant, request, input, ownerValidation);
    }

    ApprovalWorkflowQuorumSimulation.Result preflight(long tenant, UUID request, Runnable ownerValidation) {
        return evaluate(tenant, request, null, ownerValidation);
    }

    private ApprovalWorkflowQuorumSimulation.Result evaluate(long tenant, UUID request,
            ApprovalWorkflowQuorumSimulation.Input suppliedInput, Runnable ownerValidation) {
        return readOnly.execute(status -> {
            ownerValidation.run();
            var p = store.scope(tenant, request);
            String rawDefinition = store.jdbc.queryForObject("""
                    SELECT workflow.definition::text FROM apr_requests request
                      JOIN apr_tenants tenant ON tenant.tenant_id=request.tenant_id AND tenant.lifecycle_state='ACTIVE'
                      JOIN apr_workflow_versions workflow ON workflow.tenant_id=request.tenant_id
                       AND workflow.workflow_version_id=request.workflow_version_id
                     WHERE request.tenant_id=:tenant AND request.request_id=:request AND request.status='DRAFT'
                    """, p, String.class);
            var definition = ApprovalWorkflowQuorumDefinition.compile(rawDefinition);
            Context context = store.context(tenant, request, definition, store.policy(tenant, request, false), false);
            var input = suppliedInput == null
                    ? new ApprovalWorkflowQuorumSimulation.Input(context.pins(), context.requesterUserId(),
                            context.requesterPersonId(), context.payloadRevision(), context.payloadSha256(), List.of())
                    : suppliedInput;
            if (!context.pins().equals(input.expectedPins()) || context.requesterUserId() != input.requesterUserId()
                    || !context.requesterPersonId().equals(input.requesterPersonPublicId())
                    || context.payloadRevision() != input.payloadRevision()
                    || !context.payloadSha256().equals(input.payloadSha256())) throw conflict();
            Map<String, CandidatePool> pools = new HashMap<>();
            Instant now = store.now();
            var resolver = new ApprovalWorkflowQuorumSimulation.AuthorityResolver() {
                @Override public boolean condition(Pins pins, ApprovalWorkflowQuorumDefinition.Stage stage, Instant at) {
                    if (stage.routeCondition() == null) return true;
                    var rows = store.jdbc.queryForList("""
                            SELECT form.schema_payload::text AS schema,payload.payload::text AS payload
                              FROM apr_requests request JOIN apr_form_versions form ON form.tenant_id=request.tenant_id
                               AND form.form_version_id=request.form_version_id
                              JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                             WHERE request.tenant_id=:tenant AND request.request_id=:request
                            """, p);
                    if (rows.size() != 1) throw conflict();
                    var row = rows.getFirst();
                    return ApprovalWorkflowStageCondition.matches((String) row.get("schema"),
                            store.object((String) row.get("payload")), stage.routeCondition());
                }
                @Override public CandidatePool candidates(Pins pins, ApprovalWorkflowQuorumDefinition.Stage stage, Instant at) {
                    CandidatePool pool = authority.candidates(pins, request, stage, at);
                    pools.put(stage.key(), pool);
                    return pool;
                }
                @Override public CurrentAuthority voter(Pins pins, ApprovalWorkflowQuorumDefinition.Stage stage,
                        long actor, long principal, Instant at) {
                    CandidatePool pool = pools.get(stage.key());
                    if (pool == null) return null;
                    var candidates = new ApprovalWorkflowQuorumEvaluator().eligibleCandidates(pins, stage.candidateRole(), pool,
                            context.requesterUserId(), context.requesterPersonId(), at);
                    Snapshot snapshot = new Snapshot(pins, request, UUID.nameUUIDFromBytes(
                            (request + ":simulation:" + stage.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1,
                            context.requesterUserId(), context.requesterPersonId(), context.payloadRevision(), context.payloadSha256(),
                            context.rejectLength(), stage.candidateRole(), stage.quorum(), candidates, pool.authorityRevision(), at);
                    CurrentAuthority current = authority.voter(snapshot, actor, principal, at);
                    if (current != null && (current.actor().userId() != actor || current.principal().userId() != principal)) {
                        throw unavailable("Simulation owner authority identified a different voter.");
                    }
                    return current;
                }
            };
            return new ApprovalWorkflowQuorumSimulation().simulate(definition, context.pins(), context.rejectLength(), input, resolver, now);
        });
    }
}
