package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeOwnerActionCommandGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HomeRuntimeProperties properties = new HomeRuntimeProperties(
            true, true, true,
            Duration.ofMillis(900), Duration.ofMillis(400), Duration.ofSeconds(30),
            Duration.ofMinutes(5), 100, 262_144);

    @Test
    void exactOwnerActionExecutesOnlyWithCurrentSodPermit() {
        HomeRuntimeContext context = TestFixtures.context();
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        "COMMAND_CANARY", "INTERNAL", "rollout-17");
        UUID instanceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        WidgetProviderPort provider = mock(WidgetProviderPort.class);
        when(provider.providerKey()).thenReturn("platform");
        when(provider.executeCommand(eq(context), any(), any())).thenAnswer(invocation -> {
            HomeWidgetProviderContract.CommandRequest request = invocation.getArgument(1);
            return new HomeWidgetProviderContract.CommandResponse(
                    HomeWidgetProviderContract.SCHEMA_VERSION,
                    context.tenantId(), context.userId(), context.authorityDecisionRevision(),
                    UUID.randomUUID(), commandId, request.actionId(), request.commandKey(),
                    HomeWidgetProviderContract.CommandStatus.COMPLETED, "/home",
                    OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        });
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        HomeRuntimeRolloutDecision decision = decision(context, contract);
        when(readModels.read(context, trusted, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(
                        model(context, instanceId, contract, decision), "\"etag\"", decision));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeActionApproved(
                context.tenantId(), contract.providerProductKey(), contract.contractId()))
                .thenReturn(true);
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(provider), readModels, receipts,
                new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties,
                mock(PlatformAuditService.class), controls);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, contract.actionId(), "result-1",
                Map.of("recommendationKey", "work-due-soon"));

        HomeWidgetCommandService.ExecutionResult result = service.execute(
                context, trusted, "CLASSIC", "DESKTOP_STANDARD", commandId, request);

        assertThat(result.receipt().status()).isEqualTo("COMPLETED");
        verify(provider).executeCommand(eq(context), any(), any());
    }

    @Test
    void actionRollbackRejectsEvenAStoredBrokerReplayBeforeReceiptLookup() {
        HomeRuntimeContext context = TestFixtures.context();
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        "COMMAND_CANARY", "INTERNAL", "rollout-17");
        UUID instanceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        HomeRuntimeRolloutDecision decision = decision(context, contract);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.read(context, trusted, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(
                        model(context, instanceId, contract, decision), "\"etag\"", decision));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties,
                mock(PlatformAuditService.class), controls);

        assertThatThrownBy(() -> service.execute(
                context, trusted, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, contract.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon"))))
                .isInstanceOf(BaseException.class);
        verify(receipts, never()).replay(any(), any(), any(), any(), any(), any(), any());
    }

    private HomeRuntimeRolloutDecision decision(
            HomeRuntimeContext context,
            HomeOwnerActionContracts.Contract contract) {
        return new HomeRuntimeRolloutDecision(
                HomeRuntimeRolloutDecision.State.COMMAND_CANARY,
                "CLASSIC", HomeRuntimeRolloutDecision.Ring.INTERNAL, "decision-rollout-17",
                false, Set.of(contract.providerProductKey()), Set.of(contract.definitionKey()),
                Set.of(contract.contractId()), context.authorityRevalidateAt());
    }

    private HomeReadModelDtos.HomeReadModel model(
            HomeRuntimeContext context,
            UUID instanceId,
            HomeOwnerActionContracts.Contract contract,
            HomeRuntimeRolloutDecision decision) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeReadModelDtos.Widget widget = new HomeReadModelDtos.Widget(
                instanceId, contract.definitionKey(), contract.definitionVersion(),
                contract.definitionManifestHash(), "binding-revision-1234567890",
                "home.daily-brief", HomeWidgetProviderContract.State.AVAILABLE,
                new HomeReadModelDtos.SourceState(
                        "DWP_HOME", now, now.plusSeconds(30), now, null, false, "result-1"),
                Map.of(), List.of(contract.action("result-1")), List.of(),
                new HomeReadModelDtos.Governance(
                        contract.providerProductKey(), "APP.WORK",
                        List.of(contract.requiredAuthority()), "INTERNAL", "NONE", "/home"));
        return new HomeReadModelDtos.HomeReadModel(
                3, "CLASSIC", null, null, List.of(), List.of(widget),
                new HomeReadModelDtos.RuntimeDecision(
                        decision.state().name(), decision.mode(), decision.ring().name(),
                        decision.revision(), true, false, context.authorityRevalidateAt()),
                now, now.plusSeconds(30), false, List.of(), "change-1", "SHADOW");
    }
}
