package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceServiceOperationsPostgresTest extends WorkplaceServicesPostgresTestSupport {
    @Test
    void lineCancellationConcurrentKeyIsSingleProviderCallAndCrossPairingFailsInDatabase()
            throws Exception {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_CANCEL_RACE", 2, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact firstPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel one seat service")));
        LineCancellationImpact secondPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel another seat service")));
        provider.cancelOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-race", firstPreview.refundableAmount(),
                "refund-receipt-race", "Cancellation and refund confirmed"));
        LineCancellationRequest firstRequest = new LineCancellationRequest(
                firstPreview.cancellationPreviewId(), order.version(), line.version(), true,
                firstPreview.reason());
        LineCancellationRequest secondRequest = new LineCancellationRequest(
                secondPreview.cancellationPreviewId(), order.version(), line.version(), true,
                secondPreview.reason());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> firstCall = () -> concurrentCancel(adjustments, fixture, order,
                    line, "same-race-key", firstRequest, ready, start);
            Callable<Object> secondCall = () -> concurrentCancel(adjustments, fixture, order,
                    line, "same-race-key", secondRequest, ready, start);
            Future<Object> one = executor.submit(firstCall);
            Future<Object> two = executor.submit(secondCall);
            ready.await();
            start.countDown();
            List<Object> results = List.of(one.get(), two.get());
            assertThat(results).filteredOn(LineAdjustmentCommandResult.class::isInstance)
                    .hasSize(1);
            BaseException conflict = (BaseException) results.stream()
                    .filter(BaseException.class::isInstance).findFirst().orElseThrow();
            assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        }
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.operationIds).hasSize(1);
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isEqualTo(1);
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(firstPreview.refundableAmount());
                });

        ServiceOrder other = submitOrder(
                fixture, "LINE_PAIR_OTHER", 1, 60, NOW.plusDays(3));
        ServiceOrderLine otherLine = other.lines().getFirst();
        TaskRow sourceTask = repository.task(
                fixture.tenant(), order.tasks().getFirst().fulfillmentTaskId()).orElseThrow();
        assertThatThrownBy(() -> repository.createTask(new TaskRow(
                UUID.randomUUID(), fixture.tenant(), other.serviceOrderId(),
                sourceTask.lineId(), sourceTask.state(), sourceTask.providerCode(),
                sourceTask.assigneeUserId(), sourceTask.assigneeDirectorySubjectId(),
                sourceTask.assigneeDisplayName(), sourceTask.assigneeDirectoryVersion(),
                sourceTask.responseDueAt(), sourceTask.dueAt(),
                sourceTask.providerReceiptAt(), sourceTask.acceptedAt(), sourceTask.completedAt(),
                sourceTask.externalReference(), sourceTask.blockerCode(),
                sourceTask.blockerDetail(), sourceTask.resultDetail(), 1, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        LineCancellationImpact valid = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(
                        service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).version(),
                        service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId())
                                .lines().getFirst().version(), 1, "DB invariant")));
        assertThatThrownBy(() -> repository.createLineAdjustment(new LineAdjustmentRow(
                UUID.randomUUID(), fixture.tenant(), ACTOR, other.serviceOrderId(),
                otherLine.serviceOrderLineId(), valid.cancellationPreviewId(), 1,
                RefundScope.FULL, BigDecimal.ZERO, BigDecimal.ZERO, "KRW",
                LineAdjustmentState.REFUND_NOT_CONFIGURED, "DWP_NATIVE_FULFILLMENT",
                1, "internal://workplace-service-native", null, null, "invalid pairing",
                1, NOW, NOW))).isInstanceOf(DataIntegrityViolationException.class);
        LineRow persistedLine = repository.line(
                fixture.tenant(), order.serviceOrderId(), line.serviceOrderLineId()).orElseThrow();
        OrderRow persistedOrder = repository.order(
                fixture.tenant(), order.serviceOrderId()).orElseThrow();
        OrderRow otherPersistedOrder = repository.order(
                fixture.tenant(), other.serviceOrderId()).orElseThrow();
        assertThatThrownBy(() -> repository.saveLineCancellationPreview(
                cancellationPreview(fixture, otherPersistedOrder, persistedLine,
                        persistedLine.unitPrice())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.saveLineCancellationPreview(
                cancellationPreview(fixture, persistedOrder, persistedLine,
                        persistedLine.unitPrice().multiply(BigDecimal.TEN))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void generalCommandAdvisoryLockReplaysConcurrentSameKeyInsteadOfLeakingUniqueConflicts()
            throws Exception {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "MESSAGE_IDEMPOTENCY", 1, 60);
        MessageRequest request = new MessageRequest(
                order.version(), "One durable requester message", true,
                "Prove concurrent replay");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ServiceOrderCommandResult> first = () -> concurrentMessage(
                    fixture, order, request, ready, start);
            Callable<ServiceOrderCommandResult> second = () -> concurrentMessage(
                    fixture, order, request, ready, start);
            Future<ServiceOrderCommandResult> one = executor.submit(first);
            Future<ServiceOrderCommandResult> two = executor.submit(second);
            ready.await();
            start.countDown();
            List<ServiceOrderCommandResult> results = List.of(one.get(), two.get());
            assertThat(results).extracting(value -> value.receipt().replayed())
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results.getFirst().receipt().commandId())
                    .isEqualTo(results.getLast().receipt().commandId());
        }
        assertThat(repository.messages(fixture.tenant(), order.serviceOrderId()))
                .singleElement().satisfies(value ->
                        assertThat(value.message()).isEqualTo("One durable requester message"));
    }

    @Test
    void providerRetryUsesDeterministicOperationIdAndOversizedEvidenceFailsControlled() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_PROVIDER_BOUNDARY", 1, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        provider.cancelFailure.set(new RuntimeException("transport failed before outcome"));
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact preview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Deterministic provider retry")));
        LineCancellationRequest request = new LineCancellationRequest(
                preview.cancellationPreviewId(), order.version(), line.version(), true,
                preview.reason());
        LineAdjustmentCommandResult pending = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-first"));
        assertThat(pending.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        assertThat(pending.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        provider.cancelFailure.set(null);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "x".repeat(321), preview.refundableAmount(), "refund-receipt-boundary",
                "Oversized evidence"));
        LineAdjustmentCommandResult stillPending = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-second"));
        assertThat(stillPending.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-boundary", preview.refundableAmount(),
                "refund-receipt-boundary", "Authoritative provider receipt"));
        LineAdjustmentCommandResult recovered = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-third"));
        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.reconcileCalls).hasValue(2);
        assertThat(provider.operationIds).hasSize(1);
        UUID expectedOperationId = UUID.nameUUIDFromBytes(
                ("workplace-line-adjustment:" + fixture.tenant() + ':'
                        + preview.cancellationPreviewId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(provider.operationIds).containsExactly(expectedOperationId);
        assertThat(repository.lineAdjustmentByPreview(
                fixture.tenant(), ACTOR, preview.cancellationPreviewId()))
                .hasValueSatisfying(value ->
                        assertThat(value.state()).isEqualTo(LineAdjustmentState.REFUNDED));
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> assertThat(value.cancelledQuantity()).isOne());
    }

    @Test
    void unconfiguredProviderNeverClaimsPaidCancellationButAllowsFreeNativeCancellation() {
        Fixture fixture = fixture();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));

        ServiceOrder paid = submitOrder(fixture, "PAID_PROVIDER_GUARD", 1, 60);
        ServiceOrderLine paidLine = paid.lines().getFirst();
        LineCancellationImpact paidPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(paid.version(), paidLine.version(), 1,
                        "Require authoritative paid cancellation")));
        LineAdjustmentCommandResult manualReview = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                "paid-not-configured", new LineCancellationRequest(
                        paidPreview.cancellationPreviewId(), paid.version(), paidLine.version(),
                        true, paidPreview.reason()), "corr-paid-not-configured"));
        assertThat(manualReview.adjustment().state())
                .isEqualTo(LineAdjustmentState.REFUND_NOT_CONFIGURED);
        assertThat(manualReview.receipt().state()).isEqualTo(CommandState.FAILED);
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, paid.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isZero();
                    assertThat(value.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_order_events
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND event_type = 'LINE_CANCELLATION_REQUIRES_REVIEW'
                """, Long.class, fixture.tenant(), paid.serviceOrderId())).isEqualTo(1L);
        assertThatThrownBy(() -> tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(paid.version(), paidLine.version(), 1,
                        "Wait for manual review"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-manual", paidPreview.refundableAmount(),
                "refund-receipt-manual", "Provider configured and refund confirmed"));
        LineAdjustmentCommandResult recovered = tx(() -> adjustments.reconcile(
                fixture.tenant(), ACTOR + 9, paid.serviceOrderId(),
                manualReview.adjustment().lineAdjustmentId(), "manual-review-reconcile",
                new LineAdjustmentReconcileRequest(manualReview.adjustment().version(), true,
                        "Reconcile after provider configuration"),
                "corr-manual-review-reconcile"));
        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(recovered.order().lines()).singleElement().satisfies(value -> {
            assertThat(value.cancelledQuantity()).isEqualTo(1);
            assertThat(value.refundedAmount())
                    .isEqualByComparingTo(paidPreview.refundableAmount());
        });

        ServiceOrder free = submitFreeNativeOrder(
                fixture, "FREE_PROVIDER_GUARD", 1, NOW.plusDays(3));
        ServiceOrderLine freeLine = free.lines().getFirst();
        LineCancellationImpact freePreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, free.serviceOrderId(), freeLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(free.version(), freeLine.version(), 1,
                        "Cancel free native service")));
        LineAdjustmentCommandResult localCancellation = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, free.serviceOrderId(), freeLine.serviceOrderLineId(),
                "free-not-configured", new LineCancellationRequest(
                        freePreview.cancellationPreviewId(), free.version(), freeLine.version(),
                        true, freePreview.reason()), "corr-free-not-configured"));
        assertThat(localCancellation.adjustment().state())
                .isEqualTo(LineAdjustmentState.CANCELLATION_SUCCEEDED);
        assertThat(localCancellation.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(localCancellation.order().lines()).singleElement().satisfies(value -> {
            assertThat(value.cancelledQuantity()).isEqualTo(1);
            assertThat(value.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void resultUnknownPersistsStatusOnlyRecoveryAndReconcilesWithAuthoritativeReceipt() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_RECONCILE", 2, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        provider.cancelFailure.set(new OutcomeUncertainException(
                "provider-operation-18", "Provider accepted but timed out", null));
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact preview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel with provider")));
        LineCancellationRequest cancelRequest = new LineCancellationRequest(
                preview.cancellationPreviewId(), order.version(), line.version(), true,
                preview.reason());
        LineAdjustmentCommandResult unknown = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "unknown-cancel", cancelRequest, "corr-unknown"));
        assertThat(unknown.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        assertThat(unknown.adjustment().providerOperationReference()).isNull();
        assertThat(unknown.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        assertThat(unknown.receipt().statusHref()).startsWith("/v1/workplace/");
        assertThat(adjustments.adminStatus(fixture.tenant(), order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId()).providerOperationReference())
                .isEqualTo("provider-operation-18");
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> assertThat(value.cancelledQuantity()).isZero());
        assertThatThrownBy(() -> tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Do not duplicate an unresolved cancellation"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "unknown-cancel-new-key", cancelRequest, "corr-unknown-duplicate")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND action = 'workplace.service.line.cancellation.result_unknown'
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(1L);

        provider.cancelFailure.set(null);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-18", preview.refundableAmount(), "refund-receipt-18",
                "Provider cancellation and refund confirmed"));
        LineAdjustmentReconcileRequest reconcileRequest =
                new LineAdjustmentReconcileRequest(unknown.adjustment().version(), true,
                        "Reconcile provider receipt");
        LineAdjustmentCommandResult reconciled = tx(() -> adjustments.reconcile(
                fixture.tenant(), ACTOR + 9, order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId(), "reconcile-18", reconcileRequest,
                "corr-reconcile"));
        assertThat(reconciled.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(reconciled.adjustment().refundedAmount())
                .isEqualByComparingTo(preview.refundableAmount());
        assertThat(reconciled.adjustment().refundReceiptReference())
                .isEqualTo("refund-receipt-18");
        assertThat(reconciled.receipt().statusHref()).startsWith("/v1/admin/workplace/");
        assertThat(tx(() -> adjustments.reconcile(fixture.tenant(), ACTOR + 9,
                order.serviceOrderId(), unknown.adjustment().lineAdjustmentId(),
                "reconcile-18", reconcileRequest, "ignored")).receipt().replayed()).isTrue();
        assertThat(provider.reconcileCalls).hasValue(1);
        assertThat(adjustments.status(fixture.tenant(), ACTOR, order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId())).satisfies(value -> {
                    assertThat(value.providerOperationReference()).isNull();
                    assertThat(value.refundReceiptReference()).isNull();
                    assertThat(value.resultDetail()).isNull();
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(preview.refundableAmount());
                });
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isEqualTo(1);
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(preview.refundableAmount());
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND action = 'workplace.service.line.reconciled'
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(1L);
        ServiceOrder current = service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId());
        assertThat(tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(current.version(),
                        current.lines().getFirst().version(), 1,
                        "Continue after authoritative reconciliation"))).eligible()).isTrue();
    }

    @Test
    void slaSnapshotDrivesDueAndBreachAfterCatalogChanges() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "SLA_SUPPORT", 1, 60);
        FulfillmentTask task = order.tasks().getFirst();
        assertThat(task.responseDueAt()).isEqualTo(NOW.plusMinutes(30));
        assertThat(task.dueAt()).isEqualTo(order.reservationStartsAt().minusMinutes(30));
        assertThat(task.providerReceiptAt()).isNull();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.slaResponseMinutes()).isEqualTo(30);
            assertThat(line.slaFulfillmentLeadMinutes()).isEqualTo(30);
        });
        jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET sla_response_minutes = 5, sla_fulfillment_lead_minutes = 5,
                       lifecycle_state = 'INACTIVE', version = version + 1
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, fixture.tenant(), order.lines().getFirst().catalogItemId());
        WorkplaceServicesService lateReader = new WorkplaceServicesService(
                repository, mapper, Clock.fixed(FIXED.plusSeconds(3 * 86_400), ZoneOffset.UTC));
        ServiceOrder late = lateReader.adminOrder(fixture.tenant(), order.serviceOrderId());
        assertThat(late.lines()).singleElement().satisfies(line -> {
            assertThat(line.slaResponseMinutes()).isEqualTo(30);
            assertThat(line.slaFulfillmentLeadMinutes()).isEqualTo(30);
        });
        assertThat(late.tasks()).singleElement().satisfies(value -> {
            assertThat(value.responseBreached()).isTrue();
            assertThat(value.fulfillmentBreached()).isTrue();
            assertThat(value.responseRemainingSeconds()).isZero();
            assertThat(value.fulfillmentRemainingSeconds()).isZero();
        });
        ServiceOrder delayed = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), task.fulfillmentTaskId(),
                "sla-delayed", new FulfillmentUpdateRequest(task.version(), WorkState.DELAYED,
                        ACTOR, "provider-sla-18", "SLA_DELAY", "Provider delayed",
                        "Receipt recorded", 0, "Record SLA delay", true), "corr-sla")).order();
        assertThat(delayed.state()).isEqualTo(OrderState.DELAYED);
        assertThat(delayed.tasks()).singleElement().satisfies(value -> {
            assertThat(value.providerReceiptAt()).isEqualTo(NOW);
            assertThat(value.responseBreached()).isFalse();
            assertThat(value.assigneeUserId()).isEqualTo(ACTOR);
            assertThat(value.externalFulfillmentReference()).isEqualTo("provider-sla-18");
        });
        jdbc.update("""
                UPDATE wp_service_orders SET provider_operation_reference = 'internal-provider-op'
                 WHERE tenant_id = ? AND service_order_id = ?
                """, fixture.tenant(), order.serviceOrderId());
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()))
                .satisfies(value -> {
                    assertThat(value.providerOperationReference()).isNull();
                    assertThat(value.tasks()).singleElement().satisfies(taskValue -> {
                        assertThat(taskValue.assigneeUserId()).isNull();
                        assertThat(taskValue.externalFulfillmentReference()).isNull();
                    });
                });
        assertThat(service.adminOrder(fixture.tenant(), order.serviceOrderId()))
                .satisfies(value -> {
                    assertThat(value.providerOperationReference()).isEqualTo("internal-provider-op");
                    assertThat(value.tasks()).singleElement().satisfies(taskValue -> {
                        assertThat(taskValue.assigneeUserId()).isEqualTo(ACTOR);
                        assertThat(taskValue.externalFulfillmentReference())
                                .isEqualTo("provider-sla-18");
                    });
                });
    }

    @Test
    void newAttachmentIsQuarantinedUntilVersionedCleanEvidenceAndUserEvidenceIsMasked() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "ATTACHMENT_SCAN", 1, 60);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService attachmentService =
                new WorkplaceServiceAttachmentService(service, repository, storage);
        String storageReference = "tenant/opaque/attachment.pdf";
        ServiceOrderCommandResult linked = tx(() -> service.linkAttachment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), false,
                "attachment-scan-link", order.version(), "Attach service evidence",
                new AttachmentUpload("evidence.pdf", "application/pdf", 128,
                        "a".repeat(64)), () -> storageReference, "corr-link"));
        ServiceOrderAttachment quarantined = repository.attachments(
                fixture.tenant(), order.serviceOrderId()).getFirst();
        assertThat(quarantined.scanState()).isEqualTo(AttachmentScanState.QUARANTINED);
        assertThatThrownBy(() -> attachmentService.download(fixture.tenant(), ACTOR,
                order.serviceOrderId(), quarantined.attachmentId(), false, "corr-blocked"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(storage, never()).load(anyLong(), anyString());

        AttachmentScanRequest clean = new AttachmentScanRequest(1,
                AttachmentScanVerdict.CLEAN, "scanner-receipt-clean-18",
                "Structural and malware scan passed", true,
                "Register authoritative scanner evidence");
        AttachmentScanCommandResult scanned = tx(() -> attachmentService.recordScan(
                fixture.tenant(), ACTOR + 9, order.serviceOrderId(),
                quarantined.attachmentId(), "scan-clean-18", clean, "corr-scan"));
        assertThat(scanned.attachment().scanState()).isEqualTo(AttachmentScanState.CLEAN);
        assertThat(scanned.attachment().scanVersion()).isEqualTo(2);
        assertThat(tx(() -> attachmentService.recordScan(fixture.tenant(), ACTOR + 9,
                order.serviceOrderId(), quarantined.attachmentId(), "scan-clean-18",
                clean, "ignored")).receipt().replayed()).isTrue();
        when(storage.load(fixture.tenant(), storageReference))
                .thenReturn(new ByteArrayResource("clean".getBytes()));
        assertThat(attachmentService.download(fixture.tenant(), ACTOR,
                order.serviceOrderId(), quarantined.attachmentId(), false,
                "corr-download").resource()).isNotNull();
        ServiceOrderAttachment userView = queryService.ownAttachments(
                fixture.tenant(), ACTOR, order.serviceOrderId(), null, 50).items().getFirst();
        ServiceOrderAttachment adminView = queryService.adminAttachments(
                fixture.tenant(), order.serviceOrderId(), null, 50).items().getFirst();
        assertThat(userView.scannerEvidenceReference()).isNull();
        assertThat(userView.scanDetail()).isNull();
        assertThat(adminView.scannerEvidenceReference()).isEqualTo("scanner-receipt-clean-18");
        assertThat(adminView.scanDetail()).isEqualTo("Structural and malware scan passed");
        RequesterServiceOrderEvent requesterScanEvent = queryService.ownEvents(
                fixture.tenant(), ACTOR, order.serviceOrderId(), null, 50).items().stream()
                .filter(value -> value.eventType().equals("ATTACHMENT_SCAN_UPDATED"))
                .findFirst().orElseThrow();
        ServiceOrderEvent adminScanEvent = queryService.adminEvents(
                fixture.tenant(), order.serviceOrderId(), null, 50).items().stream()
                .filter(value -> value.eventType().equals("ATTACHMENT_SCAN_UPDATED"))
                .findFirst().orElseThrow();
        assertThat(requesterScanEvent.detail().has("scannerEvidenceReference")).isFalse();
        assertThat(requesterScanEvent.detail().has("reason")).isFalse();
        assertThat(requesterScanEvent.detail().path("scanState").asText()).isEqualTo("CLEAN");
        assertThat(adminScanEvent.detail().path("scannerEvidenceReference").asText())
                .isEqualTo("scanner-receipt-clean-18");
        assertThat(adminScanEvent.actorUserId()).isEqualTo(ACTOR + 9);
        assertThatThrownBy(() -> queryService.ownAttachments(
                fixture.tenant(), ACTOR + 1, order.serviceOrderId(), null, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(linked.order().providerOperationReference()).isNull();
    }

    @Test
    void providerSnapshotConstraintsRejectPartialRowsUnderPostgresThreeValuedLogic() {
        long tenantId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)
                + 6_000_000_000L;
        UUID commandId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_operations_commands (
                    operations_command_id, tenant_id, actor_user_id, command_scope,
                    idempotency_key, request_fingerprint, resource_type, resource_id,
                    command_state, status_href, created_at, updated_at)
                VALUES (?, ?, ?, 'SNAPSHOT_CONSTRAINT', 'snapshot-constraint', ?,
                        'SERVICE_PROVIDER', ?, 'ACCEPTED', '/snapshot-constraint', ?, ?)
                """, commandId, tenantId, ACTOR, "a".repeat(64), UUID.randomUUID(), NOW, NOW);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET provider_profile_id_snapshot = ?,
                       provider_credential_binding_reference =
                           'secret-manager://workplace/services-test/v7'
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, UUID.randomUUID(), tenantId, commandId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET provider_code_snapshot = 'SERVICES_TEST',
                       adapter_type_snapshot = 'SERVICES_HTTP',
                       provider_configuration_version = 7,
                       provider_credential_binding_reference =
                           'secret-manager://workplace/services-test/v7',
                       provider_capabilities_snapshot = '[]'::jsonb,
                       provider_profile_version = 3
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, tenantId, commandId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "GRANT_SNAPSHOT_CONSTRAINT", 1, 60);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,service_order_id,service_order_line_id,
                    provider_code,requester_user_id,provider_grant_reference,
                    credential_fingerprint,grant_state,reason,issued_at,expires_at,
                    adapter_type_snapshot,provider_configuration_version,
                    provider_credential_binding_reference,provider_operation_kind,
                    provider_operation_command_id,version,created_at,updated_at)
                VALUES (?,?,?,?, 'DWP_NATIVE_FULFILLMENT',?,?,?,'ISSUED',?,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        NULL,NULL,'internal://test-native','ISSUE',NULL,1,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.tenant(), order.serviceOrderId(),
                order.lines().getFirst().serviceOrderLineId(), ACTOR,
                "partial-grant-" + UUID.randomUUID(), "a".repeat(64),
                "Partial provider snapshot must be rejected"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v277MigratesZeroVersionAdjustmentAndKeepsRotatedLegacyGrantFailClosed() {
        String schema = "v277_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        PGSimpleDataSource legacySource = new PGSimpleDataSource();
        legacySource.setURL(POSTGRES.getJdbcUrl());
        legacySource.setUser(POSTGRES.getUsername());
        legacySource.setPassword(POSTGRES.getPassword());
        legacySource.setCurrentSchema(schema);
        JdbcTemplate legacy = new JdbcTemplate(legacySource);
        legacy.execute("""
                CREATE TABLE wp_service_provider_profiles (
                    tenant_id BIGINT NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    adapter_type VARCHAR(80) NOT NULL,
                    configuration_version BIGINT NOT NULL,
                    credential_binding_reference VARCHAR(320))
                """);
        legacy.execute("""
                CREATE TABLE wp_service_order_lines (
                    tenant_id BIGINT NOT NULL,
                    service_order_id UUID NOT NULL,
                    service_order_line_id UUID NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    provider_configuration_version BIGINT NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_line_adjustments (
                    line_adjustment_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    service_order_id UUID NOT NULL,
                    service_order_line_id UUID NOT NULL,
                    adjustment_state VARCHAR(32) NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_ephemeral_access_grants (
                    access_grant_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    grant_state VARCHAR(20) NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_operations_commands (
                    operations_command_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    resource_type VARCHAR(80) NOT NULL,
                    resource_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL)
                """);

        long tenantId = 7_277_000_001L;
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID adjustmentId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        legacy.update("""
                INSERT INTO wp_service_provider_profiles (
                    tenant_id,provider_code,adapter_type,configuration_version,
                    credential_binding_reference)
                VALUES (?, 'ROTATED_PROVIDER', 'CURRENT_HTTP', 2,
                        'secret-manager://rotated/current/v2')
                """, tenantId);
        legacy.update("""
                INSERT INTO wp_service_order_lines (
                    tenant_id,service_order_id,service_order_line_id,
                    provider_code,provider_configuration_version)
                VALUES (?, ?, ?, 'ROTATED_PROVIDER', 0)
                """, tenantId, orderId, lineId);
        legacy.update("""
                INSERT INTO wp_service_line_adjustments (
                    line_adjustment_id,tenant_id,service_order_id,
                    service_order_line_id,adjustment_state)
                VALUES (?, ?, ?, ?, 'RESULT_UNKNOWN')
                """, adjustmentId, tenantId, orderId, lineId);
        legacy.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,provider_code,grant_state)
                VALUES (?, ?, 'ROTATED_PROVIDER', 'ISSUED')
                """, grantId, tenantId);

        var migration = Flyway.configure().dataSource(legacySource)
                .schemas(schema).baselineOnMigrate(true).baselineVersion("276")
                .target("277")
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(1);
        assertThat(legacy.queryForMap("""
                SELECT provider_code_snapshot,provider_configuration_version,
                       provider_credential_binding_reference
                  FROM wp_service_line_adjustments
                 WHERE line_adjustment_id=?
                """, adjustmentId))
                .containsEntry("provider_code_snapshot", "ROTATED_PROVIDER")
                .containsEntry("provider_configuration_version", 0L)
                .containsEntry("provider_credential_binding_reference", null);
        assertThat(legacy.queryForMap("""
                SELECT grant_state,provider_operation_kind,adapter_type_snapshot,
                       provider_configuration_version,
                       provider_credential_binding_reference,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE access_grant_id=?
                """, grantId))
                .containsEntry("grant_state", "ISSUED")
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("adapter_type_snapshot", null)
                .containsEntry("provider_configuration_version", null)
                .containsEntry("provider_credential_binding_reference", null)
                .containsEntry("provider_operation_command_id", null);

        var adapter = new WorkplaceServiceHttpProviderAdapter(java.util.Optional.empty(),
                "CURRENT_HTTP");
        var outcome = adapter.lookup(new ProviderRequest(adjustmentId, tenantId, orderId,
                lineId, "ROTATED_PROVIDER", 0, null, 1, BigDecimal.TEN, "KRW",
                "Legacy migration recovery"), adjustmentId.toString());
        assertThat(outcome.state()).isEqualTo(OutcomeState.NOT_CONFIGURED);
    }

    @Test
    void accessGrantRecoveryBackoffIsFairAndLegacyUnknownNeverUsesCurrentProfile() {
        Fixture fixture = fixture();
        ServiceOrder firstOrder = submitOrder(fixture, "GRANT_RECOVERY_FIRST", 1, 60);
        ServiceOrder secondOrder = submitOrder(
                fixture, "GRANT_RECOVERY_SECOND", 1, 60, NOW.plusDays(3));
        UUID firstGrant = insertUnknownGrant(fixture.tenant(), firstOrder, ACTOR,
                "grant-fair-first", false);
        UUID secondGrant = insertUnknownGrant(fixture.tenant(), secondOrder, ACTOR,
                "grant-fair-second", false);
        UUID legacyGrant = insertUnknownGrant(fixture.tenant(), firstOrder, ACTOR + 99,
                "grant-legacy-unknown", true);

        var firstCandidate = operationsRepository.pendingAccessGrants(1).getFirst();
        assertThat(firstCandidate.grantId()).isIn(firstGrant, secondGrant);
        OffsetDateTime claimedAt = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(operationsRepository.claimAccessGrantRecovery(
                fixture.tenant(), firstCandidate.grantId(), claimedAt)).isTrue();

        var secondCandidate = operationsRepository.pendingAccessGrants(1).getFirst();
        assertThat(secondCandidate.grantId()).isIn(firstGrant, secondGrant)
                .isNotEqualTo(firstCandidate.grantId());
        assertThat(operationsRepository.claimAccessGrantRecovery(
                fixture.tenant(), secondCandidate.grantId(), claimedAt)).isTrue();

        assertThat(operationsRepository.pendingAccessGrants(10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_ephemeral_access_grants
                 WHERE access_grant_id IN (?, ?)
                   AND provider_recovery_attempt_count = 1
                   AND provider_next_attempt_at > ?
                """, Integer.class, firstGrant, secondGrant, claimedAt)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT provider_operation_kind,adapter_type_snapshot,
                       provider_configuration_version,
                       provider_credential_binding_reference,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE tenant_id=? AND access_grant_id=?
                """, fixture.tenant(), legacyGrant))
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("adapter_type_snapshot", null)
                .containsEntry("provider_configuration_version", null)
                .containsEntry("provider_credential_binding_reference", null)
                .containsEntry("provider_operation_command_id", null);

        UUID issuedLegacyGrant = insertLegacyIssuedGrant(
                fixture.tenant(), firstOrder, ACTOR + 100, "legacy-issued-manual-revoke");
        assertThat(operationsRepository.beginGrantRevocation(fixture.tenant(),
                issuedLegacyGrant, 1, UUID.randomUUID(), NOW)).isFalse();
        WorkplaceServiceEphemeralCredentialProvider provider =
                mock(WorkplaceServiceEphemeralCredentialProvider.class);
        WorkplaceServiceOperationsService guardedService = new WorkplaceServiceOperationsService(
                operationsRepository, mapper, List.of(), List.of(provider), transaction,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        assertThatThrownBy(() -> guardedService.revokeCredential(fixture.tenant(), ACTOR + 100,
                firstOrder.serviceOrderId(), firstOrder.lines().getFirst().serviceOrderLineId(),
                issuedLegacyGrant, "legacy-issued-revoke", new AccessCredentialRevokeRequest(
                        firstOrder.version(), true, "Manual provider revocation required"),
                "corr-legacy-issued-revoke"))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(error.getMessage()).contains("manual provider revocation");
                });
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_operations_commands
                 WHERE tenant_id=? AND resource_id=?
                """, Integer.class, fixture.tenant(), issuedLegacyGrant)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT grant_state,provider_operation_kind,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE tenant_id=? AND access_grant_id=?
                """, fixture.tenant(), issuedLegacyGrant))
                .containsEntry("grant_state", "ISSUED")
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("provider_operation_command_id", null);
    }
}
