package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** UNKNOWN is returned, not thrown in the transaction; the API must translate it after the commit. */
public final class ApprovalWorkflowQuorumInformationRuntime {
    @FunctionalInterface
    public interface RouteGuard {
        boolean matches(String condition, Map<String, Object> payload, String immutableSchema);
    }
    public record RequestCommand(UUID requestId, UUID taskId, long expectedTaskVersion, long expectedRequestVersion,
            ApprovalWorkflowQuorumExpectedVoteView expectedQuorum, String idempotencyKey, String reason, String rawBodySha256) {
        public RequestCommand(UUID requestId, UUID taskId, long expectedTaskVersion, long expectedRequestVersion,
                ApprovalWorkflowQuorumExpectedVoteView expectedQuorum, String idempotencyKey, String reason) {
            this(requestId, taskId, expectedTaskVersion, expectedRequestVersion, expectedQuorum, idempotencyKey, reason, null);
        }
    }
    public record ReplyCommand(UUID requestId, long expectedRequestVersion, long sourceGeneration,
            String idempotencyKey, String message, Map<String, Object> patch, String rawBodySha256) {
        public ReplyCommand(UUID requestId, long expectedRequestVersion, long sourceGeneration,
                String idempotencyKey, String message, Map<String, Object> patch) {
            this(requestId, expectedRequestVersion, sourceGeneration, idempotencyKey, message, patch, null);
        }
    }
    public record Receipt(String status, UUID roundId, long generation, long requestVersion,
            int payloadRevision, String payloadSha256, boolean materialChange) { }
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final TransactionTemplate transactions;
    private final TransactionTemplate business;
    private final ApprovalWorkflowQuorumRuntime runtime;
    private final ApprovalWorkflowQuorumEvidence evidence;
    private final RouteGuard routes;
    private final ApprovalWorkflowQuorumEvaluator evaluator = new ApprovalWorkflowQuorumEvaluator();

    public ApprovalWorkflowQuorumInformationRuntime(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            TransactionTemplate transactions, ApprovalWorkflowQuorumAuthority authority, AuditOutboxRecorder audit) {
        this(jdbc, mapper, transactions, authority, audit, (condition, payload, schema) -> {
            Boolean matched = ApprovalWorkflowTypedRouteCondition.match(schema, condition, payload);
            if (matched == null) throw unavailable("The original legacy conditional route evaluator is unavailable.");
            return matched;
        });
    }

    public ApprovalWorkflowQuorumInformationRuntime(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            TransactionTemplate transactions, ApprovalWorkflowQuorumAuthority authority, AuditOutboxRecorder audit, RouteGuard routes) {
        this.store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper); this.transactions = transactions;
        this.routes = java.util.Objects.requireNonNull(routes);
        business = new TransactionTemplate(transactions.getTransactionManager());
        business.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        this.runtime = new ApprovalWorkflowQuorumRuntime(jdbc, mapper, business, authority, audit);
        this.evidence = new ApprovalWorkflowQuorumEvidence(store, audit);
    }

    public Receipt request(Actor actor, RequestCommand command) {
        return request(actor, command, () -> { });
    }

    public Receipt request(Actor actor, RequestCommand command, Runnable currentOwnerGuard) {
        return request(actor, command, currentOwnerGuard, receipt -> { });
    }

    public Receipt request(Actor actor, RequestCommand command, Runnable currentOwnerGuard, Consumer<Receipt> onCompleted) {
        java.util.Objects.requireNonNull(onCompleted);
        if (command == null || command.expectedQuorum() == null || command.taskId() == null
                || command.expectedTaskVersion() < 0 || command.expectedRequestVersion() < 0
                || (command.expectedQuorum().expectedRequestVersion() != null
                    && command.expectedQuorum().expectedRequestVersion() != command.expectedRequestVersion())) throw conflict();
        raw(command.rawBodySha256());
        text(command.reason()); key(command.idempotencyKey());
        return transactions.execute(transaction -> {
            var p = lock(actor, command.requestId());
            String digest = digest("REQUEST_INFO", actor, command);
            var replay = replay(p, command.idempotencyKey(), "REQUEST_INFO", digest);
            if (replay != null) return verifiedReplay(actor, command.requestId(), replay);
            var bound = ApprovalWorkflowQuorumInformationContext.require(store, actor, command.requestId(), "IN_REVIEW");
            var expected = command.expectedQuorum();
            if (bound.requestVersion() != command.expectedRequestVersion() || !bound.context().pins().equals(expected.pins())
                    || bound.context().payloadRevision() != expected.payloadRevision()
                    || !bound.context().payloadSha256().equals(expected.payloadSha256())) throw conflict();
            var stages = store.stages(actor.tenantId(), command.requestId(), true);
            var identities = store.jdbc.queryForList("SELECT step_id FROM apr_quorum_candidates WHERE tenant_id=:tenant AND request_id=:request "
                    + "AND generation=:generation AND task_id=:task", p.addValue("generation", expected.generation()).addValue("task", command.taskId()));
            if (identities.size() != 1) throw conflict();
            var stage = stages.stream().filter(row -> row.generation() == expected.generation()
                    && row.stepId().equals(identities.getFirst().get("step_id"))).findFirst().orElseThrow(ApprovalWorkflowQuorumRuntimeStore::conflict);
            if (!"IN_PROGRESS".equals(stage.status()) || stage.version() != expected.stageVersion()) throw conflict();
            var seats = store.jdbc.queryForList("""
                    SELECT candidate.principal_user_id,candidate.principal_person_id,task.status,task.version,
                           task.assignee_user_id,task.assignee_person_public_id
                      FROM apr_quorum_candidates candidate JOIN apr_tasks task ON task.tenant_id=candidate.tenant_id
                       AND task.request_id=candidate.request_id AND task.step_id=candidate.step_id AND task.task_id=candidate.task_id
                     WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.task_id=:task
                       AND candidate.step_id=:step AND candidate.generation=:generation FOR UPDATE OF task
                    """, p.addValue("step", stage.stepId()).addValue("generation", stage.generation()).addValue("task", command.taskId()));
            if (seats.size() != 1) throw conflict();
            var seat = seats.getFirst(); long principal = ((Number) seat.get("principal_user_id")).longValue();
            if (!"CLAIMED".equals(seat.get("status")) || ((Number) seat.get("version")).longValue() != command.expectedTaskVersion()
                    || !seat.get("assignee_user_id").equals(seat.get("principal_user_id"))
                    || !seat.get("assignee_person_public_id").equals(seat.get("principal_person_id"))) throw conflict();
            CurrentAuthority current;
            try {
                currentOwnerGuard.run(); route(bound, bound.payload());
                runtime.revalidate(actor.tenantId(), command.requestId(), stages, true);
                current = runtime.current(stage.snapshot(), actor.userId(), principal, store.now(),ApprovalWorkflowRuntimeTarget.Use.CAST,command.taskId(),null);
                evaluator.verifyAuthority(stage.snapshot(), current, store.now());
                if (!actor.personPublicId().equals(current.actor().personPublicId())) throw new BaseException(ErrorCode.FORBIDDEN);
                if (store.votes(actor.tenantId(), command.requestId(), stage).stream().anyMatch(vote -> vote.actorUserId() == actor.userId()
                        || vote.actorPersonPublicId().equals(actor.personPublicId()) || vote.principalUserId() == principal)) throw conflict();
                var refreshed = ApprovalWorkflowQuorumInformationContext.require(store, actor, command.requestId(), "IN_REVIEW");
                if (!bound.same(refreshed)) throw conflict();
                currentOwnerGuard.run(); evaluator.verifyAuthority(stage.snapshot(), current, store.now());
            } catch (BaseException error) {
                if (error.getErrorCode() != ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) throw error;
                unknown(p, command.idempotencyKey(), "REQUEST_INFO", digest);
                return unknown(bound, expected.generation());
            }
            try {
                return business.execute(tx -> requestRound(actor, command, p, digest, bound, stages, stage, seat, principal, current, onCompleted));
            } catch (BaseException error) {
                if (error.getErrorCode() != ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) throw error;
                unknown(p, command.idempotencyKey(), "REQUEST_INFO", digest);
                return unknown(bound, expected.generation());
            }
        });
    }

    private Receipt requestRound(Actor actor, RequestCommand command, MapSqlParameterSource p, String digest,
            ApprovalWorkflowQuorumInformationContext.Bound bound, List<StageRow> stages, StageRow stage,
            Map<String, Object> seat, long principal, CurrentAuthority current, Consumer<Receipt> onCompleted) {
            UUID round = UUID.randomUUID();
            var retained = new java.util.TreeMap<String, Object>();
            stages.stream().filter(row -> row.snapshot() != null).forEach(row -> retained.put(row.key(), row.snapshot()));
            p.addValue("round", round).addValue("target", stage.generation() + 1).addValue("stageVersion", stage.version())
                    .addValue("actor", actor.userId()).addValue("actorPerson", actor.personPublicId()).addValue("principal", principal)
                    .addValue("person", seat.get("principal_person_id"))
                    .addValue("delegation", current.delegation() == null ? null : current.delegation().delegationId())
                    .addValue("context", store.json(bound.context())).addValue("retained", store.json(retained))
                    .addValue("reason", command.reason()).addValue("now", time(store.now()))
                    .addValue("requestVersion", command.expectedRequestVersion()).addValue("taskVersion", command.expectedTaskVersion())
                    .addValue("revision", bound.context().payloadRevision()).addValue("hash", bound.context().payloadSha256());
            store.jdbc.update("""
                    INSERT INTO apr_quorum_information_rounds(round_id,tenant_id,request_id,source_generation,target_generation,
                        step_id,task_id,source_stage_version,actor_user_id,actor_person_id,principal_user_id,principal_person_id,
                        delegation_id,context,retained_snapshots,reason,opened_at)
                    VALUES(:round,:tenant,:request,:generation,:target,:step,:task,:stageVersion,:actor,:actorPerson,:principal,
                        :person,:delegation,CAST(:context AS jsonb),CAST(:retained AS jsonb),:reason,:now)
                    """, p);
            changed(store.jdbc.update("""
                    UPDATE apr_tasks SET status='INFO_REQUESTED',version=version+1,completed_at=:now,updated_at=:now,
                        decision_reason=:reason,decision_actor_user_id=:actor,decision_actor_person_public_id=:actorPerson,
                        decision_payload_revision=:revision,decision_payload_sha256=:hash
                     WHERE tenant_id=:tenant AND request_id=:request AND task_id=:task AND status='CLAIMED'
                       AND version=:taskVersion AND assignee_user_id=:principal
                    """, p));
            cancelGeneration(p);
            changed(store.jdbc.update("""
                    UPDATE apr_requests SET status='NEEDS_INFO',version=version+1,updated_at=:now,updated_by=:actor
                     WHERE tenant_id=:tenant AND request_id=:request AND status='IN_REVIEW' AND version=:requestVersion
                    """, p));
            evidence.append(actor.tenantId(), command.requestId(), actor.userId(), "Approval.Quorum.InformationRequested",
                    Map.of("roundId", round.toString(), "generation", stage.generation(), "principalPersonPublicId", seat.get("principal_person_id").toString(),
                            "payloadRevision", bound.context().payloadRevision(), "payloadSha256", bound.context().payloadSha256()));
            var receipt = new Receipt("COMPLETED", round, stage.generation(), bound.requestVersion() + 1,
                    bound.context().payloadRevision(), bound.context().payloadSha256(), false);
            complete(p, command.idempotencyKey(), "REQUEST_INFO", digest, receipt);
            onCompleted.accept(receipt);
            return receipt;
    }

    public Receipt reply(Actor actor, ReplyCommand command, ApprovalFormPayloadNormalization normalizer) {
        return reply(actor, command, normalizer, () -> { });
    }

    public Receipt reply(Actor actor, ReplyCommand command, ApprovalFormPayloadNormalization normalizer, Runnable currentOwnerGuard) {
        return reply(actor, command, normalizer, currentOwnerGuard, receipt -> { });
    }

    public Receipt reply(Actor actor, ReplyCommand command, ApprovalFormPayloadNormalization normalizer,
            Runnable currentOwnerGuard, Consumer<Receipt> onCompleted) {
        return reply(actor, command, normalizer, currentOwnerGuard, onCompleted, null);
    }

    Receipt reply(Actor actor, ReplyCommand command, ApprovalFormPayloadNormalization normalizer,
            Runnable currentOwnerGuard, Consumer<Receipt> onCompleted,
            Function<ApprovalWorkflowQuorumInformationContext.Bound,ApprovalWorkflowInformationAttachments.PreparedReply> attachments) {
        java.util.Objects.requireNonNull(onCompleted);
        if (command == null || command.sourceGeneration() < 1 || command.expectedRequestVersion() < 0 || normalizer == null) throw conflict();
        raw(command.rawBodySha256());
        text(command.message()); key(command.idempotencyKey());
        return transactions.execute(transaction -> {
            var p = lock(actor, command.requestId()); String digest = digest("REPLY", actor, command);
            var replay = replay(p, command.idempotencyKey(), "REPLY", digest);
            if (replay != null) return verifiedReplay(actor, command.requestId(), replay);
            var bound = ApprovalWorkflowQuorumInformationContext.require(store, actor, command.requestId(), "NEEDS_INFO");
            if (bound.requestVersion() != command.expectedRequestVersion() || bound.context().requesterUserId() != actor.userId()
                    || !bound.context().requesterPersonId().equals(actor.personPublicId())) throw conflict();
            var stages = store.stages(actor.tenantId(), command.requestId(), true);
            if (stages.isEmpty() || stages.getFirst().generation() != command.sourceGeneration()) throw conflict();
            var rounds = store.jdbc.queryForList("""
                    SELECT * FROM apr_quorum_information_rounds WHERE tenant_id=:tenant AND request_id=:request
                       AND source_generation=:generation AND status='OPEN' FOR UPDATE
                    """, p.addValue("generation", command.sourceGeneration()));
            if (rounds.size() != 1) throw conflict();
            var round = rounds.getFirst(); var frozen = store.read(round.get("context").toString(), Context.class);
            var current = bound.context();
            if (!current.pins().equals(frozen.pins()) || !current.formVersionId().equals(frozen.formVersionId())
                    || current.requesterUserId() != frozen.requesterUserId() || !current.requesterPersonId().equals(frozen.requesterPersonId())
                    || current.payloadRevision() != frozen.payloadRevision() || !current.payloadSha256().equals(frozen.payloadSha256())) throw conflict();
            Map<String, Object> merged = input(bound);
            if (command.patch() != null && (command.patch().containsKey("createdFrom")
                    || command.patch().values().stream().anyMatch(java.util.Objects::isNull))) throw invalid("System fields and null patches are forbidden.");
            if (command.patch() != null) merged.putAll(command.patch());
            Map<String, Object> normalized;
            try {
                currentOwnerGuard.run();
                runtime.revalidate(actor.tenantId(), command.requestId(), stages, true);
                originalInformationAuthority(round, stages);
                normalized = ApprovalFormSchemaV2Canonical.freeze(normalizer.normalize(actor, command.requestId(),
                        bound.context().formVersionId(), bound.context().pins().formSchemaSha256(), bound.schema(),
                        merged, true, command.expectedRequestVersion()));
                route(bound, normalized);
                currentOwnerGuard.run();
                runtime.revalidate(actor.tenantId(), command.requestId(), stages, true);
                originalInformationAuthority(round, stages);
            } catch (BaseException error) {
                if (error.getErrorCode() != ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) throw error;
                unknown(p, command.idempotencyKey(), "REPLY", digest); return unknown(bound, command.sourceGeneration());
            }
            if (!bound.same(ApprovalWorkflowQuorumInformationContext.require(store, actor, command.requestId(), "NEEDS_INFO"))) throw conflict();
            // A late authority failure rolls back every business write without marking the intent transaction rollback-only.
            try {
                return business.execute(transactionStatus -> respond(actor, command, p, bound, frozen, round, normalized, onCompleted, attachments));
            } catch (BaseException error) {
                if (error.getErrorCode() != ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) throw error;
                unknown(p, command.idempotencyKey(), "REPLY", digest); return unknown(bound, command.sourceGeneration());
            }
        });
    }

    private Receipt respond(Actor actor, ReplyCommand command, MapSqlParameterSource p,
            ApprovalWorkflowQuorumInformationContext.Bound bound, Context frozen, Map<String, Object> round,
            Map<String, Object> normalized, Consumer<Receipt> onCompleted,
            Function<ApprovalWorkflowQuorumInformationContext.Bound,ApprovalWorkflowInformationAttachments.PreparedReply> attachments) {
            var prepared = attachments == null ? null : java.util.Objects.requireNonNull(attachments.apply(bound));
            boolean payloadChanged = !bound.payload().equals(normalized);
            boolean material = payloadChanged || (prepared != null && prepared.materialChange());
            final int revision;
            try {revision = material ? Math.addExact(bound.context().payloadRevision(), 1) : bound.context().payloadRevision();}
            catch (ArithmeticException overflow) {throw conflict();}
            String payload = ApprovalFormSchemaV2Canonical.json(normalized);
            String hash = payloadChanged ? store.hash(payload) : bound.context().payloadSha256();
            p.addValue("actor", actor.userId()).addValue("now", time(store.now())).addValue("requestVersion", bound.requestVersion())
                    .addValue("revision", revision).addValue("hash", hash).addValue("payload", payload)
                    .addValue("message", command.message()).addValue("material", material).addValue("round", round.get("round_id"))
                    .addValue("roundVersion", round.get("version")).addValue("slaSeconds", Math.multiplyExact(bound.definition().slaMinutes(), 60L));
            if (material) {
                changed(store.jdbc.update("""
                        UPDATE apr_request_payloads SET payload=CAST(:payload AS jsonb),payload_sha256=:hash,
                            schema_version=:revision,updated_at=:now WHERE tenant_id=:tenant AND request_id=:request
                            AND schema_version=:oldRevision AND payload_sha256=:oldHash
                        """, p.addValue("oldRevision", bound.context().payloadRevision()).addValue("oldHash", bound.context().payloadSha256())));
                store.jdbc.update("""
                        INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,
                            payload,payload_sha256,change_type,changed_by,change_reason)
                        VALUES(:payloadId,:tenant,:request,:revision,CAST(:payload AS jsonb),:hash,'INFORMATION_RESPONDED',:actor,:message)
                        """, p.addValue("payloadId", UUID.randomUUID()));
            }
            store.jdbc.update("""
                    UPDATE apr_tasks SET status='SUPERSEDED',version=version+1,decision_invalidated_at=:now,
                        decision_invalidation_reason='Information round superseded',updated_at=:now
                     WHERE tenant_id=:tenant AND request_id=:request AND status IN ('APPROVED','INFO_REQUESTED')
                       AND step_id IN (SELECT step_id FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant
                            AND request_id=:request AND generation=:generation)
                    """, p);
            changed(store.jdbc.update("""
                    UPDATE apr_quorum_information_rounds SET status='RESPONDED',version=version+1,responded_at=:now,
                        material_change=:material,response_payload_revision=:revision,response_payload_sha256=:hash
                     WHERE tenant_id=:tenant AND request_id=:request AND round_id=:round AND status='OPEN' AND version=:roundVersion
                    """, p));
            changed(store.jdbc.update("""
                    UPDATE apr_requests SET status='IN_REVIEW',version=version+1,updated_at=:now,updated_by=:actor,
                        due_at=CAST(:now AS timestamptz)+(:slaSeconds * interval '1 second')
                     WHERE tenant_id=:tenant AND request_id=:request AND status='NEEDS_INFO' AND version=:requestVersion
                    """, p));
            var pins = store.context(actor.tenantId(), command.requestId(), bound.definition(), frozen.policy()).pins();
            runtime.restart(actor.tenantId(), command.requestId(), pins, bound.definition(), command.sourceGeneration() + 1);
            if (prepared != null) prepared.seal(revision, hash);
            evidence.append(actor.tenantId(), command.requestId(), actor.userId(), "Approval.Quorum.InformationResponded",
                    Map.of("roundId", round.get("round_id").toString(), "generation", command.sourceGeneration() + 1,
                            "materialChange", material, "payloadRevision", revision, "payloadSha256", hash));
            var receipt = new Receipt("COMPLETED", (UUID) round.get("round_id"), command.sourceGeneration() + 1,
                    bound.requestVersion() + 1, revision, hash, material);
            complete(p, command.idempotencyKey(), "REPLY", digest("REPLY", actor, command), receipt);
            onCompleted.accept(receipt); return receipt;
    }

    private MapSqlParameterSource lock(Actor actor, UUID request) {
        if (actor == null || actor.personPublicId() == null || request == null) throw conflict();
        new com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard(store.jdbc).writeRequest(actor.tenantId(), request);
        var p = store.scope(actor.tenantId(), request);
        if (store.jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE", p).size() != 1)
            throw new BaseException(ErrorCode.FORBIDDEN);
        if (store.jdbc.queryForList("SELECT request_id FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request AND deleted_at IS NULL FOR UPDATE", p).size() != 1)
            throw conflict();
        return p;
    }

    private Receipt replay(MapSqlParameterSource p, String key, String operation, String digest) {
        var rows = store.jdbc.queryForList("SELECT * FROM apr_quorum_information_commands WHERE tenant_id=:tenant AND request_id=:request "
                + "AND idempotency_key=:key FOR UPDATE", p.addValue("key", key));
        if (rows.isEmpty()) return null; var row = rows.getFirst();
        if (!operation.equals(row.get("operation")) || !digest.equals(((String) row.get("command_sha256")).strip())) throw conflict();
        return "COMPLETED".equals(row.get("status")) ? store.read(row.get("receipt").toString(), Receipt.class) : null;
    }

    private Receipt verifiedReplay(Actor actor, UUID request, Receipt receipt) {
        var rounds = store.jdbc.queryForList("SELECT * FROM apr_quorum_information_rounds "
                + "WHERE tenant_id=:tenant AND request_id=:request AND round_id=:round", store.scope(actor.tenantId(), request).addValue("round", receipt.roundId()));
        if (rounds.size() != 1) throw conflict();
        var row = rounds.getFirst(); var context = store.read(row.get("context").toString(), Context.class);
        String status = store.jdbc.queryForObject("SELECT status FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request",
                store.scope(actor.tenantId(), request), String.class);
        var current = ApprovalWorkflowQuorumInformationContext.require(store, actor, request, status).context();
        if (!context.pins().equals(current.pins())) throw conflict();
        var history = store.generation(actor.tenantId(), request, ((Number) row.get("source_generation")).longValue(), true);
        runtime.revalidate(actor.tenantId(), request, history, true); originalInformationAuthority(row, history);
        return receipt;
    }

    private void originalInformationAuthority(Map<String, Object> round, List<StageRow> stages) {
        var stage = stages.stream().filter(row -> row.stepId().equals(round.get("step_id"))).findFirst().orElseThrow(ApprovalWorkflowQuorumRuntimeStore::conflict);
        var authority = runtime.current(stage.snapshot(), ((Number) round.get("actor_user_id")).longValue(),
                ((Number) round.get("principal_user_id")).longValue(), store.now(),ApprovalWorkflowRuntimeTarget.Use.INFORMATION_RECHECK,
                (UUID)round.get("task_id"),(UUID)round.get("round_id"));
        if (!round.get("actor_person_id").equals(authority.actor().personPublicId())
                || !round.get("principal_person_id").equals(authority.principal().personPublicId())
                || !java.util.Objects.equals(round.get("delegation_id"), authority.delegation() == null ? null : authority.delegation().delegationId()))
            throw new BaseException(ErrorCode.FORBIDDEN, "A replacement source or delegation cannot heal the original information decision.");
        evaluator.verifyAuthority(stage.snapshot(), authority, store.now());
    }

    private void cancelGeneration(MapSqlParameterSource p) {
        store.jdbc.update("UPDATE apr_quorum_stage_runtime SET status='CANCELLED',version=version+1,completed_at=:now "
                + "WHERE tenant_id=:tenant AND request_id=:request AND generation=:generation AND status IN ('WAITING','IN_PROGRESS')", p);
        store.jdbc.update("UPDATE apr_steps SET status='CANCELLED',version=version+1,completed_at=:now,updated_at=:now WHERE tenant_id=:tenant AND request_id=:request "
                + "AND status IN ('WAITING','IN_PROGRESS') AND step_id IN (SELECT step_id FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request AND generation=:generation)", p);
        store.jdbc.update("UPDATE apr_tasks SET status='CANCELLED',version=version+1,completed_at=:now,updated_at=:now WHERE tenant_id=:tenant AND request_id=:request "
                + "AND status IN ('PENDING','CLAIMED') AND step_id IN (SELECT step_id FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request AND generation=:generation)", p);
        store.jdbc.update("UPDATE apr_quorum_sla_timers SET status='CANCELLED',version=version+1,lease_owner=NULL,lease_until=NULL "
                + "WHERE tenant_id=:tenant AND request_id=:request AND generation=:generation AND status IN ('PENDING','CLAIMED')", p);
    }

    private void unknown(MapSqlParameterSource p, String key, String operation, String digest) {
        store.jdbc.update("""
                INSERT INTO apr_quorum_information_commands(tenant_id,request_id,idempotency_key,operation,command_sha256,status)
                VALUES(:tenant,:request,:key,:operation,:digest,'UNKNOWN') ON CONFLICT(tenant_id,request_id,idempotency_key) DO NOTHING
                """, p.addValue("key", key).addValue("operation", operation).addValue("digest", digest));
    }
    private void complete(MapSqlParameterSource p, String key, String operation, String digest, Receipt receipt) {
        unknown(p, key, operation, digest);
        changed(store.jdbc.update("UPDATE apr_quorum_information_commands SET status='COMPLETED',receipt=CAST(:receipt AS jsonb),completed_at=clock_timestamp() "
                + "WHERE tenant_id=:tenant AND request_id=:request AND idempotency_key=:key AND command_sha256=:digest AND status='UNKNOWN'",
                p.addValue("receipt", store.json(receipt))));
    }
    private Receipt unknown(ApprovalWorkflowQuorumInformationContext.Bound bound, long generation) {
        return new Receipt("UNKNOWN", null, generation, bound.requestVersion(), bound.context().payloadRevision(), bound.context().payloadSha256(), false);
    }
    private Map<String, Object> input(ApprovalWorkflowQuorumInformationContext.Bound bound) {
        var schema = store.object(bound.schema());
        if (!schema.containsKey("schemaContract")) return new LinkedHashMap<>(bound.payload());
        var compiled = new ApprovalFormSchemaV2Compiler().compile(schema);
        return new LinkedHashMap<>(new ApprovalFormSchemaV2Evaluator().withoutComputedValues(compiled, bound.payload()));
    }
    private void route(ApprovalWorkflowQuorumInformationContext.Bound bound, Map<String, Object> payload) {
        if ("CONDITIONAL".equals(bound.bindingType()) && !routes.matches(bound.bindingCondition(), payload, bound.schema()))
            throw invalid("The amended request no longer matches its governed approval route.");
    }
    private String digest(String operation, Actor actor, Object command) {
        return store.hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of(
                "operation", operation, "tenantId", actor.tenantId(), "actorUserId", actor.userId(),
                "actorPersonId", actor.personPublicId().toString(), "command", store.object(store.json(command))))));
    }
    private void text(String value) {
        if (value == null || value.length() < 4 || value.length() > 2000 || !value.equals(value.strip())) throw invalid("Information text is required.");
    }
    private void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,120}")) throw invalid("The original HTTP idempotency key is required.");
    }
    private void raw(String hash) { if (hash != null && !sha256(hash)) throw invalid("The original command digest is invalid."); }
}
