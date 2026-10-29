package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchPersistence.InboxTask;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchPersistence.StoredAdmission;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchPersistence.StoredIdentity;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityDispatchAuthority.CompletionPlan;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalAdmission;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalVerification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HrisServiceGrantQuiesceService authorities;
    private final Hrm013IdentityWorkloadCredentialProvider credentials;
    private final Hrm013IdentityTaskDispatchPersistence persistence;
    private final Hrm013IdentityDispatchAuthority dispatchAuthority;
    private final Hrm013IdentityTaskDispatchWrites writes;

    public Hrm013IdentityTaskDispatchStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper mapper, Clock clock,
            Hrm013IdentityWorkloadCredentialProvider credentials,
            HrisServiceGrantQuiesceService authorities) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setTimeout(30);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.persistence = new Hrm013IdentityTaskDispatchPersistence(
                this.jdbc, transactionManager, this.mapper);
        this.writes = new Hrm013IdentityTaskDispatchWrites(this.jdbc);
        this.dispatchAuthority = new Hrm013IdentityDispatchAuthority(
                this.mapper, this.authorities, this.persistence);

    }

    public Admission admit(
            Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest request) {
        Objects.requireNonNull(request, "request");
        long tenantId = dispatchAuthority.untrustedTenantSelector(request.compactProof());
        AdmissionResult result = transactions.execute(status -> {
            persistence.setTenant(tenantId);
            persistence.purgeExpiredReplays(tenantId);
            AtomicReference<LocalVerification> prepared =
                    new AtomicReference<>();
            Hrm013WorkloadProofV1.VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                    mapper, clock, PROOF_PROFILE, "POST", PROFILE.path(),
                    request.headers(), request.exactBody(), request.transportIdentity(),
                    (profile, selector, keyId) -> {
                        if (selector.tenantId() != tenantId) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        LocalVerification verification = dispatchAuthority.prepareDispatchAuthority(
                                selector, request.correlationId());
                        HrisServiceGrantAuthorityV1.GrantAuthority grant =
                                verification.authority();
                        if (!grant.callerSigningKeyId().equals(keyId)) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        prepared.set(verification);
                        return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                                Hrm013IdentityDispatchAuthority.rsa(grant.callerSigningPublicJwk()),
                                grant.callerSigningKeyId(), grant.callerWorkloadId());
                    }, persistence::reserveProofPair,
                    (profile, key, claims) -> dispatchAuthority.authorityStillBound(
                            prepared.get(), key, claims));

            Hrm013TaskDispatchV1.IdentityTaskDispatch body =
                    (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                            Hrm013TaskDispatchV1.decode(PROFILE, tenantId,
                                    request.exactBody());
            Hrm013IdentityDispatchAuthority.requireProofBodyBinding(proof.claims(), body);
            LocalAdmission localAdmission = authorities.authorizeLocalVerified(
                    Objects.requireNonNull(prepared.get(), "authority preparation"),
                    proof.proofSha256());
            HrisServiceGrantAuthorityV1.ResolveResponse authorityResponse =
                    dispatchAuthority.requireDispatchAuthority(localAdmission.response(), proof.claims());
            HrisServiceGrantAuthorityV1.GrantAuthority authority =
                    Hrm013IdentityDispatchAuthority.requireAdmit(authorityResponse);
            String bodyDigest = Hrm013TaskDispatchV1.requestBodySha256(
                    request.exactBody());
            persistence.lock(tenantId, "event:" + body.dispatchEventId());
            persistence.lock(tenantId, "task:" + body.taskPublicId() + ":" + body.taskVersion());

            Optional<StoredAdmission> byEvent = persistence.storedByEvent(
                    tenantId, body.dispatchEventId());
            if (byEvent.isPresent()) {
                StoredAdmission stored = byEvent.orElseThrow();
                if (!Hrm013IdentityTaskDispatchPersistence.constantEquals(stored.requestBodySha256(), bodyDigest)) {
                    persistence.quarantine(tenantId, body.dispatchEventId(),
                            stored.requestBodySha256(), bodyDigest, proof.proofSha256(),
                            "DISPATCH_DIGEST_CONFLICT", body.correlationId());
                    return AdmissionResult.digestConflict();
                }
                persistence.audit(tenantId, body.dispatchEventId(), "DISPATCH_REPLAYED", "SUCCESS",
                        stored.receiptSha256(), body.correlationId());
                authorities.recheckLocal(localAdmission);
                var ownerLease = credentials.requireOwnerCurrent(authority);
                var freshProof = credentials.admissionReceiptProof(
                        ownerLease, stored.receipt(), stored.exactReceiptBody(),
                        dispatchAuthority.authorityExpiry(authorityResponse, proof.claims().expiresAt()));
                return AdmissionResult.admitted(new Admission(
                        stored.receipt(), stored.exactReceiptBody(),
                        freshProof.compact(), true));
            }

            Optional<StoredIdentity> sameVersion = persistence.storedByTaskVersion(
                    tenantId, body.taskPublicId(), body.taskVersion());
            if (sameVersion.isPresent()) {
                StoredIdentity stored = sameVersion.orElseThrow();
                persistence.quarantine(tenantId, body.dispatchEventId(),
                        stored.requestBodySha256(), bodyDigest, proof.proofSha256(),
                        "TASK_VERSION_CONFLICT", body.correlationId());
                return AdmissionResult.digestConflict();
            }

            Instant admittedAt = persistence.databaseNow();
            var receipt = Hrm013TaskDispatchV1.seal(
                    new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                            body.dispatchEventId(), body.taskPublicId(),
                            body.payloadSha256(), bodyDigest, admittedAt,
                            "0".repeat(64), body.correlationId()));
            byte[] receiptBytes = Hrm013TaskDispatchV1.encode(receipt);
            Instant authorityExpiresAt = dispatchAuthority.authorityExpiry(authorityResponse,
                    proof.claims().expiresAt());
            var ownerLease = credentials.requireOwnerCurrent(authority);
            var receiptProof = credentials.admissionReceiptProof(
                    ownerLease, receipt, receiptBytes, authorityExpiresAt);
            byte[] authorityRequestBytes = HrisServiceGrantAuthorityV1.encodeRequest(
                    mapper, localAdmission.preparation().request());
            byte[] authorityResponseBytes = HrisServiceGrantAuthorityV1.encodeResponse(
                    mapper, localAdmission.response());
            writes.insertAdmission(tenantId, body, bodyDigest, proof, request.exactBody(),
                    authority, authorityRequestBytes, authorityResponseBytes,
                    localAdmission.preparation(), receiptBytes, receiptProof.compact(),
                    receipt.receiptSha256(), admittedAt);
            persistence.audit(tenantId, body.dispatchEventId(), "DISPATCH_ADMITTED", "SUCCESS",
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
            persistence.setTenant(command.tenantId());
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
                persistence.setTenant(tenantId);
                persistence.purgeExpiredReplays(tenantId);
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
                    InboxTask task = persistence.requireInbox(tenantId, eventId);
                    var dispatch = (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                            Hrm013TaskDispatchV1.decode(PROFILE, tenantId,
                                    task.exactRequestBody());
                    LocalAdmission executionAdmission = dispatchAuthority.currentDispatchAdmission(
                            tenantId, task, dispatch);
                    authorities.recheckLocal(executionAdmission);
                    persistence.revokeIdentityAccess(eventId);
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
            persistence.setTenant(tenantId);
            writes.recordExecutionFailure(tenantId, dispatchEventId, persistence,
                    attempt -> boundedFullJitterSeconds(dispatchEventId, attempt));
        });
    }

    private CompletionResult completeInTransaction(CompletionCommand command) {
        persistence.lock(command.tenantId(), "event:" + command.dispatchEventId());
        InboxTask task = persistence.requireInbox(command.tenantId(), command.dispatchEventId());
        String commandDigest = persistence.terminalCommandDigest(command);
        if (!"RECEIVED".equals(task.state())) {
            Completion existing = new Completion(persistence.requireCompletion(
                    command.tenantId(), command.dispatchEventId()));
            if (!task.state().equals(command.status())
                    || task.terminalCommandSha256() == null
                    || !Hrm013IdentityTaskDispatchPersistence.constantEquals(
                            task.terminalCommandSha256(), commandDigest)
                    || !Objects.equals(task.terminalExternalReceiptPublicId(),
                            command.externalReceiptPublicId())
                    || !Hrm013IdentityTaskDispatchPersistence.terminalReplayMatches(command, task,
                            existing.callback())) {
                persistence.quarantine(command.tenantId(), command.dispatchEventId(),
                        task.terminalCommandSha256() == null
                                ? "0".repeat(64) : task.terminalCommandSha256(),
                        commandDigest, commandDigest,
                        "TERMINAL_COMMAND_CONFLICT", task.correlationId());
                return CompletionResult.digestConflict();
            }
            return CompletionResult.completed(existing);
        }
        Instant occurredAt = persistence.transactionNow();
        Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch =
                (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                        Hrm013TaskDispatchV1.decode(PROFILE, command.tenantId(),
                                task.exactRequestBody());
        CompletionPlan plan = dispatchAuthority.completionPlan(command, task, dispatch, occurredAt);
        var callback = plan.callback();
        var authority = plan.localVerification().authority();
        Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                PROFILE, dispatch, Hrm013SeparationBridgeV1.HRM_008_IDENTITY,
                callback);
        byte[] callbackBytes = Hrm013SeparationBridgeV1.encode(callback);
        LocalAdmission callbackAdmission = authorities.authorizeLocalVerified(
                plan.localVerification(), Hrm013WorkloadProofV1.sha256(callbackBytes));
        writes.complete(command, commandDigest, callback, callbackBytes, authority,
                plan.mode(), plan.drain(), occurredAt);
        persistence.audit(command.tenantId(), command.dispatchEventId(),
                "COMPLETED".equals(command.status())
                        ? "TASK_COMPLETED" : "TASK_FAILED",
                "SUCCESS", callback.payloadSha256(), task.correlationId());
        authorities.recheckLocal(callbackAdmission);
        return CompletionResult.completed(new Completion(callback, authority, false));
    }

    public Optional<ReceiptClaim> claimReceipt(long tenantId, Instant now) {
        if (tenantId < 1 || now == null) throw new IllegalArgumentException("claim selector");
        Optional<ReceiptClaim> result = transactions.execute(status -> {
            persistence.setTenant(tenantId);
            persistence.purgeExpiredReplays(tenantId);
            persistence.recoverExpiredClaims(tenantId);
            var selected = writes.claimNextReceipt(tenantId);
            if (selected == null) return Optional.empty();
            return Optional.of(new ReceiptClaim(persistence.queryReceiptClaim(
                    tenantId, selected.callbackEventId(), selected.owner(), selected.token())));
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
            persistence.setTenant(claim.tenantId());
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
            persistence.setTenant(claim.tenantId());
            CallbackPreparation selected = currentCallbackPreparation(claim, body);
            LocalVerification preparation = selected.verification();
            requireClaimAuthority(claim, preparation.authority());
            var lease = credentials.requireCurrent(preparation.authority());
            ReceiptClaim effectiveClaim = claim.withAuthority(
                    selected.mode(), selected.drain());
            var signed = credentials.callbackProof(lease, effectiveClaim.proofContext(), body,
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
            return new CallbackPreparation(dispatchAuthority.prepareCallbackAuthority(
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
            return new CallbackPreparation(dispatchAuthority.prepareCallbackAuthority(
                    claim.tenantId(), claim.correlationId(), drainMode, drain),
                    drainMode, drain);
        }
    }

    /** Last fail-closed authority/credential check immediately before network I/O. */
    public AuthorizedReceiptAttempt recheckReceiptAttempt(
            AuthorizedReceiptAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        AuthorizedReceiptAttempt current = transactions.execute(status -> {
            persistence.setTenant(attempt.localAdmission().preparation().request().tenantId());
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

    private void mutateReceipt(ReceiptClaim claim, String target,
            Long retryDelaySeconds, String error) {
        transactions.executeWithoutResult(status -> {
            persistence.setTenant(claim.tenantId());
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
            persistence.audit(claim.tenantId(), claim.dispatchEventId(), action,
                    "RETRY".equals(target) ? "RETRY"
                            : "DELIVERED".equals(target) ? "SUCCESS" : "FAILED",
                    claim.callbackPayloadSha256(), claim.correlationId());
        });
    }

    private record CallbackPreparation(LocalVerification verification,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) { }
    static String revocationHistoryEvidence(long tenantId, UUID personPublicId,
            UUID dispatchEventId, UUID correlationId, LocalDate effectiveDate,
            String subjectState) {
        return Hrm013WorkloadProofV1.sha256((tenantId + "\n" + personPublicId
                + "\n" + dispatchEventId + "\n" + correlationId + "\n"
                + effectiveDate + "\n" + subjectState)
                .getBytes(StandardCharsets.UTF_8));
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

    static void requireCompletionDrainLineage(long tenantId,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        Hrm013IdentityDispatchAuthority.requireCompletionDrainLineage(
                tenantId, null, dispatch, callback, mode, drain);
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
            Hrm013WorkloadProofV1.DrainClaims drainClaims)
            implements Hrm013IdentityTerminalCommand {
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
        private Completion(Hrm013IdentityTaskDispatchPersistence.StoredCompletion stored) {
            this(stored.callback(), stored.authority(), stored.replayed());
        }
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
        private ReceiptClaim(Hrm013IdentityTaskDispatchPersistence.StoredReceiptClaim stored) {
            this(stored.tenantId(), stored.callbackEventId(), stored.dispatchEventId(),
                    stored.casePublicId(), stored.taskPublicId(), stored.correlationId(),
                    stored.exactCallbackBody(), stored.callbackPayloadSha256(),
                    stored.grantSetId(), stored.grantRevision(), stored.tenantGrantRevision(),
                    stored.authorizationRevision(), stored.callerSigningKeyId(),
                    stored.attemptCount(), stored.claimOwner(), stored.claimToken(),
                    stored.claimUntil(), stored.terminalParentCreatedAt(),
                    stored.originatingPayloadSha256(), stored.authorityMode(), stored.drainClaims());
        }
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
        Hrm013IdentityReceiptProofContext proofContext() {
            return new Hrm013IdentityReceiptProofContext(
                    exactCallbackBody, authorityMode, drainClaims);
        }
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
