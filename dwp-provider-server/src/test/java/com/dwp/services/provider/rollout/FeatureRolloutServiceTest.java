package com.dwp.services.provider.rollout;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRolloutServiceTest {

    private static final List<String> ALL_PRODUCT_SURFACE_FLAGS = List.of(
            "access.product-surfaces.context-shadow.v1",
            "access.product-surfaces.capability-enforcement.v1",
            "ux.product-surfaces.dwaion.v1",
            "ux.product-surfaces.communications.v1",
            "ux.product-surfaces.services.v1",
            "ux.product-surfaces.notifications.v1",
            "ux.product-surfaces.calendar.v1",
            "ux.product-surfaces.workplace.v1",
            "ux.product-surfaces.mail.v1",
            "ux.product-surfaces.messaging.v1",
            "ux.product-surfaces.approvals.v1",
            "ux.product-surfaces.spaces.v1",
            "ux.product-surfaces.hcm.v1");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeatureRolloutRepository repository = mock(FeatureRolloutRepository.class);
    private final ProviderAuditService audit = mock(ProviderAuditService.class);
    private final FeatureRolloutDecisionOutboxRepository decisionOutbox =
            mock(FeatureRolloutDecisionOutboxRepository.class);
    private final FeatureRolloutService service =
            new FeatureRolloutService(repository, audit, decisionOutbox);

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(7L, 1L);
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void requesterCannotApproveTheSameRevision() {
        UUID rolloutId = UUID.randomUUID();
        when(repository.rollout(rolloutId)).thenReturn(Optional.of(rollout(
                rolloutId, 7L, objectMapper.createObjectNode(), objectMapper.valueToTree(true))));

        assertThatThrownBy(() -> service.decide(
                rolloutId,
                new FeatureRolloutDtos.ApprovalDecisionRequest(
                        0, "APPROVED", "Self approval must be blocked."),
                "corr-self"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).decide(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void evaluationIsDeterministicAndUsesTheAuthorizedTenantTarget() {
        String featureKey = "workspace.search-v2";
        UUID flagId = UUID.randomUUID();
        UUID rolloutId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        var targeting = objectMapper.createObjectNode();
        targeting.putArray("tenantIds").add(tenantId.toString());
        FeatureRolloutRepository.FlagRow flag = new FeatureRolloutRepository.FlagRow(
                flagId, featureKey, "Search v2", "Controlled search experience",
                "dwp-platform-server", "BOOLEAN", objectMapper.valueToTree(false),
                objectMapper.createObjectNode(), "L2", "ACTIVE", 0L);
        FeatureRolloutRepository.RolloutRow rollout = rollout(
                rolloutId, 18L, targeting, objectMapper.valueToTree(true));
        when(repository.flag(featureKey)).thenReturn(Optional.of(flag));
        when(repository.tenant(tenantId)).thenReturn(Optional.of(
                new FeatureRolloutRepository.TenantRow(
                        tenantId, "skax", "ap-northeast-2", "ENTERPRISE", "POOL")));
        when(repository.effectiveRollouts(flagId)).thenReturn(List.of(rollout));
        when(repository.stages(rolloutId)).thenReturn(List.of(
                new FeatureRolloutRepository.StageRow(
                        UUID.randomUUID(), 1, "All tenants", BigDecimal.valueOf(100),
                        0, objectMapper.createObjectNode(), "ACTIVE", Instant.now(), null)));

        FeatureRolloutDtos.Evaluation first = service.evaluate(featureKey, tenantId);
        FeatureRolloutDtos.Evaluation second = service.evaluate(featureKey, tenantId);

        assertThat(first.value().booleanValue()).isTrue();
        assertThat(first.reasonCode()).isEqualTo("ROLLOUT_MATCH");
        assertThat(first.deterministicBucket()).isEqualTo(second.deterministicBucket());
        assertThat(first.deterministicBucket()).isBetween(0, 9_999);
    }

    @Test
    void inlineSecretMaterialIsRejectedBeforePersistence() {
        String featureKey = "integration.crm-config";
        FeatureRolloutRepository.FlagRow flag = new FeatureRolloutRepository.FlagRow(
                UUID.randomUUID(), featureKey, "CRM config", "CRM adapter config",
                "dwp-platform-server", "JSON", objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), "L3", "ACTIVE", 0L);
        when(repository.lockFlag(featureKey)).thenReturn(Optional.of(flag));
        var value = objectMapper.createObjectNode().put("apiToken", "plain-text-token");

        assertThatThrownBy(() -> service.createRollout(
                featureKey,
                new FeatureRolloutDtos.CreateRolloutRequest(
                        "CRM rollout", value, objectMapper.createObjectNode(),
                        "ALL_AT_ONCE", "Controlled activation",
                        List.of(new FeatureRolloutDtos.StageRequest(
                                "All tenants", BigDecimal.valueOf(100), 0,
                                objectMapper.createObjectNode()))),
                "corr-secret"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).createRollout(any(), any(), any(), any());
    }

    @Test
    void activationAdvancesTheInvalidationRevisionInTheSameServiceTransaction() {
        UUID rolloutId = UUID.randomUUID();
        UUID flagId = UUID.randomUUID();
        String featureKey = "ux.product-surfaces.communications.v1";
        FeatureRolloutRepository.RolloutRow active = new FeatureRolloutRepository.RolloutRow(
                rolloutId, flagId, featureKey, 2, "Communications canary", "ACTIVE",
                objectMapper.valueToTree(true), objectMapper.createObjectNode(), "RING",
                1, null, null, "Approved canary", 7L, 19L,
                Instant.now(), Instant.now(), Instant.now(), null, null, 5L);
        when(repository.rollout(rolloutId)).thenReturn(Optional.of(active));
        when(repository.activate(rolloutId, 4L)).thenReturn(true);

        service.activate(
                rolloutId,
                new FeatureRolloutDtos.VersionedReasonRequest(4L, "Canary approved"),
                "corr-canary");

        verify(decisionOutbox).appendAllTenants(flagId, featureKey, "ENABLED");
    }

    @Test
    void evaluationAllowlistCoversEveryGovernedProductSurfaceAndRejectsUnknownKeys() {
        assertThat(ALL_PRODUCT_SURFACE_FLAGS)
                .hasSize(13)
                .allMatch(FeatureRolloutService::isProductSurfaceFlag);
        assertThat(FeatureRolloutService.isProductSurfaceFlag("ux.product-surfaces.unknown.v1"))
                .isFalse();
    }

    private FeatureRolloutRepository.RolloutRow rollout(
            UUID rolloutId,
            Long requestedBy,
            com.fasterxml.jackson.databind.JsonNode targeting,
            com.fasterxml.jackson.databind.JsonNode value) {
        return new FeatureRolloutRepository.RolloutRow(
                rolloutId, UUID.randomUUID(), "workspace.search-v2", 1,
                "Search v2 rollout", "ACTIVE", value, targeting, "PERCENTAGE",
                1, null, null, "Controlled rollout", requestedBy, 19L,
                Instant.now(), Instant.now(), Instant.now(), null, null, 0L);
    }
}
