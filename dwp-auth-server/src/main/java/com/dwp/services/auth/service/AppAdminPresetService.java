package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Atomic workflow boundary for company-governed application administrator presets. */
@Service
public class AppAdminPresetService {

    private final JdbcTemplate jdbc;
    private final AppAdminPresetRepository repository;
    private final ScopedAdminDutyAssignmentService scopedDuties;
    private final IdentityAuditService audit;
    private final AppAdminPresetOutboxPublisher events;
    private final AppAdminPresetRequestService requests;
    private final AppGovernanceAuthorization authorization;
    private final AppGovernanceDenialAudit denials;
    private final AppAdminPresetLifecycleStore lifecycle;
    private final Clock clock;

    @Autowired
    public AppAdminPresetService(
            JdbcTemplate jdbc,
            AppAdminPresetRepository repository,
            ScopedAdminDutyAssignmentService scopedDuties,
            IdentityAuditService audit,
            AppAdminPresetOutboxPublisher events,
            AppAdminPresetRequestService requests) {
        this(jdbc, repository, scopedDuties, audit, events, requests, Clock.systemUTC());
    }

    AppAdminPresetService(
            JdbcTemplate jdbc,
            AppAdminPresetRepository repository,
            ScopedAdminDutyAssignmentService scopedDuties,
            IdentityAuditService audit,
            AppAdminPresetOutboxPublisher events,
            AppAdminPresetRequestService requests,
            Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.scopedDuties = scopedDuties;
        this.audit = audit;
        this.events = events;
        this.requests = requests;
        this.authorization = new AppGovernanceAuthorization(jdbc);
        this.denials = new AppGovernanceDenialAudit(audit);
        this.lifecycle = new AppAdminPresetLifecycleStore(jdbc);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardProjection dashboard(Long tenantId, Long actorId) {
        AppGovernanceAuthorization.Visibility visibility =
                authorization.requireVisibility(tenantId, actorId);
        List<AppGovernanceDtos.AppAdminPresetAssignment> assignments =
                filterAssignments(repository.assignments(tenantId), visibility);
        List<AppGovernanceDtos.AppAdminPreset> catalog = filterCatalog(
                repository.catalog(), assignments, visibility);
        List<AppGovernanceDtos.AppAdminPresetReview> reviews = repository.reviews(tenantId)
                .stream()
                .filter(review -> review.resourceSetId() != null
                        && visibility.reviewSetIds().contains(review.resourceSetId()))
                .toList();
        return new DashboardProjection(catalog, assignments, reviews);
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.AppAdminPreset> catalog(Long tenantId, Long actorId) {
        return dashboard(tenantId, actorId).catalog();
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.AppAdminPresetAssignment> assignments(
            Long tenantId, Long actorId) {
        return dashboard(tenantId, actorId).assignments();
    }

    @Transactional(readOnly = true)
    public AppGovernanceDtos.AppAdminPresetAssignment assignment(
            Long tenantId, Long actorId, UUID assignmentId) {
        AppGovernanceDtos.AppAdminPresetAssignment value =
                repository.requireAssignment(tenantId, assignmentId);
        AppGovernanceAuthorization.Visibility visibility =
                authorization.visibility(tenantId, actorId);
        if (!visibility.queueReader()
                && !visibility.resourceSetIds().contains(value.resourceSetId())
                && !isSelfServiceOwner(value, actorId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return value;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment request(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest request) {
        return requests.requestGoverned(tenantId, actorId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.AppAdminPresetSelfServiceOption> selfServiceOptions(
            Long tenantId, Long actorId, String appResourceKey) {
        return requests.selfServiceOptions(tenantId, actorId, appResourceKey);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment requestSelfService(
            Long tenantId,
            Long actorId,
            String correlationId,
            String idempotencyKey,
            AppGovernanceDtos.CreateSelfServicePresetRequest request) {
        return requests.requestSelfService(
                tenantId, actorId, correlationId, idempotencyKey, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.AppAdminPresetDecisionRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.decision-denied",
                "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(),
                () -> decideInternal(
                        tenantId, actorId, correlationId, assignmentId, request));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment decideInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.AppAdminPresetDecisionRequest request) {
        AppGovernanceDtos.AppAdminPresetAssignment before =
                repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        lifecycle.lockResourceBoundary(tenantId, before.resourceSetId());
        lifecycle.lockAggregate(tenantId, assignmentId);
        before = repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        requireVersionAndState(before, request.version(), "PENDING_APPROVAL");
        if (Objects.equals(before.requestedBy(), actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Preset requester and approver must be independent users.");
        }
        lifecycle.requireNotSubject(tenantId, actorId, before.principalType(), before.principalRef(),
                "Self-approval of an app administrator preset is forbidden.");
        String decision = request.decision().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "DENIED").contains(decision)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String state = "APPROVED".equals(decision) ? "APPROVED" : "DENIED";
        if ("APPROVED".equals(state)) {
            AppGovernanceDtos.AppAdminPreset preset =
                    repository.requirePreset(before.presetCode());
            if (preset.catalogVersion() != before.catalogVersion()) {
                throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The preset catalog changed; deny this request and create a new revision.");
            }
            requireRequestable(preset);
            validateWindow(before.reviewDueAt(), before.validTo());
        }
        try {
            lifecycle.decideResponsibility(tenantId, before.responsibilityAssignmentId(), actorId,
                    state, request.reason());
            for (AppGovernanceDtos.AppAdminPresetDutyAssignment duty : before.duties()) {
                if ("APPROVED".equals(state)) {
                    scopedDuties.approveForActivation(
                            tenantId, duty.assignmentId(), actorId,
                            duty.version(), request.reason());
                } else {
                    scopedDuties.deny(tenantId, duty.assignmentId(), actorId,
                            duty.version(), request.reason());
                }
            }
            int changed = jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET lifecycle_state = ?,
                           approved_by = ?, approved_at = CURRENT_TIMESTAMP,
                           decision_reason = ?, event_sequence = event_sequence + 1,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?
                     WHERE tenant_id = ? AND app_preset_assignment_id = ?
                       AND lifecycle_state = 'PENDING_APPROVAL' AND version = ?
                    """, state, actorId, request.reason().strip(), actorId,
                    tenantId, assignmentId, request.version());
            if (changed != 1) throw versionConflict();
            lifecycle.assertDeferredGuards();
            AppGovernanceDtos.AppAdminPresetAssignment after =
                    repository.requireAssignment(tenantId, assignmentId);
            audit.success(tenantId, actorId,
                    "APPROVED".equals(state) ? "access.app-admin-preset.approved"
                            : "access.app-admin-preset.denied",
                    "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(), correlationId,
                    snapshot(before), snapshot(after));
            events.assignment(AppAdminPresetOutboxPublisher.DECIDED,
                    tenantId, after, lifecycle.eventSequence(tenantId, assignmentId), correlationId);
            return after;
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment activate(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.ActivateAppAdminPresetRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.activation-denied",
                "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(),
                () -> activateInternal(
                        tenantId, actorId, correlationId, assignmentId, request));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment activateInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.ActivateAppAdminPresetRequest request) {
        AppGovernanceDtos.AppAdminPresetAssignment before =
                repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        lifecycle.lockResourceBoundary(tenantId, before.resourceSetId());
        lifecycle.lockAggregate(tenantId, assignmentId);
        before = repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        requireVersionAndState(before, request.version(), "APPROVED");
        if (Objects.equals(before.requestedBy(), actorId)
                || Objects.equals(before.approvedBy(), actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Requester, approver, and activator must be independent users.");
        }
        lifecycle.requireNotSubject(tenantId, actorId, before.principalType(), before.principalRef(),
                "Self-fulfilment of an app administrator preset is forbidden.");
        AppGovernanceDtos.AppAdminPreset preset = repository.requirePreset(before.presetCode());
        if (preset.catalogVersion() != before.catalogVersion()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The preset catalog changed; revoke this approved request and retry.");
        }
        requireRequestable(preset);
        validateWindow(before.reviewDueAt(), before.validTo());
        try {
            lifecycle.activateResponsibility(
                    tenantId, before.responsibilityAssignmentId(), actorId);
            for (AppGovernanceDtos.AppAdminPresetDutyAssignment duty : before.duties()) {
                scopedDuties.activate(tenantId, duty.assignmentId(), actorId,
                        duty.version(), request.reason());
            }
            int changed = jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET lifecycle_state = 'ACTIVE', valid_from = CURRENT_TIMESTAMP,
                           activated_by = ?, activated_at = CURRENT_TIMESTAMP,
                           activation_reason = ?, event_sequence = event_sequence + 1,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?
                     WHERE tenant_id = ? AND app_preset_assignment_id = ?
                       AND lifecycle_state = 'APPROVED' AND version = ?
                    """, actorId, request.reason().strip(), actorId,
                    tenantId, assignmentId, request.version());
            if (changed != 1) throw versionConflict();
            lifecycle.assertDeferredGuards();
            lifecycle.invalidatePrincipal(
                    tenantId, before.principalType(), before.principalRef(), actorId);
            AppGovernanceDtos.AppAdminPresetAssignment after =
                    repository.requireAssignment(tenantId, assignmentId);
            audit.success(tenantId, actorId, "access.app-admin-preset.activated",
                    "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(), correlationId,
                    snapshot(before), snapshot(after));
            events.assignment(AppAdminPresetOutboxPublisher.ACTIVATED,
                    tenantId, after, lifecycle.eventSequence(tenantId, assignmentId), correlationId);
            return after;
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment revoke(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.RevokeAppAdminPresetRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.revocation-denied",
                "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(),
                () -> revokeInternal(
                        tenantId, actorId, correlationId, assignmentId, request));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment revokeInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.RevokeAppAdminPresetRequest request) {
        AppGovernanceDtos.AppAdminPresetAssignment before =
                repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        lifecycle.lockResourceBoundary(tenantId, before.resourceSetId());
        lifecycle.lockAggregate(tenantId, assignmentId);
        before = repository.requireAssignment(tenantId, assignmentId);
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString());
        if (before.version() != request.version()) throw versionConflict();
        if (!Set.of("APPROVED", "ACTIVE").contains(before.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Only an approved or active preset can be revoked.");
        }
        if (Objects.equals(before.approvedBy(), actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The approver cannot fulfil or revoke the same preset request.");
        }
        try {
            lifecycle.revokeResponsibility(tenantId, before.responsibilityAssignmentId(), actorId,
                    request.reason());
            for (AppGovernanceDtos.AppAdminPresetDutyAssignment duty : before.duties()) {
                scopedDuties.revoke(tenantId, duty.assignmentId(), actorId,
                        duty.version(), request.reason());
            }
            int changed = jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET lifecycle_state = 'REVOKED', revoked_by = ?,
                           revoked_at = CURRENT_TIMESTAMP, revocation_reason = ?,
                           event_sequence = event_sequence + 1, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND app_preset_assignment_id = ?
                       AND lifecycle_state IN ('APPROVED', 'ACTIVE') AND version = ?
                    """, actorId, request.reason().strip(), actorId,
                    tenantId, assignmentId, request.version());
            if (changed != 1) throw versionConflict();
            lifecycle.assertDeferredGuards();
            if ("ACTIVE".equals(before.lifecycleState())) {
                lifecycle.invalidatePrincipal(
                        tenantId, before.principalType(), before.principalRef(), actorId);
            }
            AppGovernanceDtos.AppAdminPresetAssignment after =
                    repository.requireAssignment(tenantId, assignmentId);
            audit.success(tenantId, actorId, "access.app-admin-preset.revoked",
                    "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(), correlationId,
                    snapshot(before), snapshot(after));
            events.assignment(AppAdminPresetOutboxPublisher.REVOKED,
                    tenantId, after, lifecycle.eventSequence(tenantId, assignmentId), correlationId);
            return after;
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetReview decideReview(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID reviewId,
            AppGovernanceDtos.AppAdminPresetReviewDecisionRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.review-decision-denied",
                "APP_ADMIN_PRESET_REVIEW", reviewId.toString(),
                () -> decideReviewInternal(
                        tenantId, actorId, correlationId, reviewId, request));
    }

    private AppGovernanceDtos.AppAdminPresetReview decideReviewInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID reviewId,
            AppGovernanceDtos.AppAdminPresetReviewDecisionRequest request) {
        authorization.requireAnyScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_REVIEWER", correlationId,
                "APP_ADMIN_PRESET_REVIEW", reviewId.toString());
        jdbc.query("""
                SELECT scoped_duty_review_id FROM com_admin_scoped_duty_reviews
                 WHERE tenant_id = ? AND scoped_duty_review_id = ? FOR UPDATE
                """, ignored -> { }, tenantId, reviewId);
        AppGovernanceDtos.AppAdminPresetReview before =
                repository.requireReview(tenantId, reviewId);
        if (before.resourceSetId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Review evidence has no governed resource-set boundary.");
        }
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_REVIEWER", before.resourceSetId(),
                correlationId, "APP_ADMIN_PRESET_REVIEW", reviewId.toString());
        if (before.version() != request.version()) throw versionConflict();
        if (!"OPEN".equals(before.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only an open review can be decided.");
        }
        if (Objects.equals(before.userId(), actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "A reviewed user cannot resolve their own scoped-duty review.");
        }
        String decision = request.decision().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("RESOLVED", "DISMISSED").contains(decision)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if ("RESOLVED".equals(decision)
                && !lifecycle.hasGovernedDutyEvidence(tenantId, before)) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Activate a governed preset carrying this exact duty before resolving review.");
        }
        int changed = jdbc.update("""
                UPDATE com_admin_scoped_duty_reviews
                   SET lifecycle_state = ?, resolved_by = ?, resolved_at = CURRENT_TIMESTAMP,
                       resolution_reason = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND scoped_duty_review_id = ?
                   AND lifecycle_state = 'OPEN' AND version = ?
                """, decision, actorId, request.reason().strip(), tenantId,
                reviewId, request.version());
        if (changed != 1) throw versionConflict();
        AppGovernanceDtos.AppAdminPresetReview after = repository.requireReview(tenantId, reviewId);
        audit.success(tenantId, actorId, "access.app-admin-preset.review-decided",
                "APP_ADMIN_PRESET_REVIEW", reviewId.toString(), correlationId,
                snapshot("state", before.lifecycleState()), snapshot("state", decision));
        events.review(tenantId, after, correlationId);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireDueAssignments(int batchSize) {
        int limit = Math.min(1000, Math.max(1, batchSize));
        List<UUID> due = jdbc.query("""
                SELECT app_preset_assignment_id
                  FROM com_admin_app_preset_assignments
                 WHERE lifecycle_state = 'ACTIVE' AND valid_to <= CURRENT_TIMESTAMP
                 ORDER BY valid_to, app_preset_assignment_id
                 FOR UPDATE SKIP LOCKED LIMIT ?
                """, (result, ignored) ->
                result.getObject("app_preset_assignment_id", UUID.class), limit);
        for (UUID id : due) expireOne(id);
        return due.size();
    }

    private void expireOne(UUID assignmentId) {
        AppGovernanceDtos.AppAdminPresetAssignment before = tenantAssignment(assignmentId);
        jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = 'EXPIRED', revoked_at = CURRENT_TIMESTAMP,
                       revocation_reason = 'VALIDITY_WINDOW_ELAPSED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, tenantId(before), before.responsibilityAssignmentId());
        jdbc.update("""
                UPDATE com_admin_scoped_duty_assignments
                   SET lifecycle_state = 'EXPIRED', revoked_at = CURRENT_TIMESTAMP,
                       revocation_reason = 'VALIDITY_WINDOW_ELAPSED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                 WHERE tenant_id = ? AND app_preset_assignment_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, tenantId(before), assignmentId);
        jdbc.update("""
                UPDATE com_admin_app_preset_assignments
                   SET lifecycle_state = 'EXPIRED', revoked_at = CURRENT_TIMESTAMP,
                       revocation_reason = 'VALIDITY_WINDOW_ELAPSED',
                       event_sequence = event_sequence + 1, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                 WHERE app_preset_assignment_id = ? AND lifecycle_state = 'ACTIVE'
                """, assignmentId);
        lifecycle.assertDeferredGuards();
        Long tenantId = tenantId(before);
        lifecycle.invalidatePrincipal(
                tenantId, before.principalType(), before.principalRef(), null);
        AppGovernanceDtos.AppAdminPresetAssignment after =
                repository.requireAssignment(tenantId, assignmentId);
        audit.success(tenantId, null, "access.app-admin-preset.expired",
                "APP_ADMIN_PRESET_ASSIGNMENT", assignmentId.toString(),
                "system:app-admin-preset-expiry", snapshot(before), snapshot(after));
        events.assignment(AppAdminPresetOutboxPublisher.EXPIRED, tenantId, after,
                lifecycle.eventSequence(tenantId, assignmentId),
                "system:app-admin-preset-expiry");
    }

    private AppGovernanceDtos.AppAdminPresetAssignment tenantAssignment(UUID id) {
        Long tenantId = jdbc.queryForObject("""
                SELECT tenant_id FROM com_admin_app_preset_assignments
                 WHERE app_preset_assignment_id = ?
                """, Long.class, id);
        if (tenantId == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return repository.requireAssignment(tenantId, id);
    }

    private Long tenantId(AppGovernanceDtos.AppAdminPresetAssignment assignment) {
        return jdbc.queryForObject("""
                SELECT tenant_id FROM com_admin_app_preset_assignments
                 WHERE app_preset_assignment_id = ?
                """, Long.class, assignment.presetAssignmentId());
    }

    private void requireVersionAndState(
            AppGovernanceDtos.AppAdminPresetAssignment value,
            long expectedVersion,
            String state) {
        if (value.version() != expectedVersion) throw versionConflict();
        if (!state.equals(value.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "App administrator preset is not in the required lifecycle state.");
        }
    }

    private void requireRequestable(AppGovernanceDtos.AppAdminPreset preset) {
        if (!preset.requestable()) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "App administrator preset is unavailable: " + preset.unavailableReason());
        }
    }

    private void validateWindow(OffsetDateTime reviewDueAt, OffsetDateTime validTo) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (reviewDueAt == null || validTo == null || !reviewDueAt.isAfter(now)
                || !validTo.isAfter(now) || reviewDueAt.isAfter(validTo)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Review and validity windows must be future, ordered, and explicit.");
        }
    }

    private List<AppGovernanceDtos.AppAdminPresetAssignment> filterAssignments(
            List<AppGovernanceDtos.AppAdminPresetAssignment> values,
            AppGovernanceAuthorization.Visibility visibility) {
        return visibility.queueReader() ? values : values.stream()
                .filter(value -> visibility.resourceSetIds().contains(value.resourceSetId()))
                .toList();
    }

    private List<AppGovernanceDtos.AppAdminPreset> filterCatalog(
            List<AppGovernanceDtos.AppAdminPreset> values,
            List<AppGovernanceDtos.AppAdminPresetAssignment> assignments,
            AppGovernanceAuthorization.Visibility visibility) {
        if (visibility.queueReader()) return values;
        return values.stream()
                .filter(value -> visibility.appResourceKeys().contains(value.appResourceKey()))
                .toList();
    }

    private boolean isSelfServiceOwner(
            AppGovernanceDtos.AppAdminPresetAssignment value, Long actorId) {
        return "SELF_SERVICE".equals(value.requestChannel())
                && "USER".equals(value.principalType())
                && actorId.toString().equals(value.principalRef())
                && Objects.equals(actorId, value.requestedBy());
    }

    private BaseException integrity(DataIntegrityViolationException exception) {
        String message = Objects.toString(exception.getMostSpecificCause().getMessage(), "");
        if (message.contains("Scoped duty separation-of-duties conflict")) {
            return new BaseException(ErrorCode.SOD_CONFLICT,
                    "Preset overlaps a conflicting specialist duty.", exception);
        }
        return new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "App administrator preset conflicts with current governed state.", exception);
    }

    private BaseException versionConflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    private Map<String, Object> snapshot(
            AppGovernanceDtos.AppAdminPresetAssignment value) {
        return snapshot(
                "presetCode", value.presetCode(), "state", value.lifecycleState(),
                "principalType", value.principalType(), "principalRef", value.principalRef(),
                "resourceSetId", value.resourceSetId(), "version", value.version(),
                "catalogVersion", value.catalogVersion());
    }

    private Map<String, Object> snapshot(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    public record DashboardProjection(
            List<AppGovernanceDtos.AppAdminPreset> catalog,
            List<AppGovernanceDtos.AppAdminPresetAssignment> assignments,
            List<AppGovernanceDtos.AppAdminPresetReview> reviews) {
    }

}
