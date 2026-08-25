package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Request side, including independently approved and idempotent self-service intake. */
@Service
public class AppAdminPresetRequestService {

    private final JdbcTemplate jdbc;
    private final AppAdminPresetRepository repository;
    private final ScopedAdminDutyAssignmentService scopedDuties;
    private final IdentityAuditService audit;
    private final AppAdminPresetOutboxPublisher events;
    private final AppGovernanceAuthorization authorization;
    private final AppGovernanceDenialAudit denials;
    private final Clock clock;

    public AppAdminPresetRequestService(
            JdbcTemplate jdbc,
            AppAdminPresetRepository repository,
            ScopedAdminDutyAssignmentService scopedDuties,
            IdentityAuditService audit,
            AppAdminPresetOutboxPublisher events) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.scopedDuties = scopedDuties;
        this.audit = audit;
        this.events = events;
        this.authorization = new AppGovernanceAuthorization(jdbc);
        this.denials = new AppGovernanceDenialAudit(audit);
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.AppAdminPresetSelfServiceOption> selfServiceOptions(
            Long tenantId, Long actorId, String appResourceKey) {
        requireActiveUser(tenantId, actorId);
        String exactAppResource = normalizeAppResourceKey(appResourceKey);
        List<AppGovernanceDtos.AppAdminPresetResourceSetOption> resourceSets =
                repository.resourceSetOptions(tenantId, exactAppResource);
        return repository.catalog().stream()
                .filter(AppGovernanceDtos.AppAdminPreset::requestable)
                .filter(preset -> exactAppResource.equals(preset.appResourceKey()))
                .map(preset -> new AppGovernanceDtos.AppAdminPresetSelfServiceOption(
                        preset, resourceSets))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment requestGoverned(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.request-denied",
                "APP_ADMIN_PRESET_ASSIGNMENT", request.resourceSetId().toString(),
                () -> requestGovernedInternal(
                        tenantId, actorId, correlationId, request));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment requestGovernedInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest request) {
        authorization.requirePresetRequester(
                tenantId, actorId, request.resourceSetId(), correlationId);
        String principalType = request.principalType().strip().toUpperCase(Locale.ROOT);
        String principalRef = request.principalRef().strip();
        requireNotSubject(tenantId, actorId, principalType, principalRef);
        return create(
                tenantId, actorId, correlationId, principalType, principalRef,
                request.presetCode(), request.resourceSetId(), request.validTo(),
                request.reviewDueAt(), request.justification(), "GOVERNANCE", null, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppGovernanceDtos.AppAdminPresetAssignment requestSelfService(
            Long tenantId,
            Long actorId,
            String correlationId,
            String idempotencyKey,
            AppGovernanceDtos.CreateSelfServicePresetRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-admin-preset.self-service-request-denied",
                "APP_ADMIN_PRESET_ASSIGNMENT", request.resourceSetId().toString(),
                () -> requestSelfServiceInternal(
                        tenantId, actorId, correlationId, idempotencyKey, request));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment requestSelfServiceInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            String idempotencyKey,
            AppGovernanceDtos.CreateSelfServicePresetRequest request) {
        requireActiveUser(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(actorId, request);
        lockResourceBoundary(tenantId, request.resourceSetId());
        AppAdminPresetRepository.IdempotentRequest replay =
                repository.findIdempotentRequest(tenantId, actorId, key);
        if (replay != null) {
            if (!fingerprint.equals(replay.requestFingerprint())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was used with a different preset request.");
            }
            AppGovernanceDtos.AppAdminPresetAssignment existing =
                    repository.requireAssignment(tenantId, replay.assignmentId());
            audit.success(tenantId, actorId,
                    "access.app-admin-preset.self-service-replayed",
                    "APP_ADMIN_PRESET_ASSIGNMENT", replay.assignmentId().toString(),
                    correlationId, snapshot(existing), snapshot(existing));
            return existing;
        }
        return create(
                tenantId, actorId, correlationId, "USER", actorId.toString(),
                request.presetCode(), request.resourceSetId(), request.validTo(),
                request.reviewDueAt(), request.justification(),
                "SELF_SERVICE", key, fingerprint);
    }

    private AppGovernanceDtos.AppAdminPresetAssignment create(
            Long tenantId,
            Long actorId,
            String correlationId,
            String principalType,
            String principalRef,
            String requestedPresetCode,
            UUID resourceSetId,
            OffsetDateTime validTo,
            OffsetDateTime reviewDueAt,
            String justification,
            String requestChannel,
            String idempotencyKey,
            String requestFingerprint) {
        String presetCode = requestedPresetCode.strip().toUpperCase(Locale.ROOT);
        AppGovernanceDtos.AppAdminPreset preset = repository.requirePreset(presetCode);
        requireRequestable(preset);
        validateWindow(reviewDueAt, validTo);
        lockResourceBoundary(tenantId, resourceSetId);
        if ("GOVERNANCE".equals(requestChannel)) {
            authorization.requirePresetRequester(
                    tenantId, actorId, resourceSetId, correlationId);
        } else {
            requireActiveUser(tenantId, actorId);
        }
        requireResourceBoundary(tenantId, resourceSetId, preset.appResourceKey());
        requirePrincipal(tenantId, principalType, principalRef);
        ensureNoOpenResponsibility(
                tenantId, principalType, principalRef,
                preset.responsibilityCode(), resourceSetId);

        UUID responsibilityId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        try {
            insertResponsibility(
                    responsibilityId, tenantId, actorId, principalType, principalRef,
                    preset.responsibilityCode(), resourceSetId, validTo,
                    reviewDueAt, justification);
            jdbc.update("""
                    INSERT INTO com_admin_app_preset_assignments (
                        app_preset_assignment_id, tenant_id, preset_code,
                        preset_catalog_version, principal_type, principal_ref,
                        resource_set_id, responsibility_assignment_id,
                        assignment_source, request_channel,
                        idempotency_key, request_fingerprint,
                        lifecycle_state, valid_to, review_due_at,
                        justification, requested_by, event_sequence, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?,
                            'PENDING_APPROVAL', ?, ?, ?, ?, 1, ?, ?)
                    """, aggregateId, tenantId, preset.presetCode(), preset.catalogVersion(),
                    principalType, principalRef, resourceSetId, responsibilityId,
                    requestChannel, idempotencyKey, requestFingerprint,
                    validTo, reviewDueAt, justification.strip(), actorId, actorId, actorId);
            for (AppGovernanceDtos.AppAdminPresetDuty duty : preset.duties()) {
                scopedDuties.request(new ScopedAdminDutyAssignmentService.Request(
                        tenantId, principalType, principalRef, duty.dutyCode(),
                        resourceSetId, responsibilityId, aggregateId, "MANUAL",
                        null, validTo, reviewDueAt, justification, actorId));
            }
            assertDeferredGuards();
            AppGovernanceDtos.AppAdminPresetAssignment created =
                    repository.requireAssignment(tenantId, aggregateId);
            String action = "SELF_SERVICE".equals(requestChannel)
                    ? "access.app-admin-preset.self-service-requested"
                    : "access.app-admin-preset.requested";
            audit.success(tenantId, actorId, action,
                    "APP_ADMIN_PRESET_ASSIGNMENT", aggregateId.toString(), correlationId,
                    null, snapshot(created));
            events.assignment(AppAdminPresetOutboxPublisher.REQUESTED,
                    tenantId, created, 1, correlationId);
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    private void insertResponsibility(
            UUID id, Long tenantId, Long actorId, String principalType,
            String principalRef, String responsibility, UUID resourceSetId,
            OffsetDateTime validTo, OffsetDateTime reviewDue, String justification) {
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_to, review_due_at, justification,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', 'PENDING_APPROVAL', ?, ?, ?, ?, ?)
                """, id, tenantId, principalType, principalRef, responsibility,
                resourceSetId, validTo, reviewDue, justification.strip(), actorId, actorId);
    }

    private void requireActiveUser(Long tenantId, Long actorId) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM com_users
                 WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, tenantId, actorId);
        if (!Boolean.TRUE.equals(active)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private void requireNotSubject(
            Long tenantId, Long actorId, String principalType, String principalRef) {
        boolean self = "USER".equals(principalType)
                ? actorId.toString().equals(principalRef)
                : Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM com_group_members
                     WHERE tenant_id = ? AND group_id::text = ? AND user_id = ?)
                    """, Boolean.class, tenantId, principalRef, actorId));
        if (self) throw new BaseException(ErrorCode.SOD_CONFLICT,
                "Self-fulfilment of an app administrator preset is forbidden.");
    }

    private void requireRequestable(AppGovernanceDtos.AppAdminPreset preset) {
        if (!preset.requestable()) throw new BaseException(ErrorCode.INVALID_STATE,
                "App administrator preset is unavailable: " + preset.unavailableReason());
    }

    private void validateWindow(OffsetDateTime reviewDueAt, OffsetDateTime validTo) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (reviewDueAt == null || validTo == null || !reviewDueAt.isAfter(now)
                || !validTo.isAfter(now) || reviewDueAt.isAfter(validTo)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Review and validity windows must be future, ordered, and explicit.");
        }
    }

    private void requireResourceBoundary(Long tenantId, UUID setId, String appResourceKey) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_admin_resource_sets resource_set
                    JOIN com_admin_resource_set_members member
                      ON member.tenant_id = resource_set.tenant_id
                     AND member.resource_set_id = resource_set.resource_set_id
                     AND member.lifecycle_state = 'ACTIVE'
                   WHERE resource_set.tenant_id = ? AND resource_set.resource_set_id = ?
                     AND resource_set.lifecycle_state = 'ACTIVE'
                     AND member.resource_type = 'APP' AND member.resource_key = ?)
                """, Boolean.class, tenantId, setId, appResourceKey);
        if (!Boolean.TRUE.equals(exists)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Resource set does not carry the preset's exact application root.");
    }

    private void requirePrincipal(Long tenantId, String type, String ref) {
        String table = "USER".equals(type) ? "com_users" : "com_groups";
        String id = "USER".equals(type) ? "user_id" : "group_id";
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE tenant_id = ? AND "
                        + id + "::text = ? AND status = 'ACTIVE')",
                Boolean.class, tenantId, ref);
        if (!Boolean.TRUE.equals(exists)) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    private void ensureNoOpenResponsibility(
            Long tenantId, String principalType, String principalRef,
            String responsibility, UUID resourceSetId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = ? AND principal_ref = ?
                   AND responsibility_code = ? AND resource_set_id = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Long.class, tenantId, principalType, principalRef,
                responsibility, resourceSetId);
        if (count != null && count > 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "An active or pending responsibility already exists for this subject and scope.");
    }

    private String normalizeAppResourceKey(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("APP\\.[A-Z][A-Z0-9._]{1,150}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return normalized;
    }

    private String requireIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 8 || normalized.length() > 160
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A valid Idempotency-Key header is required.");
        }
        return normalized;
    }

    private String fingerprint(
            Long actorId, AppGovernanceDtos.CreateSelfServicePresetRequest request) {
        String canonical = String.join("\n", actorId.toString(),
                request.presetCode().strip().toUpperCase(Locale.ROOT),
                request.resourceSetId().toString(), request.validTo().toInstant().toString(),
                request.reviewDueAt().toInstant().toString(), request.justification().strip());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void lockResourceBoundary(Long tenantId, UUID resourceSetId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", ignored -> { },
                tenantId.intValue(), ("app-admin-resource-set:" + resourceSetId).hashCode());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> { },
                "dwp-app-admin-preset:" + tenantId);
    }

    private void assertDeferredGuards() {
        jdbc.execute("SET CONSTRAINTS trg_scoped_duty_assignment_sod IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_aggregate_consistency IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_duty_consistency IMMEDIATE");
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

    private Map<String, Object> snapshot(
            AppGovernanceDtos.AppAdminPresetAssignment value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("presetCode", value.presetCode());
        result.put("state", value.lifecycleState());
        result.put("principalType", value.principalType());
        result.put("principalRef", value.principalRef());
        result.put("resourceSetId", value.resourceSetId());
        result.put("version", value.version());
        return result;
    }
}
