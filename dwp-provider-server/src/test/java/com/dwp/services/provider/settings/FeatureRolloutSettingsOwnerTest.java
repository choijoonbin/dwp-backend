package com.dwp.services.provider.settings;

import com.dwp.services.provider.rollout.FeatureRolloutDtos;
import com.dwp.services.provider.rollout.FeatureRolloutApplicationReceiptService;
import com.dwp.services.provider.rollout.FeatureRolloutService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureRolloutSettingsOwnerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ROLLOUT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-17T05:00:00Z");

    @Test
    void projectsExistingFeatureFlagMetadataWithoutCreatingAnotherOwnerStore() {
        FeatureRolloutService service = mock(FeatureRolloutService.class);
        when(service.flags()).thenReturn(List.of(flag()));
        FeatureRolloutSettingsOwner owner = new FeatureRolloutSettingsOwner(
                service, mock(FeatureRolloutApplicationReceiptService.class));

        SettingsContracts.Definition definition = owner.definitions().get(0);

        assertThat(definition.settingId())
                .isEqualTo("ux.product-surfaces.approvals.v1");
        assertThat(definition.owner().service()).isEqualTo("gateway");
        assertThat(definition.owner().readPermission())
                .isEqualTo("FEATURE_ROLLOUT_READ");
        assertThat(definition.change().workflow())
                .isEqualTo(SettingsContracts.ChangeWorkflow.APPROVE_AND_ACTIVATE);
        assertThat(definition.supportedScopes())
                .containsExactly(SettingsContracts.ScopeType.TENANT);
    }

    @Test
    void resolvesTheOwnerDecisionAsUnobservedUntilGatewayProvidesAReceipt() {
        FeatureRolloutService service = mock(FeatureRolloutService.class);
        FeatureRolloutApplicationReceiptService receipts =
                mock(FeatureRolloutApplicationReceiptService.class);
        FeatureRolloutDtos.FeatureFlag flag = flag();
        FeatureRolloutDtos.Evaluation evaluation = new FeatureRolloutDtos.Evaluation(
                flag.featureKey(), TENANT_ID, "acme", JSON.valueToTree(true),
                "ROLLOUT_MATCH", ROLLOUT_ID, 4, BigDecimal.valueOf(25),
                1200, false, NOW);
        when(service.flags()).thenReturn(List.of(flag));
        when(service.resolveEffectiveValue(flag.featureKey(), TENANT_ID)).thenReturn(evaluation);
        when(receipts.snapshot(flag.featureKey(), TENANT_ID)).thenReturn(
                new FeatureRolloutApplicationReceiptService.ApplicationSnapshot(
                        true, "rev-00000000000000000007", NOW.minusSeconds(30),
                        1, List.of()));
        FeatureRolloutSettingsOwner owner = new FeatureRolloutSettingsOwner(service, receipts);

        SettingsContracts.OwnerSnapshot snapshot = owner.resolve(
                owner.definitions().get(0),
                new SettingsContracts.ScopeTarget(
                        SettingsContracts.ScopeType.TENANT,
                        TENANT_ID.toString(), "production"));

        assertThat(snapshot.effectiveValue()).isEqualTo(JSON.valueToTree(true));
        assertThat(snapshot.effectiveVersion()).isEqualTo("rev-00000000000000000007");
        assertThat(snapshot.provenance()).singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceType())
                            .isEqualTo(SettingsContracts.SourceType.ACTIVE_ROLLOUT);
                    assertThat(item.decisionCode()).isEqualTo("ROLLOUT_MATCH");
                });
        assertThat(snapshot.applicationEvidence().observationSupported()).isTrue();
        assertThat(snapshot.applicationEvidence().expectedTargetCount()).isEqualTo(1);
        assertThat(snapshot.applicationEvidence().observations()).isEmpty();
    }

    @Test
    void projectsOnlyTheTrustedSampledRequestPathReceiptAsApplicationEvidence() {
        FeatureRolloutService service = mock(FeatureRolloutService.class);
        FeatureRolloutApplicationReceiptService receipts =
                mock(FeatureRolloutApplicationReceiptService.class);
        FeatureRolloutDtos.FeatureFlag flag = flag();
        when(service.resolveEffectiveValue(flag.featureKey(), TENANT_ID)).thenReturn(
                new FeatureRolloutDtos.Evaluation(
                        flag.featureKey(), TENANT_ID, "acme", JSON.valueToTree(true),
                        "ROLLOUT_MATCH", ROLLOUT_ID, 4, BigDecimal.valueOf(25),
                        1200, false, NOW));
        when(receipts.snapshot(flag.featureKey(), TENANT_ID)).thenReturn(
                new FeatureRolloutApplicationReceiptService.ApplicationSnapshot(
                        true, "rev-00000000000000000007", NOW.minusSeconds(30), 1,
                        List.of(new FeatureRolloutApplicationReceiptService.TargetReceipt(
                                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                                "APPLIED", "rev-00000000000000000007",
                                NOW.minusSeconds(5), NOW.minusSeconds(5), null))));
        FeatureRolloutSettingsOwner owner = new FeatureRolloutSettingsOwner(service, receipts);

        SettingsContracts.OwnerSnapshot snapshot = owner.resolve(
                definition(),
                new SettingsContracts.ScopeTarget(
                        SettingsContracts.ScopeType.TENANT, TENANT_ID.toString(), null));

        assertThat(snapshot.applicationEvidence().observations()).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.targetId())
                            .isEqualTo(FeatureRolloutApplicationReceiptService.GATEWAY_TARGET);
                    assertThat(observation.state())
                            .isEqualTo(SettingsContracts.ObservationState.APPLIED);
                    assertThat(observation.observedVersion())
                            .isEqualTo("rev-00000000000000000007");
                });
        assertThat(new SettingsApplicationStatusEvaluator()
                .evaluate(snapshot.applicationEvidence(), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.CONVERGED);
    }

    @Test
    void rejectsNonUuidTenantTargetsBeforeCallingTheOwner() {
        FeatureRolloutService service = mock(FeatureRolloutService.class);
        FeatureRolloutSettingsOwner owner = new FeatureRolloutSettingsOwner(
                service, mock(FeatureRolloutApplicationReceiptService.class));

        assertThatThrownBy(() -> owner.resolve(
                definition(),
                new SettingsContracts.ScopeTarget(
                        SettingsContracts.ScopeType.TENANT, "not-a-uuid", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a UUID");
    }

    @Test
    void removesAnEnvironmentDimensionTheRolloutOwnerDoesNotSupport() {
        FeatureRolloutSettingsOwner owner = new FeatureRolloutSettingsOwner(
                mock(FeatureRolloutService.class),
                mock(FeatureRolloutApplicationReceiptService.class));

        SettingsContracts.ScopeTarget target = owner.canonicalTarget(
                new SettingsContracts.ScopeTarget(
                        SettingsContracts.ScopeType.TENANT,
                        TENANT_ID.toString(), "production"));

        assertThat(target.environment()).isNull();
    }

    private FeatureRolloutDtos.FeatureFlag flag() {
        return new FeatureRolloutDtos.FeatureFlag(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                "ux.product-surfaces.approvals.v1",
                "Approvals UI",
                "Controls approvals user interface availability.",
                "gateway",
                "BOOLEAN",
                JSON.valueToTree(false),
                JSON.createObjectNode().put("type", "boolean"),
                "L2",
                "ACTIVE",
                7);
    }

    private SettingsContracts.Definition definition() {
        return new SettingsContracts.Definition(
                "ux.product-surfaces.approvals.v1", "Approvals UI", "Description",
                new SettingsContracts.Owner(
                        "gateway", "FEATURE_ROLLOUT", "FEATURE_ROLLOUT_READ",
                        "/provider/feature-rollouts"),
                java.util.Set.of(SettingsContracts.ScopeType.TENANT),
                new SettingsContracts.ValidationContract(
                        SettingsContracts.ValueType.BOOLEAN, "v1",
                        JSON.createObjectNode(), true),
                SettingsContracts.Sensitivity.INTERNAL,
                new SettingsContracts.ChangeContract(
                        "L2", SettingsContracts.ChangeWorkflow.APPROVE_AND_ACTIVATE,
                        true, true),
                SettingsContracts.LifecycleState.ACTIVE, 7);
    }
}
