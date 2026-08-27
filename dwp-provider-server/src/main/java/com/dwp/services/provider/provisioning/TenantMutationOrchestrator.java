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
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantMutationOrchestrator {

    private final TenantMutationRepository repository;
    private final DownstreamProvisioningClient downstream;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String workerId;
    private final int maximumAttempts;
    private final Duration leaseDuration;

    public TenantMutationOrchestrator(
            TenantMutationRepository repository,
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
                List.copyOf(commands))), true);
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
                List.copyOf(commands))), true);
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
        process(command, false);
    }

    private void execute(TenantMutationRepository.Mutation mutation, boolean synchronous) {
        if ("SUCCEEDED".equals(mutation.lifecycleState())) return;
        if ("RECONCILIATION_REQUIRED".equals(mutation.lifecycleState())) {
            throw conflict("The tenant mutation requires reconciliation before it can be replayed.");
        }
        if ("COMPENSATED".equals(mutation.lifecycleState())) {
            throw conflict("The tenant mutation was safely compensated and was not committed.");
        }
        while (true) {
            TenantMutationRepository.CommandLease command =
                    repository.claimNext(mutation.mutationId(), workerId, leaseDuration);
            if (command == null) {
                TenantMutationRepository.Completion completion =
                        repository.completeIfReady(mutation.mutationId());
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
            process(command, synchronous);
        }
    }

    private void process(TenantMutationRepository.CommandLease command, boolean synchronous) {
        try {
            ProviderTenantCommand.Receipt receipt = downstream.executeTenantCommand(
                    command.targetService(), command.providerTenantId(), command.request());
            repository.markApplied(command, receipt);
            TenantMutationRepository.Completion completion =
                    repository.completeIfReady(command.mutationId());
            if (completion == TenantMutationRepository.Completion.SUCCEEDED) metric("succeeded");
            if (completion == TenantMutationRepository.Completion.COMPENSATED) metric("compensated");
            if (completion == TenantMutationRepository.Completion.RECONCILIATION_REQUIRED) {
                metric("reconciliation_required");
            }
        } catch (HttpClientErrorException.Conflict conflict) {
            TenantMutationRepository.FailureDisposition disposition = repository.markFailed(
                    command, maximumAttempts, true, "DOWNSTREAM_REVISION_CONFLICT", conflict.getMessage());
            metric(disposition.name().toLowerCase(java.util.Locale.ROOT));
            if (synchronous) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "A downstream tenant command was rejected as duplicate or out of order.",
                        conflict);
            }
        } catch (RuntimeException failure) {
            TenantMutationRepository.FailureDisposition disposition = repository.markFailed(
                    command, maximumAttempts, false, failure.getClass().getSimpleName(), failure.getMessage());
            metric(disposition.name().toLowerCase(java.util.Locale.ROOT));
            if (synchronous) {
                if (failure instanceof BaseException baseException
                        && baseException.getErrorCode() == ErrorCode.RESOURCE_CONFLICT) {
                    throw baseException;
                }
                throw new BaseException(
                        ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The tenant mutation was persisted and will be recovered automatically.",
                        failure);
            }
        }
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

    private void metric(String outcome) {
        meterRegistry.counter("dwp.provider.tenant.mutation", "outcome", outcome).increment();
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
