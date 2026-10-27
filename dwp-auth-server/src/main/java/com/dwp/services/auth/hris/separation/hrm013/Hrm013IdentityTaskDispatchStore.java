package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.Fence;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalAdmission;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalVerification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-scoped V272 durable inbox, conflict ledger and HRM-008 outbox. */
public final class Hrm013IdentityTaskDispatchStore {
    static final Duration OUTBOUND_VALIDITY_MARGIN = Duration.ofSeconds(6);
    static final String TENANT_CONTEXT = "dwp.auth.hrm013.tenant";
    private static final Hrm013TaskDispatchV1.Profile PROFILE =
            Hrm013TaskDispatchV1.IDENTITY;
    private static final Hrm013WorkloadProofV1.Profile PROOF_PROFILE =
            Hrm013WorkloadProofV1.taskDispatch(Hrm013WorkloadProofV1.TaskOwner.IDENTITY);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate replayTransactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HrisServiceGrantQuiesceService authorities;
    private final Hrm013IdentityWorkloadCredentialProvider credentials;

    public Hrm013IdentityTaskDispatchStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper mapper, Clock clock,
            Hrm013IdentityWorkloadCredentialProvider credentials,
            HrisServiceGrantQuiesceService authorities) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setTimeout(30);
        this.replayTransactions = new TransactionTemplate(transactionManager);
        this.replayTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.replayTransactions.setTimeout(5);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    public Admission admit(
            Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest request) {
        Objects.requireNonNull(request, "request");
        long tenantId = untrustedTenantSelector(request.compactProof());
        AdmissionResult result = transactions.execute(status -> {
            setTenant(tenantId);
            purgeExpiredReplays(tenantId);
            AtomicReference<LocalVerification> prepared =
                    new AtomicReference<>();
            Hrm013WorkloadProofV1.VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                    mapper, clock, PROOF_PROFILE, "POST", PROFILE.path(),
                    request.headers(), request.exactBody(), request.transportIdentity(),
                    (profile, selector, keyId) -> {
                        if (selector.tenantId() != tenantId) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        LocalVerification verification = prepareDispatchAuthority(
                                selector, request.correlationId());
                        HrisServiceGrantAuthorityV1.GrantAuthority grant =
                                verification.authority();
                        if (!grant.callerSigningKeyId().equals(keyId)) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        prepared.set(verification);
                        return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                                rsa(grant.callerSigningPublicJwk()),
                                grant.callerSigningKeyId(), grant.callerWorkloadId());
                    }, this::reserveProofPair,
                    (profile, key, claims) -> authorityStillBound(
                            prepared.get(), key, claims));

            Hrm013TaskDispatchV1.IdentityTaskDispatch body =
                    (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                            Hrm013TaskDispatchV1.decode(PROFILE, tenantId,
                                    request.exactBody());
            requireProofBodyBinding(proof.claims(), body);
            LocalAdmission localAdmission = authorities.authorizeLocalVerified(
                    Objects.requireNonNull(prepared.get(), "authority preparation"),
                    proof.proofSha256());
            HrisServiceGrantAuthorityV1.ResolveResponse authorityResponse =
                    requireDispatchAuthority(localAdmission.response(), proof.claims());
            HrisServiceGrantAuthorityV1.GrantAuthority authority =
                    requireAdmit(authorityResponse);
            String bodyDigest = Hrm013TaskDispatchV1.requestBodySha256(
                    request.exactBody());
            lock(tenantId, "event:" + body.dispatchEventId());
            lock(tenantId, "task:" + body.taskPublicId() + ":" + body.taskVersion());

            Optional<StoredAdmission> byEvent = storedByEvent(
                    tenantId, body.dispatchEventId());
            if (byEvent.isPresent()) {
                StoredAdmission stored = byEvent.orElseThrow();
                if (!constantEquals(stored.requestBodySha256(), bodyDigest)) {
                    quarantine(tenantId, body.dispatchEventId(),
                            stored.requestBodySha256(), bodyDigest, proof.proofSha256(),
                            "DISPATCH_DIGEST_CONFLICT", body.correlationId());
                    return AdmissionResult.digestConflict();
                }
                audit(tenantId, body.dispatchEventId(), "DISPATCH_REPLAYED", "SUCCESS",
                        stored.receiptSha256(), body.correlationId());
                authorities.recheckLocal(localAdmission);
                var ownerLease = credentials.requireOwnerCurrent(authority);
                var freshProof = credentials.admissionReceiptProof(
                        ownerLease, stored.receipt(), stored.exactReceiptBody(),
                        authorityExpiry(authorityResponse, proof.claims().expiresAt()));
                return AdmissionResult.admitted(new Admission(
                        stored.receipt(), stored.exactReceiptBody(),
                        freshProof.compact(), true));
            }

            Optional<StoredIdentity> sameVersion = storedByTaskVersion(
                    tenantId, body.taskPublicId(), body.taskVersion());
            if (sameVersion.isPresent()) {
                StoredIdentity stored = sameVersion.orElseThrow();
                quarantine(tenantId, body.dispatchEventId(),
                        stored.requestBodySha256(), bodyDigest, proof.proofSha256(),
                        "TASK_VERSION_CONFLICT", body.correlationId());
                return AdmissionResult.digestConflict();
            }

            Instant admittedAt = databaseNow();
            var receipt = Hrm013TaskDispatchV1.seal(
                    new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                            body.dispatchEventId(), body.taskPublicId(),
                            body.payloadSha256(), bodyDigest, admittedAt,
                            "0".repeat(64), body.correlationId()));
            byte[] receiptBytes = Hrm013TaskDispatchV1.encode(receipt);
            Instant authorityExpiresAt = authorityExpiry(authorityResponse,
                    proof.claims().expiresAt());
            var ownerLease = credentials.requireOwnerCurrent(authority);
            var receiptProof = credentials.admissionReceiptProof(
                    ownerLease, receipt, receiptBytes, authorityExpiresAt);
            byte[] authorityRequestBytes = HrisServiceGrantAuthorityV1.encodeRequest(
                    mapper, localAdmission.preparation().request());
            byte[] authorityResponseBytes = HrisServiceGrantAuthorityV1.encodeResponse(
                    mapper, localAdmission.response());
            var businessAuthority = (Hrm013WorkloadProofV1.BusinessAuthority)
                    proof.claims().authorityMode();
            var inboundDrain = proof.claims().drainClaims();
            int inserted = jdbc.update("""
                    INSERT INTO auth_hrm013_identity_task_dispatch_inbox(
                      tenant_id,dispatch_event_id,case_public_id,task_public_id,
                      task_version,task_owner_revision,person_public_id,separation_type,
                      effective_date,correlation_id,payload_sha256,request_body_sha256,
                      workload_proof_sha256,exact_request_body,dispatch_grant_set_id,
                      dispatch_grant_revision,tenant_grant_revision,authorization_revision,
                      dispatch_grant_use_mode,drain_root_work_item_id,
                      drain_root_created_at,drain_root_payload_sha256,
                      caller_signing_key_id,dispatch_authority_request,
                      dispatch_authority_response,dispatch_preparation_tx_id,
                      dispatch_preparation_sha256,admission_receipt,
                      admission_receipt_proof,admission_receipt_sha256,
                      lifecycle_state,admitted_at,next_execution_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,
                            ?,?,?,?,?,?,?,?,?,?,
                            ?,?,?,?,?,?,?,?,?,?,'RECEIVED',?,?)
                    """, tenantId, body.dispatchEventId(), body.casePublicId(),
                    body.taskPublicId(), body.taskVersion(), body.taskOwnerRevision(),
                    body.personPublicId(), body.separationType(), body.effectiveDate(),
                    body.correlationId(), body.payloadSha256(), bodyDigest,
                    proof.proofSha256(), request.exactBody(), authority.grantSetId(),
                    authority.grantRevision(), authority.tenantGrantRevision(),
                    authority.authorizationRevision(),
                    businessAuthority.grantUseMode().name(),
                    inboundDrain == null ? null : inboundDrain.drainRootWorkItemId(),
                    inboundDrain == null ? null
                            : Timestamp.from(inboundDrain.drainRootCreatedAt()),
                    inboundDrain == null ? null : inboundDrain.drainRootPayloadSha256(),
                    authority.callerSigningKeyId(),
                    authorityRequestBytes, authorityResponseBytes,
                    localAdmission.preparation().transactionId(),
                    localAdmission.preparation().preparationSha256(), receiptBytes,
                    receiptProof.compact(), receipt.receiptSha256(),
                    Timestamp.from(admittedAt), Timestamp.from(admittedAt));
            if (inserted != 1) throw Hrm013SafeErrorV1.authorityUnavailable(1);
            audit(tenantId, body.dispatchEventId(), "DISPATCH_ADMITTED", "SUCCESS",
                    receipt.receiptSha256(), body.correlationId());
            authorities.recheckLocal(localAdmission);
            return AdmissionResult.admitted(new Admission(
                    receipt, receiptBytes, receiptProof.compact(), false));
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        if (result.conflict()) throw Hrm013SafeErrorV1.digestConflict();
        return result.admission();
    }

    /** Completes a task and creates exactly one immutable HRM-008 callback body atomically. */
    public Completion complete(CompletionCommand command) {
        Objects.requireNonNull(command, "command");
        CompletionResult result = transactions.execute(status -> {
            setTenant(command.tenantId());
            return completeInTransaction(command);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        if (result.conflict()) throw Hrm013SafeErrorV1.digestConflict();
        return result.completion();
    }

    /** Executes one due identity revocation and creates its callback in one transaction. */
    public Optional<Completion> executeNext(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId");
        try {
            Optional<Completion> result = transactions.execute(status -> {
                setTenant(tenantId);
                purgeExpiredReplays(tenantId);
                List<UUID> ids = jdbc.queryForList("""
                    SELECT inbox.dispatch_event_id
                      FROM auth_hrm013_identity_task_dispatch_inbox inbox
                      JOIN com_tenants tenant ON tenant.tenant_id=inbox.tenant_id
                     WHERE inbox.tenant_id=? AND inbox.lifecycle_state='RECEIVED'
                       AND inbox.execution_attempt_count < 10
                       AND inbox.next_execution_at <= statement_timestamp()
                       AND inbox.effective_date <=
                           (statement_timestamp() AT TIME ZONE tenant.time_zone)::DATE
                     ORDER BY inbox.effective_date,inbox.next_execution_at,
                              inbox.admitted_at,inbox.dispatch_event_id
                     FOR UPDATE OF inbox SKIP LOCKED LIMIT 1
                    """, UUID.class, tenantId);
                if (ids.isEmpty()) return Optional.empty();
                UUID eventId = ids.getFirst();
                try {
                    InboxTask task = requireInbox(tenantId, eventId);
                    var dispatch = (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                            Hrm013TaskDispatchV1.decode(PROFILE, tenantId,
                                    task.exactRequestBody());
                    LocalAdmission executionAdmission = currentDispatchAdmission(
                            tenantId, task, dispatch);
                    authorities.recheckLocal(executionAdmission);
                    revokeIdentityAccess(eventId);
                    UUID callbackId = Hrm013WorkloadProofV1.terminalChildEventId(
                            tenantId, task.drainRootWorkItemId() == null
                                    ? eventId : task.drainRootWorkItemId(),
                            dispatch.taskOwnerRevision(),
                            Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId());
                    UUID externalReceiptId = UUID.nameUUIDFromBytes((
                            "DWP-HRM013-IDENTITY-REVOCATION:" + tenantId + ':' + eventId)
                            .getBytes(StandardCharsets.UTF_8));
                    CompletionResult completed = completeInTransaction(
                            new CompletionCommand(tenantId, eventId, callbackId,
                                    externalReceiptId, "COMPLETED", null, null));
                    if (completed.conflict()) throw Hrm013SafeErrorV1.digestConflict();
                    return Optional.of(completed.completion());
                } catch (RuntimeException failure) {
                    throw new DueExecutionFailure(eventId, failure);
                }
            });
            return result == null ? Optional.empty() : result;
        } catch (DueExecutionFailure failure) {
            recordExecutionFailure(tenantId, failure.dispatchEventId());
            return Optional.empty();
        }
    }

    void recordExecutionFailure(long tenantId, UUID dispatchEventId) {
        transactions.executeWithoutResult(status -> {
            setTenant(tenantId);
            Integer attempt = jdbc.queryForObject("""
                    SELECT execution_attempt_count + 1
                      FROM auth_hrm013_identity_task_dispatch_inbox
                     WHERE tenant_id=? AND dispatch_event_id=?
                       AND lifecycle_state='RECEIVED' FOR UPDATE
                    """, Integer.class, tenantId, dispatchEventId);
            if (attempt == null || attempt > 10) return;
            long delay = boundedFullJitterSeconds(dispatchEventId, attempt);
            int changed = jdbc.update("""
                    UPDATE auth_hrm013_identity_task_dispatch_inbox
                       SET execution_attempt_count=?,
                           next_execution_at=statement_timestamp()
                               + (? * INTERVAL '1 second'),
                           last_execution_error='EXECUTION_FAILED'
                     WHERE tenant_id=? AND dispatch_event_id=?
                       AND lifecycle_state='RECEIVED'
                    """, attempt, delay, tenantId, dispatchEventId);
            if (changed == 1) {
                if (attempt == 10) {
                    quarantineExecutionPoison(tenantId, dispatchEventId);
                }
                audit(tenantId, dispatchEventId, "TASK_FAILED", "RETRY",
                        Hrm013WorkloadProofV1.sha256((dispatchEventId + "\n" + attempt)
                                .getBytes(StandardCharsets.UTF_8)), UUID.randomUUID());
                if (attempt == 10) {
                    audit(tenantId, dispatchEventId, "DISPATCH_QUARANTINED", "FAILED",
                        Hrm013WorkloadProofV1.sha256((dispatchEventId + "\n" + attempt)
                                .getBytes(StandardCharsets.UTF_8)), UUID.randomUUID());
                }
            }
        });
    }

    private LocalAdmission currentDispatchAdmission(long tenantId, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch) {
        var newWork = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                task.tenantGrantRevision(), task.authorizationRevision());
        try {
            LocalVerification prepared = prepareDispatchAuthority(
                    tenantId, task.correlationId(), newWork, null);
            return authorities.authorizeLocalVerified(
                    prepared, task.workloadProofSha256());
        } catch (Hrm013SafeErrorV1.Failure rejected) {
            UUID rootId = task.drainRootWorkItemId() == null
                    ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
            Instant rootCreatedAt = task.drainRootCreatedAt() == null
                    ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
            String rootPayload = task.drainRootPayloadSha256() == null
                    ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
            var drain = new Hrm013WorkloadProofV1.DrainClaims(
                    rootId, rootCreatedAt, rootPayload,
                    Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                    dispatch.dispatchEventId(), dispatch.taskOwnerRevision(),
                    dispatch.payloadSha256());
            var drainMode = new Hrm013WorkloadProofV1.BusinessAuthority(
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    task.tenantGrantRevision(), task.authorizationRevision());
            LocalVerification prepared = prepareDispatchAuthority(
                    tenantId, task.correlationId(), drainMode, drain);
            return authorities.authorizeLocalVerified(
                    prepared, task.workloadProofSha256());
        }
    }

    private CompletionResult completeInTransaction(CompletionCommand command) {
        lock(command.tenantId(), "event:" + command.dispatchEventId());
        InboxTask task = requireInbox(command.tenantId(), command.dispatchEventId());
        String commandDigest = terminalCommandDigest(command);
        if (!"RECEIVED".equals(task.state())) {
            Completion existing = requireCompletion(
                    command.tenantId(), command.dispatchEventId());
            if (!task.state().equals(command.status())
                    || task.terminalCommandSha256() == null
                    || !constantEquals(task.terminalCommandSha256(), commandDigest)
                    || !Objects.equals(task.terminalExternalReceiptPublicId(),
                            command.externalReceiptPublicId())
                    || !terminalReplayMatches(command, task,
                            existing.callback())) {
                quarantine(command.tenantId(), command.dispatchEventId(),
                        task.terminalCommandSha256() == null
                                ? "0".repeat(64) : task.terminalCommandSha256(),
                        commandDigest, commandDigest,
                        "TERMINAL_COMMAND_CONFLICT", task.correlationId());
                return CompletionResult.digestConflict();
            }
            return CompletionResult.completed(existing);
        }
        Instant occurredAt = transactionNow();
        Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch =
                (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                        Hrm013TaskDispatchV1.decode(PROFILE, command.tenantId(),
                                task.exactRequestBody());
        CompletionPlan plan = completionPlan(command, task, dispatch, occurredAt);
        var callback = plan.callback();
        var authority = plan.localVerification().authority();
        Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                PROFILE, dispatch, Hrm013SeparationBridgeV1.HRM_008_IDENTITY,
                callback);
        byte[] callbackBytes = Hrm013SeparationBridgeV1.encode(callback);
        LocalAdmission callbackAdmission = authorities.authorizeLocalVerified(
                plan.localVerification(), Hrm013WorkloadProofV1.sha256(callbackBytes));
        int updated = jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET lifecycle_state=?, completed_at=?, terminal_command_sha256=?,
                       terminal_external_receipt_public_id=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED'
                """, command.status(), Timestamp.from(occurredAt), commandDigest,
                command.externalReceiptPublicId(), command.tenantId(),
                command.dispatchEventId());
        if (updated != 1) throw Hrm013SafeErrorV1.invalidState();
        int inserted = jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_receipt_outbox(
                  tenant_id,callback_event_id,dispatch_event_id,case_public_id,
                  task_public_id,correlation_id,exact_callback_body,
                  callback_payload_sha256,callback_grant_set_id,
                  callback_grant_revision,tenant_grant_revision,
                  authorization_revision,caller_signing_key_id,grant_use_mode,
                  drain_root_work_item_id,drain_root_created_at,
                  drain_root_payload_sha256,drain_continuation_kind,
                  drain_continuation_event_id,drain_continuation_revision,
                  drain_continuation_payload_sha256,next_attempt_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, command.tenantId(), callback.callbackEventId(),
                command.dispatchEventId(), callback.casePublicId(),
                callback.taskPublicId(), callback.correlationId(), callbackBytes,
                callback.payloadSha256(), authority.grantSetId(),
                authority.grantRevision(), authority.tenantGrantRevision(),
                authority.authorizationRevision(), authority.callerSigningKeyId(),
                plan.mode().grantUseMode().name(),
                plan.drain() == null ? null : plan.drain().drainRootWorkItemId(),
                plan.drain() == null ? null
                        : Timestamp.from(plan.drain().drainRootCreatedAt()),
                plan.drain() == null ? null : plan.drain().drainRootPayloadSha256(),
                plan.drain() == null ? null
                        : plan.drain().drainContinuationKind().name(),
                plan.drain() == null ? null
                        : plan.drain().drainContinuationEventId(),
                plan.drain() == null ? null
                        : plan.drain().drainContinuationRevision(),
                plan.drain() == null ? null
                        : plan.drain().drainContinuationPayloadSha256(),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
        audit(command.tenantId(), command.dispatchEventId(),
                "COMPLETED".equals(command.status())
                        ? "TASK_COMPLETED" : "TASK_FAILED",
                "SUCCESS", callback.payloadSha256(), task.correlationId());
        authorities.recheckLocal(callbackAdmission);
        return CompletionResult.completed(new Completion(callback, authority, false));
    }

    public Optional<ReceiptClaim> claimReceipt(long tenantId, Instant now) {
        if (tenantId < 1 || now == null) throw new IllegalArgumentException("claim selector");
        Optional<ReceiptClaim> result = transactions.execute(status -> {
            setTenant(tenantId);
            purgeExpiredReplays(tenantId);
            recoverExpiredClaims(tenantId);
            List<UUID> ids = jdbc.queryForList("""
                    SELECT callback_event_id
                      FROM auth_hrm013_identity_task_receipt_outbox
                     WHERE tenant_id=? AND attempt_count < 10
                       AND next_attempt_at <= statement_timestamp()
                       AND (delivery_state IN ('PENDING','RETRY')
                            OR (delivery_state='CLAIMED'
                                AND claim_until <= statement_timestamp()))
                     ORDER BY created_at,callback_event_id
                     FOR UPDATE SKIP LOCKED LIMIT 1
                    """, UUID.class, tenantId);
            if (ids.isEmpty()) return Optional.empty();
            UUID id = ids.getFirst();
            UUID claimToken = UUID.randomUUID();
            String claimOwner = "dwp-auth-server:hrm013-receipt-relay";
            int changed = jdbc.update("""
                    UPDATE auth_hrm013_identity_task_receipt_outbox
                       SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                           claim_until=statement_timestamp() + INTERVAL '30 seconds',
                           claim_owner=?,claim_token=?,last_error_code=NULL
                     WHERE tenant_id=? AND callback_event_id=?
                       AND attempt_count < 10
                       AND next_attempt_at <= statement_timestamp()
                       AND (delivery_state IN ('PENDING','RETRY')
                            OR (delivery_state='CLAIMED'
                                AND claim_until <= statement_timestamp()))
                    """, claimOwner, claimToken, tenantId, id);
            if (changed != 1) throw Hrm013SafeErrorV1.invalidState();
            return Optional.of(queryReceiptClaim(
                    tenantId, id, claimOwner, claimToken));
        });
        return result == null ? Optional.empty() : result;
    }

    public void receiptDelivered(ReceiptClaim claim, Instant deliveredAt) {
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        mutateReceipt(claim, "DELIVERED", null, null);
    }

    public void receiptFailed(
            ReceiptClaim claim, Instant now, String safeCode, Instant retryAt) {
        if (safeCode == null || !safeCode.matches("[A-Z][A-Z0-9_]{2,31}")) {
            throw new IllegalArgumentException("safeCode");
        }
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(now, "now");
        if (claim.attemptCount() < 10) {
            if (retryAt == null || retryAt.isBefore(now)
                    || retryAt.isAfter(now.plusSeconds(300))
                    || "RESULT_UNKNOWN".equals(safeCode)) {
                throw new IllegalArgumentException("retry bounds");
            }
            mutateReceipt(claim, "RETRY",
                    java.time.Duration.between(now, retryAt).toSeconds(), safeCode);
        } else {
            if (claim.attemptCount() != 10 || retryAt != null
                    || !"RESULT_UNKNOWN".equals(safeCode)) {
                throw new IllegalArgumentException("terminal result");
            }
            mutateReceipt(claim, "DEAD", null, "RESULT_UNKNOWN");
        }
    }

    /** Re-resolves the frozen HRM-008 tuple through the local V271 authority. */
    public HrisServiceGrantAuthorityV1.GrantAuthority currentReceiptAuthority(
            ReceiptClaim claim) {
        Objects.requireNonNull(claim, "claim");
        HrisServiceGrantAuthorityV1.GrantAuthority result = transactions.execute(status -> {
            setTenant(claim.tenantId());
            var body = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                    claim.exactCallbackBody());
            return currentCallbackPreparation(claim, body).verification().authority();
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    /** Signs and authorizes exactly one outbound HRM-008 attempt atomically. */
    public AuthorizedReceiptAttempt authorizeReceiptAttempt(ReceiptClaim claim,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(body, "body");
        AuthorizedReceiptAttempt result = transactions.execute(status -> {
            setTenant(claim.tenantId());
            CallbackPreparation selected = currentCallbackPreparation(claim, body);
            LocalVerification preparation = selected.verification();
            requireClaimAuthority(claim, preparation.authority());
            var lease = credentials.requireCurrent(preparation.authority());
            ReceiptClaim effectiveClaim = claim.withAuthority(
                    selected.mode(), selected.drain());
            var signed = credentials.callbackProof(lease, effectiveClaim, body,
                    clock.instant().plusSeconds(30));
            String proofSha256 = Hrm013WorkloadProofV1.sha256(
                    signed.compact().getBytes(StandardCharsets.US_ASCII));
            LocalAdmission admission = authorities.authorizeLocalVerified(
                    preparation, proofSha256);
            if (admission.response().drainAdmission() != null
                    && signed.expiresAt().isAfter(admission.response()
                            .drainAdmission().drainAuthorizationExpiresAt())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            authorities.recheckLocal(admission);
            var profile = Hrm013WorkloadProofV1.hrm008(
                    Hrm013WorkloadProofV1.TaskOwner.IDENTITY,
                    body.casePublicId(), body.taskPublicId());
            return new AuthorizedReceiptAttempt(
                    Hrm013WorkloadProofV1.headers(
                            profile, signed.compact(), body.correlationId()),
                    lease, admission, effectiveClaim, signed.expiresAt());
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    private CallbackPreparation currentCallbackPreparation(ReceiptClaim claim,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        var newWork = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                claim.tenantGrantRevision(), claim.authorizationRevision());
        try {
            return new CallbackPreparation(prepareCallbackAuthority(
                    claim.tenantId(), claim.correlationId(), newWork, null),
                    newWork, null);
        } catch (Hrm013SafeErrorV1.Failure rejected) {
            var drain = new Hrm013WorkloadProofV1.DrainClaims(
                    claim.drainClaims() == null
                            ? claim.dispatchEventId()
                            : claim.drainClaims().drainRootWorkItemId(),
                    claim.drainClaims() == null
                            ? claim.terminalParentCreatedAt()
                            : claim.drainClaims().drainRootCreatedAt(),
                    claim.drainClaims() == null
                            ? claim.originatingPayloadSha256()
                            : claim.drainClaims().drainRootPayloadSha256(),
                    Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                    body.callbackEventId(), body.ownerRevision(), body.payloadSha256());
            var drainMode = new Hrm013WorkloadProofV1.BusinessAuthority(
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    claim.tenantGrantRevision(), claim.authorizationRevision());
            return new CallbackPreparation(prepareCallbackAuthority(
                    claim.tenantId(), claim.correlationId(), drainMode, drain),
                    drainMode, drain);
        }
    }

    /** Last fail-closed authority/credential check immediately before network I/O. */
    public AuthorizedReceiptAttempt recheckReceiptAttempt(
            AuthorizedReceiptAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        AuthorizedReceiptAttempt current = transactions.execute(status -> {
            setTenant(attempt.localAdmission().preparation().request().tenantId());
            authorities.recheckLocal(attempt.localAdmission());
            var lease = credentials.recheckCurrent(
                    attempt.localAdmission().response().grantAuthority(),
                    attempt.credential(), OUTBOUND_VALIDITY_MARGIN);
            if (attempt.proofExpiresAt().isBefore(
                    clock.instant().plus(OUTBOUND_VALIDITY_MARGIN))) {
                throw Hrm013SafeErrorV1.authorityUnavailable(1);
            }
            ReceiptClaim claim = attempt.claim();
            List<UUID> owned = jdbc.queryForList("""
                    SELECT callback_event_id
                      FROM auth_hrm013_identity_task_receipt_outbox
                     WHERE tenant_id=? AND callback_event_id=?
                       AND delivery_state='CLAIMED' AND attempt_count=?
                       AND claim_owner=? AND claim_token=?
                       AND claim_until=?
                       AND claim_until >= clock_timestamp() + INTERVAL '6 seconds'
                     FOR SHARE
                    """, UUID.class, claim.tenantId(), claim.callbackEventId(),
                    claim.attemptCount(), claim.claimOwner(), claim.claimToken(),
                    Timestamp.from(claim.claimUntil()));
            if (owned.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
            return new AuthorizedReceiptAttempt(
                    attempt.headers(), lease, attempt.localAdmission(), claim,
                    attempt.proofExpiresAt());
        });
        if (current == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return current;
    }

    private static void requireClaimAuthority(ReceiptClaim claim,
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        if (authority.tenantId() != claim.tenantId()
                || !authority.grantSetId().equals(claim.grantSetId())
                || authority.grantRevision() != claim.grantRevision()
                || authority.tenantGrantRevision() != claim.tenantGrantRevision()
                || authority.authorizationRevision() != claim.authorizationRevision()
                || !authority.callerSigningKeyId()
                        .equals(claim.callerSigningKeyId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private LocalAdmission restoreDispatchAdmission(InboxTask task) {
        HrisServiceGrantAuthorityV1.ResolveRequest request =
                HrisServiceGrantAuthorityV1.decodeRequest(
                        mapper, task.dispatchAuthorityRequest());
        HrisServiceGrantAuthorityV1.ResolveResponse response =
                HrisServiceGrantAuthorityV1.decodeResponse(
                        mapper, task.dispatchAuthorityResponse());
        HrisServiceGrantAuthorityV1.ServiceRole role = request.requestingWorkloadId()
                .equals(request.targetCallerWorkloadId())
                ? HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                : HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        Fence fence = response.drainAdmission() == null ? null : new Fence(
                response.drainAdmission().quiesceFenceId(),
                response.grantAuthority().grantSetId(),
                response.drainAdmission().quiesceRevision(), "QUIESCING",
                response.drainAdmission().quiesceEffectiveAt());
        LocalVerification preparation = new LocalVerification(request,
                response.grantAuthority(), role, fence,
                task.dispatchPreparationTransactionId(),
                task.dispatchPreparationSha256());
        return new LocalAdmission(preparation, response, task.workloadProofSha256());
    }

    private void mutateReceipt(ReceiptClaim claim, String target,
            Long retryDelaySeconds, String error) {
        transactions.executeWithoutResult(status -> {
            setTenant(claim.tenantId());
            int changed = jdbc.update("""
                    UPDATE auth_hrm013_identity_task_receipt_outbox
                       SET delivery_state=?,claim_until=NULL,claim_owner=NULL,
                           claim_token=NULL,
                           delivered_at=CASE WHEN ?='DELIVERED'
                               THEN statement_timestamp() ELSE NULL END,
                           next_attempt_at=CASE WHEN ?='RETRY'
                               THEN statement_timestamp() + (? * INTERVAL '1 second')
                               ELSE next_attempt_at END,
                           last_error_code=?
                     WHERE tenant_id=? AND callback_event_id=?
                       AND delivery_state='CLAIMED' AND attempt_count=?
                       AND claim_owner=? AND claim_token=?
                       AND claim_until=? AND claim_until > statement_timestamp()
                    """, target, target, target,
                    retryDelaySeconds == null ? 0L : retryDelaySeconds, error,
                    claim.tenantId(), claim.callbackEventId(), claim.attemptCount(),
                    claim.claimOwner(), claim.claimToken(),
                    Timestamp.from(claim.claimUntil()));
            if (changed != 1) throw Hrm013SafeErrorV1.invalidState();
            String action = switch (target) {
                case "DELIVERED" -> "RECEIPT_DELIVERED";
                case "RETRY" -> "RECEIPT_RETRY";
                default -> "RECEIPT_DEAD";
            };
            audit(claim.tenantId(), claim.dispatchEventId(), action,
                    "RETRY".equals(target) ? "RETRY"
                            : "DELIVERED".equals(target) ? "SUCCESS" : "FAILED",
                    claim.callbackPayloadSha256(), claim.correlationId());
        });
    }

    private LocalVerification prepareDispatchAuthority(
            Hrm013WorkloadProofV1.UntrustedSelector selector, UUID correlationId) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = authorityRequest(
                selector.tenantId(), PROFILE.bindingId(), PROFILE.callerWorkloadId(),
                PROFILE.ownerWorkloadId(), PROFILE.purpose(), selector.authorityMode(),
                selector.drainClaims(), correlationId);
        return authorities.prepareLocalVerification(request);
    }

    private LocalVerification prepareDispatchAuthority(long tenantId,
            UUID correlationId, Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        return authorities.prepareLocalVerification(authorityRequest(
                tenantId, PROFILE.bindingId(), PROFILE.callerWorkloadId(),
                PROFILE.ownerWorkloadId(), PROFILE.purpose(), mode, drain,
                correlationId));
    }

    private LocalVerification prepareCallbackAuthority(
            long tenantId, UUID correlationId,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = authorityRequest(
                tenantId, Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.callerWorkloadId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.ownerWorkloadId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.purpose(), mode,
                drain, correlationId);
        return authorities.prepareLocalVerification(request);
    }

    private CompletionPlan completionPlan(CompletionCommand command, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch, Instant occurredAt) {
        if (command.authorityMode() != null) {
            if (!(command.authorityMode()
                    instanceof Hrm013WorkloadProofV1.BusinessAuthority mode)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            var callback = callback(command, task, command.callbackEventId(), occurredAt);
            requireCompletionDrainLineage(
                    command.tenantId(), task, dispatch, callback, mode,
                    command.drainClaims());
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), mode,
                    command.drainClaims());
            return new CompletionPlan(callback, verification, mode,
                    command.drainClaims());
        }

        var newWork = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                task.tenantGrantRevision(), task.authorizationRevision());
        var callback = callback(command, task, command.callbackEventId(), occurredAt);
        try {
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), newWork, null);
            return new CompletionPlan(callback, verification, newWork, null);
        } catch (Hrm013SafeErrorV1.Failure newWorkRejected) {
            UUID rootId = task.drainRootWorkItemId() == null
                    ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
            Instant rootCreatedAt = task.drainRootCreatedAt() == null
                    ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
            String rootPayload = task.drainRootPayloadSha256() == null
                    ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
            UUID callbackId = Hrm013WorkloadProofV1.terminalChildEventId(
                    command.tenantId(), rootId, dispatch.taskOwnerRevision(),
                    Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId());
            var drainCallback = callback(command, task, callbackId, occurredAt);
            var drain = new Hrm013WorkloadProofV1.DrainClaims(
                    rootId, rootCreatedAt, rootPayload,
                    Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                    callbackId, 1, drainCallback.payloadSha256());
            var drainMode = new Hrm013WorkloadProofV1.BusinessAuthority(
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    task.tenantGrantRevision(), task.authorizationRevision());
            requireCompletionDrainLineage(
                    command.tenantId(), task, dispatch, drainCallback, drainMode, drain);
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), drainMode, drain);
            return new CompletionPlan(drainCallback, verification, drainMode, drain);
        }
    }

    private static Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback(
            CompletionCommand command, InboxTask task, UUID callbackEventId,
            Instant occurredAt) {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, task.casePublicId(), task.taskPublicId(),
                        PROFILE.taskType(), command.status(), 1,
                        command.externalReceiptPublicId(), task.payloadSha256(),
                        occurredAt, "0".repeat(64), task.correlationId()));
    }

    static void requireCompletionDrainLineage(long tenantId,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        requireCompletionDrainLineage(tenantId, null, dispatch, callback, mode, drain);
    }

    private static void requireCompletionDrainLineage(long tenantId, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        if (!mode.drainExisting()) {
            if (drain != null) throw Hrm013SafeErrorV1.authorityDenied();
            return;
        }
        UUID expectedRootId = task == null || task.drainRootWorkItemId() == null
                ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
        Instant expectedRootCreatedAt = task == null || task.drainRootCreatedAt() == null
                ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
        String expectedRootPayload = task == null || task.drainRootPayloadSha256() == null
                ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
        if (drain == null
                || drain.drainContinuationKind()
                != Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD
                || !drain.drainRootWorkItemId().equals(expectedRootId)
                || !drain.drainRootCreatedAt().equals(expectedRootCreatedAt)
                || !drain.drainRootPayloadSha256().equals(expectedRootPayload)
                || !drain.drainContinuationEventId().equals(callback.callbackEventId())
                || drain.drainContinuationRevision() != callback.ownerRevision()
                || !drain.drainContinuationPayloadSha256()
                        .equals(callback.payloadSha256())
                || !Hrm013WorkloadProofV1.terminalChildEventId(
                        tenantId, expectedRootId, callback.ownerRevision(),
                        Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId())
                        .equals(callback.callbackEventId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest authorityRequest(
            long tenantId, String bindingId, String caller, String owner,
            String purpose, Hrm013WorkloadProofV1.AuthorityMode authorityMode,
            Hrm013WorkloadProofV1.DrainClaims drain, UUID correlationId) {
        if (!(authorityMode instanceof Hrm013WorkloadProofV1.BusinessAuthority business)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var mode = business.grantUseMode()
                == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
        HrisServiceGrantAuthorityV1.DrainFields fields = drain == null ? null
                : new HrisServiceGrantAuthorityV1.DrainFields(
                        drain.drainRootWorkItemId(), drain.drainRootCreatedAt(),
                        drain.drainRootPayloadSha256(),
                        HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                                drain.drainContinuationKind().name()),
                        drain.drainContinuationEventId(),
                        drain.drainContinuationRevision(),
                        drain.drainContinuationPayloadSha256());
        return new HrisServiceGrantAuthorityV1.ResolveRequest(
                tenantId, "dwp-auth-server", caller, owner, bindingId, purpose,
                mode, business.tenantGrantRevision(), business.authorizationRevision(),
                correlationId, fields);
    }

    private boolean reserveProofPair(Hrm013WorkloadProofV1.ReplayReservation replay) {
        if (!PROFILE.bindingId().equals(replay.bindingId())
                || !PROFILE.callerWorkloadId().equals(replay.callerWorkloadId())
                || replay.proofIdSha256().equals(replay.requestNonceSha256())) return false;
        Boolean reserved = replayTransactions.execute(status -> {
            setTenant(replay.tenantId());
            Instant now = databaseNow();
            if (!replay.retainUntil().isAfter(now)
                    || replay.retainUntil().isAfter(now.plusSeconds(35))) return false;
            Integer count = jdbc.queryForObject("""
                    WITH inserted AS (
                      INSERT INTO auth_hrm013_identity_task_dispatch_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,
                        token_digest,expires_at,reserved_at)
                      VALUES (?, ?, ?, 'JTI', ?, ?, ?),
                             (?, ?, ?, 'REQUEST_NONCE', ?, ?, ?)
                      ON CONFLICT (tenant_id,token_digest) DO NOTHING
                      RETURNING 1)
                    SELECT count(*) FROM inserted
                    """, Integer.class,
                    replay.tenantId(), replay.bindingId(), replay.callerWorkloadId(),
                    replay.proofIdSha256(), Timestamp.from(replay.retainUntil()),
                    Timestamp.from(now), replay.tenantId(), replay.bindingId(),
                    replay.callerWorkloadId(), replay.requestNonceSha256(),
                    Timestamp.from(replay.retainUntil()), Timestamp.from(now));
            if (count == null || count != 2) status.setRollbackOnly();
            return count != null && count == 2;
        });
        return Boolean.TRUE.equals(reserved);
    }

    private void purgeExpiredReplays(long tenantId) {
        jdbc.queryForObject(
                "SELECT auth_v272_purge_expired_identity_replays(?)",
                Long.class, tenantId);
    }

    private boolean authorityStillBound(
            LocalVerification preparation,
            Hrm013WorkloadProofV1.VerifiedSigningKey key,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        try {
            if (preparation == null) return false;
            var authority = preparation.authority();
            return authority != null
                && authority.tenantId() == claims.tenantId()
                && authority.tenantGrantRevision() == claims.tenantGrantRevision()
                && authority.authorizationRevision() == claims.authorizationRevision()
                && authority.bindingId().equals(PROFILE.bindingId())
                && authority.purpose().equals(PROFILE.purpose())
                && authority.callerWorkloadId().equals(PROFILE.callerWorkloadId())
                && authority.ownerWorkloadId().equals(PROFILE.ownerWorkloadId())
                && authority.callerSigningKeyId().equals(key.keyId());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private HrisServiceGrantAuthorityV1.ResolveResponse requireDispatchAuthority(
            HrisServiceGrantAuthorityV1.ResolveResponse response,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        requireAdmit(response);
        boolean drain = claims.authorityMode().drainExisting();
        HrisServiceGrantAuthorityV1.DrainAdmission admission = response.drainAdmission();
        if (drain != (admission != null)) throw Hrm013SafeErrorV1.authorityDenied();
        if (drain && (admission.attestingServiceRole()
                != HrisServiceGrantAuthorityV1.ServiceRole.OWNER
                || claims.expiresAt().isAfter(admission.drainAuthorizationExpiresAt())
                || !databaseNow().isBefore(admission.drainAuthorizationExpiresAt()))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return response;
    }

    private Instant authorityExpiry(
            HrisServiceGrantAuthorityV1.ResolveResponse response,
            Instant proofExpiry) {
        Instant drainExpiry = response.drainAdmission() == null
                ? proofExpiry : response.drainAdmission().drainAuthorizationExpiresAt();
        return proofExpiry.isBefore(drainExpiry) ? proofExpiry : drainExpiry;
    }

    private void requireBefore(HrisServiceGrantAuthorityV1.ResolveResponse response) {
        if (response.drainAdmission() != null
                && !databaseNow().isBefore(
                        response.drainAdmission().drainAuthorizationExpiresAt())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static HrisServiceGrantAuthorityV1.GrantAuthority requireAdmit(
            HrisServiceGrantAuthorityV1.ResolveResponse response) {
        if (response == null || response.status()
                != HrisServiceGrantAuthorityV1.Status.ADMIT
                || response.grantAuthority() == null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return response.grantAuthority();
    }

    private static PublicKey rsa(HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            var decoder = Base64.getUrlDecoder();
            BigInteger modulus = new BigInteger(1, decoder.decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, decoder.decode(jwk.e()));
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireProofBodyBinding(
            Hrm013WorkloadProofV1.ProofClaims claims,
            Hrm013TaskDispatchV1.IdentityTaskDispatch body) {
        Map<String, Object> values = claims.profileClaims();
        if (!body.correlationId().equals(claims.correlationId())
                || !body.dispatchEventId().equals(values.get("dispatchEventId"))
                || !body.casePublicId().equals(values.get("casePublicId"))
                || !body.taskPublicId().equals(values.get("taskPublicId"))
                || !body.taskType().equals(values.get("taskType"))
                || !Long.valueOf(body.taskOwnerRevision())
                        .equals(values.get("taskOwnerRevision"))
                || !body.payloadSha256().equals(values.get("payloadSha256"))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private long untrustedTenantSelector(String compactProof) {
        try {
            if (compactProof == null
                    || compactProof.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
                throw new IllegalArgumentException();
            }
            String[] segments = compactProof.split("\\.", -1);
            if (segments.length != 3) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(segments[1]);
            if (bytes.length == 0 || bytes.length > 14_336) {
                throw new IllegalArgumentException();
            }
            JsonNode node = mapper.readTree(bytes);
            JsonNode tenant = node == null ? null : node.get("tenantId");
            if (tenant == null || !tenant.isIntegralNumber() || !tenant.canConvertToLong()
                    || tenant.longValue() < 1) throw new IllegalArgumentException();
            return tenant.longValue();
        } catch (RuntimeException | java.io.IOException invalid) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private Optional<StoredAdmission> storedByEvent(long tenantId, UUID eventId) {
        List<StoredAdmission> rows = jdbc.query("""
                SELECT request_body_sha256,admission_receipt,
                       admission_receipt_proof,admission_receipt_sha256
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> new StoredAdmission(rs.getString(1),
                Hrm013TaskDispatchV1.decodeAdmissionReceipt(rs.getBytes(2)),
                rs.getBytes(2), rs.getString(3), rs.getString(4)), tenantId, eventId);
        if (rows.size() > 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.stream().findFirst();
    }

    private Optional<StoredIdentity> storedByTaskVersion(
            long tenantId, UUID taskId, long taskVersion) {
        List<StoredIdentity> rows = jdbc.query("""
                SELECT dispatch_event_id,request_body_sha256
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND task_public_id=? AND task_version=? FOR UPDATE
                """, (rs, row) -> new StoredIdentity(
                rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, taskId, taskVersion);
        return rows.stream().findFirst();
    }

    private InboxTask requireInbox(long tenantId, UUID eventId) {
        List<InboxTask> rows = jdbc.query("""
                SELECT case_public_id,task_public_id,correlation_id,payload_sha256,
                       exact_request_body,lifecycle_state,tenant_grant_revision,
                       authorization_revision,dispatch_grant_use_mode,
                       drain_root_work_item_id,drain_root_created_at,
                       drain_root_payload_sha256,workload_proof_sha256,
                       dispatch_authority_request,dispatch_authority_response,
                       dispatch_preparation_tx_id,dispatch_preparation_sha256,
                       terminal_command_sha256,
                       terminal_external_receipt_public_id,completed_at
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> new InboxTask(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getLong(7),
                rs.getLong(8), rs.getString(9), rs.getObject(10, UUID.class),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(),
                rs.getString(12), rs.getString(13), rs.getBytes(14),
                rs.getBytes(15), rs.getLong(16), rs.getString(17),
                rs.getString(18), rs.getObject(19, UUID.class),
                rs.getTimestamp(20) == null ? null
                        : rs.getTimestamp(20).toInstant()), tenantId, eventId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.notFound();
        return rows.getFirst();
    }

    private Completion requireCompletion(long tenantId, UUID eventId) {
        List<Completion> rows = jdbc.query("""
                SELECT exact_callback_body,callback_grant_set_id,
                       callback_grant_revision,tenant_grant_revision,
                       authorization_revision,caller_signing_key_id
                  FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> {
            var callback = Hrm013SeparationBridgeV1.decodeTaskReceipt(rs.getBytes(1));
            var authority = storedAuthority(tenantId, rs.getObject(2, UUID.class),
                    rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6),
                    Hrm013SeparationBridgeV1.HRM_008_IDENTITY);
            return new Completion(callback, authority, true);
        }, tenantId, eventId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    private ReceiptClaim queryReceiptClaim(long tenantId, UUID callbackId,
            String claimOwner, UUID claimToken) {
        List<ReceiptClaim> rows = jdbc.query("""
                SELECT outbox.dispatch_event_id,outbox.case_public_id,
                       outbox.task_public_id,outbox.correlation_id,
                       outbox.exact_callback_body,outbox.callback_payload_sha256,
                       outbox.callback_grant_set_id,outbox.callback_grant_revision,
                       outbox.tenant_grant_revision,outbox.authorization_revision,
                       outbox.caller_signing_key_id,outbox.attempt_count,
                       outbox.claim_until,outbox.grant_use_mode,
                       outbox.drain_root_work_item_id,outbox.drain_root_created_at,
                       outbox.drain_root_payload_sha256,
                       outbox.drain_continuation_kind,
                       outbox.drain_continuation_event_id,
                       outbox.drain_continuation_revision,
                       outbox.drain_continuation_payload_sha256,
                       inbox.exact_request_body,inbox.payload_sha256
                  FROM auth_hrm013_identity_task_receipt_outbox outbox
                  JOIN auth_hrm013_identity_task_dispatch_inbox inbox
                    ON inbox.tenant_id=outbox.tenant_id
                   AND inbox.dispatch_event_id=outbox.dispatch_event_id
                 WHERE outbox.tenant_id=? AND outbox.callback_event_id=?
                   AND outbox.delivery_state='CLAIMED'
                   AND outbox.claim_owner=? AND outbox.claim_token=? FOR UPDATE
                """, (rs, row) -> {
            var parent = (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                    Hrm013TaskDispatchV1.decode(PROFILE, tenantId, rs.getBytes(22));
            String persistedMode = rs.getString(14);
            return new ReceiptClaim(tenantId, callbackId,
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                    rs.getBytes(5), rs.getString(6), rs.getObject(7, UUID.class),
                    rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getString(11),
                    rs.getInt(12), claimOwner, claimToken,
                    rs.getTimestamp(13).toInstant(), parent.dispatchedAt(),
                    rs.getString(23),
                    new Hrm013WorkloadProofV1.BusinessAuthority(
                            Hrm013WorkloadProofV1.GrantUseMode.valueOf(persistedMode),
                            rs.getLong(9), rs.getLong(10)),
                    "NEW_WORK".equals(persistedMode) ? null
                            : new Hrm013WorkloadProofV1.DrainClaims(
                                    rs.getObject(15, UUID.class),
                                    rs.getTimestamp(16).toInstant(), rs.getString(17),
                                    Hrm013WorkloadProofV1.DrainContinuationKind.valueOf(
                                            rs.getString(18)),
                                    rs.getObject(19, UUID.class), rs.getLong(20),
                                    rs.getString(21)));
        }, tenantId, callbackId, claimOwner, claimToken);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    private HrisServiceGrantAuthorityV1.GrantAuthority storedAuthority(
            long tenantId, UUID grantSetId, long grantRevision,
            long tenantRevision, long authorizationRevision, String keyId,
            Hrm013SeparationBridgeV1.BindingProfile profile) {
        List<HrisServiceGrantAuthorityV1.GrantAuthority> rows = jdbc.query("""
                SELECT grant_set.grant_set_id,grant_set.grant_revision,
                       grant_set.tenant_id,grant_set.tenant_grant_revision,
                       grant_set.authorization_revision,grant_set.decision,
                       binding.caller_workload_id,binding.caller_spiffe_id,
                       binding.caller_signing_key_id,binding.caller_signing_public_jwk::text,
                       binding.owner_workload_id,binding.owner_spiffe_id,
                       binding.owner_signing_key_id,binding.owner_signing_public_jwk::text,
                       binding.binding_id,binding.purpose,
                       grant_set.supersedes_grant_set_id,grant_set.grant_digest,
                       grant_set.recorded_at
                  FROM auth_hris_service_grant_sets grant_set
                  JOIN auth_hris_service_grant_bindings binding
                    ON binding.tenant_id=grant_set.tenant_id
                   AND binding.grant_set_id=grant_set.grant_set_id
                 WHERE grant_set.tenant_id=? AND grant_set.grant_set_id=?
                   AND grant_set.grant_revision=?
                   AND grant_set.tenant_grant_revision=?
                   AND grant_set.authorization_revision=?
                   AND binding.binding_id=? AND binding.caller_workload_id=?
                   AND binding.owner_workload_id=? AND binding.purpose=?
                   AND binding.caller_signing_key_id=?
                """, (rs, row) -> new HrisServiceGrantAuthorityV1.GrantAuthority(
                rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), readJwk(rs.getString(10)),
                rs.getString(11), rs.getString(12), rs.getString(13),
                readJwk(rs.getString(14)), rs.getString(15), rs.getString(16),
                rs.getObject(17, UUID.class), rs.getString(18),
                rs.getTimestamp(19).toInstant()), tenantId, grantSetId, grantRevision,
                tenantRevision, authorizationRevision, profile.bindingId(),
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.purpose(), keyId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
        return rows.getFirst();
    }

    private HrisServiceGrantAuthorityV1.RsaPublicJwk readJwk(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            return new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                    node.path("alg").asText(), node.path("e").asText(),
                    node.path("kid").asText(), node.path("kty").asText(),
                    node.path("n").asText(), node.path("use").asText());
        } catch (RuntimeException | java.io.IOException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private void recoverExpiredClaims(long tenantId) {
        jdbc.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='DEAD',claim_until=NULL,claim_owner=NULL,
                       claim_token=NULL,last_error_code='RESULT_UNKNOWN'
                 WHERE tenant_id=? AND delivery_state='CLAIMED'
                   AND attempt_count=10 AND claim_until <= statement_timestamp()
                """, tenantId);
    }

    private String terminalCommandDigest(CompletionCommand command) {
        var node = mapper.createObjectNode();
        node.put("tenantId", command.tenantId());
        node.put("dispatchEventId", command.dispatchEventId().toString());
        node.put("callbackEventId", command.callbackEventId().toString());
        node.put("externalReceiptPublicId",
                command.externalReceiptPublicId().toString());
        node.put("status", command.status());
        if (command.authorityMode() == null) {
            node.put("authoritySelection", "AUTO_NEW_WORK_THEN_DRAIN");
        } else if (command.authorityMode()
                instanceof Hrm013WorkloadProofV1.BusinessAuthority mode) {
            node.put("grantUseMode", mode.grantUseMode().name());
            node.put("tenantGrantRevision", mode.tenantGrantRevision());
            node.put("authorizationRevision", mode.authorizationRevision());
        } else {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadProofV1.DrainClaims drain = command.drainClaims();
        if (drain != null) {
            node.put("drainRootWorkItemId", drain.drainRootWorkItemId().toString());
            node.put("drainRootCreatedAt", drain.drainRootCreatedAt().toString());
            node.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
            node.put("drainContinuationKind",
                    drain.drainContinuationKind().name());
            node.put("drainContinuationEventId",
                    drain.drainContinuationEventId().toString());
            node.put("drainContinuationRevision",
                    drain.drainContinuationRevision());
            node.put("drainContinuationPayloadSha256",
                    drain.drainContinuationPayloadSha256());
        }
        try {
            return Hrm013WorkloadProofV1.rfc8785Sha256(
                    mapper, mapper.writeValueAsBytes(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private static boolean terminalReplayMatches(CompletionCommand command,
            InboxTask task,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback) {
        return command.callbackEventId().equals(callback.callbackEventId())
                && command.externalReceiptPublicId()
                        .equals(callback.externalReceiptPublicId())
                && command.status().equals(callback.status())
                && task.casePublicId().equals(callback.casePublicId())
                && task.taskPublicId().equals(callback.taskPublicId())
                && task.payloadSha256().equals(callback.originatingPayloadSha256())
                && task.correlationId().equals(callback.correlationId())
                && Objects.equals(task.completedAt(), callback.occurredAt())
                && callback.payloadSha256().equals(
                        Hrm013SeparationBridgeV1.payloadSha256(callback));
    }

    /** Applies the identity-owner side effect under the inbox row lock. */
    private void revokeIdentityAccess(UUID dispatchEventId) {
        List<RevocationOutcome> outcomes = jdbc.query("""
                SELECT subject_state,evidence_sha256
                  FROM public.auth_v272_revoke_identity_access(?)
                """, (rs, row) -> new RevocationOutcome(
                rs.getString(1), rs.getString(2)), dispatchEventId);
        if (outcomes.size() != 1
                || !List.of("ABSENT", "REVOKED", "ALREADY_REVOKED")
                        .contains(outcomes.getFirst().subjectState())
                || outcomes.getFirst().evidenceSha256() == null
                || !outcomes.getFirst().evidenceSha256()
                        .matches("^[a-f0-9]{64}$")) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    static String revocationHistoryEvidence(long tenantId, UUID personPublicId,
            UUID dispatchEventId, UUID correlationId, LocalDate effectiveDate,
            String subjectState) {
        return Hrm013WorkloadProofV1.sha256((tenantId + "\n" + personPublicId
                + "\n" + dispatchEventId + "\n" + correlationId + "\n"
                + effectiveDate + "\n" + subjectState)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void quarantine(long tenantId, UUID eventId, String storedDigest,
            String rejectedDigest, String proofDigest, String reason, UUID correlationId) {
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_quarantine(
                  quarantine_id,tenant_id,dispatch_event_id,stored_request_body_sha256,
                  rejected_request_body_sha256,rejected_workload_proof_sha256,
                  reason_code,correlation_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, eventId, storedDigest,
                rejectedDigest, proofDigest, reason, correlationId);
        audit(tenantId, eventId, "DISPATCH_QUARANTINED", "CONFLICT",
                rejectedDigest, correlationId);
    }

    private void quarantineExecutionPoison(long tenantId, UUID eventId) {
        int inserted = jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_quarantine(
                  quarantine_id,tenant_id,dispatch_event_id,
                  stored_request_body_sha256,rejected_request_body_sha256,
                  rejected_workload_proof_sha256,reason_code,correlation_id)
                SELECT ?,tenant_id,dispatch_event_id,request_body_sha256,
                       request_body_sha256,workload_proof_sha256,
                       'EXECUTION_POISON',correlation_id
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED'
                   AND execution_attempt_count=10
                """, UUID.randomUUID(), tenantId, eventId);
        if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
    }

    private void audit(long tenantId, UUID eventId, String action, String outcome,
            String evidence, UUID correlationId) {
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_audit_events(
                  audit_event_id,tenant_id,dispatch_event_id,action_code,
                  outcome_code,evidence_sha256,correlation_id)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, eventId, action, outcome,
                evidence, correlationId);
    }

    private void setTenant(long tenantId) {
        if (tenantId < 1) throw Hrm013SafeErrorV1.authorityDenied();
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class,
                TENANT_CONTEXT, Long.toString(tenantId));
    }

    private void lock(long tenantId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_IDENTITY_V1:" + tenantId + ":" + key);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class);
        if (value == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return value.toInstant();
    }

    private Instant transactionNow() {
        Timestamp value = jdbc.queryForObject(
                "SELECT transaction_timestamp()", Timestamp.class);
        if (value == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return value.toInstant();
    }

    private static boolean constantEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    static long boundedFullJitterSeconds(UUID eventId, int attempt) {
        if (attempt < 1 || attempt > 10) throw new IllegalArgumentException("attempt");
        long cap = Math.min(300L, 1L << Math.min(8, attempt));
        long seed = eventId.getMostSignificantBits()
                ^ Long.rotateLeft(eventId.getLeastSignificantBits(), 17)
                ^ (0x9e3779b97f4a7c15L * attempt);
        return Math.floorMod(seed, cap + 1L);
    }

    private static final class DueExecutionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final UUID dispatchEventId;
        private DueExecutionFailure(UUID dispatchEventId, RuntimeException cause) {
            super(cause);
            this.dispatchEventId = Objects.requireNonNull(dispatchEventId);
        }
        UUID dispatchEventId() { return dispatchEventId; }
    }

    public record Admission(
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt,
            byte[] exactReceiptBody, String receiptProof, boolean replayed) {
        public Admission {
            Objects.requireNonNull(receipt);
            exactReceiptBody = Objects.requireNonNull(exactReceiptBody).clone();
            if (receiptProof == null || receiptProof.isBlank()
                    || receiptProof.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
                throw new IllegalArgumentException("bounded receipt proof required");
            }
        }
        @Override public byte[] exactReceiptBody() { return exactReceiptBody.clone(); }
    }

    public record CompletionCommand(
            long tenantId, UUID dispatchEventId, UUID callbackEventId,
            UUID externalReceiptPublicId, String status,
            Hrm013WorkloadProofV1.AuthorityMode authorityMode,
            Hrm013WorkloadProofV1.DrainClaims drainClaims) {
        public CompletionCommand {
            if (tenantId < 1) throw new IllegalArgumentException("tenantId");
            Objects.requireNonNull(dispatchEventId); Objects.requireNonNull(callbackEventId);
            Objects.requireNonNull(externalReceiptPublicId);
            if (!List.of("COMPLETED", "FAILED").contains(status)) {
                throw new IllegalArgumentException("status");
            }
            if (authorityMode != null
                    && authorityMode.drainExisting() != (drainClaims != null)) {
                throw new IllegalArgumentException("drain authority");
            }
        }
    }

    public record Completion(
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            boolean replayed) {
        public Completion {
            Objects.requireNonNull(callback); Objects.requireNonNull(authority);
        }
    }

    public record ReceiptClaim(
            long tenantId, UUID callbackEventId, UUID dispatchEventId,
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            byte[] exactCallbackBody, String callbackPayloadSha256,
            UUID grantSetId, long grantRevision, long tenantGrantRevision,
            long authorizationRevision, String callerSigningKeyId,
            int attemptCount, String claimOwner, UUID claimToken,
            Instant claimUntil, Instant terminalParentCreatedAt,
            String originatingPayloadSha256,
            Hrm013WorkloadProofV1.BusinessAuthority authorityMode,
            Hrm013WorkloadProofV1.DrainClaims drainClaims) {
        public ReceiptClaim {
            exactCallbackBody = exactCallbackBody.clone();
            Objects.requireNonNull(claimOwner); Objects.requireNonNull(claimToken);
            Objects.requireNonNull(claimUntil);
            Objects.requireNonNull(terminalParentCreatedAt);
            Objects.requireNonNull(originatingPayloadSha256);
            Objects.requireNonNull(authorityMode);
            if (authorityMode.drainExisting() != (drainClaims != null)) {
                throw new IllegalArgumentException("receipt drain authority");
            }
        }
        @Override public byte[] exactCallbackBody() { return exactCallbackBody.clone(); }
        ReceiptClaim withAuthority(Hrm013WorkloadProofV1.BusinessAuthority mode,
                Hrm013WorkloadProofV1.DrainClaims drain) {
            return new ReceiptClaim(tenantId, callbackEventId, dispatchEventId,
                    casePublicId, taskPublicId, correlationId, exactCallbackBody,
                    callbackPayloadSha256, grantSetId, grantRevision,
                    tenantGrantRevision, authorizationRevision, callerSigningKeyId,
                    attemptCount, claimOwner, claimToken, claimUntil,
                    terminalParentCreatedAt, originatingPayloadSha256, mode, drain);
        }
    }

    public record AuthorizedReceiptAttempt(
            List<Hrm013WorkloadProofV1.Header> headers,
            Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease credential,
            LocalAdmission localAdmission, ReceiptClaim claim,
            Instant proofExpiresAt) {
        public AuthorizedReceiptAttempt {
            headers = List.copyOf(headers);
            Objects.requireNonNull(credential);
            Objects.requireNonNull(localAdmission);
            Objects.requireNonNull(claim);
            Objects.requireNonNull(proofExpiresAt);
        }
    }

    private record StoredAdmission(
            String requestBodySha256,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt,
            byte[] exactReceiptBody, String receiptProof, String receiptSha256) {
        private StoredAdmission { exactReceiptBody = exactReceiptBody.clone(); }
        @Override public byte[] exactReceiptBody() { return exactReceiptBody.clone(); }
    }
    private record StoredIdentity(UUID dispatchEventId, String requestBodySha256) { }
    private record RevocationOutcome(String subjectState, String evidenceSha256) { }
    private record InboxTask(
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            String payloadSha256, byte[] exactRequestBody, String state,
            long tenantGrantRevision, long authorizationRevision,
            String dispatchGrantUseMode, UUID drainRootWorkItemId,
            Instant drainRootCreatedAt, String drainRootPayloadSha256,
            String workloadProofSha256, byte[] dispatchAuthorityRequest,
            byte[] dispatchAuthorityResponse,
            long dispatchPreparationTransactionId,
            String dispatchPreparationSha256,
            String terminalCommandSha256,
            UUID terminalExternalReceiptPublicId, Instant completedAt) {
        private InboxTask {
            exactRequestBody = exactRequestBody.clone();
            dispatchAuthorityRequest = dispatchAuthorityRequest.clone();
            dispatchAuthorityResponse = dispatchAuthorityResponse.clone();
        }
        @Override public byte[] exactRequestBody() { return exactRequestBody.clone(); }
        @Override public byte[] dispatchAuthorityRequest() {
            return dispatchAuthorityRequest.clone();
        }
        @Override public byte[] dispatchAuthorityResponse() {
            return dispatchAuthorityResponse.clone();
        }
    }
    private record CompletionPlan(
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            LocalVerification localVerification,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) { }
    private record CallbackPreparation(LocalVerification verification,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) { }
    private record AdmissionResult(Admission admission, boolean conflict) {
        static AdmissionResult admitted(Admission value) {
            return new AdmissionResult(value, false);
        }
        static AdmissionResult digestConflict() { return new AdmissionResult(null, true); }
    }
    private record CompletionResult(Completion completion, boolean conflict) {
        static CompletionResult completed(Completion value) {
            return new CompletionResult(Objects.requireNonNull(value), false);
        }
        static CompletionResult digestConflict() {
            return new CompletionResult(null, true);
        }
    }
}
