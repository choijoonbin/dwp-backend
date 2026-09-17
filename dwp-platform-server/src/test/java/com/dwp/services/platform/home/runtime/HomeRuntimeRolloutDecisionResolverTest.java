package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.HomeModeV4ActivationGate;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryActivationInterlock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeRuntimeRolloutDecisionResolverTest {

    @Test
    void deploymentCeilingPreventsForgedCommandPromotion() {
        HomeRuntimeRolloutDecisionResolver resolver = resolver(
                new HomeRuntimeRolloutProperties("SHADOW_COMPARE", "INTERNAL", "CLASSIC", ""),
                mock(WidgetRegistryMutationGuard.class));

        HomeRuntimeRolloutDecision decision = resolver.resolve(
                TestFixtures.context(), trusted("COMMAND_CANARY"), "CLASSIC", catalog());

        assertThat(decision.state())
                .isEqualTo(HomeRuntimeRolloutDecision.State.SHADOW_COMPARE);
        assertThat(decision.commandsEnabled()).isFalse();
        assertThat(decision.registryAuthoritative()).isFalse();
    }

    @Test
    void modeControlDowngradesEligibleReadToShadow() {
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeDenied(
                eq("RUNTIME_RENDER"), eq(71L), isNull(), isNull(), isNull(),
                eq("CLASSIC"), isNull())).thenReturn(true);
        HomeRuntimeRolloutDecisionResolver resolver = resolver(
                new HomeRuntimeRolloutProperties(
                        "READ_ONLY_ACTIVE", "INTERNAL", "CLASSIC", ""), controls);

        HomeRuntimeRolloutDecision decision = resolver.resolve(
                TestFixtures.context(), trusted("READ_ONLY_ACTIVE"), "CLASSIC", catalog());

        assertThat(decision.state())
                .isEqualTo(HomeRuntimeRolloutDecision.State.SHADOW_COMPARE);
    }

    @Test
    void commandCanaryNeedsExactConfiguredContractAndConsumedSodApproval() {
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeActionApproved(
                71L,
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION.providerProductKey(),
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION.contractId()))
                .thenReturn(true);
        HomeRuntimeRolloutDecisionResolver resolver = resolver(
                new HomeRuntimeRolloutProperties(
                        "COMMAND_CANARY", "INTERNAL", "CLASSIC",
                        HomeOwnerActionContracts.DISMISS_RECOMMENDATION.contractId()),
                controls);

        HomeRuntimeRolloutDecision allowed = resolver.resolve(
                TestFixtures.context(), trusted("COMMAND_CANARY"), "CLASSIC", catalog());
        when(controls.runtimeActionApproved(any(), any(), any())).thenReturn(false);
        HomeRuntimeRolloutDecision denied = resolver.resolve(
                TestFixtures.context(), trusted("COMMAND_CANARY"), "CLASSIC", catalog());

        assertThat(allowed.state()).isEqualTo(HomeRuntimeRolloutDecision.State.COMMAND_CANARY);
        assertThat(allowed.allowedActions()).containsExactly(
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION.contractId());
        assertThat(denied.state()).isEqualTo(HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE);
        assertThat(denied.allowedActions()).isEmpty();
    }

    @Test
    void malformedRolloutAndExpiredAuthorityFailClosed() {
        assertThatThrownBy(() -> HomeRuntimeRolloutDecision.TrustedInput.parse(
                "ACTIVE", "INTERNAL", "revision-1"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> HomeRuntimeContext.create(
                71L, 82L, UUID.randomUUID(), "APP.WORK:VIEW", "MEMBER", "team-a",
                "decision-17",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                "ko-KR", "Asia/Seoul"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void malformedConfiguredRingCannotActivateControl() {
        HomeRuntimeRolloutProperties rollout = new HomeRuntimeRolloutProperties(
                "READ_ONLY_ACTIVE", "INTERNL", "CLASSIC", "");

        assertThat(rollout.activeRing(HomeRuntimeRolloutDecision.Ring.CONTROL)).isFalse();
        assertThat(rollout.activeRing(HomeRuntimeRolloutDecision.Ring.INTERNAL)).isFalse();
    }

    private HomeRuntimeRolloutDecisionResolver resolver(
            HomeRuntimeRolloutProperties rollout,
            WidgetRegistryMutationGuard controls) {
        HomeRuntimeProperties runtime = new HomeRuntimeProperties(
                true, true, true,
                Duration.ofMillis(900), Duration.ofMillis(400), Duration.ofSeconds(30),
                Duration.ofMinutes(5), 100, 262_144);
        WidgetRegistryActivationInterlock interlock = mock(WidgetRegistryActivationInterlock.class);
        WidgetCatalogService catalog = mock(WidgetCatalogService.class);
        when(catalog.isHomeRuntimeBaseline(any())).thenReturn(true);
        when(catalog.authorityEvidence()).thenReturn(
                new WidgetRegistryActivationInterlock.AuthorityEvidence(
                        "SHADOW", false, 21, "binding-revision-1234567890", 7));
        return new HomeRuntimeRolloutDecisionResolver(
                runtime, rollout, new HomeModeV4ActivationGate(false), controls,
                interlock, catalog);
    }

    private HomeRuntimeRolloutDecision.TrustedInput trusted(String state) {
        return HomeRuntimeRolloutDecision.TrustedInput.parse(
                state, "INTERNAL", "rollout-17");
    }

    private WidgetCatalogService.RuntimeCatalog catalog() {
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        WidgetCatalogService.RuntimeDefinition definition =
                new WidgetCatalogService.RuntimeDefinition(
                        UUID.randomUUID(), contract.definitionKey(), "daily-brief",
                        UUID.randomUUID(), contract.definitionVersion(),
                        contract.definitionManifestHash(), "binding-revision-1234567890",
                        "home.daily-brief", contract.providerProductKey(), "APP.WORK",
                        List.of(contract.requiredAuthority()), "INTERNAL", "NONE", 30,
                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE, List.of());
        return new WidgetCatalogService.RuntimeCatalog(
                "SHADOW", "catalog-3", "binding-revision-1234567890",
                "policy-4", "safety-5", "catalog-decision-6", List.of(definition));
    }
}
