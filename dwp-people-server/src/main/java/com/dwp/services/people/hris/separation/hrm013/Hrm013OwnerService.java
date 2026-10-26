package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;

/** Atomic producer for dispatch, waiver and reconciliation protocol fences. */
public final class Hrm013OwnerService implements SeparationReconciliationPort {
    private final JdbcHrm013OwnerStore owner;
    private final Hrm013PersistencePorts.TaskDispatchStore dispatches;
    private final Hrm013PersistencePorts.WaiverRequestOutboxStore waivers;
    private final Hrm013PersistencePorts.CallbackAuthorityStore callbackAuthorities;
    private final Hrm013ServiceAuthorityPort authority;
    private final Hrm013WorkloadCredentialProvider credentials;
    private final TransactionTemplate required;
    private final Clock clock;

    public Hrm013OwnerService(
            JdbcHrm013OwnerStore owner,
            Hrm013PersistencePorts.TaskDispatchStore dispatches,
            Hrm013PersistencePorts.WaiverRequestOutboxStore waivers,
            Hrm013PersistencePorts.CallbackAuthorityStore callbackAuthorities,
            Hrm013ServiceAuthorityPort authority,
            Hrm013WorkloadCredentialProvider credentials,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.owner = Objects.requireNonNull(owner);
        this.dispatches = Objects.requireNonNull(dispatches);
        this.waivers = Objects.requireNonNull(waivers);
        this.callbackAuthorities = Objects.requireNonNull(callbackAuthorities);
        this.authority = Objects.requireNonNull(authority);
        this.credentials = Objects.requireNonNull(credentials);
        this.clock = Objects.requireNonNull(clock);
        required = new TransactionTemplate(transactions);
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        required.setTimeout(30);
    }

    public TaskDispatchFence activateTask(
            long tenantId,
            Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body) {
        Objects.requireNonNull(profile); Objects.requireNonNull(body);
        Hrm013ServiceAuthorityPort.SigningAuthority dispatchAuthority =
                resolveAuthority(tenantId, profile.bindingId(),
                "dwp-people-server", profile.ownerWorkloadId(), profile.purpose());
        Hrm013ServiceAuthorityPort.SigningAuthority callbackAuthority =
                resolveAuthority(tenantId, profile.callbackBindingId(),
                profile.ownerWorkloadId(), "dwp-people-server",
                profile.callbackPurpose());
        return activateTaskAuthorized(
                tenantId, profile, body, dispatchAuthority, callbackAuthority);
    }

    private TaskDispatchFence activateTaskAuthorized(
            long tenantId,
            Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body,
            Hrm013ServiceAuthorityPort.SigningAuthority dispatchAuthority,
            Hrm013ServiceAuthorityPort.SigningAuthority callbackAuthority) {
        Hrm013TaskDispatchV1.requireProfileBinding(profile, tenantId, body);
        byte[] exactBody = Hrm013TaskDispatchV1.encode(profile, tenantId, body);
        TaskDispatchFence result = required.execute(status -> {
            if (!authority.remainsCurrent(dispatchAuthority)
                    || !authority.remainsCurrent(callbackAuthority)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            JdbcHrm013OwnerStore.OwnerTask task = owner.requireTaskForUpdate(
                    tenantId, body.casePublicId(), body.taskPublicId());
            if (!task.taskType().equals(profile.taskType())
                    || !task.ownerWorkloadId().equals(profile.ownerWorkloadId())
                    || body.taskVersion() != task.taskVersion() + 1) {
                throw Hrm013SafeErrorV1.revisionConflict();
            }
            owner.activateDispatch(task, body.payloadSha256(), task.taskVersion(),
                    body.taskOwnerRevision());
            Instant now = clock.instant();
            CallbackAuthorityRecord callbackFence = new CallbackAuthorityRecord(
                    UUID.randomUUID(), new CallbackAuthorityKey(
                    tenantId, profile.callbackBindingId(), profile.ownerWorkloadId(),
                    body.casePublicId(), body.taskPublicId(), profile.callbackPurpose(), null),
                    task.separationCaseId(), task.separationTaskId(), "TASK_RECEIPT",
                    "dwp-people-server", profile.taskType(), null,
                    callbackAuthority.grant().tenantGrantRevision(),
                    callbackAuthority.grant().authorizationRevision(),
                    body.taskOwnerRevision(), body.correlationId(), body.payloadSha256(),
                    1, "ADMIT", null,
                    authorityDigest(tenantId, profile.callbackBindingId(),
                            body.dispatchEventId(), body.payloadSha256(),
                            callbackAuthority.grant().tenantGrantRevision(),
                            callbackAuthority.grant().authorizationRevision()), now, null);
            TaskDispatchFence dispatchFence = new TaskDispatchFence(
                    body.dispatchEventId(), tenantId, body.casePublicId(),
                    body.taskPublicId(), profile.bindingId(), profile.ownerWorkloadId(),
                    profile.purpose(), profile.taskType(), profile.eventType(),
                    body.taskVersion(), body.taskOwnerRevision(), exactBody,
                    Hrm013TaskDispatchV1.requestBodySha256(exactBody),
                    body.payloadSha256(), body.correlationId(),
                    dispatchAuthority.grant().tenantGrantRevision(),
                    dispatchAuthority.grant().authorizationRevision(),
                    callbackAuthority.grant().tenantGrantRevision(),
                    callbackAuthority.grant().authorizationRevision(), body.dispatchedAt());
            return dispatches.insert(dispatchFence, callbackFence);
        });
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    /** Creates the case, complete task set, dispatches and HRM-008 fences atomically. */
    public List<TaskDispatchFence> createCaseAndActivateTasks(
            CaseCreation command, List<PlannedDispatch> plans) {
        Objects.requireNonNull(command, "command");
        plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        if (plans.size() != Hrm013TaskDispatchV1.PROFILES.size()) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
        java.util.Set<String> taskTypes = new java.util.HashSet<>();
        java.util.ArrayList<AuthorizedDispatch> authorized = new java.util.ArrayList<>();
        for (PlannedDispatch plan : plans) {
            if (!taskTypes.add(plan.profile().taskType())
                    || !plan.body().casePublicId().equals(command.casePublicId())
                    || !plan.body().correlationId().equals(command.correlationId())
                    || plan.body().taskVersion() != 1
                    || plan.body().taskOwnerRevision() != 1) {
                throw Hrm013SafeErrorV1.digestConflict();
            }
            Hrm013TaskDispatchV1.requireProfileBinding(
                    plan.profile(), command.tenantId(), plan.body());
            authorized.add(new AuthorizedDispatch(plan,
                    resolveAuthority(command.tenantId(), plan.profile().bindingId(),
                            "dwp-people-server", plan.profile().ownerWorkloadId(),
                            plan.profile().purpose()),
                    resolveAuthority(command.tenantId(),
                            plan.profile().callbackBindingId(),
                            plan.profile().ownerWorkloadId(), "dwp-people-server",
                            plan.profile().callbackPurpose())));
        }
        java.util.Set<String> requiredTaskTypes = Hrm013TaskDispatchV1.PROFILES.stream()
                .map(Hrm013TaskDispatchV1.Profile::taskType)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!taskTypes.equals(requiredTaskTypes)) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
        List<PlannedDispatch> stable = plans;
        List<TaskDispatchFence> result = required.execute(status -> {
            boolean replay = owner.createCaseAndTasks(command, stable);
            if (replay) {
                return authorized.stream().map(packet -> {
                    PlannedDispatch plan = packet.plan();
                    TaskDispatchFence stored = dispatches.requireStoredIntegrity(
                            plan.body().dispatchEventId(), command.tenantId());
                    TaskDispatchFence expected = dispatchFence(
                            command.tenantId(), plan.profile(), plan.body(),
                            packet.dispatchAuthority(), packet.callbackAuthority());
                    if (!sameDispatch(stored, expected)) {
                        throw Hrm013SafeErrorV1.digestConflict();
                    }
                    return stored;
                }).toList();
            }
            java.util.ArrayList<TaskDispatchFence> created = new java.util.ArrayList<>();
            for (AuthorizedDispatch packet : authorized) {
                PlannedDispatch plan = packet.plan();
                created.add(activateTaskAuthorized(
                        command.tenantId(), plan.profile(), plan.body(),
                        packet.dispatchAuthority(), packet.callbackAuthority()));
            }
            return List.copyOf(created);
        });
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    public WaiverRequestFence createWaiverRequest(
            WaiverRequestFence fence,
            String taskType) {
        var body = Hrm013SeparationBridgeV1.decodeWaiverRequest(
                fence.exactRequestBody());
        Hrm013SeparationBridgeV1.requireBodyBinding(
                Hrm013SeparationBridgeV1.APR_002, body);
        Hrm013SeparationBridgeV1.requirePayloadSha256(body);
        String expectedCommand = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.APR_002,
                Hrm013SeparationBridgeV1.APR_002.path(null, null),
                fence.tenantId(), fence.exactRequestBody());
        if (!body.approvalRequestId().equals(fence.approvalRequestId())
                || !body.casePublicId().equals(fence.casePublicId())
                || !body.taskPublicId().equals(fence.taskPublicId())
                || !body.taskType().equals(taskType)
                || !body.caseMakerPublicId().equals(fence.caseMakerPublicId())
                || !body.requestedByActorPublicId().equals(
                fence.requestedByActorPublicId())
                || !body.reasonCode().equals(fence.reasonCode())
                || body.taskOwnerRevision() != fence.taskOwnerRevision()
                || body.taskVersion() != fence.taskVersion()
                || !body.payloadSha256().equals(
                fence.approvalRequestPayloadSha256())
                || !body.correlationId().equals(fence.correlationId())
                || !body.requestedAt().equals(fence.createdAt())
                || !Hrm013SeparationBridgeV1.requestBodySha256(
                fence.exactRequestBody()).equals(fence.requestBodySha256())
                || !expectedCommand.equals(fence.commandDigest())) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
        Hrm013ServiceAuthorityPort.SigningAuthority approvalAuthority =
                resolveAuthority(fence.tenantId(), "SVC-PEP-APR-002",
                "dwp-people-server", "dwp-approval-server",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST");
        Hrm013ServiceAuthorityPort.SigningAuthority callbackAuthority =
                resolveAuthority(fence.tenantId(), "SVC-PEP-HRM-010",
                "dwp-approval-server", "dwp-people-server",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION");
        WaiverRequestFence result = required.execute(status -> {
            if (!authority.remainsCurrent(approvalAuthority)
                    || !authority.remainsCurrent(callbackAuthority)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            JdbcHrm013OwnerStore.OwnerTask task = owner.requireTaskForUpdate(
                    fence.tenantId(), fence.casePublicId(), fence.taskPublicId());
            if (task.separationCaseId() != fence.separationCaseId()
                    || task.separationTaskId() != fence.separationTaskId()
                    || !task.taskType().equals(taskType)
                    || !task.caseMakerPublicId().equals(fence.caseMakerPublicId())
                    || task.ownerRevision() == null
                    || task.ownerRevision() != fence.taskOwnerRevision()
                    || task.taskVersion() != fence.taskVersion()) {
                throw Hrm013SafeErrorV1.revisionConflict();
            }
            WaiverRequestFence stored = waivers.insert(fence);
            Instant now = clock.instant();
            CallbackAuthorityKey callbackKey = new CallbackAuthorityKey(
                    fence.tenantId(), "SVC-PEP-HRM-010", "dwp-approval-server",
                    fence.casePublicId(), fence.taskPublicId(),
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION", fence.approvalRequestId());
            var existing = callbackAuthorities.latestForUpdate(callbackKey);
            if (existing.isPresent()) {
                CallbackAuthorityRecord prior = existing.orElseThrow();
                if (prior.separationCaseId() != fence.separationCaseId()
                        || prior.separationTaskId() != fence.separationTaskId()
                        || !"APPROVAL_DECISION".equals(prior.callbackType())
                        || !taskType.equals(prior.taskType())
                        || prior.expectedNextOwnerRevision() != 1
                        || !prior.correlationId().equals(fence.correlationId())
                        || !prior.originatingPayloadSha256().equals(
                        fence.approvalRequestPayloadSha256())) {
                    throw Hrm013SafeErrorV1.digestConflict();
                }
                return stored;
            }
            callbackAuthorities.append(new CallbackAuthorityRecord(
                    UUID.randomUUID(), callbackKey,
                    fence.separationCaseId(), fence.separationTaskId(),
                    "APPROVAL_DECISION", "dwp-people-server", taskType, null,
                    callbackAuthority.grant().tenantGrantRevision(),
                    callbackAuthority.grant().authorizationRevision(), 1,
                    fence.correlationId(), fence.approvalRequestPayloadSha256(),
                    1, "ADMIT", null,
                    authorityDigest(fence.tenantId(), "SVC-PEP-HRM-010",
                            fence.approvalRequestId(),
                            fence.approvalRequestPayloadSha256(),
                            callbackAuthority.grant().tenantGrantRevision(),
                            callbackAuthority.grant().authorizationRevision()), now, null));
            return stored;
        });
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    @Override
    public SeparationReconciliationPort.Result reconcile(
            SeparationReconciliationPort.Command command) {
        Objects.requireNonNull(command, "command");
        String caller = command.sourceWorkloadId();
        String purpose;
        if ("dwp-time-server".equals(caller)) {
            purpose = "HRIS_SEPARATION_TIME_RECONCILIATION";
        } else if ("dwp-payroll-server".equals(caller)) {
            purpose = "HRIS_SEPARATION_PAYROLL_RECONCILIATION";
        } else {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadCredentialProvider.AtomicGrantCredentialLease lease =
                credentials.requireCurrent(
                        command.tenantId(), "SVC-PEP-HRM-009", purpose,
                        caller, "dwp-people-server",
                        Hrm013WorkloadCredentialProvider.ServiceRole.OWNER);
        Hrm013ServiceAuthorityPort.Query query = new Hrm013ServiceAuthorityPort.Query(
                command.tenantId(), caller, "SVC-PEP-HRM-009", purpose,
                lease.tenantGrantRevision(), lease.authorizationRevision());
        Hrm013ServiceAuthorityPort.SigningAuthority current =
                authority.requireSigningAuthority(query);
        credentials.requireAttested(lease, current);
        requireAuthority(current, command.tenantId(), "SVC-PEP-HRM-009",
                caller, "dwp-people-server", purpose);
        SeparationReconciliationPort.Result result = required.execute(status -> {
            var prior = owner.findReconciliationAdmissionForUpdate(
                    command, lease.tenantGrantRevision(),
                    lease.authorizationRevision());
            if (prior.isPresent()) return prior.orElseThrow();
            if (!authority.remainsCurrent(current)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            long caseId = owner.requireCaseIdForUpdate(
                    command.tenantId(), command.casePublicId());
            Instant now = clock.instant();
            CallbackAuthorityRecord callback = new CallbackAuthorityRecord(
                    UUID.randomUUID(), new CallbackAuthorityKey(
                    command.tenantId(), "SVC-PEP-HRM-009", caller,
                    command.casePublicId(), null, purpose, null),
                    caseId, 0, "RECONCILIATION_RESULT", "dwp-people-server",
                    null, command.consumerEventId(),
                    current.grant().tenantGrantRevision(),
                    current.grant().authorizationRevision(), 1,
                    command.correlationId(), command.sourcePayloadSha256(),
                    1, "ADMIT", null,
                    authorityDigest(command.tenantId(), "SVC-PEP-HRM-009",
                            command.consumerEventId(), command.sourcePayloadSha256(),
                            current.grant().tenantGrantRevision(),
                            current.grant().authorizationRevision()), now, null);
            callbackAuthorities.append(callback);
            return owner.appendReconciliationAdmission(
                    command, lease.tenantGrantRevision(),
                    lease.authorizationRevision(), now);
        });
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    private static void requireAuthority(
            Hrm013ServiceAuthorityPort.SigningAuthority value, long tenantId,
            String bindingId, String caller, String owner, String purpose) {
        if (value == null || value.grant().tenantId() != tenantId
                || !bindingId.equals(value.grant().bindingId())
                || !caller.equals(value.grant().callerWorkloadId())
                || !purpose.equals(value.grant().purpose())
                || !owner.equals(value.ownerWorkloadId())
                || !("spiffe://dwp/service/" + caller).equals(value.callerSpiffeId())
                || !("spiffe://dwp/service/" + owner).equals(value.ownerSpiffeId())
                || !value.query().equals(new Hrm013ServiceAuthorityPort.Query(
                tenantId, caller, bindingId, purpose,
                value.grant().tenantGrantRevision(),
                value.grant().authorizationRevision()))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private Hrm013ServiceAuthorityPort.SigningAuthority resolveAuthority(
            long tenantId, String bindingId, String caller,
            String ownerWorkload, String purpose) {
        Hrm013WorkloadCredentialProvider.ServiceRole role =
                "dwp-people-server".equals(caller)
                        ? Hrm013WorkloadCredentialProvider.ServiceRole.CALLER
                        : "dwp-people-server".equals(ownerWorkload)
                                ? Hrm013WorkloadCredentialProvider.ServiceRole.OWNER
                                : null;
        if (role == null || caller.equals(ownerWorkload)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadCredentialProvider.AtomicGrantCredentialLease lease =
                credentials.requireCurrent(tenantId, bindingId, purpose,
                        caller, ownerWorkload, role);
        Hrm013ServiceAuthorityPort.SigningAuthority resolved =
                authority.requireSigningAuthority(new Hrm013ServiceAuthorityPort.Query(
                        tenantId, caller, bindingId, purpose,
                        lease.tenantGrantRevision(), lease.authorizationRevision()));
        credentials.requireAttested(lease, resolved);
        requireAuthority(resolved, tenantId, bindingId, caller,
                ownerWorkload, purpose);
        return resolved;
    }

    private static String authorityDigest(
            long tenantId, String bindingId, UUID identity, String payloadSha256,
            long tenantGrantRevision, long authorizationRevision) {
        String preimage = tenantId + "\n" + bindingId + "\n" + identity + "\n"
                + payloadSha256 + "\n" + tenantGrantRevision + "\n"
                + authorizationRevision;
        return Hrm013PersistenceModels.sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    private static TaskDispatchFence dispatchFence(
            long tenantId, Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body,
            Hrm013ServiceAuthorityPort.SigningAuthority dispatchAuthority,
            Hrm013ServiceAuthorityPort.SigningAuthority callbackAuthority) {
        byte[] exactBody = Hrm013TaskDispatchV1.encode(profile, tenantId, body);
        return new TaskDispatchFence(
                body.dispatchEventId(), tenantId, body.casePublicId(),
                body.taskPublicId(), profile.bindingId(), profile.ownerWorkloadId(),
                profile.purpose(), profile.taskType(), profile.eventType(),
                body.taskVersion(), body.taskOwnerRevision(), exactBody,
                Hrm013TaskDispatchV1.requestBodySha256(exactBody),
                body.payloadSha256(), body.correlationId(),
                dispatchAuthority.grant().tenantGrantRevision(),
                dispatchAuthority.grant().authorizationRevision(),
                callbackAuthority.grant().tenantGrantRevision(),
                callbackAuthority.grant().authorizationRevision(), body.dispatchedAt());
    }

    private static boolean sameDispatch(
            TaskDispatchFence left, TaskDispatchFence right) {
        return left.dispatchEventId().equals(right.dispatchEventId())
                && left.tenantId() == right.tenantId()
                && left.casePublicId().equals(right.casePublicId())
                && left.taskPublicId().equals(right.taskPublicId())
                && left.bindingId().equals(right.bindingId())
                && left.ownerWorkloadId().equals(right.ownerWorkloadId())
                && left.purpose().equals(right.purpose())
                && left.taskType().equals(right.taskType())
                && left.eventType().equals(right.eventType())
                && left.taskVersion() == right.taskVersion()
                && left.expectedOwnerRevision() == right.expectedOwnerRevision()
                && MessageDigest.isEqual(
                left.exactRequestBody(), right.exactRequestBody())
                && left.requestBodySha256().equals(right.requestBodySha256())
                && left.payloadSha256().equals(right.payloadSha256())
                && left.correlationId().equals(right.correlationId())
                && left.dispatchTenantGrantRevision()
                == right.dispatchTenantGrantRevision()
                && left.dispatchAuthorizationRevision()
                == right.dispatchAuthorizationRevision()
                && left.callbackTenantGrantRevision()
                == right.callbackTenantGrantRevision()
                && left.callbackAuthorizationRevision()
                == right.callbackAuthorizationRevision()
                && left.occurredAt().equals(right.occurredAt());
    }

    public record CaseCreation(
            long tenantId, UUID casePublicId, long workerId,
            long workRelationshipId, String separationType, String reasonCode,
            LocalDate effectiveDate, LocalDate lastWorkingDate,
            String inputSnapshotHash, UUID taskTemplatePublicId,
            long taskTemplateRevision, String taskTemplateHash,
            UUID makerPrincipalPublicId, UUID correlationId,
            long actorId, Instant createdAt) {
        public CaseCreation {
            if (tenantId < 1 || workerId < 1 || workRelationshipId < 1
                    || taskTemplateRevision < 1 || actorId < 1) {
                throw new IllegalArgumentException("positive case values required");
            }
            Objects.requireNonNull(casePublicId); Objects.requireNonNull(effectiveDate);
            Objects.requireNonNull(lastWorkingDate); Objects.requireNonNull(taskTemplatePublicId);
            Objects.requireNonNull(makerPrincipalPublicId); Objects.requireNonNull(correlationId);
            Objects.requireNonNull(createdAt);
            if (separationType == null || separationType.isBlank()
                    || reasonCode == null || reasonCode.isBlank()
                    || inputSnapshotHash == null
                    || !inputSnapshotHash.matches("[0-9a-f]{64}")
                    || taskTemplateHash == null
                    || !taskTemplateHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid case values");
            }
        }
    }

    public record PlannedDispatch(
            Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body,
            boolean mandatory, boolean irreversible) {
        public PlannedDispatch {
            Objects.requireNonNull(profile); Objects.requireNonNull(body);
        }
    }

    private record AuthorizedDispatch(
            PlannedDispatch plan,
            Hrm013ServiceAuthorityPort.SigningAuthority dispatchAuthority,
            Hrm013ServiceAuthorityPort.SigningAuthority callbackAuthority) {
        private AuthorizedDispatch {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(dispatchAuthority);
            Objects.requireNonNull(callbackAuthority);
        }
    }
}
