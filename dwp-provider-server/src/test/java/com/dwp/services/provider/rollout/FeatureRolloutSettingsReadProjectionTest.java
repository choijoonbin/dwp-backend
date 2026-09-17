package com.dwp.services.provider.rollout;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRolloutSettingsReadProjectionTest {

    private final FeatureRolloutRepository repository = mock(FeatureRolloutRepository.class);
    private final FeatureRolloutService service = new FeatureRolloutService(
            repository,
            mock(ProviderAuditService.class),
            mock(FeatureRolloutDecisionOutboxRepository.class));

    @BeforeEach
    void setContext() {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                1L, 2L, 3L, "operator", Set.of("PROVIDER_ADMIN"),
                Set.of("FEATURE_ROLLOUT_READ"),
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void effectiveSettingsProjectionDoesNotWriteRuntimeEvaluationTelemetry() {
        UUID flagId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var json = JsonMapper.builder().build();
        when(repository.flag("provider.example.flag")).thenReturn(Optional.of(
                new FeatureRolloutRepository.FlagRow(
                        flagId, "provider.example.flag", "Example", "Description",
                        "provider", "BOOLEAN", json.valueToTree(false),
                        json.createObjectNode(), "L2", "ACTIVE", 1)));
        when(repository.tenant(tenantId)).thenReturn(Optional.of(
                new FeatureRolloutRepository.TenantRow(
                        tenantId, "acme", "ap-northeast-2", "ENTERPRISE", "POOL")));
        when(repository.effectiveRollouts(flagId)).thenReturn(List.of());

        FeatureRolloutDtos.Evaluation result = service.resolveEffectiveValue(
                "provider.example.flag", tenantId);

        assertThat(result.reasonCode()).isEqualTo("DEFAULT");
        assertThat(result.value()).isEqualTo(json.valueToTree(false));
        verify(repository, never()).recordEvaluation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
