package com.dwp.services.provider.provisioning;

import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantMutationOrchestratorTest {

    private final TenantMutationRepository repository = mock(TenantMutationRepository.class);
    private final ProviderOnboardingActivationRepository onboardingActivationRepository =
            mock(ProviderOnboardingActivationRepository.class);
    private final DownstreamProvisioningClient downstream = mock(DownstreamProvisioningClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantMutationOrchestrator orchestrator = new TenantMutationOrchestrator(
            repository, onboardingActivationRepository, downstream,
            objectMapper, new SimpleMeterRegistry(),
            "mutation-test", 3, Duration.ofSeconds(30));

    @BeforeEach
    void setContext() {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                21L, 21L, 1L, "Tenant mutation test",
                Set.of("PROVIDER_TENANT_PROVISIONER"), Set.of("TENANT_WRITE")));
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void downstreamHttpResponseBodyIsNeverPersistedAsFailureEvidence() {
        UUID tenantId = UUID.randomUUID();
        UUID mutationId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        var payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        String hash = "a".repeat(64);
        TenantMutationRepository.Mutation mutation = new TenantMutationRepository.Mutation(
                mutationId, tenantId, "LIFECYCLE", "mutation-key", hash,
                0, 1, objectMapper.createObjectNode(), payload,
                "PENDING", 21L, "corr-redaction");
        TenantMutationRepository.CommandLease command =
                new TenantMutationRepository.CommandLease(
                        commandId, mutationId, tenantId, "AUTH", "LIFECYCLE",
                        0, 1, hash, payload, 1, false, leaseToken);
        when(repository.create(any())).thenReturn(mutation);
        when(repository.claimNext(any(), anyString(), any())).thenReturn(command);
        String canary = "pii-canary@example.test bearer-secret";
        when(downstream.executeTenantCommand(anyString(), any(), any())).thenThrow(
                HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Provider failed",
                        HttpHeaders.EMPTY,
                        ("{\"detail\":\"" + canary + "\"}").getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
        when(repository.markFailed(any(), anyInt(), anyBoolean(), anyString(), anyString()))
                .thenReturn(TenantMutationRepository.FailureDisposition.RETRY_SCHEDULED);

        assertThatThrownBy(() -> orchestrator.lifecycle(
                tenant, 0L, "SUSPENDED", "Approved containment", "corr-redaction"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("recovered automatically");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(repository).markFailed(
                any(), anyInt(), anyBoolean(), code.capture(), message.capture());
        assertThat(code.getValue()).isEqualTo("HTTP_500");
        assertThat(message.getValue()).isEqualTo("Downstream tenant command failed (HTTP 500).");
        assertThat(message.getValue()).doesNotContain(canary, "bearer-secret");
    }

    @Test
    void completedOnboardingActivationReplaysTheOperationBoundPayloadWithoutRemoteCalls() {
        UUID tenantId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID operationLeaseToken = UUID.randomUUID();
        String idempotencyKey = "provider-onboarding:" + operationId + ":activate";
        var previous = objectMapper.createObjectNode().put("lifecycleState", "PROVISIONING");
        var desired = objectMapper.createObjectNode()
                .put("lifecycleState", "ACTIVE")
                .put("justification", "Provider onboarding activation")
                .put("providerOperationId", operationId.toString());
        TenantMutationRepository.Mutation replay = new TenantMutationRepository.Mutation(
                UUID.randomUUID(), tenantId, "LIFECYCLE", idempotencyKey,
                ProviderTenantCommand.payloadSha256(objectMapper, desired),
                0L, 1L, previous, desired, "SUCCEEDED", 21L, "corr-original");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("ACTIVE")
                .onboardingState("PENDING_EXTERNAL")
                .version(1L)
                .build();
        when(onboardingActivationRepository.byIdempotencyKey(idempotencyKey))
                .thenReturn(replay);
        when(onboardingActivationRepository.create(any(), any())).thenReturn(replay);

        TenantMutationOrchestrator.ActivationFence fence = orchestrator.activateForOnboarding(
                tenant, operationId, operationLeaseToken, "corr-retry");

        ArgumentCaptor<TenantMutationRepository.MutationRequest> request =
                ArgumentCaptor.forClass(TenantMutationRepository.MutationRequest.class);
        verify(onboardingActivationRepository).create(request.capture(), any());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(request.getValue().expectedTenantVersion()).isZero();
        assertThat(request.getValue().payloadSha256()).isEqualTo(replay.payloadSha256());
        assertThat(request.getValue().desiredPayload()).isEqualTo(desired);
        assertThat(fence.committedTenantVersion()).isEqualTo(1L);
        verifyNoInteractions(downstream);
    }

    @Test
    void failedServiceProjectionCanStartTheOperationBoundActivationAtItsCurrentVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID operationLeaseToken = UUID.randomUUID();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("PROVISIONING")
                .onboardingState("FAILED")
                .version(3L)
                .build();
        TenantMutationRepository.Mutation mutation = new TenantMutationRepository.Mutation(
                UUID.randomUUID(), tenantId, "LIFECYCLE",
                "provider-onboarding:" + operationId + ":activate", "b".repeat(64),
                3L, 1L, objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                "SUCCEEDED", 21L, "corr-service-retry");
        when(onboardingActivationRepository.byIdempotencyKey(anyString())).thenReturn(null);
        when(onboardingActivationRepository.create(any(), any())).thenReturn(mutation);

        TenantMutationOrchestrator.ActivationFence fence = orchestrator.activateForOnboarding(
                tenant, operationId, operationLeaseToken, "corr-service-retry");

        ArgumentCaptor<TenantMutationRepository.MutationRequest> request =
                ArgumentCaptor.forClass(TenantMutationRepository.MutationRequest.class);
        verify(onboardingActivationRepository).create(request.capture(), any());
        assertThat(request.getValue().expectedTenantVersion()).isEqualTo(3L);
        assertThat(request.getValue().desiredPayload().path("providerOperationId").asText())
                .isEqualTo(operationId.toString());
        assertThat(fence.committedTenantVersion()).isEqualTo(4L);
        verifyNoInteractions(downstream);
    }
}
