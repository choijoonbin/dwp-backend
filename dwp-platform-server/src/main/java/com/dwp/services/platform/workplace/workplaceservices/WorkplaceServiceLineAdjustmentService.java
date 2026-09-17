package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProtocol.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

@Service
public class WorkplaceServiceLineAdjustmentService extends WorkplaceServicesServiceSupport {
    private final WorkplaceServiceLineAdjustmentProvider provider;
    private final WorkplaceServiceLineAdjustmentTransactionStore transactionStore;

    @Autowired
    public WorkplaceServiceLineAdjustmentService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper,
            WorkplaceServiceLineAdjustmentProvider provider,
            WorkplaceServiceLineAdjustmentTransactionStore transactionStore) {
        this(repository, objectMapper, provider, transactionStore, Clock.systemUTC());
    }

    public WorkplaceServiceLineAdjustmentService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper,
            WorkplaceServiceLineAdjustmentProvider provider) {
        this(repository, objectMapper, provider, fallbackStore(repository), Clock.systemUTC());
    }

    WorkplaceServiceLineAdjustmentService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper,
            WorkplaceServiceLineAdjustmentProvider provider, Clock clock) {
        this(repository, objectMapper, provider, fallbackStore(repository), clock);
    }

    WorkplaceServiceLineAdjustmentService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper,
            WorkplaceServiceLineAdjustmentProvider provider,
            WorkplaceServiceLineAdjustmentTransactionStore transactionStore, Clock clock) {
        super(repository, objectMapper, clock);
        this.provider = provider;
        this.transactionStore = transactionStore;
    }

    @Transactional
    public LineCancellationImpact preview(
            long tenantId, long actorUserId, UUID orderId, UUID lineId,
            LineCancellationImpactRequest request) {
        requireActor(tenantId, actorUserId);
        if (request == null || request.cancelQuantity() < 1 || blank(request.reason())
                || request.reason().trim().length() > 500) {
            throw invalid("Cancellation quantity and reason are required.");
        }
        OrderRow order = repository.userOrder(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        LineRow line = repository.line(tenantId, orderId, lineId)
                .orElseThrow(() -> notFound("The service order line was not found."));
        if (hasUnresolvedAdjustment(tenantId, orderId, lineId)) {
            throw conflict("Resolve the pending line cancellation before starting another cancellation.");
        }
        if (order.version() != request.expectedOrderVersion()
                || line.version() != request.expectedLineVersion()) {
            throw versionConflict("The service order changed. Refresh before previewing cancellation.");
        }
        if (order.state() == OrderState.CANCELLED || order.state() == OrderState.FULFILLED
                || line.state() == WorkState.CANCELLED || line.state() == WorkState.FULFILLED) {
            throw conflict("This service line can no longer be cancelled.");
        }
        int remaining = line.quantity() - line.fulfilledQuantity() - line.cancelledQuantity();
        if (request.cancelQuantity() > remaining) {
            throw invalid("Cancellation quantity exceeds the unfulfilled line quantity.");
        }
        OffsetDateTime now = now();
        boolean beforeCutoff = now.isBefore(
                order.startsAt().minusMinutes(line.cancellationCutoffMinutes()));
        RefundScope scope = beforeCutoff
                ? (request.cancelQuantity() == remaining ? RefundScope.FULL : RefundScope.PARTIAL)
                : RefundScope.NONE;
        BigDecimal refundable = beforeCutoff
                ? line.unitPrice().multiply(BigDecimal.valueOf(request.cancelQuantity()))
                : BigDecimal.ZERO;
        LineCancellationPreviewRow preview = new LineCancellationPreviewRow(
                UUID.randomUUID(), tenantId, actorUserId, orderId, lineId,
                order.version(), line.version(), request.cancelQuantity(),
                line.fulfilledQuantity(), line.cancelledQuantity(), line.catalogVersion(),
                line.providerConfigurationVersion(), line.unitPrice(), line.currency(),
                line.cancellationCutoffMinutes(), line.cancellationPolicyKo(),
                line.cancellationPolicyEn(), scope, refundable, beforeCutoff,
                request.reason().trim(), now.plus(PREVIEW_TTL), now);
        repository.saveLineCancellationPreview(preview);
        return impact(preview, remaining);
    }

    public LineAdjustmentCommandResult cancel(
            long tenantId, long actorUserId, UUID orderId, UUID lineId,
            String idempotencyKey, LineCancellationRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateCancellationRequest(request);
        String scope = "SERVICE_LINE_CANCEL:" + lineId;
        String fingerprint = fingerprint(orderId, lineId, request);
        PreparedInvocation prepared = committed(() -> prepareCancellation(
                tenantId, actorUserId, orderId, lineId, key, request,
                normalizeCorrelation(correlationId), scope, fingerprint));
        if (prepared.immediateResult() != null) return prepared.immediateResult();

        ProviderOutcome outcome = prepared.recoveryLookup()
                ? invokeLookup(prepared.providerRequest(),
                        prepared.adjustment().providerOperationReference())
                : invokeCancel(prepared.providerRequest());
        return completeOutsideProviderTransaction(prepared, outcome);
    }

    public LineAdjustmentCommandResult reconcile(
            long tenantId, long actorUserId, UUID orderId, UUID adjustmentId,
            String idempotencyKey, LineAdjustmentReconcileRequest request,
            String correlationId) {
        requireActor(tenantId, actorUserId);
        if (request == null || request.expectedVersion() < 1
                || !request.explicitConfirmation() || blank(request.reason())
                || request.reason().trim().length() > 500) {
            throw invalid("Explicit confirmation and a reconciliation reason are required.");
        }
        String key = requireKey(idempotencyKey);
        String scope = "SERVICE_LINE_RECONCILE:" + adjustmentId;
        String fingerprint = fingerprint(orderId, adjustmentId, request);
        PreparedInvocation prepared = committed(() -> prepareReconciliation(
                tenantId, actorUserId, orderId, adjustmentId, key, request,
                normalizeCorrelation(correlationId), scope, fingerprint));
        if (prepared.immediateResult() != null) return prepared.immediateResult();
        ProviderOutcome outcome = invokeLookup(prepared.providerRequest(),
                prepared.adjustment().providerOperationReference());
        return completeOutsideProviderTransaction(prepared, outcome);
    }

    @Transactional(readOnly = true)
    public LineAdjustment status(
            long tenantId, long actorUserId, UUID orderId, UUID adjustmentId) {
        requireActor(tenantId, actorUserId);
        repository.userOrder(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        return repository.lineAdjustment(tenantId, actorUserId, orderId, adjustmentId)
                .map(this::requesterAdjustment)
                .orElseThrow(() -> notFound("The line adjustment was not found."));
    }

    @Transactional(readOnly = true)
    public LineAdjustment adminStatus(long tenantId, UUID orderId, UUID adjustmentId) {
        requireTenant(tenantId);
        repository.order(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        return repository.adminLineAdjustment(tenantId, orderId, adjustmentId)
                .map(this::adjustment)
                .orElseThrow(() -> notFound("The line adjustment was not found."));
    }

    /** Performs only provider status lookup for a previously accepted operation. */
    LineAdjustmentCommandResult recoverPending(LineAdjustmentRow adjustment) {
        if (adjustment == null || !recoverable(adjustment.state())) {
            throw conflict("Only a pending line adjustment can be recovered.");
        }
        CommandRow origin = originCommand(
                adjustment.tenantId(), adjustment.orderId(), adjustment.adjustmentId());
        PreparedInvocation prepared = recoveryInvocation(adjustment.tenantId(),
                adjustment.actorUserId(), adjustment, origin, origin, false, false,
                cancellationPreview(adjustment.tenantId(), adjustment).reason());
        ProviderOutcome outcome = invokeLookup(prepared.providerRequest(),
                adjustment.providerOperationReference());
        return completeOutsideProviderTransaction(prepared, outcome);
    }

    private PreparedInvocation prepareCancellation(
            long tenantId, long actorUserId, UUID orderId, UUID lineId, String key,
            LineCancellationRequest request, String correlation, String scope,
            String fingerprint) {
        lockCommand(tenantId, actorUserId, scope, key);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            LineAdjustmentRow adjustment = repository.lineAdjustmentByPreview(
                    tenantId, actorUserId, request.cancellationPreviewId())
                    .orElseThrow(() -> conflict("The cancellation receipt is unavailable."));
            if (!recoverable(adjustment.state())) {
                return PreparedInvocation.immediate(
                        result(tenantId, adjustment, existing, true, true));
            }
            return recoveryInvocation(tenantId, actorUserId, adjustment, existing,
                    existing, true, false, request.reason());
        }

        OrderRow order = repository.userOrderForUpdate(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        LineCancellationPreviewRow preview = repository.lineCancellationPreviewForUpdate(
                tenantId, actorUserId, orderId, lineId, request.cancellationPreviewId())
                .orElseThrow(() -> notFound("The cancellation impact preview was not found."));
        if (hasUnresolvedAdjustment(tenantId, orderId, lineId)) {
            throw conflict("Resolve the pending line cancellation before starting another cancellation.");
        }
        if (repository.lineAdjustmentByPreview(tenantId, actorUserId, preview.previewId()).isPresent()) {
            throw conflict("The cancellation impact preview was already consumed.");
        }
        OffsetDateTime timestamp = now();
        if (!preview.expiresAt().isAfter(timestamp) || !preview.eligible()) {
            throw conflict("The cancellation impact preview expired or is no longer eligible.");
        }
        if (!preview.reason().equals(request.reason().trim())) {
            throw conflict("The cancellation reason changed. Preview the impact again.");
        }
        LineRow line = repository.line(tenantId, orderId, lineId)
                .orElseThrow(() -> notFound("The service order line was not found."));
        verifyInitialSnapshot(preview, order, line, request);

        UUID adjustmentId = UUID.nameUUIDFromBytes(
                ("workplace-line-adjustment:" + tenantId + ':' + preview.previewId())
                        .getBytes(StandardCharsets.UTF_8));
        UUID commandId = UUID.randomUUID();
        String href = "/v1/workplace/service-orders/" + orderId
                + "/line-adjustments/" + adjustmentId;
        CommandRow command = new CommandRow(commandId, tenantId, actorUserId, scope,
                key, fingerprint, orderId, CommandState.ACCEPTED, href,
                correlation, timestamp, timestamp);
        repository.createCommand(command);
        LineAdjustmentRow pending = new LineAdjustmentRow(
                adjustmentId, tenantId, actorUserId, orderId, lineId,
                preview.previewId(), preview.cancelQuantity(), preview.refundScope(),
                preview.refundableAmount(), BigDecimal.ZERO, preview.currency(),
                LineAdjustmentState.CANCELLATION_PENDING, line.providerCode(),
                line.providerConfigurationVersion(),
                line.providerCredentialBindingReference(), null, null,
                "Provider cancellation accepted for dispatch.", 1, timestamp, timestamp);
        transactionStore.createPendingAdjustment(pending, commandId);
        return new PreparedInvocation(tenantId, actorUserId, true, false, false, false,
                preview, line, pending, command, command, commandId,
                providerRequest(pending, preview, request.reason()), null);
    }

    private PreparedInvocation prepareReconciliation(
            long tenantId, long actorUserId, UUID orderId, UUID adjustmentId,
            String key, LineAdjustmentReconcileRequest request, String correlation,
            String scope, String fingerprint) {
        repository.order(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        lockCommand(tenantId, actorUserId, scope, key);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        LineAdjustmentRow current = repository.adminLineAdjustmentForUpdate(
                tenantId, orderId, adjustmentId)
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            if (!recoverable(current.state())) {
                return PreparedInvocation.immediate(
                        result(tenantId, current, existing, true, false));
            }
            return recoveryInvocation(tenantId, actorUserId, current, existing,
                    originCommand(tenantId, orderId, adjustmentId), false, true,
                    request.reason());
        }
        if (current.version() != request.expectedVersion()) {
            throw versionConflict("The line adjustment changed. Refresh before reconciling.");
        }
        if (!recoverable(current.state())
                && current.state() != LineAdjustmentState.REFUND_NOT_CONFIGURED) {
            throw conflict("Only an unresolved line adjustment can be reconciled.");
        }
        LineCancellationPreviewRow preview = cancellationPreview(tenantId, current);
        LineRow line = repository.line(tenantId, orderId, current.lineId())
                .orElseThrow(() -> notFound("The service order line was not found."));
        OffsetDateTime timestamp = now();
        UUID commandId = UUID.randomUUID();
        String href = "/v1/admin/workplace/service-orders/" + orderId
                + "/line-adjustments/" + adjustmentId;
        CommandRow command = new CommandRow(commandId, tenantId, actorUserId, scope,
                key, fingerprint, orderId, CommandState.ACCEPTED, href,
                correlation, timestamp, timestamp);
        repository.createCommand(command);
        if (!transactionStore.beginReconciliation(tenantId, orderId, adjustmentId,
                current.version(), commandId, timestamp)) {
            throw versionConflict("The line adjustment changed. Refresh before reconciling.");
        }
        LineAdjustmentRow pending = repository.adminLineAdjustment(
                tenantId, orderId, adjustmentId)
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        CommandRow origin = originCommand(tenantId, orderId, adjustmentId);
        return new PreparedInvocation(tenantId, actorUserId, false, true, true, false,
                preview, line, pending, command, origin, origin.commandId(),
                providerRequest(pending, preview, request.reason()), null);
    }

    private PreparedInvocation recoveryInvocation(
            long tenantId, long actorUserId, LineAdjustmentRow adjustment,
            CommandRow activeCommand, CommandRow originCommand, boolean requesterProjection,
            boolean reconciliation, String reason) {
        LineCancellationPreviewRow preview = cancellationPreview(tenantId, adjustment);
        LineRow line = repository.line(tenantId, adjustment.orderId(), adjustment.lineId())
                .orElseThrow(() -> notFound("The service order line was not found."));
        return new PreparedInvocation(tenantId, actorUserId, requesterProjection,
                reconciliation, true, true, preview, line, adjustment, activeCommand,
                originCommand, originCommand.commandId(),
                providerRequest(adjustment, preview, reason), null);
    }

    private LineCancellationPreviewRow cancellationPreview(
            long tenantId, LineAdjustmentRow adjustment) {
        return repository.lineCancellationPreview(tenantId, adjustment.actorUserId(),
                adjustment.orderId(), adjustment.lineId(), adjustment.previewId())
                .orElseThrow(() -> conflict("The cancellation snapshot is unavailable."));
    }

    private CommandRow originCommand(long tenantId, UUID orderId, UUID adjustmentId) {
        UUID originId = transactionStore.originCommandId(tenantId, orderId, adjustmentId);
        return transactionStore.command(tenantId, originId)
                .orElseThrow(() -> conflict("The cancellation origin command is unavailable."));
    }

    private LineAdjustmentCommandResult completeOutsideProviderTransaction(
            PreparedInvocation prepared, ProviderOutcome providerOutcome) {
        ProviderOutcome outcome;
        try {
            outcome = validateOutcome(providerOutcome, prepared.preview());
        } catch (RuntimeException invalidOutcome) {
            outcome = uncertainOutcome(
                    prepared.adjustment().providerOperationReference(), invalidOutcome);
        }
        ProviderOutcome terminalOutcome = outcome;
        try {
            return committed(() -> completeInvocation(prepared, terminalOutcome));
        } catch (RuntimeException completionFailure) {
            ProviderOutcome pending = uncertainOutcome(
                    terminalOutcome.providerOperationReference(), completionFailure);
            return committed(() -> persistPendingRecovery(prepared, pending));
        }
    }

    private LineAdjustmentCommandResult completeInvocation(
            PreparedInvocation prepared, ProviderOutcome outcome) {
        transactionStore.lockOrderAndLine(prepared.tenantId(),
                prepared.adjustment().orderId(), prepared.adjustment().lineId());
        LineAdjustmentRow current = repository.adminLineAdjustmentForUpdate(
                prepared.tenantId(), prepared.adjustment().orderId(),
                prepared.adjustment().adjustmentId())
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        if (!recoverable(current.state())
                && current.state() != LineAdjustmentState.REFUND_NOT_CONFIGURED) {
            CommandRow command = currentCommand(prepared);
            return result(prepared.tenantId(), current, command, true,
                    prepared.requesterProjection());
        }
        if (prepared.commandWasExisting()
                && current.state() == LineAdjustmentState.RECONCILIATION_PENDING
                && outcome.state() == OutcomeState.RESULT_UNKNOWN
                && prepared.activeCommand().state() == CommandState.RESULT_UNKNOWN) {
            return result(prepared.tenantId(), current, prepared.activeCommand(), true,
                    prepared.requesterProjection());
        }

        OrderRow order = repository.order(prepared.tenantId(), current.orderId())
                .orElseThrow(() -> notFound("The service order was not found."));
        LineRow line = repository.line(prepared.tenantId(), current.orderId(), current.lineId())
                .orElseThrow(() -> notFound("The service order line was not found."));
        ProviderOutcome effective = outcome;
        boolean cancellationApplied = appliesCancellation(outcome, prepared.preview(), line);
        if (cancellationApplied && !canApplyCancellation(prepared.preview(), order, line)) {
            effective = new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                    outcome.providerOperationReference(), BigDecimal.ZERO, null,
                    "Provider outcome is authoritative, but the local line snapshot changed; "
                            + "operator reconciliation is required.");
            cancellationApplied = false;
        }
        LineAdjustmentState adjustmentState = state(
                effective, prepared.preview().refundableAmount(), cancellationApplied);
        OffsetDateTime timestamp = now();
        if (cancellationApplied) {
            applyCancellation(prepared.tenantId(),
                    prepared.requesterProjection() ? prepared.actorUserId() : null,
                    order, line, prepared.preview(), effective.refundedAmount(), timestamp);
        }
        if (!repository.updateLineAdjustment(prepared.tenantId(), current.orderId(),
                current.adjustmentId(), current.version(), adjustmentState,
                effective.providerOperationReference(), effective.refundedAmount(),
                effective.refundReceiptReference(), effective.detail(), timestamp)) {
            throw versionConflict("The line adjustment changed while provider truth was recorded.");
        }
        LineAdjustmentRow updated = repository.adminLineAdjustment(
                prepared.tenantId(), current.orderId(), current.adjustmentId())
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        CommandState commandState = commandState(adjustmentState);
        appendTerminalEvidence(prepared, updated, commandState, timestamp);
        CommandRow command = currentCommand(prepared);
        return result(prepared.tenantId(), updated, command, prepared.commandWasExisting(),
                prepared.requesterProjection());
    }

    private LineAdjustmentCommandResult persistPendingRecovery(
            PreparedInvocation prepared, ProviderOutcome outcome) {
        LineAdjustmentRow current = repository.adminLineAdjustmentForUpdate(
                prepared.tenantId(), prepared.adjustment().orderId(),
                prepared.adjustment().adjustmentId())
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        boolean transitioned = current.state() != LineAdjustmentState.RECONCILIATION_PENDING;
        if (recoverable(current.state()) || current.state() == LineAdjustmentState.REFUND_NOT_CONFIGURED) {
            if (!repository.updateLineAdjustment(prepared.tenantId(), current.orderId(),
                    current.adjustmentId(), current.version(),
                    LineAdjustmentState.RECONCILIATION_PENDING,
                    outcome.providerOperationReference(), BigDecimal.ZERO, null,
                    outcome.detail(), now())) {
                throw versionConflict("The line adjustment changed while recovery was recorded.");
            }
        }
        LineAdjustmentRow updated = repository.adminLineAdjustment(
                prepared.tenantId(), current.orderId(), current.adjustmentId())
                .orElseThrow(() -> notFound("The line adjustment was not found."));
        if (transitioned) {
            appendTerminalEvidence(prepared, updated, CommandState.RESULT_UNKNOWN, now());
        } else {
            transactionStore.updateCommandState(prepared.tenantId(),
                    prepared.activeCommand().commandId(), CommandState.RESULT_UNKNOWN, now());
        }
        return result(prepared.tenantId(), updated, currentCommand(prepared),
                prepared.commandWasExisting(), prepared.requesterProjection());
    }

    private void appendTerminalEvidence(
            PreparedInvocation prepared, LineAdjustmentRow adjustment,
            CommandState commandState, OffsetDateTime timestamp) {
        ObjectNode detail = detail(adjustment);
        LineAdjustmentState state = adjustment.state();
        repository.appendEvent(prepared.tenantId(), adjustment.orderId(),
                prepared.reconciliation() ? "LINE_ADJUSTMENT_RECONCILED"
                        : WorkplaceServiceLineAdjustmentProtocol.eventType(state),
                prepared.actorUserId(), detail, timestamp);
        repository.appendAuditAndOutbox(prepared.tenantId(), prepared.actorUserId(),
                adjustment.orderId(), prepared.reconciliation()
                        ? reconciliationAction(state) : auditAction(state),
                "ServiceOrderLineAdjustmentUpdated",
                prepared.activeCommand().correlationId(), detail, timestamp);
        transactionStore.updateCommandState(prepared.tenantId(),
                prepared.activeCommand().commandId(), commandState, timestamp);
        if (!prepared.originCommandId().equals(prepared.activeCommand().commandId())) {
            transactionStore.updateCommandState(prepared.tenantId(),
                    prepared.originCommandId(), commandState, timestamp);
        }
    }

    private CommandRow currentCommand(PreparedInvocation prepared) {
        return transactionStore.command(prepared.tenantId(),
                prepared.activeCommand().commandId()).orElse(prepared.activeCommand());
    }

    private ObjectNode detail(LineAdjustmentRow adjustment) {
        return objectMapper.createObjectNode()
                .put("lineAdjustmentId", adjustment.adjustmentId().toString())
                .put("serviceOrderLineId", adjustment.lineId().toString())
                .put("cancelQuantity", adjustment.cancelQuantity())
                .put("refundScope", adjustment.refundScope().name())
                .put("refundableAmount", adjustment.refundableAmount())
                .put("refundedAmount", adjustment.refundedAmount())
                .put("currency", adjustment.currency())
                .put("state", adjustment.state().name());
    }

    private void applyCancellation(
            long tenantId, Long requesterUserId, OrderRow order, LineRow line,
            LineCancellationPreviewRow preview, BigDecimal refundedAmount,
            OffsetDateTime now) {
        if (!repository.advanceOrderVersion(tenantId, order.orderId(), requesterUserId,
                order.version(), now)
                || !repository.applyLineCancellation(tenantId, order.orderId(), line.lineId(),
                    line.version(), preview.cancelQuantity(), refundedAmount, now)) {
            throw versionConflict("The service line changed. Reconcile from current status.");
        }
        repository.cancelFullyCancelledLineTasks(
                tenantId, order.orderId(), line.lineId(), now);
        repository.recalculateOrderState(tenantId, order.orderId(), now);
    }

    private boolean canApplyCancellation(
            LineCancellationPreviewRow preview, OrderRow order, LineRow line) {
        return order.version() == preview.orderVersion()
                && line.version() == preview.lineVersion()
                && reconciliationSnapshotMatches(preview, line);
    }

    private boolean reconciliationSnapshotMatches(
            LineCancellationPreviewRow preview, LineRow line) {
        return preview.catalogVersion() == line.catalogVersion()
                && preview.providerConfigurationVersion() == line.providerConfigurationVersion()
                && preview.unitPrice().compareTo(line.unitPrice()) == 0
                && preview.currency().equals(line.currency())
                && preview.cancellationCutoffMinutes() == line.cancellationCutoffMinutes()
                && preview.cancellationPolicyKo().equals(line.cancellationPolicyKo())
                && preview.cancellationPolicyEn().equals(line.cancellationPolicyEn())
                && preview.fulfilledQuantity() == line.fulfilledQuantity()
                && preview.previouslyCancelledQuantity() == line.cancelledQuantity();
    }

    private void verifyInitialSnapshot(
            LineCancellationPreviewRow preview, OrderRow order, LineRow line,
            LineCancellationRequest request) {
        if (order.version() != request.expectedOrderVersion()
                || line.version() != request.expectedLineVersion()
                || preview.orderVersion() != order.version()
                || preview.lineVersion() != line.version()) {
            throw versionConflict("The service order changed. Preview cancellation again.");
        }
        verifyReconciliationSnapshot(preview, line);
        if (!now().isBefore(order.startsAt().minusMinutes(line.cancellationCutoffMinutes()))) {
            throw conflict("The service line cancellation cutoff has passed.");
        }
    }

    private void verifyReconciliationSnapshot(
            LineCancellationPreviewRow preview, LineRow line) {
        if (preview.catalogVersion() != line.catalogVersion()
                || preview.providerConfigurationVersion() != line.providerConfigurationVersion()
                || preview.unitPrice().compareTo(line.unitPrice()) != 0
                || !preview.currency().equals(line.currency())
                || preview.cancellationCutoffMinutes() != line.cancellationCutoffMinutes()
                || !preview.cancellationPolicyKo().equals(line.cancellationPolicyKo())
                || !preview.cancellationPolicyEn().equals(line.cancellationPolicyEn())
                || preview.fulfilledQuantity() != line.fulfilledQuantity()
                || preview.previouslyCancelledQuantity() != line.cancelledQuantity()) {
            throw versionConflict("Cancellation policy or service line changed. Preview again.");
        }
    }

    private static ProviderOutcome validateOutcome(
            ProviderOutcome outcome, LineCancellationPreviewRow preview) {
        if (outcome == null || outcome.state() == null) {
            throw conflict("The cancellation provider returned no authoritative outcome.");
        }
        BigDecimal refunded = outcome.refundedAmount() == null
                ? BigDecimal.ZERO : outcome.refundedAmount();
        boolean receipt = !blank(outcome.refundReceiptReference());
        if (refunded.signum() < 0 || refunded.compareTo(preview.refundableAmount()) > 0) {
            throw conflict("The cancellation provider returned an invalid refund amount.");
        }
        if (outcome.state() == OutcomeState.SUCCEEDED
                && preview.refundableAmount().signum() > 0
                && (refunded.compareTo(preview.refundableAmount()) != 0 || !receipt)) {
            throw conflict("A completed refund requires the full authoritative provider receipt.");
        }
        if (outcome.state() == OutcomeState.SUCCEEDED
                && preview.refundableAmount().signum() == 0
                && (refunded.signum() != 0 || receipt)) {
            throw conflict("A non-monetary cancellation cannot claim a refund receipt.");
        }
        if (outcome.state() != OutcomeState.SUCCEEDED
                && (refunded.signum() != 0 || receipt)) {
            throw conflict("A non-success provider outcome cannot claim a completed refund.");
        }
        String operationReference = normalize(outcome.providerOperationReference());
        String receiptReference = normalize(outcome.refundReceiptReference());
        String detail = normalize(outcome.detail());
        if ((operationReference != null && operationReference.length() > 320)
                || (receiptReference != null && receiptReference.length() > 320)
                || (detail != null && detail.length() > 1_000)) {
            throw conflict("The cancellation provider returned oversized evidence.");
        }
        return new ProviderOutcome(outcome.state(), operationReference,
                refunded, receiptReference, detail);
    }

    private ProviderOutcome invokeCancel(ProviderRequest request) {
        try {
            return provider.cancel(request);
        } catch (OutcomeUncertainException uncertain) {
            return uncertainOutcome(uncertain.providerOperationReference(), uncertain);
        } catch (RuntimeException uncertain) {
            return uncertainOutcome(null, uncertain);
        }
    }

    private ProviderOutcome invokeLookup(
            ProviderRequest request, String providerOperationReference) {
        try {
            return provider.lookup(request, providerOperationReference);
        } catch (OutcomeUncertainException uncertain) {
            return uncertainOutcome(uncertain.providerOperationReference(), uncertain);
        } catch (RuntimeException uncertain) {
            return uncertainOutcome(providerOperationReference, uncertain);
        }
    }

    private static ProviderOutcome uncertainOutcome(
            String providerOperationReference, RuntimeException failure) {
        String message = normalize(failure.getMessage());
        if (message == null) message = failure.getClass().getSimpleName();
        if (message.length() > 1_000) message = message.substring(0, 1_000);
        return new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                normalize(providerOperationReference), BigDecimal.ZERO, null, message);
    }

    private <T> T committed(Supplier<T> work) {
        return transactionStore == null ? work.get() : transactionStore.committed(work);
    }

    private boolean hasUnresolvedAdjustment(long tenantId, UUID orderId, UUID lineId) {
        return transactionStore == null
                ? repository.hasUnresolvedLineAdjustment(tenantId, orderId, lineId)
                : transactionStore.hasUnresolvedAdjustment(tenantId, orderId, lineId);
    }

    private static WorkplaceServiceLineAdjustmentTransactionStore fallbackStore(
            WorkplaceServicesRepository repository) {
        if (repository == null || repository.jdbc == null
                || repository.jdbc.getDataSource() == null) return null;
        return new WorkplaceServiceLineAdjustmentTransactionStore(repository.jdbc,
                new DataSourceTransactionManager(repository.jdbc.getDataSource()));
    }

    private static void validateCancellationRequest(LineCancellationRequest request) {
        if (request == null || request.cancellationPreviewId() == null
                || request.expectedOrderVersion() < 1 || request.expectedLineVersion() < 1
                || !request.explicitConfirmation() || blank(request.reason())) {
            throw invalid("Explicit confirmation, preview, and reason are required.");
        }
        if (request.reason().trim().length() > 500) {
            throw invalid("The cancellation reason is too long.");
        }
    }

    private LineAdjustmentCommandResult result(
            long tenantId, LineAdjustmentRow row, CommandRow command, boolean replayed,
            boolean requesterProjection) {
        ServiceOrder order = commandOrder(tenantId, row.orderId());
        CommandReceipt receipt = new CommandReceipt(command.commandId(), row.orderId(),
                command.state(), command.statusHref(), replayed, command.correlationId(),
                command.createdAt());
        return new LineAdjustmentCommandResult(
                requesterProjection ? requesterAdjustment(row) : adjustment(row),
                requesterProjection ? requesterOrder(order) : order, receipt);
    }

    private LineAdjustment adjustment(LineAdjustmentRow row) {
        return new LineAdjustment(row.adjustmentId(), row.orderId(), row.lineId(), row.previewId(),
                row.cancelQuantity(), row.refundScope(), row.refundableAmount(),
                row.refundedAmount(), row.currency(), row.state(),
                row.providerOperationReference(), row.refundReceiptReference(), row.resultDetail(),
                row.version(), row.createdAt(), row.updatedAt());
    }

    private LineAdjustment requesterAdjustment(LineAdjustmentRow row) {
        LineAdjustment value = adjustment(row);
        return new LineAdjustment(value.lineAdjustmentId(), value.serviceOrderId(),
                value.serviceOrderLineId(), value.cancellationPreviewId(),
                value.cancelQuantity(), value.refundScope(), value.refundableAmount(),
                value.refundedAmount(), value.currency(), value.state(), null,
                null, null, value.version(),
                value.createdAt(), value.updatedAt());
    }

    private static LineCancellationImpact impact(
            LineCancellationPreviewRow row, int remainingBeforeCancellation) {
        return new LineCancellationImpact(row.previewId(), row.orderId(), row.lineId(),
                row.orderVersion(), row.lineVersion(), row.cancelQuantity(),
                row.fulfilledQuantity(), row.previouslyCancelledQuantity(),
                remainingBeforeCancellation, row.refundScope(), row.refundableAmount(),
                row.currency(), row.eligible(), row.reason(), row.expiresAt(), row.createdAt());
    }

    private record PreparedInvocation(
            long tenantId,
            long actorUserId,
            boolean requesterProjection,
            boolean reconciliation,
            boolean recoveryLookup,
            boolean commandWasExisting,
            LineCancellationPreviewRow preview,
            LineRow line,
            LineAdjustmentRow adjustment,
            CommandRow activeCommand,
            CommandRow originCommand,
            UUID originCommandId,
            ProviderRequest providerRequest,
            LineAdjustmentCommandResult immediateResult) {

        private PreparedInvocation {
            if (immediateResult == null) {
                Objects.requireNonNull(preview);
                Objects.requireNonNull(line);
                Objects.requireNonNull(adjustment);
                Objects.requireNonNull(activeCommand);
                Objects.requireNonNull(originCommand);
                Objects.requireNonNull(originCommandId);
                Objects.requireNonNull(providerRequest);
            }
        }

        static PreparedInvocation immediate(LineAdjustmentCommandResult result) {
            return new PreparedInvocation(0, 0, false, false, false, true,
                    null, null, null, null, null, null, null,
                    Objects.requireNonNull(result));
        }
    }
}
