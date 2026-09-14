package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable escalation producer, not auto-approval or an in-memory timer. Scheduler wiring is owner-controlled. */
public final class ApprovalWorkflowQuorumSlaRuntime {
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final ApprovalWorkflowQuorumEvidence evidence;
    private final ApprovalWorkflowQuorumAuthority authority;
    private final TransactionTemplate transactions;
    private final com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard live;
    private java.util.function.Function<Lease, ProducerAuthority> producer;

    public record Lease(UUID timerId, long tenantId, UUID requestId, UUID stepId, long generation,
            long policyVersion, String kind, long epoch, String owner, Instant until) { }
    public record ProducerAuthority(List<Long> recipients, String authorityRevision, Instant expiresAt, Runnable requireCurrent,
            java.util.function.Consumer<UUID> originalWitness) {
        public ProducerAuthority(List<Long> recipients, String revision, Instant expiry, Runnable current) { this(recipients,revision,expiry,current,null); }
        public ProducerAuthority {
            recipients = List.copyOf(recipients);
            if (recipients.isEmpty() || recipients.size() > 1000 || !revision(authorityRevision) || expiresAt == null || requireCurrent == null)
                throw unavailable("Current sealed SLA recipients are required.");
            long previous = 0; for (Long user : recipients) { if (user == null || user <= previous || user > 9007199254740991L) throw invalid("Exact sorted SLA recipients are required."); previous = user; }
        }
    }
    void bindProducer(java.util.function.Function<Lease, ProducerAuthority> producer) { this.producer = java.util.Objects.requireNonNull(producer); }

    public ApprovalWorkflowQuorumSlaRuntime(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            TransactionTemplate transactions, ApprovalWorkflowQuorumAuthority authority, AuditOutboxRecorder audit) {
        this(jdbc,mapper,transactions,authority,audit,false);
    }
    public ApprovalWorkflowQuorumSlaRuntime(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,TransactionTemplate transactions,AuditOutboxRecorder audit) {
        this(jdbc,mapper,transactions,null,audit,true);
    }
    private ApprovalWorkflowQuorumSlaRuntime(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,TransactionTemplate transactions,
            ApprovalWorkflowQuorumAuthority authority,AuditOutboxRecorder audit,boolean dedicated) {
        store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
        evidence = new ApprovalWorkflowQuorumEvidence(store, audit);
        this.transactions = transactions;
        this.authority = dedicated ? null : java.util.Objects.requireNonNull(authority);
        live = new com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard(jdbc);
    }

    public List<Lease> claim(String owner, int leaseSeconds, int limit) {
        if (authority==null && producer==null) throw unavailable("The dedicated SYSTEM_SLA producer is unavailable.");
        if (owner == null || !owner.matches("[A-Za-z0-9._:-]{1,160}") || leaseSeconds < 1 || leaseSeconds > 300
                || limit < 1 || limit > 100) throw invalid("A bounded worker lease is required.");
        return transactions.execute(status -> {
            var parameters = new MapSqlParameterSource().addValue("limit",limit);
            var requests = store.jdbc.queryForList("""
                    SELECT request.tenant_id,request.request_id FROM apr_requests request
                     WHERE request.status='IN_REVIEW' AND request.deleted_at IS NULL
                       AND EXISTS(SELECT 1 FROM apr_tenants tenant WHERE tenant.tenant_id=request.tenant_id AND tenant.lifecycle_state='ACTIVE')
                       AND EXISTS(SELECT 1 FROM apr_quorum_sla_timers timer JOIN apr_quorum_stage_runtime stage
                         ON stage.tenant_id=timer.tenant_id AND stage.request_id=timer.request_id AND stage.step_id=timer.step_id
                          AND stage.generation=timer.generation AND stage.status='IN_PROGRESS'
                        WHERE timer.tenant_id=request.tenant_id AND timer.request_id=request.request_id
                          AND stage.generation=(SELECT MAX(generation) FROM apr_quorum_stage_runtime WHERE tenant_id=request.tenant_id AND request_id=request.request_id)
                          AND timer.due_at<=clock_timestamp() AND (timer.status='PENDING' OR timer.status='CLAIMED' AND timer.lease_until<=clock_timestamp()))
                       AND %s ORDER BY request.tenant_id,request.request_id FOR UPDATE OF request SKIP LOCKED LIMIT :limit
                    """.formatted(com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard.LIVE),parameters);
            var leases = new java.util.ArrayList<Lease>();
            for (var request : requests) {
                long tenant = ((Number) request.get("tenant_id")).longValue(); UUID id = (UUID) request.get("request_id");
                live.writeRequest(tenant,id);
                if (store.jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE",store.scope(tenant,id)).isEmpty()) continue;
                var stages = store.stages(tenant,id,true); long generation = stages.stream().mapToLong(StageRow::generation).max().orElse(0);
                for (var stage : stages) {
                    if (stage.generation()!=generation || !"IN_PROGRESS".equals(stage.status()) || leases.size()>=limit) continue;
                    var p = store.scope(tenant,id).addValue("step",stage.stepId()).addValue("generation",generation)
                            .addValue("owner",owner).addValue("seconds",leaseSeconds).addValue("limit",limit-leases.size());
                    leases.addAll(store.jdbc.query("""
                            WITH ready AS (SELECT timer_id FROM apr_quorum_sla_timers WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step
                              AND generation=:generation AND due_at<=clock_timestamp() AND (status='PENDING' OR status='CLAIMED' AND lease_until<=clock_timestamp())
                              ORDER BY due_at,timer_id FOR UPDATE SKIP LOCKED LIMIT :limit)
                            UPDATE apr_quorum_sla_timers timer SET status='CLAIMED',lease_epoch=lease_epoch+1,version=version+1,
                              lease_owner=:owner,lease_until=clock_timestamp()+make_interval(secs=>:seconds) FROM ready WHERE timer.timer_id=ready.timer_id RETURNING timer.*
                            """,p,(rs,index) -> new Lease(rs.getObject("timer_id",UUID.class),tenant,id,stage.stepId(),generation,
                                    rs.getLong("policy_version"),rs.getString("kind"),rs.getLong("lease_epoch"),rs.getString("lease_owner"),rs.getTimestamp("lease_until").toInstant())));
                }
            }
            return List.copyOf(leases);
        });
    }

    public boolean finish(Lease lease) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            live.writeRequest(lease.tenantId(),lease.requestId());
            var p = store.scope(lease.tenantId(), lease.requestId()).addValue("timer", lease.timerId())
                    .addValue("step", lease.stepId()).addValue("generation", lease.generation())
                    .addValue("epoch", lease.epoch()).addValue("owner", lease.owner());
            if (store.jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant "
                    + "AND lifecycle_state='ACTIVE' FOR SHARE", p).isEmpty()) return false;
            var requests = store.jdbc.queryForList("SELECT status FROM apr_requests WHERE tenant_id=:tenant "
                    + "AND request_id=:request FOR UPDATE", p);
            if (requests.size() != 1 || !"IN_REVIEW".equals(requests.getFirst().get("status"))) return false;
            var stage = store.stages(lease.tenantId(), lease.requestId(), true).stream()
                    .filter(row -> row.stepId().equals(lease.stepId()) && row.generation() == lease.generation()).findFirst();
            var timers = store.jdbc.queryForList("SELECT * FROM apr_quorum_sla_timers WHERE timer_id=:timer "
                    + "AND tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation FOR UPDATE", p);
            Instant now = store.now();
            if (stage.isEmpty() || !"IN_PROGRESS".equals(stage.get().status()) || timers.size() != 1) return false;
            var timer = timers.getFirst();
            if (!"CLAIMED".equals(timer.get("status")) || ((Number) timer.get("lease_epoch")).longValue() != lease.epoch()
                    || !lease.owner().equals(timer.get("lease_owner")) || !((java.sql.Timestamp) timer.get("lease_until")).toInstant().isAfter(now)
                    || ((Number) timer.get("policy_version")).longValue() != lease.policyVersion()
                    || !lease.kind().equals(timer.get("kind"))) return false;
            StageRow row = stage.get();
            store.verify(row.context(), lease.tenantId(), lease.requestId(), row.definition());
            var definition = ApprovalWorkflowQuorumDefinition.compile(row.definition()).stages().stream()
                    .filter(value -> value.key().equals(row.key())).findFirst().orElseThrow();
            if (producer != null) {
                var proof = producer.apply(lease);
                if (!proof.expiresAt().isAfter(store.now()) || !row.snapshot().candidates().stream().map(Candidate::userId).toList().containsAll(proof.recipients()))
                    throw unavailable("Current sealed SLA recipients changed.");
                proof.requireCurrent().run();
                return complete(lease,row,p,proof.recipients(),proof.authorityRevision(),proof.expiresAt(),proof.requireCurrent(),proof.originalWitness(),now);
            }
            if (authority==null) throw unavailable("The dedicated SYSTEM_SLA producer is unavailable.");
            CandidatePool pool = authority.candidates(row.context().pins(), lease.requestId(), definition, store.now());
            var eligible = new ApprovalWorkflowQuorumEvaluator().eligibleCandidates(row.context().pins(), definition.candidateRole(), pool,
                    row.context().requesterUserId(), row.context().requesterPersonId(), store.now());
            List<Long> recipients = row.snapshot().candidates().stream().filter(eligible::contains).map(Candidate::userId).toList();
            if (recipients.isEmpty()) throw unavailable("No currently authorized frozen escalation recipient exists.");
            if (!pool.expiresAt().isAfter(store.now())) throw unavailable("Escalation candidate authority expired.");
            return complete(lease,row,p,recipients,pool.authorityRevision(),pool.expiresAt(),null,null,now);
        }));
    }
    private boolean complete(Lease lease,StageRow row,MapSqlParameterSource p,List<Long> recipients,String revision,Instant expires,Runnable current,
            java.util.function.Consumer<UUID> originalWitness,Instant now) {
            if (!expires.isAfter(store.now())) throw unavailable("Escalation candidate authority expired.");
            UUID event = evidence.append(lease.tenantId(), lease.requestId(), null,
                    "WARNING".equals(lease.kind()) ? "Approval.Quorum.SlaWarning" : "Approval.Quorum.SlaBreached",
                    ApprovalWorkflowQuorumSlaEvent.payload(store, lease.requestId(), lease.timerId(), lease.epoch(), row,
                            recipients, revision, now));
            if (originalWitness!=null) originalWitness.accept(event);
            if (current!=null) current.run(); if (!expires.isAfter(store.now())) throw unavailable("Escalation candidate authority expired.");
            p.addValue("event", event);
            changed(store.jdbc.update("""
                    UPDATE apr_quorum_sla_timers SET status='COMPLETED',version=version+1,event_id=:event,
                        lease_owner=NULL,lease_until=NULL
                     WHERE timer_id=:timer AND tenant_id=:tenant AND request_id=:request AND step_id=:step
                       AND generation=:generation AND status='CLAIMED' AND lease_owner=:owner AND lease_epoch=:epoch
                       AND lease_until>clock_timestamp()
                    """, p));
            return true;
    }
}
