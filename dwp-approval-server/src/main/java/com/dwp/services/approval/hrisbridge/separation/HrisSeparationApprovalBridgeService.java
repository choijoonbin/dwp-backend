package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumFacade;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** APR-002 proof, replay, requester, catalog and native Approval orchestration. */
@Service
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class HrisSeparationApprovalBridgeService {
    private final ObjectMapper mapper;
    private final HrisSeparationApprovalAuthorityResolver authorities;
    private final ApprovalCommandRepository commands;
    private final ApprovalWorkflowQuorumFacade quorum;
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationWaiverBindingStore bindings;
    private final HrisSeparationRlsScope scope;
    private final TransactionTemplate transactions;
    private final TransactionTemplate replayTransactions;
    private final Clock clock;

    HrisSeparationApprovalBridgeService(ObjectMapper mapper,
            HrisSeparationApprovalAuthorityResolver authorities,
            ApprovalCommandRepository commands, ApprovalWorkflowQuorumFacade quorum,
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(mapper, authorities, commands, quorum, jdbc, manager, Clock.systemUTC());
    }

    HrisSeparationApprovalBridgeService(ObjectMapper mapper,
            HrisSeparationApprovalAuthorityResolver authorities,
            ApprovalCommandRepository commands, ApprovalWorkflowQuorumFacade quorum,
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            Clock clock) {
        this.mapper = mapper;
        this.authorities = authorities;
        this.commands = commands;
        this.quorum = quorum;
        this.jdbc = jdbc;
        this.clock = clock;
        HrisSeparationGrantStore grants = new HrisSeparationGrantStore(jdbc, manager);
        bindings = new HrisSeparationWaiverBindingStore(jdbc, manager, grants);
        scope = new HrisSeparationRlsScope(jdbc);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(30);
        replayTransactions = new TransactionTemplate(manager);
        replayTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record PersistedResponse(byte[] exactResponseBody, boolean replayed) {
        PersistedResponse { exactResponseBody = exactResponseBody.clone(); }
        @Override public byte[] exactResponseBody() { return exactResponseBody.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof PersistedResponse value
                    && replayed == value.replayed
                    && Arrays.equals(exactResponseBody, value.exactResponseBody);
        }
        @Override public int hashCode() {
            return 31 * Boolean.hashCode(replayed) + Arrays.hashCode(exactResponseBody);
        }
    }

    PersistedResponse dispatch(String method, String path,
            List<Hrm013WorkloadProofV1.Header> headers, byte[] exactBody,
            Hrm013WorkloadProofV1.TransportIdentity transportIdentity) {
        var profile = Hrm013WorkloadProofV1.apr002();
        var authority = authorities.begin();
        Hrm013WorkloadProofV1.VerifiedProof proof;
        try {
            proof = Hrm013WorkloadProofV1.verify(mapper, clock, profile, method, path,
                    headers, exactBody, transportIdentity, authority,
                    this::reserveReplay, authority);
        } catch (Hrm013WorkloadProofV1.VerificationException invalid) {
            if (authority.failure() != null) throw authority.failure();
            throw HrisSeparationProtocolException.authenticationRequired();
        }
        var body = Hrm013SeparationBridgeV1.decodeWaiverRequest(exactBody);
        Hrm013SeparationBridgeV1.requireBodyBinding(
                Hrm013SeparationBridgeV1.APR_002, body);
        requireProofBody(proof.claims(), body);
        var grant = authority.admitted();
        PersistedResponse result = transactions.execute(status -> {
            scope.bind(proof.claims().tenantId());
            Optional<HrisSeparationPersistenceModels.BindingReceipt> replay =
                    bindings.replay(proof.claims().tenantId(),
                            body.approvalRequestId(), exactBody,
                            proof.commandDigest());
            if (replay.isPresent()) {
                return new PersistedResponse(
                        replay.get().exactResponseBody(), true);
            }
            var nativeAuthority = authority.nativeAuthority(
                    body, proof.claims(), grant, clock.instant());
            registerCommitRecheck(authority, profile, body, proof.claims(),
                    nativeAuthority.authorityExpiresAt());
            return create(proof, body, exactBody, grant, nativeAuthority);
        });
        if (result == null) throw HrisSeparationProtocolException.unavailable();
        return result;
    }

    private void registerCommitRecheck(
            HrisSeparationApprovalAuthorityResolver.Session authority,
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1 body,
            Hrm013WorkloadProofV1.ProofClaims claims, Instant expiresAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw HrisSeparationProtocolException.unavailable();
        }
        requireDatabaseTimeBefore(expiresAt);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public int getOrder() {
                        return Ordered.LOWEST_PRECEDENCE;
                    }

                    @Override public void beforeCommit(boolean readOnly) {
                        authority.recheckForCommit(profile, body, claims);
                        requireDatabaseTimeBefore(expiresAt);
                    }
                });
    }

    private void requireDatabaseTimeBefore(Instant expiresAt) {
        Timestamp now = jdbc.queryForObject(
                "SELECT clock_timestamp()", Map.of(), Timestamp.class);
        if (expiresAt == null || now == null
                || !now.toInstant().isBefore(expiresAt)) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private PersistedResponse create(Hrm013WorkloadProofV1.VerifiedProof proof,
            Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1 body,
            byte[] exactBody,
            HrisSeparationApprovalAuthorityResolver.Resolution grant,
            HrisSeparationApprovalAuthorityResolver.NativeAuthority authority) {
        long tenantId = proof.claims().tenantId();
        scope.bind(tenantId);
        Optional<HrisSeparationPersistenceModels.BindingReceipt> concurrent =
                bindings.replay(tenantId, body.approvalRequestId(), exactBody,
                        proof.commandDigest());
        if (concurrent.isPresent()) {
            return new PersistedResponse(concurrent.get().exactResponseBody(), true);
        }
        Integer collision = jdbc.queryForObject(
                "SELECT count(*) FROM apr_requests WHERE request_id=:request",
                new MapSqlParameterSource("request", body.approvalRequestId()), Integer.class);
        if (collision != null && collision != 0) throw HrisSeparationProtocolException.conflict();
        var actor = new ApprovalRequestContext.Actor(authority.requesterUserId(), tenantId,
                authority.requesterPersonPublicId(), "HRIS requester", Set.of(), Set.of());
        commands.createPinnedHrisDraft(actor, body.approvalRequestId(),
                authority.workflowVersionId(), authority.formVersionId(),
                "Separation task waiver",
                "Governed HRIS separation-task waiver request", "NORMAL",
                nativePayload(body), body.correlationId().toString(),
                "HRIS separation-task waiver draft created",
                "HRIS_SEPARATION_TASK_WAIVER");
        quorum.submit(actor, body.approvalRequestId(), 0, body.correlationId().toString());
        List<Map<String, Object>> pins = jdbc.queryForList("""
                SELECT DISTINCT (context->'pins'->>'policyVersion')::bigint AS policy_version
                  FROM apr_quorum_stage_runtime
                 WHERE tenant_id=:tenant AND request_id=:request
                """, parameters(tenantId, body.approvalRequestId()));
        Long requestRevision = jdbc.queryForObject("""
                SELECT version FROM apr_requests
                 WHERE tenant_id=:tenant AND request_id=:request AND status='IN_REVIEW'
                """, parameters(tenantId, body.approvalRequestId()), Long.class);
        if (pins.size() != 1 || requestRevision == null || requestRevision < 1) {
            throw HrisSeparationProtocolException.unavailable();
        }
        long policy = ((Number) pins.getFirst().get("policy_version")).longValue();
        Instant acceptedAt = clock.instant();
        var receipt = new Hrm013SeparationBridgeV1
                .SeparationWaiverApprovalSubmissionReceiptV1(
                body.approvalRequestId(), requestRevision, "PENDING",
                proof.commandDigest(), body.correlationId(), acceptedAt);
        byte[] response = Hrm013SeparationBridgeV1.encode(receipt);
        var write = new HrisSeparationPersistenceModels.RequestBindingWrite(
                UUID.randomUUID(), tenantId, grant.authority().grantSetId(),
                grant.authority().tenantGrantRevision(),
                grant.authority().authorizationRevision(), body.approvalRequestId(),
                body.casePublicId(), body.taskPublicId(), body.taskType(),
                body.caseMakerPublicId(), body.requestedByActorPublicId(),
                body.reasonCode(), body.taskOwnerRevision(), body.taskVersion(),
                authority.workflowVersionId(), authority.formVersionId(), policy,
                body.correlationId(), body.payloadSha256(), exactBody,
                Hrm013SeparationBridgeV1.requestBodySha256(exactBody),
                proof.commandDigest(), response, Hrm013SeparationBridgeV1.sha256(response),
                body.requestedAt(), acceptedAt);
        var stored = bindings.saveOrReplay(write);
        return new PersistedResponse(stored.exactResponseBody(), stored.replayed());
    }

    private boolean reserveReplay(Hrm013WorkloadProofV1.ReplayReservation value) {
        Boolean result = replayTransactions.execute(status -> {
            scope.bind(value.tenantId());
            int inserted = jdbc.update("""
                    INSERT INTO apr_hrm013_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,
                        token_digest,expires_at,reserved_at)
                    VALUES(:tenant,:binding,:caller,'JTI',:jti,:expires,:now),
                          (:tenant,:binding,:caller,'REQUEST_NONCE',:nonce,:expires,:now)
                    ON CONFLICT (tenant_id,token_digest) DO NOTHING
                    """, new MapSqlParameterSource("tenant", value.tenantId())
                    .addValue("binding", value.bindingId())
                    .addValue("caller", value.callerWorkloadId())
                    .addValue("jti", value.proofIdSha256())
                    .addValue("nonce", value.requestNonceSha256())
                    .addValue("expires", Timestamp.from(value.retainUntil()))
                    .addValue("now", Timestamp.from(clock.instant())));
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(result);
    }

    private static void requireProofBody(Hrm013WorkloadProofV1.ProofClaims proof,
            Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1 body) {
        Map<String, Object> fields = proof.profileClaims();
        if (!body.correlationId().equals(proof.correlationId())
                || !body.approvalRequestId().equals(fields.get("approvalRequestId"))
                || !body.casePublicId().equals(fields.get("casePublicId"))
                || !body.taskPublicId().equals(fields.get("taskPublicId"))) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    private static Map<String, Object> nativePayload(
            Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1 body) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaName", body.schemaName()); value.put("schemaVersion", body.schemaVersion());
        value.put("approvalRequestId", body.approvalRequestId().toString());
        value.put("casePublicId", body.casePublicId().toString());
        value.put("taskPublicId", body.taskPublicId().toString()); value.put("taskType", body.taskType());
        value.put("caseMakerPublicId", body.caseMakerPublicId().toString());
        value.put("requestedByActorPublicId", body.requestedByActorPublicId().toString());
        value.put("reasonCode", body.reasonCode()); value.put("taskOwnerRevision", body.taskOwnerRevision());
        value.put("taskVersion", body.taskVersion()); value.put("requestedAt", body.requestedAt().toString());
        value.put("payloadSha256", body.payloadSha256()); value.put("correlationId", body.correlationId().toString());
        return value;
    }

    private static MapSqlParameterSource parameters(long tenant, UUID request) {
        return new MapSqlParameterSource("tenant", tenant).addValue("request", request);
    }
}
