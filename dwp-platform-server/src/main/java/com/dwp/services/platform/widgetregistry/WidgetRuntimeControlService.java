package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetRuntimeControlService {
    private final WidgetRuntimeControlRepository controls;
    private final WidgetRuntimeEnableApprovalRepository approvals;
    private final WidgetRegistryResponseMapper mapper;
    private final WidgetRegistryCommandReceiptService receipts;
    private final WidgetRegistryLedger ledger;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WidgetRegistryOwnerScopeGuard ownerScope;

    @Autowired
    public WidgetRuntimeControlService(
            WidgetRuntimeControlRepository controls,
            WidgetRuntimeEnableApprovalRepository approvals,
            WidgetRegistryResponseMapper mapper,
            WidgetRegistryCommandReceiptService receipts,
            WidgetRegistryLedger ledger,
            ObjectMapper objectMapper,
            WidgetRegistryOwnerScopeGuard ownerScope) {
        this(
                controls,
                approvals,
                mapper,
                receipts,
                ledger,
                objectMapper,
                ownerScope,
                Clock.systemUTC());
    }

    WidgetRuntimeControlService(
            WidgetRuntimeControlRepository controls,
            WidgetRuntimeEnableApprovalRepository approvals,
            WidgetRegistryResponseMapper mapper,
            WidgetRegistryCommandReceiptService receipts,
            WidgetRegistryLedger ledger,
            ObjectMapper objectMapper,
            WidgetRegistryOwnerScopeGuard ownerScope,
            Clock clock) {
        this.controls = controls;
        this.approvals = approvals;
        this.mapper = mapper;
        this.receipts = receipts;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ownerScope = ownerScope;
    }

    @Transactional
    public WidgetRegistryDtos.RuntimeControlPage list(int page, int size) {
        expireElapsed(now());
        List<WidgetRegistryDtos.RuntimeControlResponse> all = controls
                .findAllByOrderByCreatedAtDesc().stream()
                .filter(ownerScope::allowsControl)
                .map(mapper::control)
                .toList();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new WidgetRegistryDtos.RuntimeControlPage(
                all.subList(from, to), safePage, safeSize, all.size(), to < all.size(),
                Long.toString(ledger.state().getSafetyRevision()));
    }

    @Transactional
    public WidgetRegistryDtos.RuntimeControlResponse disable(
            Long actorId,
            UUID commandId,
            String correlationId,
            WidgetRegistryDtos.RuntimeDisableRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = target(request);
        var replay = receipts.replay(actorId, commandId, "DISABLE_RUNTIME_CONTROL", target,
                fingerprint, WidgetRegistryDtos.RuntimeControlResponse.class);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        expireElapsed(now);
        if (request.expectedVersion() != 0) throw conflict();
        validateTarget(request);
        ownerScope.requireDisableTarget(request);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Runtime control expiry must be in the future.");
        }
        WidgetRuntimeControl control = WidgetRuntimeControl.builder()
                .controlId(UUID.randomUUID()).tenantId(request.tenantId())
                .providerProductKey(request.providerProductKey()).controlScope(request.scope())
                .targetType(request.targetType()).targetId(request.targetId())
                .controlState("DISABLED").controlRevision(1L)
                .reasonCode(request.publicReasonCode()).reasonText(request.reasonText())
                .incidentRef(request.internalIncidentRef()).expiresAt(request.expiresAt())
                .createdBy(actorId).build();
        try {
            controls.saveAndFlush(control);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        var response = mapper.control(control);
        ledger.append(request.tenantId(), "RUNTIME_CONTROL", control.getControlId().toString(),
                "WIDGET_RUNTIME_DISABLED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.SAFETY);
        receipts.store(actorId, commandId, "DISABLE_RUNTIME_CONTROL", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.RuntimeEnableApprovalResponse approveEnable(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID controlId,
            WidgetRegistryDtos.RuntimeEnableApprovalRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = controlId.toString();
        var replay = receipts.replay(actorId, commandId, "APPROVE_RUNTIME_ENABLE", target,
                fingerprint, WidgetRegistryDtos.RuntimeEnableApprovalResponse.class);
        if (replay != null) return replay;
        expireElapsed(now());
        WidgetRuntimeControl control = lock(controlId, request.expectedVersion());
        ownerScope.requireControl(control);
        if (!"DISABLED".equals(control.getControlState())
                || !request.controlRevision().equals(control.getControlRevision())
                || actorId.equals(control.getCreatedBy())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "Enable approval must bind the disabled control and an independent reviewer.");
        }
        OffsetDateTime now = now();
        WidgetRuntimeEnableApproval approval = approvals.save(WidgetRuntimeEnableApproval.builder()
                .approvalId(UUID.randomUUID()).controlId(controlId)
                .controlRevision(control.getControlRevision()).approvalState("ACTIVE")
                .evidenceRefs(objectMapper.valueToTree(request.evidenceRefs()))
                .expiresAt(now.plusMinutes(30)).approvedBy(actorId).build());
        var response = approvalResponse(approval);
        ledger.append(control.getTenantId(), "RUNTIME_ENABLE_APPROVAL", approval.getApprovalId().toString(),
                "WIDGET_RUNTIME_ENABLE_APPROVED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.SAFETY);
        receipts.store(actorId, commandId, "APPROVE_RUNTIME_ENABLE", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.RuntimeControlResponse enable(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID controlId,
            WidgetRegistryDtos.RuntimeEnableRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = controlId.toString();
        var replay = receipts.replay(actorId, commandId, "ENABLE_RUNTIME_CONTROL", target,
                fingerprint, WidgetRegistryDtos.RuntimeControlResponse.class);
        if (replay != null) return replay;
        expireElapsed(now());
        WidgetRuntimeControl control = lock(controlId, request.expectedVersion());
        ownerScope.requireControl(control);
        WidgetRuntimeEnableApproval approval = approvals.lockById(request.enableApprovalId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        OffsetDateTime now = now();
        if (!"DISABLED".equals(control.getControlState())
                || !request.controlRevision().equals(control.getControlRevision())
                || !controlId.equals(approval.getControlId())
                || !request.controlRevision().equals(approval.getControlRevision())
                || !"ACTIVE".equals(approval.getApprovalState())
                || !approval.getExpiresAt().isAfter(now)
                || actorId.equals(control.getCreatedBy())
                || actorId.equals(approval.getApprovedBy())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "Runtime enable approval is invalid, stale, expired, or not independent.");
        }
        control.setControlState("ENABLED");
        control.setControlRevision(control.getControlRevision() + 1);
        control.setReasonCode(request.reasonCode());
        control.setReasonText(request.reasonText());
        approval.setApprovalState("CONSUMED");
        approval.setConsumedAt(now);
        approval.setConsumedByCommandId(commandId);
        try {
            approvals.save(approval);
            controls.saveAndFlush(control);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw conflict();
        }
        var response = mapper.control(control);
        ledger.append(control.getTenantId(), "RUNTIME_CONTROL", controlId.toString(),
                "WIDGET_RUNTIME_ENABLED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.SAFETY);
        receipts.store(actorId, commandId, "ENABLE_RUNTIME_CONTROL", target, fingerprint, response);
        return response;
    }

    private WidgetRuntimeControl lock(UUID controlId, Long expectedVersion) {
        WidgetRuntimeControl control = controls.lockById(controlId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!Objects.equals(expectedVersion, control.getVersion() == null ? 0L : control.getVersion())) {
            throw conflict();
        }
        return control;
    }

    private WidgetRegistryDtos.RuntimeEnableApprovalResponse approvalResponse(
            WidgetRuntimeEnableApproval value) {
        return new WidgetRegistryDtos.RuntimeEnableApprovalResponse(
                value.getApprovalId(), value.getControlId(), value.getControlRevision(),
                value.getApprovalState(), value.getEvidenceRefs(), value.getExpiresAt(),
                value.getConsumedAt(), "actor:" + value.getApprovedBy(), value.getCreatedAt());
    }

    private static void validateTarget(WidgetRegistryDtos.RuntimeDisableRequest request) {
        boolean valid = switch (request.targetType()) {
            case "GLOBAL" -> request.targetId() == null
                    && request.tenantId() == null && request.providerProductKey() == null;
            case "TENANT" -> request.tenantId() != null && request.targetId() != null
                    && request.targetId().equals(request.tenantId().toString());
            case "PROVIDER" -> request.providerProductKey() != null
                    && request.providerProductKey().equals(request.targetId());
            case "DEFINITION", "VERSION" -> request.targetId() != null;
            case "MODE" -> request.targetId() != null
                    && Set.of("CLASSIC", "FLOW_V1", "MZ_V1").contains(request.targetId())
                    && request.providerProductKey() == null;
            case "ACTION" -> request.targetId() != null
                    && request.providerProductKey() != null
                    && request.targetId().split("\\|", -1).length == 5;
            default -> false;
        };
        if (!valid) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Runtime control target is inconsistent.");
    }

    private static String target(WidgetRegistryDtos.RuntimeDisableRequest request) {
        return request.scope() + ":" + request.targetType() + ":" + Objects.toString(request.targetId(), "GLOBAL");
    }

    private void expireElapsed(OffsetDateTime now) {
        for (WidgetRuntimeControl control : controls.findElapsedDisabled(now).stream()
                .filter(ownerScope::allowsControl).toList()) {
            var before = mapper.control(control);
            control.setControlState("EXPIRED");
            control.setControlRevision(control.getControlRevision() + 1);
            controls.saveAndFlush(control);
            var after = mapper.control(control);
            ledger.append(
                    control.getTenantId(),
                    "RUNTIME_CONTROL",
                    control.getControlId().toString(),
                    "WIDGET_RUNTIME_CONTROL_EXPIRED",
                    null,
                    1L,
                    "widget-runtime-expiry",
                    before,
                    after,
                    List.of(),
                    WidgetRegistryLedger.RevisionAxis.SAFETY);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException conflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }
}
