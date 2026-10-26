package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;

/** Receipt-first callback owner. Exact replay avoids every aggregate read. */
public final class Hrm013CallbackService {
    private final Hrm013PersistencePorts.CallbackReceiptStore receipts;
    private final Hrm013PersistencePorts.CallbackAuthorityStore authorities;
    private final Hrm013PersistencePorts.WaiverRequestOutboxStore waivers;
    private final JdbcHrm013OwnerStore owner;
    private final Hrm013ServiceAuthorityPort serviceAuthority;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public Hrm013CallbackService(
            Hrm013PersistencePorts.CallbackReceiptStore receipts,
            Hrm013PersistencePorts.CallbackAuthorityStore authorities,
            Hrm013PersistencePorts.WaiverRequestOutboxStore waivers,
            JdbcHrm013OwnerStore owner,
            Hrm013ServiceAuthorityPort serviceAuthority,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.receipts = java.util.Objects.requireNonNull(receipts);
        this.authorities = java.util.Objects.requireNonNull(authorities);
        this.waivers = java.util.Objects.requireNonNull(waivers);
        this.owner = java.util.Objects.requireNonNull(owner);
        this.serviceAuthority = java.util.Objects.requireNonNull(serviceAuthority);
        this.clock = java.util.Objects.requireNonNull(clock);
        required = new TransactionTemplate(transactions);
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        required.setTimeout(30);
        requiresNew = new TransactionTemplate(transactions);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public byte[] accept(Hrm013CallbackRequestContext context) {
        long tenantId = context.verified().proof().claims().tenantId();
        UUID eventId = callbackEventId(context.body());
        CallbackIdentity identity = new CallbackIdentity(
                tenantId, context.route().bindingProfile().callerWorkloadId(), eventId);
        String requestDigest = Hrm013SeparationBridgeV1.requestBodySha256(
                context.verified().exactBody());
        String commandDigest = context.verified().proof().commandDigest();
        try {
            byte[] response = required.execute(status -> {
                Optional<CallbackReceiptRecord> prior = receipts.findForUpdate(identity);
                if (prior.isPresent()) {
                    CallbackReceiptRecord stored = prior.orElseThrow();
                    if (!stored.commandDigest().equals(commandDigest)
                            || !stored.requestBodySha256().equals(requestDigest)) {
                        throw new DigestConflict();
                    }
                    return stored.exactResponseBody();
                }

                CallbackAuthorityKey key = authorityKey(context, tenantId);
                CallbackAuthorityRecord fence = authorities.latestForUpdate(key)
                        .filter(value -> "ADMIT".equals(value.decision()))
                        .orElseThrow(Hrm013SafeErrorV1::authorityDenied);
                requireAuthority(context, fence);

                CallbackReceiptRecord record = applyAndRecord(
                        context, identity, fence, requestDigest, commandDigest);
                receipts.append(record);
                authorities.append(close(fence, record));
                requireFinalAuthority(context, tenantId);
                return record.exactResponseBody();
            });
            if (response == null) throw Hrm013SafeErrorV1.invalidState();
            return response;
        } catch (DigestConflict conflict) {
            requiresNew.executeWithoutResult(status -> owner.quarantine(
                    tenantId, eventId, context.route().correlationId(),
                    requestDigest, commandDigest));
            throw Hrm013SafeErrorV1.digestConflict();
        } catch (DataIntegrityViolationException uniqueCollision) {
            requiresNew.executeWithoutResult(status -> owner.quarantine(
                    tenantId, eventId, context.route().correlationId(),
                    requestDigest, commandDigest));
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    private CallbackReceiptRecord applyAndRecord(
            Hrm013CallbackRequestContext context,
            CallbackIdentity identity,
            CallbackAuthorityRecord fence,
            String requestDigest,
            String commandDigest) {
        Instant receivedAt = clock.instant();
        var body = context.body();
        long caseId = fence.separationCaseId();
        long taskId = fence.separationTaskId();
        UUID externalReceipt = null;
        String callbackType;
        String callbackValue;
        UUID approvalRequest = null;
        String waiverDecision = null;
        Long decisionRevision = null;
        UUID decisionActor = null;
        UUID caseMaker = null;
        UUID requestedBy = null;
        String reasonCode = null;
        UUID stepUpId = null;
        String stepUpAudience = null;
        String stepUpPurpose = null;
        String stepUpNonceDigest = null;
        String stepUpDigest = null;
        Instant stepUpVerified = null;
        Instant stepUpConsumed = null;
        Instant occurredAt;

        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value) {
            JdbcHrm013OwnerStore.OwnerTask task = owner.requireTaskForUpdate(
                    identity.tenantId(), value.casePublicId(), value.taskPublicId());
            requireAggregateFence(task, fence);
            Hrm013TaskDispatchV1.Profile dispatchProfile =
                    Hrm013TaskDispatchV1.PROFILES.stream()
                            .filter(profile -> profile.taskType().equals(task.taskType()))
                            .findFirst().orElseThrow(Hrm013SafeErrorV1::authorityDenied);
            UUID rootId = Hrm013TaskDispatchV1.dispatchEventId(
                    identity.tenantId(), task.taskPublicId(), task.taskVersion(),
                    dispatchProfile.bindingId());
            requireDrainLineage(context, fence, rootId,
                    owner.requireDispatchOccurredAt(identity.tenantId(), rootId), true);
            owner.applyTaskReceipt(task, value);
            callbackType = "TASK_RECEIPT";
            callbackValue = value.status();
            externalReceipt = value.externalReceiptPublicId();
            occurredAt = value.occurredAt();
        } else if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value) {
            WaiverRequestFence waiver = waivers.find(identity.tenantId(),
                    value.approvalRequestId()).orElseThrow(Hrm013SafeErrorV1::notFound);
            if (!waiver.casePublicId().equals(value.casePublicId())
                    || !waiver.taskPublicId().equals(value.taskPublicId())
                    || !waiver.approvalRequestPayloadSha256()
                    .equals(value.originatingPayloadSha256())
                    || value.decisionActorPublicId().equals(waiver.caseMakerPublicId())
                    || value.decisionActorPublicId().equals(
                    waiver.requestedByActorPublicId())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            requireDrainLineage(context, fence, waiver.approvalRequestId(),
                    waiver.createdAt(), true);
            JdbcHrm013OwnerStore.OwnerTask task = owner.requireTaskForUpdate(
                    identity.tenantId(), value.casePublicId(), value.taskPublicId());
            requireAggregateFence(task, fence);
            owner.applyWaiverDecision(task, value);
            callbackType = "APPROVAL_DECISION";
            callbackValue = value.decision();
            approvalRequest = value.approvalRequestId();
            waiverDecision = value.decision();
            decisionRevision = value.decisionRevision();
            decisionActor = value.decisionActorPublicId();
            caseMaker = waiver.caseMakerPublicId();
            requestedBy = waiver.requestedByActorPublicId();
            reasonCode = value.reasonCode();
            if ("APPROVED".equals(value.decision())) {
                stepUpId = value.stepUpEvidenceId();
                stepUpAudience = value.stepUpEvidenceAudience();
                stepUpPurpose = value.stepUpEvidencePurpose();
                stepUpNonceDigest = Hrm013PersistenceModels.tokenDigest(
                        value.stepUpEvidenceNonce());
                stepUpDigest = value.stepUpEvidenceDigest();
                stepUpVerified = value.stepUpVerifiedAt();
                stepUpConsumed = value.stepUpConsumedAt();
            }
            occurredAt = value.occurredAt();
        } else if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value) {
            long lockedCaseId = owner.requireCaseIdForUpdate(
                    identity.tenantId(), value.casePublicId());
            if (lockedCaseId != fence.separationCaseId()) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            requireDrainLineage(context, fence, value.callbackEventId(),
                    value.occurredAt(), false);
            owner.applyReconciliation(identity.tenantId(), value);
            callbackType = "RECONCILIATION_RESULT";
            callbackValue = value.result();
            occurredAt = value.occurredAt();
            taskId = 0;
        } else {
            throw Hrm013SafeErrorV1.malformed();
        }

        UUID receiptId = UUID.randomUUID();
        var response = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                receiptId, identity.callbackEventId(), callbackType,
                context.route().casePublicId(), context.route().taskPublicId(),
                fence.expectedNextOwnerRevision(), "ACCEPTED",
                context.route().correlationId(), commandDigest, receivedAt);
        byte[] exactResponse = Hrm013SeparationBridgeV1.encode(response);
        return new CallbackReceiptRecord(
                identity, caseId, taskId, callbackType, callbackValue,
                externalReceipt, fence.expectedNextOwnerRevision(),
                fence.tenantGrantRevision(), fence.authorizationRevision(),
                body.payloadSha256(), fence.originatingPayloadSha256(), commandDigest,
                requestDigest, "dwp-people-server", fence.key().purposeCode(),
                context.route().correlationId(), approvalRequest, waiverDecision,
                decisionRevision, decisionActor, caseMaker, requestedBy, reasonCode,
                stepUpId, stepUpAudience, stepUpPurpose, stepUpNonceDigest,
                stepUpDigest, stepUpVerified, stepUpConsumed, receiptId,
                exactResponse, Hrm013PersistenceModels.sha256(exactResponse),
                "ACCEPTED", occurredAt, receivedAt);
    }

    private static CallbackAuthorityKey authorityKey(
            Hrm013CallbackRequestContext context, long tenantId) {
        UUID approval = context.body()
                instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value
                ? value.approvalRequestId() : null;
        return new CallbackAuthorityKey(
                tenantId, context.route().bindingProfile().bindingId(),
                context.route().bindingProfile().callerWorkloadId(),
                context.route().casePublicId(), context.route().taskPublicId(),
                context.route().bindingProfile().purpose(), approval);
    }

    static void requireAuthority(
            Hrm013CallbackRequestContext context, CallbackAuthorityRecord fence) {
        var claims = context.verified().proof().claims();
        if (fence.tenantGrantRevision() != claims.tenantGrantRevision()
                || fence.authorizationRevision() != claims.authorizationRevision()
                || fence.expectedNextOwnerRevision() != ownerRevision(context.body())
                || !fence.originatingPayloadSha256().equals(
                originatingPayload(context.body()))
                || !fence.correlationId().equals(context.route().correlationId())
                || !"dwp-people-server".equals(fence.audience())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        if (context.body() instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value) {
            if (!"TASK_RECEIPT".equals(fence.callbackType())
                    || !value.taskType().equals(fence.taskType())
                    || fence.separationTaskId() < 1
                    || fence.consumerEventId() != null
                    || fence.key().approvalRequestId() != null) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        } else if (context.body()
                instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value) {
            if (!"APPROVAL_DECISION".equals(fence.callbackType())
                    || fence.taskType() == null || fence.separationTaskId() < 1
                    || fence.consumerEventId() != null
                    || value.ownerRevision() != 1
                    || value.decisionRevision() != 1
                    || !value.approvalRequestId().equals(
                    fence.key().approvalRequestId())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        } else if (context.body()
                instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value) {
            if (!"RECONCILIATION_RESULT".equals(fence.callbackType())
                    || fence.taskType() != null || fence.separationTaskId() != 0
                    || fence.consumerEventId() == null
                    || !fence.consumerEventId().equals(value.callbackEventId())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        }
    }

    private static void requireAggregateFence(
            JdbcHrm013OwnerStore.OwnerTask task, CallbackAuthorityRecord fence) {
        if (task.separationCaseId() != fence.separationCaseId()
                || task.separationTaskId() != fence.separationTaskId()
                || !task.casePublicId().equals(fence.key().casePublicId())
                || !task.taskPublicId().equals(fence.key().taskPublicId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static CallbackAuthorityRecord close(
            CallbackAuthorityRecord fence, CallbackReceiptRecord receipt) {
        Instant closed = receipt.receivedAt();
        String digest = Hrm013PersistenceModels.sha256((fence.authorityDigest() + "\n"
                + receipt.commandDigest() + "\n" + receipt.responseBodySha256())
                .getBytes(StandardCharsets.UTF_8));
        return new CallbackAuthorityRecord(
                UUID.randomUUID(), fence.key(), fence.separationCaseId(),
                fence.separationTaskId(), fence.callbackType(), fence.audience(),
                fence.taskType(), fence.consumerEventId(), fence.tenantGrantRevision(),
                fence.authorizationRevision(), fence.expectedNextOwnerRevision(),
                fence.correlationId(), fence.originatingPayloadSha256(),
                fence.authorityRevision() + 1, "REVOKE", fence.publicId(), digest,
                closed, closed);
    }

    static void requireDrainLineage(
            Hrm013CallbackRequestContext context, CallbackAuthorityRecord fence,
            UUID terminalParentId, Instant terminalParentCreatedAt,
            boolean terminalChildAllowed) {
        var claims = context.verified().proof().claims();
        if (claims.grantUseMode()
                != Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING) return;
        Hrm013WorkloadProofV1.DrainClaims drain = claims.drainClaims();
        UUID callbackId = callbackEventId(context.body());
        if (drain == null) throw Hrm013SafeErrorV1.authorityDenied();
        if (drain.drainContinuationKind()
                == Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY) {
            if (!drain.drainRootWorkItemId().equals(callbackId)
                    || !drain.drainContinuationEventId().equals(callbackId)
                    || drain.drainContinuationRevision() != 0
                    || !drain.drainRootCreatedAt().equals(
                    occurredAt(context.body()))
                    || !drain.drainRootPayloadSha256().equals(
                    context.body().payloadSha256())
                    || !drain.drainContinuationPayloadSha256().equals(
                    context.body().payloadSha256())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            return;
        }
        if (!terminalChildAllowed
                || drain.drainContinuationKind()
                != Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD
                || !drain.drainRootWorkItemId().equals(terminalParentId)
                || !drain.drainRootCreatedAt().equals(terminalParentCreatedAt)
                || !drain.drainRootPayloadSha256().equals(
                fence.originatingPayloadSha256())
                || !drain.drainContinuationEventId().equals(callbackId)
                || !drain.drainContinuationPayloadSha256().equals(
                context.body().payloadSha256())
                || drain.drainContinuationRevision()
                != ownerRevision(context.body())
                || drain.drainContinuationRevision()
                != fence.expectedNextOwnerRevision()
                || !Hrm013WorkloadProofV1.terminalChildEventId(
                fence.key().tenantId(), terminalParentId,
                drain.drainContinuationRevision(), fence.key().bindingId())
                .equals(callbackId)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static UUID callbackEventId(Hrm013SeparationBridgeV1.PayloadBody body) {
        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value)
            return value.callbackEventId();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value)
            return value.callbackEventId();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value)
            return value.callbackEventId();
        throw Hrm013SafeErrorV1.malformed();
    }

    private void requireFinalAuthority(
            Hrm013CallbackRequestContext context, long tenantId) {
        Hrm013ServiceAuthorityPort.SigningAuthority verified =
                context.verified().authority();
        if (!serviceAuthority.remainsCurrent(verified)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Instant deadline = context.verified().proof().claims().expiresAt();
        if (verified.drainAdmission() != null
                && verified.drainAdmission().drainAuthorizationExpiresAt()
                .isBefore(deadline)) {
            deadline = verified.drainAdmission().drainAuthorizationExpiresAt();
        }
        owner.requireDatabaseTimeBefore(tenantId, deadline);
    }

    private static long ownerRevision(Hrm013SeparationBridgeV1.PayloadBody body) {
        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value)
            return value.ownerRevision();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value)
            return value.ownerRevision();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value)
            return value.ownerRevision();
        throw Hrm013SafeErrorV1.malformed();
    }

    private static Instant occurredAt(Hrm013SeparationBridgeV1.PayloadBody body) {
        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value)
            return value.occurredAt();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value)
            return value.occurredAt();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value)
            return value.occurredAt();
        throw Hrm013SafeErrorV1.malformed();
    }

    private static String originatingPayload(Hrm013SeparationBridgeV1.PayloadBody body) {
        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value)
            return value.originatingPayloadSha256();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value)
            return value.originatingPayloadSha256();
        if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value)
            return value.originatingPayloadSha256();
        throw Hrm013SafeErrorV1.malformed();
    }

    private static final class DigestConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
