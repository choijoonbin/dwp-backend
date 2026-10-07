package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.ProviderState;

@Service
public class WorkplaceServiceOperationsService extends WorkplaceServiceOperationsComponent {
    private static final Duration ACCESS_GRANT_MAX_TTL = Duration.ofMinutes(15);

    private final WorkplaceServiceProviderOperations providerOperations;
    private final List<WorkplaceServiceEphemeralCredentialProvider> credentialProviders;

    @Autowired
    public WorkplaceServiceOperationsService(
            WorkplaceServiceOperationsRepository repository,
            ObjectMapper objectMapper,
            List<WorkplaceServiceProviderVerifier> verifiers,
            List<WorkplaceServiceEphemeralCredentialProvider> credentialProviders,
            PlatformTransactionManager transactionManager) {
        this(repository, objectMapper, verifiers, credentialProviders,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    WorkplaceServiceOperationsService(
            WorkplaceServiceOperationsRepository repository,
            ObjectMapper objectMapper,
            List<WorkplaceServiceProviderVerifier> verifiers,
            List<WorkplaceServiceEphemeralCredentialProvider> credentialProviders,
            TransactionTemplate transaction,
            Clock clock) {
        super(repository, objectMapper, transaction, clock);
        this.providerOperations = new WorkplaceServiceProviderOperations(
                repository, objectMapper, verifiers, transaction, clock);
        this.credentialProviders = List.copyOf(credentialProviders);
    }

    @Transactional(readOnly = true)
    public ProviderProfiles providers(long tenantId) {
        return providerOperations.providers(tenantId);
    }

    @Transactional(readOnly = true)
    public ProviderProfile provider(long tenantId, UUID providerId) {
        return providerOperations.provider(tenantId, providerId);
    }

    @Transactional
    public ProviderCommandResult createProvider(
            long tenantId, long actorUserId, String idempotencyKey,
            ProviderCreateRequest request, String correlationId) {
        return providerOperations.createProvider(
                tenantId, actorUserId, idempotencyKey, request, correlationId);
    }

    @Transactional
    public ProviderCommandResult updateProvider(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderUpdateRequest request, String correlationId) {
        return providerOperations.updateProvider(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
    }

    @Transactional
    public ProviderCommandResult changeProviderState(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderStateRequest request, String correlationId) {
        return providerOperations.changeProviderState(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
    }

    public ProviderCommandResult verifyProvider(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderVerifyRequest request, String correlationId) {
        return providerOperations.verifyProvider(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
    }

    @Transactional(readOnly = true)
    public CapacityRange capacity(long tenantId, UUID catalogItemId, String siteReference,
                                  OffsetDateTime from, OffsetDateTime to) {
        return providerOperations.capacity(
                tenantId, catalogItemId, siteReference, from, to);
    }

    @Transactional
    public CapacityUpsertResult upsertCapacity(
            long tenantId, long actorUserId, UUID catalogItemId, String idempotencyKey,
            CapacityUpsertRequest request, String correlationId) {
        return providerOperations.upsertCapacity(
                tenantId, actorUserId, catalogItemId, idempotencyKey, request, correlationId);
    }

    @Transactional(readOnly = true)
    public AssigneeSearchResult searchAssignees(
            long tenantId, String purpose, UUID providerId, String siteReference,
            String query, Integer requestedLimit) {
        requireTenant(tenantId);
        if (!"FULFILLMENT".equals(purpose)) throw invalid("Purpose must be FULFILLMENT.");
        if (query == null || query.trim().length() < 2) {
            throw invalid("Assignee search requires at least two characters.");
        }
        ProviderRow provider = requireProvider(tenantId, providerId);
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 50));
        OffsetDateTime now = now();
        return new AssigneeSearchResult(repository.searchAssignees(tenantId,
                provider.providerCode(), siteReference, query.trim(), limit, now).stream()
                .map(this::assignee).toList(), now);
    }

    @Transactional
    public AssigneeAssignmentResult assign(
            long tenantId, long actorUserId, UUID orderId, UUID taskId,
            String idempotencyKey, AssigneeAssignmentRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required.");
        }
        String scope = "SERVICE_TASK_ASSIGN:" + taskId;
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, taskId, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        TaskContextRow context = repository.taskContext(tenantId, orderId, taskId)
                .orElseThrow(() -> notFound("The fulfillment task was not found."));
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            AssigneeRow resolved = repository.assignee(tenantId, request.directorySubjectId(),
                    context.providerCode(), context.siteReference(), now())
                    .orElseThrow(() -> conflict("Assignee directory evidence is no longer fresh."));
            return new AssigneeAssignmentResult(orderId, taskId, assignee(resolved),
                    context.taskVersion(), receipt(existing, true));
        }
        OffsetDateTime now = now();
        AssigneeRow resolved = repository.assignee(tenantId, request.directorySubjectId(),
                context.providerCode(), context.siteReference(), now)
                .orElseThrow(() -> conflict(
                        "Assignee tenant, capability, provider, site, or freshness could not be verified."));
        if (!repository.assignTask(tenantId, taskId, request.expectedTaskVersion(), resolved, now)) {
            throw versionConflict("The fulfillment task changed. Refresh before assigning it.");
        }
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_ORDER", orderId, CommandState.SUCCEEDED,
                "/v1/admin/workplace/service-orders/" + orderId,
                correlationId, now);
        repository.createCommand(command);
        ObjectNode detail = detail("taskId", taskId, request.reason())
                .put("assigneeDisplayName", resolved.displayName());
        repository.appendOrderEvent(tenantId, orderId, "ASSIGNEE_ASSIGNED",
                actorUserId, detail, now);
        audit(tenantId, actorUserId, orderId, "WORKPLACE_SERVICE_ORDER",
                "workplace.service.assignee.assigned", "WorkplaceServiceAssigneeAssigned",
                correlationId, detail, now);
        return new AssigneeAssignmentResult(orderId, taskId, assignee(resolved),
                context.taskVersion() + 1, receipt(command, false));
    }

    @Transactional(readOnly = true)
    public InspectionStatus inspection(long tenantId, long actorUserId, UUID orderId,
                                       UUID lineId, boolean admin) {
        requireActor(tenantId, actorUserId);
        TaskContextRow context = repository.lineContext(tenantId, orderId, lineId,
                admin ? null : actorUserId)
                .orElseThrow(() -> notFound("The service order line was not found."));
        return inspectionStatus(tenantId, context);
    }

    @Transactional
    public InspectionCommandResult inspect(
            long tenantId, long actorUserId, UUID orderId, UUID lineId, boolean admin,
            String idempotencyKey, InspectionAttemptRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required.");
        }
        TaskContextRow context = repository.lineContext(tenantId, orderId, lineId,
                admin ? null : actorUserId)
                .orElseThrow(() -> notFound("The service order line was not found."));
        InspectionActorRole role = admin ? InspectionActorRole.OPERATOR
                : InspectionActorRole.REQUESTER;
        if (context.inspectionMode() == InspectionMode.NONE) {
            throw conflict("This service line does not require an inspection.");
        }
        if (!context.inspectionMode().name().equals(role.name())) {
            throw conflict("This inspection must be completed by the required actor role.");
        }
        if (context.orderVersion() != request.expectedOrderVersion()
                || context.taskVersion() != request.expectedTaskVersion()) {
            throw versionConflict("The service order changed. Refresh before inspection.");
        }
        if (!inspectionReady(context)) {
            throw conflict("The service line is not ready for final inspection.");
        }
        validateStructuredAnswers(context.inspectionSchema(), request.checklistResponses());
        if (!repository.attachmentsClean(tenantId, orderId, request.attachmentIds())) {
            throw conflict("Every inspection attachment must belong to this order and be clean.");
        }
        String scope = "SERVICE_LINE_INSPECTION:" + lineId;
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, lineId, role, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return new InspectionCommandResult(inspectionStatus(tenantId, context),
                    receipt(existing, true));
        }
        OffsetDateTime now = now();
        UUID attemptId = UUID.randomUUID();
        repository.createInspection(tenantId, attemptId, context, actorUserId, role, request, now);
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_ORDER", orderId, CommandState.SUCCEEDED,
                inspectionHref(admin, orderId, lineId), correlationId, now);
        repository.createCommand(command);
        String event = request.decision() == InspectionDecision.PASSED
                ? "INSPECTION_PASSED" : "INSPECTION_FAILED";
        ObjectNode detail = detail("inspectionAttemptId", attemptId, request.reason())
                .put("lineId", lineId.toString()).put("actorRole", role.name());
        repository.appendOrderEvent(tenantId, orderId, event, actorUserId, detail, now);
        audit(tenantId, actorUserId, orderId, "WORKPLACE_SERVICE_ORDER",
                "workplace.service.inspection." + request.decision().name().toLowerCase(Locale.ROOT),
                "WorkplaceServiceInspection" + request.decision().name(),
                correlationId, detail, now);
        return new InspectionCommandResult(inspectionStatus(tenantId, context),
                receipt(command, false));
    }

    public AccessCredentialIssueResult issueCredential(
            long tenantId, long actorUserId, UUID orderId, UUID lineId,
            String idempotencyKey, AccessCredentialIssueRequest request,
            String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()
                || request.stepUpReceipt() == null || request.stepUpReceipt().isBlank()) {
            throw invalid("Current step-up evidence and explicit confirmation are required.");
        }
        String scope = "SERVICE_ACCESS_ISSUE:" + lineId;
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(orderId, lineId, request);
        CredentialPreparation prepared = transaction.execute(status -> {
            repository.lockCommand(tenantId, actorUserId, scope, key);
            OperationsCommandRow existing = repository.command(
                    tenantId, actorUserId, scope, key).orElse(null);
            if (existing != null) {
                requireFingerprint(existing, fingerprint);
                AccessGrantRow grant = repository.accessGrant(
                        tenantId, orderId, lineId, existing.resourceId(), actorUserId)
                        .orElseThrow(() -> conflict(
                                "Credential command recovery state is missing."));
                boolean lookup = existing.state() == CommandState.RESULT_UNKNOWN
                        && grant.state() == AccessGrantState.RESULT_UNKNOWN
                        && "ISSUE".equals(grant.operationKind());
                return new CredentialPreparation(null, grant, existing,
                        existing.resourceId(), true, lookup);
            }
            TaskContextRow context = repository.lineContext(
                    tenantId, orderId, lineId, actorUserId)
                    .orElseThrow(() -> notFound("The service order line was not found."));
            WorkplaceServiceCredentialEligibility.requireEligible(
                    context, request.expectedOrderVersion(), now());
            UUID grantId = UUID.randomUUID();
            OffsetDateTime issuedAt = now();
            OffsetDateTime placeholderExpiry = issuedAt.plus(ACCESS_GRANT_MAX_TTL);
            OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                    fingerprint, "SERVICE_ACCESS_GRANT", grantId, CommandState.ACCEPTED,
                    "/v1/workplace/service-orders/" + orderId + "/lines/" + lineId
                            + "/access-credentials/" + grantId,
                    correlationId, issuedAt);
            repository.createCommand(command);
            repository.createPendingGrant(tenantId, grantId, context, actorUserId,
                    "pending:" + grantId, sha256("pending:" + grantId),
                    request.reason().trim(), command.commandId(), issuedAt, placeholderExpiry);
            AccessGrantRow grant = repository.accessGrant(
                    tenantId, orderId, lineId, grantId, actorUserId).orElseThrow();
            return new CredentialPreparation(context, grant, command, grantId, false, false);
        });
        if (prepared == null) throw new IllegalStateException("Credential prepare returned null.");
        if (prepared.replayed() && !prepared.recoveryLookup()) {
            return new AccessCredentialIssueResult(prepared.grant().grantId(), null,
                    prepared.grant().expiresAt(), true,
                    receipt(prepared.command(), true));
        }
        String adapterType = prepared.recoveryLookup()
                ? prepared.grant().adapterType() : prepared.context().adapterType();
        WorkplaceServiceEphemeralCredentialProvider provider = credentialProviders.stream()
                .filter(candidate -> candidate.supports(adapterType))
                .findFirst().orElseThrow(() -> conflict(
                        "No ephemeral credential adapter is registered for this provider."));
        WorkplaceServiceEphemeralCredentialProvider.IssueRequest providerRequest =
                issueRequest(prepared);
        WorkplaceServiceEphemeralCredentialProvider.IssuedCredential issued;
        try {
            issued = prepared.recoveryLookup()
                    ? provider.lookupIssue(providerRequest) : provider.issue(providerRequest);
            validateIssuedCredential(issued);
        } catch (RuntimeException exception) {
            transaction.executeWithoutResult(status -> repository.updateCommand(tenantId,
                    prepared.command().commandId(), CommandState.RESULT_UNKNOWN, now()));
            throw exception;
        }
        transaction.executeWithoutResult(status -> {
            OffsetDateTime completedAt = now();
            repository.finalizeGrant(tenantId, prepared.grantId(),
                    issued.providerGrantReference(), sha256(issued.oneTimeCredential()),
                    issued.expiresAt(), completedAt);
            repository.updateCommand(tenantId, prepared.command().commandId(),
                    CommandState.SUCCEEDED, completedAt);
            ObjectNode detail = detail("grantId", prepared.grantId(), request.reason())
                    .put("lineId", lineId.toString()).put("expiresAt", issued.expiresAt().toString());
            repository.appendOrderEvent(tenantId, orderId, "ACCESS_CREDENTIAL_ISSUED",
                    actorUserId, detail, completedAt);
            audit(tenantId, actorUserId, orderId, "WORKPLACE_SERVICE_ORDER",
                    "workplace.service.access.credential.issued",
                    "WorkplaceServiceAccessCredentialIssued", correlationId, detail, completedAt);
        });
        OperationsCommandRow completed = repository.command(
                tenantId, actorUserId, scope, key).orElseThrow();
        return new AccessCredentialIssueResult(prepared.grantId(), issued.oneTimeCredential(),
                issued.expiresAt(), true, receipt(completed, prepared.replayed()));
    }

    public AccessCredentialStatus revokeCredential(
            long tenantId, long actorUserId, UUID orderId, UUID lineId, UUID grantId,
            String idempotencyKey, AccessCredentialRevokeRequest request,
            String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required.");
        }
        String scope = "SERVICE_ACCESS_REVOKE:" + grantId;
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(orderId, lineId, grantId, request);
        RevokePreparation prepared = transaction.execute(status -> {
            repository.lockCommand(tenantId, actorUserId, scope, key);
            OperationsCommandRow existing = repository.command(
                    tenantId, actorUserId, scope, key).orElse(null);
            AccessGrantRow grant = repository.accessGrant(
                    tenantId, orderId, lineId, grantId, actorUserId)
                    .orElseThrow(() -> notFound("The access grant was not found."));
            if ("LEGACY_UNKNOWN".equals(grant.operationKind())) {
                throw conflict("The legacy access grant has no immutable provider binding; "
                        + "manual provider revocation is required.");
            }
            if (existing != null) {
                requireFingerprint(existing, fingerprint);
                boolean lookup = existing.state() == CommandState.RESULT_UNKNOWN
                        && grant.state() == AccessGrantState.RESULT_UNKNOWN
                        && "REVOKE".equals(grant.operationKind());
                return new RevokePreparation(null, grant, existing, true, lookup);
            }
            TaskContextRow context = repository.lineContext(
                    tenantId, orderId, lineId, actorUserId)
                    .orElseThrow(() -> notFound("The service order line was not found."));
            if (context.orderVersion() != request.expectedOrderVersion()) {
                throw versionConflict("The service order changed. Refresh before revoking access.");
            }
            OffsetDateTime acceptedAt = now();
            OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                    fingerprint, "SERVICE_ACCESS_GRANT", grantId, CommandState.ACCEPTED,
                    "/v1/workplace/service-orders/" + orderId + "/lines/" + lineId
                            + "/access-credentials/" + grantId,
                    correlationId, acceptedAt);
            repository.createCommand(command);
            if (!repository.beginGrantRevocation(tenantId, grantId, grant.version(),
                    command.commandId(), acceptedAt)) {
                throw versionConflict("The access grant changed. Refresh before revoking access.");
            }
            AccessGrantRow pending = repository.accessGrant(
                    tenantId, orderId, lineId, grantId, actorUserId).orElseThrow();
            return new RevokePreparation(context, pending, command, false, false);
        });
        if (prepared == null) throw new IllegalStateException("Credential revoke prepare returned null.");
        if ((prepared.replayed() && !prepared.recoveryLookup())
                || prepared.grant().state() == AccessGrantState.REVOKED) {
            return credentialStatus(prepared.grant(), prepared.command(), prepared.replayed());
        }
        WorkplaceServiceEphemeralCredentialProvider provider = credentialProviders.stream()
                .filter(candidate -> candidate.supports(prepared.grant().adapterType()))
                .findFirst().orElseThrow(() -> conflict(
                        "No ephemeral credential adapter is registered for this provider."));
        WorkplaceServiceEphemeralCredentialProvider.RevokeRequest providerRequest =
                revokeRequest(prepared.grant());
        WorkplaceServiceEphemeralCredentialProvider.RevokeResult result;
        try {
            result = prepared.recoveryLookup()
                    ? provider.lookupRevoke(providerRequest) : provider.revoke(providerRequest);
        } catch (RuntimeException exception) {
            transaction.executeWithoutResult(status -> {
                repository.markGrantRevoked(tenantId, grantId, true, now());
                repository.updateCommand(tenantId, prepared.command().commandId(),
                        CommandState.RESULT_UNKNOWN, now());
            });
            throw exception;
        }
        transaction.executeWithoutResult(status -> {
            OffsetDateTime completedAt = now();
            repository.completeGrantRevocation(tenantId, grantId, result.revoked(),
                    result.resultUnknown(), completedAt);
            repository.updateCommand(tenantId, prepared.command().commandId(),
                    result.resultUnknown() ? CommandState.RESULT_UNKNOWN
                            : result.revoked() ? CommandState.SUCCEEDED : CommandState.FAILED,
                    completedAt);
            if (result.revoked()) {
                ObjectNode detail = detail("grantId", grantId, request.reason())
                        .put("lineId", lineId.toString());
                repository.appendOrderEvent(tenantId, orderId, "ACCESS_CREDENTIAL_REVOKED",
                        actorUserId, detail, completedAt);
                audit(tenantId, actorUserId, orderId, "WORKPLACE_SERVICE_ORDER",
                        "workplace.service.access.credential.revoked",
                        "WorkplaceServiceAccessCredentialRevoked",
                        correlationId, detail, completedAt);
            }
        });
        AccessGrantRow completedGrant = repository.accessGrant(
                tenantId, orderId, lineId, grantId, actorUserId).orElseThrow();
        OperationsCommandRow completedCommand = repository.command(
                tenantId, actorUserId, scope, key).orElseThrow();
        return credentialStatus(completedGrant, completedCommand, prepared.replayed());
    }

    @Transactional
    public ContactResult contact(
            long tenantId, long actorUserId, UUID orderId, String idempotencyKey,
            ContactRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required.");
        }
        if (request.serviceOrderLineId() == null) {
            throw invalid("A service order line is required to resolve the contact target.");
        }
        TaskContextRow context = repository.lineContext(
                tenantId, orderId, request.serviceOrderLineId(), actorUserId)
                .orElseThrow(() -> notFound("The service order line was not found."));
        if (context.orderVersion() != request.expectedOrderVersion()) {
            throw versionConflict("The service order changed. Refresh before contacting support.");
        }
        String scope = "SERVICE_CONTACT:" + request.serviceOrderLineId();
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return new ContactResult(existing.resourceId(), request.target(),
                    "Contact target resolved", "QUEUED", receipt(existing, true));
        }
        OffsetDateTime now = now();
        ContactTarget target = repository.resolveContact(tenantId, context, request.target(), now);
        if (target == null) {
            throw conflict("The requested contact target is unavailable or stale.");
        }
        UUID contactId = repository.createContactRequest(tenantId, context, actorUserId,
                request.target(), target, request.message().trim(), request.reason().trim(), now);
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_CONTACT", contactId, CommandState.ACCEPTED,
                "/v1/workplace/service-orders/" + orderId + "/messages",
                correlationId, now);
        repository.createCommand(command);
        ObjectNode detail = detail("contactRequestId", contactId, request.reason())
                .put("targetType", request.target().name())
                .put("targetDisplayName", target.displayName());
        repository.appendOrderEvent(tenantId, orderId, "CONTACT_REQUESTED",
                actorUserId, detail, now);
        audit(tenantId, actorUserId, orderId, "WORKPLACE_SERVICE_ORDER",
                "workplace.service.contact.requested", "WorkplaceServiceContactRequested",
                correlationId, detail, now);
        return new ContactResult(contactId, request.target(), target.displayName(),
                "QUEUED", receipt(command, false));
    }

    /** Recovers a revoke after restart by read-only provider status lookup. */
    boolean recoverPendingGrant(AccessGrantRow grant) {
        if (grant == null || grant.state() != AccessGrantState.RESULT_UNKNOWN
                || !"REVOKE".equals(grant.operationKind())
                || grant.operationCommandId() == null) return false;
        WorkplaceServiceEphemeralCredentialProvider provider = credentialProviders.stream()
                .filter(candidate -> candidate.supports(grant.adapterType()))
                .findFirst().orElse(null);
        if (provider == null) return false;
        WorkplaceServiceEphemeralCredentialProvider.RevokeResult result;
        try {
            result = provider.lookupRevoke(revokeRequest(grant));
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (result == null || result.resultUnknown()) return false;
        OperationsCommandRow command = repository.command(
                grant.tenantId(), grant.operationCommandId()).orElse(null);
        if (command == null) return false;
        transaction.executeWithoutResult(status -> {
            OffsetDateTime completedAt = now();
            repository.completeGrantRevocation(grant.tenantId(), grant.grantId(),
                    result.revoked(), false, completedAt);
            repository.updateCommand(grant.tenantId(), command.commandId(),
                    result.revoked() ? CommandState.SUCCEEDED : CommandState.FAILED,
                    completedAt);
            if (result.revoked()) {
                ObjectNode detail = detail("grantId", grant.grantId(), grant.reason())
                        .put("lineId", grant.lineId().toString());
                repository.appendOrderEvent(grant.tenantId(), grant.orderId(),
                        "ACCESS_CREDENTIAL_REVOKED", grant.requesterUserId(), detail, completedAt);
                audit(grant.tenantId(), grant.requesterUserId(), grant.orderId(),
                        "WORKPLACE_SERVICE_ORDER",
                        "workplace.service.access.credential.revoked",
                        "WorkplaceServiceAccessCredentialRevoked",
                        command.correlationId(), detail, completedAt);
            }
        });
        return true;
    }

    CapacityReservation reserveCapacity(
            long tenantId, UUID previewId, UUID catalogItemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, int quantity,
            OffsetDateTime expiresAt) {
        CatalogOperationsPolicy policy = repository.catalogPolicy(tenantId, catalogItemId);
        if (policy == null || policy.capacityMode() == CapacityMode.UNBOUNDED) return null;
        if (siteReference == null || siteReference.isBlank()) {
            throw conflict("A governed site is required for bucketed service capacity.");
        }
        CapacityHoldResult held = repository.createCapacityHolds(tenantId, previewId,
                catalogItemId, siteReference, from, to, quantity,
                policy.capacityFreshnessSeconds(), expiresAt, now());
        if (!held.held()) throw conflict("Service capacity is unavailable: " + held.limitation());
        return new CapacityReservation(held.holdIds(), held.expiresAt(), held.freshUntil());
    }

    void commitCapacity(long tenantId, UUID previewId, UUID orderId) {
        if (!repository.commitCapacityHolds(tenantId, previewId, orderId, now())) {
            throw conflict("Service capacity holds expired or changed. Preview services again.");
        }
    }

    boolean inspectionSatisfied(long tenantId, UUID lineId) {
        return repository.inspectionSatisfied(tenantId, lineId);
    }

    private AssigneeProjection assignee(AssigneeRow row) {
        return new AssigneeProjection(row.subjectId(), row.displayName(),
                row.contactAvailable(), row.capabilities(), row.directoryVersion(),
                row.receivedAt(), row.freshUntil());
    }

    private InspectionStatus inspectionStatus(long tenantId, TaskContextRow context) {
        InspectionAttempt latest = repository.latestInspection(
                tenantId, context.orderId(), context.lineId()).orElse(null);
        boolean accepted = context.inspectionMode() == InspectionMode.NONE
                || (latest != null && latest.decision() == InspectionDecision.PASSED
                    && latest.mode() == context.inspectionMode());
        return new InspectionStatus(context.orderId(), context.lineId(),
                context.inspectionMode(), context.inspectionMode() != InspectionMode.NONE,
                inspectionReady(context), accepted,
                latest != null && latest.remediationRequired(), latest, now());
    }

    private static boolean inspectionReady(TaskContextRow context) {
        return "IN_PREPARATION".equals(context.lineState())
                || "PARTIALLY_FULFILLED".equals(context.lineState());
    }

    private AccessCredentialStatus credentialStatus(
            AccessGrantRow grant, OperationsCommandRow command, boolean replayed) {
        return new AccessCredentialStatus(grant.grantId(), grant.state(), grant.issuedAt(),
                grant.expiresAt(), grant.revokedAt(), receipt(command, replayed));
    }

    private static String inspectionHref(boolean admin, UUID orderId, UUID lineId) {
        String prefix = admin ? "/v1/admin/workplace/service-orders/"
                : "/v1/workplace/service-orders/";
        return prefix + orderId + "/lines/" + lineId + "/inspection";
    }

    private static void validateStructuredAnswers(JsonNode schema, JsonNode answers) {
        if (schema == null || !schema.isArray() || answers == null || !answers.isObject()) {
            throw invalid("Inspection checklist responses are invalid.");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode field : schema) {
            String key = field.path("key").asText("");
            String type = field.path("type").asText("");
            if (key.isBlank() || type.isBlank() || !keys.add(key)) {
                throw conflict("The inspection checklist schema is invalid.");
            }
            JsonNode value = answers.get(key);
            if ((value == null || value.isNull()) && field.path("required").asBoolean(false)) {
                throw invalid("A required inspection response is missing: " + key);
            }
            if (value == null || value.isNull()) continue;
            boolean valid = switch (type) {
                case "TEXT" -> value.isTextual() && value.asText().length() <= 1000;
                case "BOOLEAN" -> value.isBoolean();
                case "NUMBER" -> value.isNumber();
                case "SINGLE_SELECT" -> value.isTextual()
                        && contains(field.path("values"), value.asText());
                case "MULTI_SELECT" -> value.isArray() && value.size() <= 100
                        && stream(value).stream().allMatch(item -> item.isTextual()
                            && contains(field.path("values"), item.asText()));
                default -> false;
            };
            if (!valid) throw invalid("An inspection response has the wrong type: " + key);
        }
        answers.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) throw invalid("Unknown inspection response: " + key);
        });
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (value.asText().equals(expected)) return true;
        return false;
    }

    private static List<JsonNode> stream(JsonNode values) {
        List<JsonNode> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private void validateIssuedCredential(
            WorkplaceServiceEphemeralCredentialProvider.IssuedCredential issued) {
        OffsetDateTime now = now();
        if (issued == null || issued.providerGrantReference() == null
                || issued.providerGrantReference().isBlank()
                || issued.providerGrantReference().length() > 320
                || issued.oneTimeCredential() == null || issued.oneTimeCredential().isBlank()
                || issued.oneTimeCredential().length() > 512 || issued.expiresAt() == null
                || !issued.expiresAt().isAfter(now)
                || issued.expiresAt().isAfter(now.plus(ACCESS_GRANT_MAX_TTL))) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The credential provider returned an invalid one-time credential.");
        }
    }

    private WorkplaceServiceEphemeralCredentialProvider.IssueRequest issueRequest(
            CredentialPreparation prepared) {
        if (prepared.recoveryLookup()) {
            AccessGrantRow grant = prepared.grant();
            return new WorkplaceServiceEphemeralCredentialProvider.IssueRequest(
                    grant.tenantId(), grant.grantId(), grant.orderId(), grant.lineId(),
                    grant.providerCode(), grant.adapterType(),
                    grant.providerConfigurationVersion(), grant.credentialBindingReference(),
                    grant.requesterUserId(), grant.expiresAt());
        }
        TaskContextRow context = prepared.context();
        return new WorkplaceServiceEphemeralCredentialProvider.IssueRequest(
                prepared.grant().tenantId(), prepared.grantId(), context.orderId(),
                context.lineId(), context.providerCode(), context.adapterType(),
                context.providerConfigurationVersion(), context.credentialBindingReference(),
                prepared.grant().requesterUserId(), prepared.grant().expiresAt());
    }

    private static WorkplaceServiceEphemeralCredentialProvider.RevokeRequest revokeRequest(
            AccessGrantRow grant) {
        return new WorkplaceServiceEphemeralCredentialProvider.RevokeRequest(
                grant.tenantId(), grant.grantId(), grant.providerCode(), grant.adapterType(),
                grant.providerConfigurationVersion(), grant.credentialBindingReference(),
                grant.providerReference());
    }

}
