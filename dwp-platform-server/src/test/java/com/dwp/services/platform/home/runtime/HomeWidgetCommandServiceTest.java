package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class HomeWidgetCommandServiceTest {

    private static final HomeOwnerActionContracts.Contract CONTRACT =
            HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
    private static final HomeRuntimeRolloutDecision.TrustedInput TRUSTED_ROLLOUT =
            new HomeRuntimeRolloutDecision.TrustedInput(
                    HomeRuntimeRolloutDecision.State.COMMAND_CANARY,
                    HomeRuntimeRolloutDecision.Ring.INTERNAL,
                    "rollout-command-test-1");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HomeRuntimeProperties properties = new HomeRuntimeProperties(
            true, false, true, Duration.ofMillis(900), Duration.ofMillis(400),
            Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);

    @Test
    void reauthorizesCurrentActionAndPersistsIdempotentReceipt() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-1");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, CONTRACT.actionId(), "result-1",
                Map.of("recommendationKey", "work-due-soon"));

        HomeReadModelDtos.HomeWidgetCommandReceipt receipt = service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId, request)
                .receipt();

        assertThat(receipt.commandId()).isEqualTo(commandId);
        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(provider.calls).hasValue(1);
        verify(receipts).record(
                eq(context.tenantId()), eq(context.userId()), eq(commandId),
                eq("HOME_WIDGET_ACTION"), eq(instanceId + ":" + CONTRACT.actionId()),
                any(String.class), eq(receipt));
    }

    @Test
    void staleExpectedResultVersionStopsBeforeProviderCommand() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-2");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        assertThatThrownBy(() -> service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon"))))
                .isInstanceOf(BaseException.class);
        assertThat(provider.calls).hasValue(0);
        verify(receipts, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completedIdempotencyReceiptReplaysWithoutReadingProviders() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubDecision(readModels, context);
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeReadModelDtos.HomeWidgetCommandReceipt prior = new HomeReadModelDtos.HomeWidgetCommandReceipt(
                UUID.randomUUID(), commandId, CONTRACT.commandKey(), "ACCEPTED",
                "/approvals/requests/1", OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        when(receipts.replay(
                eq(context.tenantId()), eq(context.userId()), eq(commandId),
                eq("HOME_WIDGET_ACTION"), eq(instanceId + ":" + CONTRACT.actionId()),
                any(String.class), eq(HomeReadModelDtos.HomeWidgetCommandReceipt.class))).thenReturn(prior);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        HomeReadModelDtos.HomeWidgetCommandReceipt actual = service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon")))
                .receipt();

        assertThat(actual).isEqualTo(prior);
        verify(readModels, never()).read(any(), any(), any(), any());
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void concurrentSameKeyExecutesOwnerCommandExactlyOnce() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        provider.delayMillis = 80;
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-1");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        emulateReceiptStore(receipts);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, CONTRACT.actionId(), "result-1",
                Map.of("recommendationKey", "work-due-soon"));

        CompletableFuture<HomeReadModelDtos.HomeWidgetCommandReceipt> first = CompletableFuture.supplyAsync(
                () -> service.execute(
                        context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId, request)
                        .receipt());
        CompletableFuture<HomeReadModelDtos.HomeWidgetCommandReceipt> second = CompletableFuture.supplyAsync(
                () -> service.execute(
                        context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId, request)
                        .receipt());

        assertThat(first.join()).isEqualTo(second.join());
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentCommandDigestIsAConflict() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-1");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        emulateReceiptStore(receipts);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        service.execute(context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon")));

        assertThatThrownBy(() -> service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-later"))))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT));
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void commandsRemainFailClosedAndAuditedUntilWaveSixPromotion() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        PlatformAuditService audit = mock(PlatformAuditService.class);
        HomeRuntimeProperties disabled = new HomeRuntimeProperties(
                true, false, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
        HomeWidgetCommandService service = service(
                provider, readModels, receipts, disabled, audit);

        assertThatThrownBy(() -> service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon"))))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE));

        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.attempted",
                "HOME_WIDGET_ACTION", instanceId + ":" + CONTRACT.actionId(),
                commandId.toString(), "SUCCESS");
        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.denied",
                "HOME_WIDGET_ACTION", instanceId + ":" + CONTRACT.actionId(),
                commandId.toString(), "DENIED");
        verify(readModels, never()).read(any(), any(), any(), any());
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void technicalProviderFailureIsAuditedAsFailedRatherThanDenied() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        provider.fail = true;
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-1");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        PlatformAuditService audit = mock(PlatformAuditService.class);
        HomeWidgetCommandService service = service(
                provider, readModels, receipts, properties, audit);

        assertThatThrownBy(() -> service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon"))))
                .isInstanceOf(WidgetProviderException.class);

        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.failed",
                "HOME_WIDGET_ACTION", instanceId + ":" + CONTRACT.actionId(),
                commandId.toString(), "FAILED");
        verify(audit, never()).event(
                context.tenantId(), context.userId(), "home.widget.command.denied",
                "HOME_WIDGET_ACTION", instanceId + ":" + CONTRACT.actionId(),
                commandId.toString(), "DENIED");
    }

    @Test
    void authorityExpiryDuringOwnerCommandRejectsTheReceiptAndCapsDeadline() {
        HomeRuntimeContext context = TestFixtures.withAuthorityRevalidateAt(
                TestFixtures.context(),
                OffsetDateTime.now(ZoneOffset.UTC).plus(Duration.ofMillis(300)));
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        provider.delayMillis = 500;
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        stubRead(readModels, context, instanceId, "result-1");
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        assertThatThrownBy(() -> service.execute(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, CONTRACT.actionId(), "result-1",
                        Map.of("recommendationKey", "work-due-soon"))))
                .isInstanceOf(BaseException.class);

        assertThat(provider.deadline.get()).isBeforeOrEqualTo(context.authorityRevalidateAt());
        verify(receipts, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    private HomeWidgetCommandService service(
            FakeCommandProvider provider,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts) {
        return service(
                provider, readModels, receipts, properties, mock(PlatformAuditService.class));
    }

    private HomeWidgetCommandService service(
            FakeCommandProvider provider,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts,
            HomeRuntimeProperties runtimeProperties,
            PlatformAuditService audit) {
        WidgetRegistryMutationGuard controls = mock(WidgetRegistryMutationGuard.class);
        when(controls.runtimeActionApproved(
                anyLong(), eq(CONTRACT.providerProductKey()), eq(CONTRACT.contractId())))
                .thenReturn(true);
        return new HomeWidgetCommandService(
                List.of(provider), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, runtimeProperties), runtimeProperties,
                audit, controls, mock(HomeRuntimeTelemetry.class));
    }

    private void stubRead(
            HomeReadModelService readModels,
            HomeRuntimeContext context,
            UUID instanceId,
            String resultVersion) {
        HomeRuntimeRolloutDecision decision = stubDecision(readModels, context);
        when(readModels.read(
                context, TRUSTED_ROLLOUT, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(
                        model(instanceId, resultVersion, decision), "\"e\"", decision));
    }

    private HomeRuntimeRolloutDecision stubDecision(
            HomeReadModelService readModels,
            HomeRuntimeContext context) {
        HomeRuntimeRolloutDecision decision = new HomeRuntimeRolloutDecision(
                HomeRuntimeRolloutDecision.State.COMMAND_CANARY,
                "CLASSIC",
                HomeRuntimeRolloutDecision.Ring.INTERNAL,
                TRUSTED_ROLLOUT.revision(),
                false,
                Set.of(CONTRACT.providerProductKey()),
                Set.of(CONTRACT.definitionKey()),
                Set.of(CONTRACT.contractId()),
                context.authorityRevalidateAt());
        when(readModels.resolveCurrentDecision(context, TRUSTED_ROLLOUT, "CLASSIC"))
                .thenReturn(decision);
        return decision;
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

    private HomeReadModelDtos.HomeReadModel model(
            UUID instanceId,
            String resultVersion,
            HomeRuntimeRolloutDecision decision) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.Action action = CONTRACT.action(resultVersion);
        HomeReadModelDtos.Widget widget = new HomeReadModelDtos.Widget(
                instanceId, CONTRACT.definitionKey(), CONTRACT.definitionVersion(),
                CONTRACT.definitionManifestHash(), "binding-1", "home.dailyBrief",
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeReadModelDtos.SourceState(
                        "PLATFORM_HOME", now, now.plusSeconds(30), now,
                        null, false, resultVersion),
                Map.of("count", 1), List.of(action), List.of(),
                new HomeReadModelDtos.Governance(
                        CONTRACT.providerProductKey(), "APP.WORK",
                        List.of(CONTRACT.requiredAuthority()),
                        "INTERNAL", "NONE", "/home"));
        return new HomeReadModelDtos.HomeReadModel(
                HomeReadModelDtos.SCHEMA_VERSION, "CLASSIC", null, null,
                List.of(), List.of(widget), TestFixtures.runtimeDecision(decision), now,
                now.plusSeconds(30), false, List.of(), "change-1", "SHADOW");
    }

    private static final class FakeCommandProvider implements WidgetProviderPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final HomeRuntimeContext context;
        private final UUID commandId;
        private final AtomicReference<OffsetDateTime> deadline = new AtomicReference<>();
        private volatile long delayMillis;
        private volatile boolean fail;

        private FakeCommandProvider(HomeRuntimeContext context, UUID commandId) {
            this.context = context;
            this.commandId = commandId;
        }

        @Override
        public String providerKey() {
            return CONTRACT.providerKey();
        }

        @Override
        public HomeWidgetProviderContract.BatchResponse readBatch(
                HomeRuntimeContext ignored,
                List<Request> requests,
                OffsetDateTime deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HomeWidgetProviderContract.CommandResponse executeCommand(
                HomeRuntimeContext ignored,
                HomeWidgetProviderContract.CommandRequest request,
                OffsetDateTime deadline) {
            calls.incrementAndGet();
            this.deadline.set(deadline);
            if (fail) {
                throw new WidgetProviderException(
                        WidgetProviderException.Kind.UNAVAILABLE,
                        "PROVIDER_HTTP_5XX", "Owner provider failed.");
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return new HomeWidgetProviderContract.CommandResponse(
                    1, context.tenantId(), context.userId(),
                    context.authorityDecisionRevision(), UUID.randomUUID(), commandId,
                    request.actionId(), request.commandKey(),
                    HomeWidgetProviderContract.CommandStatus.ACCEPTED,
                    "/approvals/requests/1", OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        }
    }
}
