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

import java.math.BigDecimal;
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
public class WorkplaceServiceOperationsService {
    private static final Duration PROVIDER_FRESHNESS = Duration.ofMinutes(15);
    private static final Duration ACCESS_GRANT_MAX_TTL = Duration.ofMinutes(15);

    private final WorkplaceServiceOperationsRepository repository;
    private final ObjectMapper objectMapper;
    private final List<WorkplaceServiceProviderVerifier> verifiers;
    private final List<WorkplaceServiceEphemeralCredentialProvider> credentialProviders;
    private final TransactionTemplate transaction;
    private final Clock clock;

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
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.verifiers = List.copyOf(verifiers);
        this.credentialProviders = List.copyOf(credentialProviders);
        this.transaction = transaction;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProviderProfiles providers(long tenantId) {
        requireTenant(tenantId);
        OffsetDateTime now = now();
        return new ProviderProfiles(repository.providers(tenantId).stream()
                .map(row -> provider(row, now)).toList(), now);
    }

    @Transactional(readOnly = true)
    public ProviderProfile provider(long tenantId, UUID providerId) {
        requireTenant(tenantId);
        return provider(requireProvider(tenantId, providerId), now());
    }

    @Transactional
    public ProviderCommandResult createProvider(
            long tenantId, long actorUserId, String idempotencyKey,
            ProviderCreateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        validateProviderRequest(request);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String scope = "SERVICE_PROVIDER_CREATE";
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return providerCommandResult(tenantId, existing, fingerprint, true);
        if (repository.providerByCode(tenantId, request.providerCode().trim()).isPresent()) {
            throw conflict("The provider code already exists.");
        }
        UUID providerId = UUID.randomUUID();
        OffsetDateTime now = now();
        repository.createProvider(tenantId, providerId, request, now);
        String href = "/v1/admin/workplace/service-providers/" + providerId;
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_PROVIDER", providerId, CommandState.SUCCEEDED,
                href, correlationId, now);
        repository.createCommand(command);
        audit(tenantId, actorUserId, providerId, "WORKPLACE_SERVICE_PROVIDER",
                "workplace.service.provider.created", "WorkplaceServiceProviderCreated",
                correlationId, detail("providerId", providerId, request.reason()), now);
        return providerCommandResult(tenantId, command, fingerprint, false);
    }

    @Transactional
    public ProviderCommandResult updateProvider(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderUpdateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        validateProviderRequest(request);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String scope = "SERVICE_PROVIDER_UPDATE:" + providerId;
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(providerId, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return providerCommandResult(tenantId, existing, fingerprint, true);
        ProviderRow current = requireProvider(tenantId, providerId);
        if (current.lifecycleState() == ProviderLifecycleState.RETIRED) {
            throw conflict("Retired provider profiles cannot be changed.");
        }
        if (!repository.updateProvider(tenantId, providerId, request, now())) {
            throw versionConflict("The provider profile changed. Refresh before saving.");
        }
        OffsetDateTime now = now();
        String href = "/v1/admin/workplace/service-providers/" + providerId;
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_PROVIDER", providerId, CommandState.SUCCEEDED,
                href, correlationId, now);
        repository.createCommand(command);
        audit(tenantId, actorUserId, providerId, "WORKPLACE_SERVICE_PROVIDER",
                "workplace.service.provider.updated", "WorkplaceServiceProviderUpdated",
                correlationId, detail("providerId", providerId, request.reason()), now);
        return providerCommandResult(tenantId, command, fingerprint, false);
    }

    @Transactional
    public ProviderCommandResult changeProviderState(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderStateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || request.lifecycleState() == null) throw invalid("State is required.");
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        ProviderRow current = requireProvider(tenantId, providerId);
        if (request.lifecycleState() == ProviderLifecycleState.ACTIVE
                && current.credentialBindingReference() == null) {
            throw conflict("An opaque credential binding is required before activation.");
        }
        if (current.lifecycleState() == ProviderLifecycleState.RETIRED
                && request.lifecycleState() != ProviderLifecycleState.RETIRED) {
            throw conflict("A retired provider cannot be reactivated.");
        }
        String scope = "SERVICE_PROVIDER_STATE:" + providerId;
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(providerId, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return providerCommandResult(tenantId, existing, fingerprint, true);
        OffsetDateTime now = now();
        if (!repository.changeProviderState(tenantId, providerId, request.expectedVersion(),
                request.lifecycleState(), now)) {
            throw versionConflict("The provider profile changed. Refresh before changing state.");
        }
        String href = "/v1/admin/workplace/service-providers/" + providerId;
        OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                fingerprint, "SERVICE_PROVIDER", providerId, CommandState.SUCCEEDED,
                href, correlationId, now);
        repository.createCommand(command);
        audit(tenantId, actorUserId, providerId, "WORKPLACE_SERVICE_PROVIDER",
                "workplace.service.provider.state.changed",
                "WorkplaceServiceProviderStateChanged", correlationId,
                detail("lifecycleState", request.lifecycleState().name(), request.reason()), now);
        return providerCommandResult(tenantId, command, fingerprint, false);
    }

    public ProviderCommandResult verifyProvider(
            long tenantId, long actorUserId, UUID providerId, String idempotencyKey,
            ProviderVerifyRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required.");
        }
        String scope = "SERVICE_PROVIDER_VERIFY:" + providerId;
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(providerId, request);
        VerificationPreparation prepared = transaction.execute(status -> {
            repository.lockCommand(tenantId, actorUserId, scope, key);
            OperationsCommandRow existing = repository.command(
                    tenantId, actorUserId, scope, key).orElse(null);
            if (existing != null) {
                requireFingerprint(existing, fingerprint);
                if (existing.state() == CommandState.SUCCEEDED) {
                    return new VerificationPreparation(null, existing, true);
                }
                ProviderCommandSnapshot snapshot = repository.commandProviderSnapshot(
                        tenantId, existing.commandId()).orElseThrow(() -> conflict(
                                "Provider verification recovery snapshot is unavailable."));
                if (!providerId.equals(snapshot.providerId())) {
                    throw conflict("Provider verification command identity is invalid.");
                }
                return new VerificationPreparation(snapshot, existing, true);
            }
            ProviderRow profile = requireProvider(tenantId, providerId);
            if (profile.lifecycleState() != ProviderLifecycleState.ACTIVE
                    || profile.credentialBindingReference() == null) {
                throw conflict("Only an active provider with a credential binding can be verified.");
            }
            OffsetDateTime acceptedAt = now();
            OperationsCommandRow command = command(tenantId, actorUserId, scope, key,
                    fingerprint, "SERVICE_PROVIDER", providerId, CommandState.ACCEPTED,
                    "/v1/admin/workplace/service-providers/" + providerId,
                    correlationId, acceptedAt);
            repository.createCommand(command);
            repository.snapshotCommandProvider(tenantId, command.commandId(), profile);
            return new VerificationPreparation(new ProviderCommandSnapshot(
                    profile.providerId(), profile.providerCode(), profile.adapterType(),
                    profile.configurationVersion(), profile.credentialBindingReference(),
                    profile.capabilities(), profile.version()), command, false);
        });
        if (prepared == null) throw new IllegalStateException("Verification prepare returned null.");
        if (prepared.replayed() && prepared.command().state() == CommandState.SUCCEEDED) {
            return providerCommandResult(tenantId, prepared.command(), fingerprint, true);
        }
        WorkplaceServiceProviderVerifier verifier = verifiers.stream()
                .filter(candidate -> candidate.supports(prepared.snapshot().adapterType()))
                .findFirst().orElseThrow(() -> conflict(
                        "No verifier is registered for this provider adapter."));
        WorkplaceServiceProviderVerifier.VerificationResult outcome;
        try {
            outcome = verifier.verify(new WorkplaceServiceProviderVerifier.VerificationRequest(
                    tenantId, prepared.command().commandId(), providerId,
                    prepared.snapshot().providerCode(),
                    prepared.snapshot().adapterType(),
                    prepared.snapshot().credentialBindingReference(),
                    prepared.snapshot().configurationVersion(),
                    prepared.snapshot().capabilities()));
            validateVerification(outcome);
        } catch (RuntimeException exception) {
            transaction.executeWithoutResult(status -> repository.updateCommand(
                    tenantId, prepared.command().commandId(), CommandState.RESULT_UNKNOWN, now()));
            throw exception;
        }
        transaction.executeWithoutResult(status -> {
            OffsetDateTime receivedAt = now();
            ProviderRow current = requireProvider(tenantId, providerId);
            if (current.version() != prepared.snapshot().providerVersion()
                    || current.configurationVersion() != prepared.snapshot().configurationVersion()) {
                repository.updateCommand(tenantId, prepared.command().commandId(),
                        CommandState.FAILED, receivedAt);
                throw versionConflict("Provider configuration changed during verification.");
            }
            if (!repository.completeProviderVerification(tenantId, providerId,
                    prepared.snapshot().providerVersion(), actorUserId, outcome, receivedAt)) {
                throw versionConflict("Provider truth changed during verification.");
            }
            repository.updateCommand(tenantId, prepared.command().commandId(),
                    CommandState.SUCCEEDED, receivedAt);
            audit(tenantId, actorUserId, providerId, "WORKPLACE_SERVICE_PROVIDER",
                    "workplace.service.provider.verified", "WorkplaceServiceProviderVerified",
                    correlationId, detail("evidenceReference", outcome.evidenceReference(),
                            request.reason()), receivedAt);
        });
        OperationsCommandRow completed = repository.command(
                tenantId, actorUserId, scope, key).orElseThrow();
        return providerCommandResult(tenantId, completed, fingerprint, prepared.replayed());
    }

    @Transactional(readOnly = true)
    public CapacityRange capacity(long tenantId, UUID catalogItemId, String siteReference,
                                  OffsetDateTime from, OffsetDateTime to) {
        requireTenant(tenantId);
        validateRange(siteReference, from, to);
        CatalogOperationsPolicy policy = repository.catalogPolicy(tenantId, catalogItemId);
        if (policy == null) throw notFound("The service catalog item was not found.");
        OffsetDateTime now = now();
        if (policy.capacityMode() == CapacityMode.UNBOUNDED) {
            return new CapacityRange(catalogItemId, siteReference, from, to,
                    CapacityMode.UNBOUNDED, List.of(), true, List.of(), now);
        }
        List<CapacityBucket> buckets = repository.capacityBuckets(
                tenantId, catalogItemId, siteReference, from, to, now).stream()
                .map(row -> capacityBucket(row, policy.capacityFreshnessSeconds(), now)).toList();
        List<String> limitations = capacityLimitations(buckets, from, to);
        return new CapacityRange(catalogItemId, siteReference, from, to,
                CapacityMode.BUCKETED, buckets, limitations.isEmpty(), limitations, now);
    }

    @Transactional
    public CapacityUpsertResult upsertCapacity(
            long tenantId, long actorUserId, UUID catalogItemId, String idempotencyKey,
            CapacityUpsertRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        validateCapacityRequest(request);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        CatalogOperationsPolicy policy = repository.catalogPolicy(tenantId, catalogItemId);
        if (policy == null) throw notFound("The service catalog item was not found.");
        if (policy.capacityMode() != CapacityMode.BUCKETED) {
            throw conflict("Capacity buckets can only be written for BUCKETED catalog items.");
        }
        String scope = "SERVICE_CAPACITY_UPSERT:" + catalogItemId;
        String key = requireKey(idempotencyKey);
        repository.lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(catalogItemId, request);
        OperationsCommandRow existing = repository.command(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            CapacityRange current = capacity(tenantId, catalogItemId, request.siteReference(),
                    request.buckets().getFirst().startsAt(), request.buckets().getLast().endsAt());
            return new CapacityUpsertResult(current, receipt(existing, true));
        }
        OffsetDateTime now = now();
        request.buckets().forEach(bucket -> repository.upsertCapacityBucket(
                tenantId, catalogItemId, request.siteReference().trim(), bucket, now));
        UUID commandId = UUID.randomUUID();
        String href = "/v1/admin/workplace/service-catalog/" + catalogItemId + "/capacity";
        OperationsCommandRow command = new OperationsCommandRow(commandId, tenantId,
                actorUserId, scope, key, fingerprint, "SERVICE_CAPACITY", catalogItemId,
                CommandState.SUCCEEDED, href, normalizeCorrelation(correlationId), now, now);
        repository.createCommand(command);
        audit(tenantId, actorUserId, catalogItemId, "WORKPLACE_SERVICE_CAPACITY",
                "workplace.service.capacity.updated", "WorkplaceServiceCapacityUpdated",
                correlationId, detail("siteReference", request.siteReference(), request.reason()), now);
        CapacityRange current = capacity(tenantId, catalogItemId, request.siteReference(),
                request.buckets().getFirst().startsAt(), request.buckets().getLast().endsAt());
        return new CapacityUpsertResult(current, receipt(command, false));
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
            requireCredentialEligible(context, request.expectedOrderVersion());
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

    private ProviderProfile provider(ProviderRow row, OffsetDateTime now) {
        ProviderState readiness;
        if (row.lifecycleState() != ProviderLifecycleState.ACTIVE
                || row.credentialBindingReference() == null || !row.configured()) {
            readiness = ProviderState.NOT_CONFIGURED;
        } else if (row.reportedState() == null || row.evidenceReference() == null
                || row.observedAt() == null || row.receivedAt() == null
                || row.observedConfigurationVersion() == null
                || row.observedConfigurationVersion() != row.configurationVersion()) {
            readiness = ProviderState.CONFIGURED_UNVERIFIED;
        } else if ("DWP_NATIVE_FULFILLMENT".equals(row.providerCode())) {
            readiness = "HEALTHY".equals(row.reportedState())
                    ? ProviderState.READY : ProviderState.DEGRADED;
        } else if (row.receivedAt().isBefore(now.minus(PROVIDER_FRESHNESS))) {
            readiness = ProviderState.STALE;
        } else if ("HEALTHY".equals(row.reportedState())) {
            readiness = ProviderState.READY;
        } else {
            readiness = ProviderState.DEGRADED;
        }
        return new ProviderProfile(row.providerId(), row.providerCode(), row.displayNameKo(),
                row.displayNameEn(), row.adapterType(), row.lifecycleState(), row.siteScope(),
                row.capabilities(), row.support(), row.credentialBindingReference() != null,
                row.configurationVersion(), readiness, row.observedConfigurationVersion(),
                row.evidenceReference(), row.observedAt(), row.receivedAt(), row.errorCode(),
                row.version(), row.updatedAt());
    }

    private ProviderCommandResult providerCommandResult(
            long tenantId, OperationsCommandRow command, String fingerprint, boolean replayed) {
        requireFingerprint(command, fingerprint);
        return new ProviderCommandResult(provider(tenantId, command.resourceId()),
                receipt(command, replayed));
    }

    private CapacityBucket capacityBucket(CapacityBucketRow row, int freshnessSeconds,
                                          OffsetDateTime now) {
        int available = Math.max(0,
                row.capacityLimit() - row.committedQuantity() - row.heldQuantity());
        OffsetDateTime freshUntil = row.receivedAt().plusSeconds(freshnessSeconds);
        return new CapacityBucket(row.bucketId(), row.catalogItemId(), row.siteReference(),
                row.startsAt(), row.endsAt(), row.capacityLimit(), row.committedQuantity(),
                row.heldQuantity(), available, row.sourceVersion(), row.sourceObservedAt(),
                row.receivedAt(), freshUntil, freshUntil.isAfter(now), row.version());
    }

    private static List<String> capacityLimitations(
            List<CapacityBucket> buckets, OffsetDateTime from, OffsetDateTime to) {
        if (buckets.isEmpty()) return List.of("CAPACITY_BUCKET_MISSING");
        List<String> values = new ArrayList<>();
        OffsetDateTime cursor = from;
        for (CapacityBucket bucket : buckets) {
            if (bucket.startsAt().isAfter(cursor)) values.add("CAPACITY_BUCKET_GAP");
            if (!bucket.fresh()) values.add("CAPACITY_STALE");
            if (bucket.availableQuantity() == 0) values.add("CAPACITY_EXHAUSTED");
            if (bucket.endsAt().isAfter(cursor)) cursor = bucket.endsAt();
        }
        if (cursor.isBefore(to)) values.add("CAPACITY_BUCKET_GAP");
        return List.copyOf(new LinkedHashSet<>(values));
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

    private void requireCredentialEligible(TaskContextRow context, long expectedOrderVersion) {
        if (context.orderVersion() != expectedOrderVersion) {
            throw versionConflict("The service order changed. Refresh before issuing access.");
        }
        if (!context.providerCapabilities().contains("EPHEMERAL_CREDENTIAL")) {
            throw conflict("This provider does not support ephemeral credentials.");
        }
        if (context.providerLifecycleState() != ProviderLifecycleState.ACTIVE
                || context.credentialBindingReference() == null
                || !context.providerConfigured()
                || !Objects.equals(context.observedConfigurationVersion(),
                    context.providerConfigurationVersion())
                || !"HEALTHY".equals(context.reportedState())
                || context.evidenceReference() == null || context.receivedAt() == null
                || context.receivedAt().isBefore(now().minus(PROVIDER_FRESHNESS))) {
            throw conflict("Provider readiness is not fresh enough to issue access.");
        }
    }

    private void validateProviderRequest(ProviderCreateRequest request) {
        if (request == null || request.support() == null || !request.support().isObject()) {
            throw invalid("Provider support metadata must be an object.");
        }
        validateCapabilities(request.capabilities());
    }

    private void validateProviderRequest(ProviderUpdateRequest request) {
        if (request == null || request.support() == null || !request.support().isObject()) {
            throw invalid("Provider support metadata must be an object.");
        }
        if (request.clearCredentialBinding() && request.credentialBindingReference() != null
                && !request.credentialBindingReference().isBlank()) {
            throw invalid("Credential binding cannot be replaced and cleared together.");
        }
        validateCapabilities(request.capabilities());
    }

    private void validateCapabilities(List<String> capabilities) {
        if (capabilities == null) throw invalid("Provider capabilities are required.");
        Set<String> unique = new LinkedHashSet<>();
        for (String capability : capabilities) {
            if (capability == null || !capability.matches("[A-Z][A-Z0-9_]{1,79}")) {
                throw invalid("Provider capabilities must use stable uppercase identifiers.");
            }
            if (!unique.add(capability)) throw invalid("Provider capabilities must be unique.");
        }
    }

    private void validateVerification(WorkplaceServiceProviderVerifier.VerificationResult result) {
        if (result == null || result.evidenceReference() == null
                || result.evidenceReference().isBlank() || result.evidenceReference().length() > 320
                || result.sourceObservedAt() == null
                || !Set.of("HEALTHY", "DEGRADED", "UNAVAILABLE")
                    .contains(result.reportedState())) {
            throw conflict("The provider returned incomplete verification evidence.");
        }
        OffsetDateTime now = now();
        if (result.sourceObservedAt().isAfter(now.plusMinutes(1))
                || result.sourceObservedAt().isBefore(now.minus(PROVIDER_FRESHNESS))) {
            throw conflict("The provider verification evidence is stale or future dated.");
        }
    }

    private void validateCapacityRequest(CapacityUpsertRequest request) {
        if (request == null || request.siteReference() == null
                || request.siteReference().isBlank() || request.buckets() == null
                || request.buckets().isEmpty()) throw invalid("Capacity buckets are required.");
        OffsetDateTime previousEnd = null;
        OffsetDateTime now = now();
        for (CapacityBucketInput bucket : request.buckets()) {
            if (bucket == null || bucket.startsAt() == null || bucket.endsAt() == null
                    || !bucket.endsAt().isAfter(bucket.startsAt())) {
                throw invalid("Every capacity bucket must have a valid period.");
            }
            if (previousEnd != null && bucket.startsAt().isBefore(previousEnd)) {
                throw invalid("Capacity buckets must be ordered and non-overlapping.");
            }
            if (bucket.sourceObservedAt().isAfter(now.plusMinutes(1))) {
                throw invalid("Capacity evidence cannot be future dated.");
            }
            previousEnd = bucket.endsAt();
        }
    }

    private static void validateRange(String siteReference, OffsetDateTime from, OffsetDateTime to) {
        if (siteReference == null || siteReference.isBlank() || from == null || to == null
                || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("A valid site and capacity range of at most 31 days are required.");
        }
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

    private ProviderRow requireProvider(long tenantId, UUID providerId) {
        if (providerId == null) throw invalid("Provider id is required.");
        return repository.provider(tenantId, providerId)
                .orElseThrow(() -> notFound("The provider profile was not found."));
    }

    private OperationsCommandRow command(
            long tenantId, long actorUserId, String scope, String key, String fingerprint,
            String resourceType, UUID resourceId, CommandState state, String href,
            String correlationId, OffsetDateTime now) {
        return new OperationsCommandRow(UUID.randomUUID(), tenantId, actorUserId, scope,
                key, fingerprint, resourceType, resourceId, state, href,
                normalizeCorrelation(correlationId), now, now);
    }

    private OperationsCommandReceipt receipt(OperationsCommandRow command, boolean replayed) {
        return new OperationsCommandReceipt(command.commandId(), command.state(),
                command.statusHref(), replayed, command.correlationId(), command.createdAt());
    }

    private void requireFingerprint(OperationsCommandRow row, String expected) {
        if (!row.fingerprint().equals(expected)) {
            throw conflict("The idempotency key was already used for another command.");
        }
    }

    private String fingerprint(Object... values) {
        try {
            return sha256(objectMapper.writeValueAsString(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint Workplace service command.",
                    exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void audit(long tenantId, long actorUserId, UUID aggregateId,
                       String aggregateType, String action, String eventType,
                       String correlationId, JsonNode detail, OffsetDateTime now) {
        repository.appendAuditAndOutbox(tenantId, actorUserId, aggregateId,
                aggregateType, action, eventType, normalizeCorrelation(correlationId), detail, now);
    }

    private ObjectNode detail(String key, Object value, String reason) {
        ObjectNode detail = objectMapper.createObjectNode().put("reason", reason.trim());
        if (value instanceof UUID id) detail.put(key, id.toString());
        else detail.put(key, String.valueOf(value));
        return detail;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("Tenant id is required.");
    }

    private static void requireActor(long tenantId, long actorUserId) {
        requireTenant(tenantId);
        if (actorUserId <= 0) throw invalid("Actor user id is required.");
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw invalid("A valid Idempotency-Key is required.");
        }
        return value.trim();
    }

    private static String normalizeCorrelation(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().substring(0, Math.min(160, value.trim().length()));
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private record VerificationPreparation(
            ProviderCommandSnapshot snapshot, OperationsCommandRow command, boolean replayed) { }
    private record CredentialPreparation(
            TaskContextRow context, AccessGrantRow grant, OperationsCommandRow command,
            UUID grantId, boolean replayed, boolean recoveryLookup) { }
    private record RevokePreparation(
            TaskContextRow context, AccessGrantRow grant,
            OperationsCommandRow command, boolean replayed, boolean recoveryLookup) { }
}
