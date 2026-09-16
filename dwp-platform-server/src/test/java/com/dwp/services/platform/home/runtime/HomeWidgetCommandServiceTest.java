package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class HomeWidgetCommandServiceTest {

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
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-1"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, "accept-item", "result-1", Map.of("comment", "Approved"));

        HomeReadModelDtos.CommandReceipt receipt = service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId, request);

        assertThat(receipt.commandId()).isEqualTo(commandId);
        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(provider.calls).hasValue(1);
        verify(receipts).record(
                eq(context.tenantId()), eq(context.userId()), eq(commandId),
                eq("HOME_WIDGET_ACTION"), eq(instanceId + ":accept-item"),
                any(String.class), eq(receipt));
    }

    @Test
    void staleExpectedResultVersionStopsBeforeProviderCommand() {
        HomeRuntimeContext context = TestFixtures.context();
        UUID commandId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        FakeCommandProvider provider = new FakeCommandProvider(context, commandId);
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-2"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        assertThatThrownBy(() -> service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of())))
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
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeReadModelDtos.CommandReceipt prior = new HomeReadModelDtos.CommandReceipt(
                UUID.randomUUID(), commandId, "approval.accept", "ACCEPTED",
                "/approvals/requests/1", OffsetDateTime.now(ZoneOffset.UTC), "result-2");
        when(receipts.replay(
                eq(context.tenantId()), eq(context.userId()), eq(commandId),
                eq("HOME_WIDGET_ACTION"), eq(instanceId + ":accept-item"),
                any(String.class), eq(HomeReadModelDtos.CommandReceipt.class))).thenReturn(prior);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        HomeReadModelDtos.CommandReceipt actual = service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of()));

        assertThat(actual).isEqualTo(prior);
        verify(readModels, never()).read(any(), any(), any());
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
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-1"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        emulateReceiptStore(receipts);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                instanceId, "accept-item", "result-1", Map.of("comment", "Approved"));

        CompletableFuture<HomeReadModelDtos.CommandReceipt> first = CompletableFuture.supplyAsync(
                () -> service.execute(context, "CLASSIC", "DESKTOP_STANDARD", commandId, request));
        CompletableFuture<HomeReadModelDtos.CommandReceipt> second = CompletableFuture.supplyAsync(
                () -> service.execute(context, "CLASSIC", "DESKTOP_STANDARD", commandId, request));

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
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-1"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        emulateReceiptStore(receipts);
        HomeWidgetCommandService service = service(provider, readModels, receipts);
        service.execute(context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of("comment", "A")));

        assertThatThrownBy(() -> service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of("comment", "B"))))
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
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(provider), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, disabled), disabled, audit);

        assertThatThrownBy(() -> service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of())))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE));

        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.attempted",
                "HOME_WIDGET_ACTION", instanceId + ":accept-item", commandId.toString(), "SUCCESS");
        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.denied",
                "HOME_WIDGET_ACTION", instanceId + ":accept-item", commandId.toString(), "DENIED");
        verify(readModels, never()).read(any(), any(), any());
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
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-1"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        PlatformAuditService audit = mock(PlatformAuditService.class);
        HomeWidgetCommandService service = new HomeWidgetCommandService(
                List.of(provider), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties, audit);

        assertThatThrownBy(() -> service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of())))
                .isInstanceOf(WidgetProviderException.class);

        verify(audit).event(
                context.tenantId(), context.userId(), "home.widget.command.failed",
                "HOME_WIDGET_ACTION", instanceId + ":accept-item", commandId.toString(), "FAILED");
        verify(audit, never()).event(
                context.tenantId(), context.userId(), "home.widget.command.denied",
                "HOME_WIDGET_ACTION", instanceId + ":accept-item", commandId.toString(), "DENIED");
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
        when(readModels.read(context, "CLASSIC", "DESKTOP_STANDARD"))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(instanceId, "result-1"), "\"e\""));
        HomeCommandReceiptService receipts = mock(HomeCommandReceiptService.class);
        HomeWidgetCommandService service = service(provider, readModels, receipts);

        assertThatThrownBy(() -> service.execute(
                context, "CLASSIC", "DESKTOP_STANDARD", commandId,
                new HomeReadModelDtos.CommandRequest(
                        instanceId, "accept-item", "result-1", Map.of())))
                .isInstanceOf(BaseException.class);

        assertThat(provider.deadline.get()).isBeforeOrEqualTo(context.authorityRevalidateAt());
        verify(receipts, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    private HomeWidgetCommandService service(
            FakeCommandProvider provider,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts) {
        return new HomeWidgetCommandService(
                List.of(provider), readModels, receipts, new HomeCanonicalJson(objectMapper),
                new ProviderResultValidator(objectMapper, properties), properties,
                mock(PlatformAuditService.class));
    }

    private void emulateReceiptStore(HomeCommandReceiptService receipts) {
        AtomicReference<HomeReadModelDtos.CommandReceipt> stored = new AtomicReference<>();
        AtomicReference<String> storedFingerprint = new AtomicReference<>();
        when(receipts.replay(any(), any(), any(), any(), any(), any(),
                eq(HomeReadModelDtos.CommandReceipt.class))).thenAnswer(invocation -> {
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

    private HomeReadModelDtos.HomeReadModel model(UUID instanceId, String resultVersion) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.Action action = new HomeWidgetProviderContract.Action(
                "accept-item", "home.action.accept",
                HomeWidgetProviderContract.ActionKind.COMMAND,
                null, "approval.accept", resultVersion, true);
        HomeReadModelDtos.Widget widget = new HomeReadModelDtos.Widget(
                instanceId, "approval.pending", "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "binding-1", "home.approval", HomeWidgetProviderContract.State.AVAILABLE,
                new HomeReadModelDtos.SourceState(
                        "APPROVAL_HOME", now, now.plusSeconds(30), now,
                        null, false, resultVersion),
                Map.of("count", 1), List.of(action), List.of(),
                new HomeReadModelDtos.Governance(
                        "approval", "APP.APPROVALS", List.of("APP.APPROVALS:VIEW"),
                        "CONFIDENTIAL", "NONE", "/approvals"));
        return new HomeReadModelDtos.HomeReadModel(
                2, "CLASSIC", null, null, List.of(), List.of(widget), now,
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
            return "approval";
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
