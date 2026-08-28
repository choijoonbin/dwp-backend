package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantMutationOrchestrator {

    private final TenantMutationRepository repository;
    private final ProviderOnboardingActivationRepository onboardingActivationRepository;
    private final DownstreamProvisioningClient downstream;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String workerId;
    private final int maximumAttempts;
    private final Duration leaseDuration;

    public TenantMutationOrchestrator(
            TenantMutationRepository repository,
            ProviderOnboardingActivationRepository onboardingActivationRepository,
            DownstreamProvisioningClient downstream,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${dwp.provider.tenant-mutation.worker-id:provider-tenant-mutation}") String workerId,
            @Value("${dwp.provider.tenant-mutation.maximum-attempts:8}") int maximumAttempts,
            @Value("${dwp.provider.tenant-mutation.lease-duration:30s}") Duration leaseDuration) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
            throw new IllegalArgumentException("A bounded tenant mutation worker id is required.");
        }
        if (maximumAttempts < 1 || maximumAttempts > 100
                || leaseDuration.isZero() || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid tenant mutation recovery limits.");
        }
        this.repository = repository;
        this.onboardingActivationRepository = onboardingActivationRepository;
        this.downstream = downstream;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.workerId = workerId;
        this.maximumAttempts = maximumAttempts;
        this.leaseDuration = leaseDuration;
    }

    public void lifecycle(
            ProviderTenant tenant,
            long expectedVersion,
            String desiredState,
            String justification,
            String correlationId) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        ObjectNode previous = objectMapper.createObjectNode()
                .put("lifecycleState", tenant.getLifecycleState());
        ObjectNode desired = objectMapper.createObjectNode()
                .put("lifecycleState", desiredState)
                .put("justification", justification);
        ObjectNode commandPayload = objectMapper.createObjectNode()
                .put("lifecycleState", desiredState);
        List<TenantMutationRepository.CommandSpec> commands = new ArrayList<>();
        List<String> targets = "ACTIVE".equals(desiredState)
                ? List.of("PLATFORM", "PEOPLE", "AUTH")
                : List.of("AUTH", "PLATFORM", "PEOPLE");
        targets.forEach(target -> commands.add(new TenantMutationRepository.CommandSpec(
                target, "LIFECYCLE", commandPayload.deepCopy())));
        execute(repository.create(new TenantMutationRepository.MutationRequest(
                tenant.getProviderTenantId(), "LIFECYCLE",
                idempotencyKey(tenant.getProviderTenantId(), "LIFECYCLE", expectedVersion),
                ProviderTenantCommand.payloadSha256(objectMapper, desired),
                expectedVersion, previous, desired, actor.operatorId(), correlationId,
                List.copyOf(commands))), true, null);
    }

    public ActivationFence activateForOnboarding(
            ProviderTenant tenant,
            UUID providerOperationId,
            UUID operationLeaseToken,
            String correlationId) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        String idempotencyKey = "provider-onboarding:" + providerOperationId + ":activate";
        TenantMutationRepository.Mutation replay =
                onboardingActivationRepository.byIdempotencyKey(idempotencyKey);
        long currentVersion = tenant.getVersion() == null ? 0L : tenant.getVersion();
        long expectedVersion = replay == null ? currentVersion : replay.expectedTenantVersion();
        if (replay == null && !activationCanRun(tenant)) {
            throw conflict("Tenant containment superseded onboarding before activation started.");
        }
        if (replay != null
                && !"SUCCEEDED".equals(replay.lifecycleState())
                && (tenant.getVersion() == null || tenant.getVersion() != expectedVersion
                || !activationCanRun(tenant))) {
            throw conflict("Tenant containment superseded the durable onboarding activation.");
        }

        ObjectNode previous = objectMapper.createObjectNode()
                .put("lifecycleState", replay == null
                        ? tenant.getLifecycleState()
                        : replay.previousPayload().path("lifecycleState").asText());
        ObjectNode desired = objectMapper.createObjectNode()
                .put("lifecycleState", "ACTIVE")
                .put("justification", "Provider onboarding activation")
                .put("providerOperationId", providerOperationId.toString());
        ObjectNode commandPayload = objectMapper.createObjectNode().put("lifecycleState", "ACTIVE");
        List<TenantMutationRepository.CommandSpec> commands = List.of(
                new TenantMutationRepository.CommandSpec(
                        "PLATFORM", "LIFECYCLE", commandPayload.deepCopy()),
                new TenantMutationRepository.CommandSpec(
                        "PEOPLE", "LIFECYCLE", commandPayload.deepCopy()),
                new TenantMutationRepository.CommandSpec(
                        "AUTH", "LIFECYCLE", commandPayload.deepCopy()));
        ProviderOnboardingActivationRepository.OperationLease operationLease =
                new ProviderOnboardingActivationRepository.OperationLease(
                        providerOperationId, operationLeaseToken);
        TenantMutationRepository.Mutation mutation = onboardingActivationRepository.create(
                new TenantMutationRepository.MutationRequest(
                        tenant.getProviderTenantId(), "LIFECYCLE", idempotencyKey,
                        ProviderTenantCommand.payloadSha256(objectMapper, desired),
                        expectedVersion, previous, desired, actor.operatorId(), correlationId,
                        commands), operationLease);
        execute(mutation, true, operationLease);
        return new ActivationFence(
                mutation.mutationId(), mutation.providerTenantId(),
                mutation.expectedTenantVersion() + 1L, providerOperationId,
                operationLeaseToken);
    }

    public void completeOnboardingProjection(
            ActivationFence activation,
            long operatorId) {
        onboardingActivationRepository.completeProjection(
                activation.mutationId(), activation.providerTenantId(),
                activation.committedTenantVersion(),
                new ProviderOnboardingActivationRepository.OperationLease(
                        activation.operationId(), activation.operationLeaseToken()),
                operatorId);
    }

    public void replaceEntitlements(
            ProviderTenant tenant,
            long expectedVersion,
            List<String> desiredEntitlements,
            String justification,
            String correlationId) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        List<String> previousKeys = repository.activeEntitlementKeys(tenant.getProviderTenantId());
        List<String> desiredKeys = desiredEntitlements.stream().distinct().sorted().toList();
        ObjectNode previous = objectMapper.createObjectNode();
        previous.set("entitlementKeys", array(previousKeys));
        ObjectNode desired = objectMapper.createObjectNode();
        desired.set("entitlementKeys", array(desiredKeys));
        desired.put("justification", justification);

        Set<String> removed = new LinkedHashSet<>(previousKeys);
        removed.removeAll(desiredKeys);
        Set<String> additions = new LinkedHashSet<>(desiredKeys);
        additions.removeAll(previousKeys);

        List<TenantMutationRepository.CommandSpec> commands = new ArrayList<>();
        if (!removed.isEmpty()) {
            List<String> revokeFirst = previousKeys.stream()
                    .filter(desiredKeys::contains).sorted().toList();
            commands.add(entitlementCommand("AUTH", revokeFirst));
        }
        commands.add(entitlementCommand("PLATFORM", desiredKeys));
        if (removed.isEmpty() || !additions.isEmpty()) {
            commands.add(entitlementCommand("AUTH", desiredKeys));
        }

        execute(repository.create(new TenantMutationRepository.MutationRequest(
                tenant.getProviderTenantId(), "ENTITLEMENTS",
                idempotencyKey(tenant.getProviderTenantId(), "ENTITLEMENTS", expectedVersion),
                ProviderTenantCommand.payloadSha256(objectMapper, desired),
                expectedVersion, previous, desired, actor.operatorId(), correlationId,
                List.copyOf(commands))), true, null);
    }

    void recoverOne() {
        repository.releaseExpiredLeases();
        TenantMutationRepository.CommandLease command =
                repository.claimNext(null, workerId, leaseDuration);
        if (command == null) {
            TenantMutationRepository.Completion completion = repository.completeNextReady();
            if (completion == TenantMutationRepository.Completion.SUCCEEDED) metric("succeeded");
            if (completion == TenantMutationRepository.Completion.COMPENSATED) metric("compensated");
            if (completion == TenantMutationRepository.Completion.RECONCILIATION_REQUIRED) {
                metric("reconciliation_required");
            }
            return;
        }
        process(command, false, null);
    }

    private void execute(
            TenantMutationRepository.Mutation mutation,
            boolean synchronous,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        if ("SUCCEEDED".equals(mutation.lifecycleState())) return;
        if ("RECONCILIATION_REQUIRED".equals(mutation.lifecycleState())) {
            throw conflict("The tenant mutation requires reconciliation before it can be replayed.");
        }
        if ("COMPENSATED".equals(mutation.lifecycleState())) {
            throw conflict("The tenant mutation was safely compensated and was not committed.");
        }
        while (true) {
            TenantMutationRepository.CommandLease command = claimNext(
                    mutation.mutationId(), operationLease);
            if (command == null) {
                TenantMutationRepository.Completion completion =
                        completeIfReady(mutation.mutationId(), operationLease);
                if (completion == TenantMutationRepository.Completion.SUCCEEDED) return;
                if (completion == TenantMutationRepository.Completion.RECONCILIATION_REQUIRED) {
                    metric("reconciliation_required");
                    throw conflict("The tenant mutation requires reconciliation.");
                }
                if (completion == TenantMutationRepository.Completion.COMPENSATED) {
                    metric("compensated");
                    throw new BaseException(
                            ErrorCode.EXTERNAL_SERVICE_ERROR,
                            "The tenant mutation failed and was safely compensated.");
                }
                throw new BaseException(
                        ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The durable tenant mutation is waiting for recovery.");
            }
            process(command, synchronous, operationLease);
        }
    }

    private void process(
            TenantMutationRepository.CommandLease command,
            boolean synchronous,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        try {
            ProviderTenantCommand.Receipt receipt = downstream.executeTenantCommand(
                    command.targetService(), command.providerTenantId(), command.request());
            markApplied(command, receipt, operationLease);
            TenantMutationRepository.Completion completion =
                    completeIfReady(command.mutationId(), operationLease);
            if (completion == TenantMutationRepository.Completion.SUCCEEDED) metric("succeeded");
            if (completion == TenantMutationRepository.Completion.COMPENSATED) metric("compensated");
            if (completion == TenantMutationRepository.Completion.RECONCILIATION_REQUIRED) {
                metric("reconciliation_required");
            }
        } catch (HttpClientErrorException.Conflict conflict) {
            TenantMutationRepository.FailureDisposition disposition = markFailed(
                    command, maximumAttempts, true,
                    "DOWNSTREAM_REVISION_CONFLICT", safeMessage(conflict), operationLease);
            metric(disposition.name().toLowerCase(java.util.Locale.ROOT));
            if (synchronous) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "A downstream tenant command was rejected as duplicate or out of order.",
                        conflict);
            }
        } catch (RuntimeException failure) {
            TenantMutationRepository.FailureDisposition disposition = markFailed(
                    command, maximumAttempts, false,
                    safeCode(failure), safeMessage(failure), operationLease);
            metric(disposition.name().toLowerCase(java.util.Locale.ROOT));
            if (synchronous) {
                if (failure instanceof BaseException baseException
                        && baseException.getErrorCode() == ErrorCode.RESOURCE_CONFLICT) {
                    throw baseException;
                }
                throw new BaseException(
                        ErrorCode.EXTERNAL_SERVICE_ERROR,
                        operationLease == null
                                ? "The tenant mutation was persisted and will be recovered automatically."
                                : "The onboarding activation was persisted and requires a provider operation retry.",
                        failure);
            }
        }
    }

    private TenantMutationRepository.Completion completeIfReady(
            UUID mutationId,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        return operationLease == null
                ? repository.completeIfReady(mutationId)
                : onboardingActivationRepository.completeIfReady(mutationId, operationLease);
    }

    private TenantMutationRepository.CommandLease claimNext(
            UUID mutationId,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        return operationLease == null
                ? repository.claimNext(mutationId, workerId, leaseDuration)
                : onboardingActivationRepository.claimNext(
                        mutationId, workerId, leaseDuration, operationLease);
    }

    private void markApplied(
            TenantMutationRepository.CommandLease command,
            ProviderTenantCommand.Receipt receipt,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        if (operationLease == null) {
            repository.markApplied(command, receipt);
        } else {
            onboardingActivationRepository.markApplied(command, receipt, operationLease);
        }
    }

    private TenantMutationRepository.FailureDisposition markFailed(
            TenantMutationRepository.CommandLease command,
            int attempts,
            boolean permanent,
            String errorCode,
            String errorMessage,
            ProviderOnboardingActivationRepository.OperationLease operationLease) {
        return operationLease == null
                ? repository.markFailed(
                        command, attempts, permanent, errorCode, errorMessage)
                : onboardingActivationRepository.markFailed(
                        command, attempts, permanent, errorCode, errorMessage, operationLease);
    }

    private TenantMutationRepository.CommandSpec entitlementCommand(
            String target,
            List<String> keys) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("entitlementKeys", array(keys));
        return new TenantMutationRepository.CommandSpec(target, "ENTITLEMENTS", payload);
    }

    private ArrayNode array(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private String idempotencyKey(UUID tenantId, String type, long expectedVersion) {
        return "tenant-mutation:" + tenantId + ":" + type + ":" + expectedVersion;
    }

    private boolean activationCanRun(ProviderTenant tenant) {
        return "PROVISIONING".equals(tenant.getLifecycleState())
                && Set.of("PENDING_EXTERNAL", "FAILED").contains(tenant.getOnboardingState());
    }

    private String safeCode(RuntimeException failure) {
        if (failure instanceof RestClientResponseException response) {
            return "HTTP_" + response.getStatusCode().value();
        }
        if (failure instanceof DataAccessException) return "PERSISTENCE_FAILURE";
        if (failure instanceof BaseException baseException) {
            return baseException.getErrorCode().name();
        }
        return "TENANT_COMMAND_FAILED";
    }

    private String safeMessage(RuntimeException failure) {
        if (failure instanceof RestClientResponseException response) {
            return "Downstream tenant command failed (HTTP "
                    + response.getStatusCode().value() + ").";
        }
        if (failure instanceof DataAccessException) {
            return "Tenant mutation persistence failed. Review the correlated service trace.";
        }
        if (failure instanceof BaseException baseException) {
            return "Tenant mutation command failed (" + baseException.getErrorCode().name() + ").";
        }
        return "Tenant mutation command failed. Review the correlated service trace.";
    }

    private void metric(String outcome) {
        meterRegistry.counter("dwp.provider.tenant.mutation", "outcome", outcome).increment();
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public record ActivationFence(
            UUID mutationId,
            UUID providerTenantId,
            long committedTenantVersion,
            UUID operationId,
            UUID operationLeaseToken) {
    }

}
