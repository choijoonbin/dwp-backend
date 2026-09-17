package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
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
        when(readModels.resolveCurrentDecision(context, trusted, "CLASSIC"))
                .thenReturn(decision);
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
        when(readModels.resolveCurrentDecision(context, trusted, "CLASSIC"))
                .thenReturn(decision);
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

    @Test
    void refreshedAuthorityLeaseReplaysBeforeChangedWidgetStateIsReadAgain() {
        UUID personId = UUID.randomUUID();
        OffsetDateTime firstExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(4);
        HomeRuntimeContext firstContext = HomeRuntimeContext.create(
                71L, 82L, personId, "APP.WORK:VIEW", "MEMBER", "team-a",
                "decision-17", firstExpiry.toString(), "ko-KR", "Asia/Seoul");
        HomeRuntimeContext renewedContext = HomeRuntimeContext.create(
                71L, 82L, personId, "APP.WORK:VIEW", "MEMBER", "team-a",
                "decision-17", firstExpiry.plusMinutes(1).toString(),
                "ko-KR", "Asia/Seoul");
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        "COMMAND_CANARY", "INTERNAL", "rollout-17");
        UUID instanceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        HomeRuntimeRolloutDecision firstDecision = decision(firstContext, contract);
        HomeRuntimeRolloutDecision renewedDecision = decision(renewedContext, contract);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.resolveCurrentDecision(firstContext, trusted, "CLASSIC"))
                .thenReturn(firstDecision);
        when(readModels.resolveCurrentDecision(renewedContext, trusted, "CLASSIC"))
                .thenReturn(renewedDecision);
        when(readModels.read(firstContext, trusted, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(
                        model(firstContext, instanceId, contract, firstDecision),
                        "\"etag\"", firstDecision));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        emulateReceiptStore(receipts);
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeActionApproved(
                firstContext.tenantId(), contract.providerProductKey(), contract.contractId()))
                .thenReturn(true);
        WidgetProviderPort provider = mock(WidgetProviderPort.class);
        when(provider.providerKey()).thenReturn("platform");
        when(provider.executeCommand(eq(firstContext), any(), any())).thenAnswer(invocation -> {
            HomeWidgetProviderContract.CommandRequest providerRequest = invocation.getArgument(1);
            return new HomeWidgetProviderContract.CommandResponse(
                    HomeWidgetProviderContract.SCHEMA_VERSION,
                    firstContext.tenantId(), firstContext.userId(),
                    firstContext.authorityDecisionRevision(), UUID.randomUUID(), commandId,
                    providerRequest.actionId(), providerRequest.commandKey(),
                    HomeWidgetProviderContract.CommandStatus.COMPLETED, "/home",
                    OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        });
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(provider), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties,
                mock(PlatformAuditService.class), controls);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, contract.actionId(), "result-1",
                Map.of("recommendationKey", "work-due-soon"));

        HomeWidgetCommandService.ExecutionResult first = service.execute(
                firstContext, trusted, "CLASSIC", "DESKTOP_STANDARD", commandId, request);
        HomeWidgetCommandService.ExecutionResult replay = service.execute(
                renewedContext, trusted, "CLASSIC", "DESKTOP_STANDARD", commandId, request);

        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(replay.replayed()).isTrue();
        assertThat(firstContext.fingerprint()).isNotEqualTo(renewedContext.fingerprint());
        assertThat(firstContext.stableAuthorityFingerprint())
                .isEqualTo(renewedContext.stableAuthorityFingerprint());
        verify(provider, times(1)).executeCommand(any(), any(), any());
        verify(readModels, never()).read(
                renewedContext, trusted, "CLASSIC", "DESKTOP_STANDARD");
    }

    @Test
    void crossScopeOwnerReceiptIsBlockedAndEmittedExactlyOnce() {
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
            HomeWidgetProviderContract.CommandRequest providerRequest = invocation.getArgument(1);
            return new HomeWidgetProviderContract.CommandResponse(
                    HomeWidgetProviderContract.SCHEMA_VERSION,
                    context.tenantId(), context.userId() + 1,
                    context.authorityDecisionRevision(), UUID.randomUUID(), commandId,
                    providerRequest.actionId(), providerRequest.commandKey(),
                    HomeWidgetProviderContract.CommandStatus.COMPLETED, "/home",
                    OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        });
        HomeRuntimeRolloutDecision decision = decision(context, contract);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.resolveCurrentDecision(context, trusted, "CLASSIC"))
                .thenReturn(decision);
        when(readModels.read(context, trusted, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(
                        model(context, instanceId, contract, decision), "\"etag\"", decision));
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeActionApproved(
                context.tenantId(), contract.providerProductKey(), contract.contractId()))
                .thenReturn(true);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(provider), readModels, mock(HomeCommandReceiptService.class),
                new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties,
                mock(PlatformAuditService.class), controls, new HomeRuntimeTelemetry(meters));
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, contract.actionId(), "result-1",
                Map.of("recommendationKey", "work-due-soon"));

        assertThatThrownBy(() -> service.execute(
                context, trusted, "CLASSIC", "DESKTOP_STANDARD", commandId, request))
                .isInstanceOf(WidgetProviderException.class);

        assertThat(meters.get("dwp.home.security.violation")
                .tags("release_ring", "INTERNAL", "mode", "CLASSIC",
                        "scope", "ACTION", "reason", "RECEIPT_MISMATCH")
                .counter().count()).isEqualTo(1);
    }

    private void emulateReceiptStore(HomeCommandReceiptService receipts) {
        AtomicReference<HomeReadModelDtos.HomeWidgetCommandReceipt> stored = new AtomicReference<>();
        AtomicReference<String> storedFingerprint = new AtomicReference<>();
        when(receipts.replay(any(), any(), any(), any(), any(), any(),
                eq(HomeReadModelDtos.HomeWidgetCommandReceipt.class))).thenAnswer(invocation -> {
            if (stored.get() == null) return null;
            String fingerprint = invocation.getArgument(5);
            if (!fingerprint.equals(storedFingerprint.get())) {
                throw new BaseException(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT);
            }
            return stored.get();
        });
        doAnswer(invocation -> {
            storedFingerprint.set(invocation.getArgument(5));
            stored.set(invocation.getArgument(6));
            return null;
        }).when(receipts).record(any(), any(), any(), any(), any(), any(), any());
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
