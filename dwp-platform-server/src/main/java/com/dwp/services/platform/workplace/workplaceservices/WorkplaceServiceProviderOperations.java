package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.ProviderState;

final class WorkplaceServiceProviderOperations extends WorkplaceServiceOperationsComponent {
    private static final Duration PROVIDER_FRESHNESS = Duration.ofMinutes(15);

    private final List<WorkplaceServiceProviderVerifier> verifiers;

    WorkplaceServiceProviderOperations(
            WorkplaceServiceOperationsRepository repository,
            ObjectMapper objectMapper,
            List<WorkplaceServiceProviderVerifier> verifiers,
            TransactionTemplate transaction,
            Clock clock) {
        super(repository, objectMapper, transaction, clock);
        this.verifiers = List.copyOf(verifiers);
    }

    ProviderProfiles providers(long tenantId) {
        requireTenant(tenantId);
        OffsetDateTime now = now();
        return new ProviderProfiles(repository.providers(tenantId).stream()
                .map(row -> provider(row, now)).toList(), now);
    }

    ProviderProfile provider(long tenantId, UUID providerId) {
        requireTenant(tenantId);
        return provider(requireProvider(tenantId, providerId), now());
    }

    ProviderCommandResult createProvider(
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

    ProviderCommandResult updateProvider(
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

    ProviderCommandResult changeProviderState(
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

    ProviderCommandResult verifyProvider(
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

    CapacityRange capacity(long tenantId, UUID catalogItemId, String siteReference,
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

    CapacityUpsertResult upsertCapacity(
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
        } else {
            readiness = "HEALTHY".equals(row.reportedState())
                    ? ProviderState.READY : ProviderState.DEGRADED;
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
                || request.buckets().isEmpty()) {
            throw invalid("Capacity buckets are required.");
        }
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

    private static void validateRange(
            String siteReference, OffsetDateTime from, OffsetDateTime to) {
        if (siteReference == null || siteReference.isBlank() || from == null || to == null
                || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("A valid site and capacity range of at most 31 days are required.");
        }
    }

    private record VerificationPreparation(
            ProviderCommandSnapshot snapshot, OperationsCommandRow command, boolean replayed) { }
}
