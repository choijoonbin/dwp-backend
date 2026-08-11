package com.dwp.services.auth.identity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.identity.EmailAddressNormalizer;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.IdentityAccountService;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkforceIdentitySyncService {

    private static final String HRIS = "HRIS";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final IdentityAccountService identityAccountService;
    private final JdbcTemplate jdbc;
    private final IdentityAuditService auditService;
    private final ObjectMapper objectMapper;

    public WorkforceIdentitySyncService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            IdentityAccountService identityAccountService,
            JdbcTemplate jdbc,
            IdentityAuditService auditService,
            ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.identityAccountService = identityAccountService;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkforceIdentityDtos.SyncResult synchronize(
            WorkforceIdentityDtos.WorkforceIdentityEvent event) {
        Tenant tenant = tenantRepository.findByPublicId(event.providerTenantId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (received(event, tenant.getTenantId())) {
            Long userId = userRepository
                    .findByTenantIdAndPersonPublicId(tenant.getTenantId(), event.personPublicId())
                    .map(User::getUserId)
                    .orElse(null);
            return new WorkforceIdentityDtos.SyncResult(
                    event.eventId(), tenant.getTenantId(), userId, "UNCHANGED", true);
        }

        User user = locateUser(tenant.getTenantId(), event);
        boolean created = user == null;
        PreviousIdentity previous = created ? null : PreviousIdentity.from(user);
        if (created) {
            user = User.builder()
                    .publicId(UUID.randomUUID())
                    .tenantId(tenant.getTenantId())
                    .personPublicId(event.personPublicId())
                    .sourceType(HRIS)
                    .mfaEnabled(false)
                    .accessRevision(0L)
                    .build();
        } else if (!HRIS.equals(user.getSourceType())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The workforce identity is already owned by another provisioning source.");
        }

        user.setPersonPublicId(event.personPublicId());
        user.setExternalId(event.externalId().strip());
        user.setDisplayName(event.displayName().strip());
        user.setGivenName(trimToNull(event.givenName()));
        user.setFamilyName(trimToNull(event.familyName()));
        user.setEmail(normalizedEmail(event.workEmail()));
        user.setJobTitle(trimToNull(event.jobTitle()));
        user.setPreferredLocale(trimToNull(event.preferredLocale()));
        user.setStatus(status(event.workerStatus()));
        user.setSourceType(HRIS);
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user = save(user);
        identityAccountService.synchronizeManagedUser(user);
        String lifecycleType = lifecycleType(created, previous, user);
        LifecycleControls controls = applyLifecycleControls(
                tenant.getTenantId(), user.getUserId(), user.getStatus());
        recordLifecycleEvent(event, tenant.getTenantId(), user, previous, lifecycleType, controls);
        auditService.success(
                tenant.getTenantId(), null, "identity.lifecycle.applied", "USER",
                user.getUserId().toString(), event.eventId().toString(),
                previous == null ? null : previous.auditSnapshot(),
                Map.of(
                        "lifecycleType", lifecycleType,
                        "status", user.getStatus(),
                        "sessionsRevoked", controls.sessionsRevoked(),
                        "directRolesRemoved", controls.directRolesRemoved(),
                        "groupMembershipsRemoved", controls.groupMembershipsRemoved()));

        return new WorkforceIdentityDtos.SyncResult(
                event.eventId(), tenant.getTenantId(), user.getUserId(),
                created ? "CREATED" : "UPDATED", false);
    }

    private boolean received(
            WorkforceIdentityDtos.WorkforceIdentityEvent event,
            Long tenantId) {
        return jdbc.update("""
                INSERT INTO sys_identity_sync_receipts (
                    event_id, tenant_id, source_type, person_public_id)
                VALUES (?, ?, 'HRIS', ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), tenantId, event.personPublicId()) == 0;
    }

    private User locateUser(
            Long tenantId,
            WorkforceIdentityDtos.WorkforceIdentityEvent event) {
        User byPerson = userRepository
                .findByTenantIdAndPersonPublicId(tenantId, event.personPublicId())
                .orElse(null);
        User byExternal = userRepository
                .findByTenantIdAndSourceTypeAndExternalId(
                        tenantId, HRIS, event.externalId().strip())
                .orElse(null);
        if (byPerson != null && byExternal != null
                && !byPerson.getUserId().equals(byExternal.getUserId())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The workforce person and external identifiers resolve to different users.");
        }
        return byPerson == null ? byExternal : byPerson;
    }

    private User save(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The workforce email or external identifier is already assigned.",
                    exception);
        }
    }

    private LifecycleControls applyLifecycleControls(Long tenantId, Long userId, String status) {
        if (!"SUSPENDED".equals(status) && !"INACTIVE".equals(status)) {
            return LifecycleControls.NONE;
        }
        int sessions = jdbc.update("""
                UPDATE sys_auth_sessions
                   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
                       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                 WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL
                """, tenantId, userId);
        if (!"INACTIVE".equals(status)) {
            return new LifecycleControls(sessions, 0, 0);
        }
        int directRoles = jdbc.update(
                "DELETE FROM com_role_members WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        int groupMemberships = jdbc.update(
                "DELETE FROM com_group_members WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        return new LifecycleControls(sessions, directRoles, groupMemberships);
    }

    private void recordLifecycleEvent(
            WorkforceIdentityDtos.WorkforceIdentityEvent event,
            Long tenantId,
            User user,
            PreviousIdentity previous,
            String lifecycleType,
            LifecycleControls controls) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changedFields", changedFields(previous, user));
        summary.put("previousStatus", previous == null ? null : previous.status());
        summary.put("currentStatus", user.getStatus());
        summary.put("sourceVersion", trimToNull(event.sourceVersion()));
        summary.put("sessionsRevoked", controls.sessionsRevoked());
        summary.put("directRolesRemoved", controls.directRolesRemoved());
        summary.put("groupMembershipsRemoved", controls.groupMembershipsRemoved());
        jdbc.update("""
                INSERT INTO sys_identity_lifecycle_events (
                    identity_lifecycle_event_id, tenant_id, user_id, person_public_id,
                    lifecycle_type, source_type, source_version, processing_state,
                    change_summary, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'HRIS', ?, 'APPLIED', CAST(? AS jsonb), ?)
                """,
                event.eventId(), tenantId, user.getUserId(), user.getPersonPublicId(),
                lifecycleType, trimToNull(event.sourceVersion()), toJson(summary),
                event.eventId().toString());
    }

    private String lifecycleType(boolean created, PreviousIdentity previous, User user) {
        if (created) return "JOINER";
        if ("INACTIVE".equals(user.getStatus())
                && !"INACTIVE".equals(previous.status())) return "LEAVER";
        if ("ACTIVE".equals(user.getStatus())
                && ("INACTIVE".equals(previous.status())
                    || "SUSPENDED".equals(previous.status()))) return "REHIRE";
        return changedFields(previous, user).stream()
                .anyMatch(field -> !"status".equals(field)) ? "MOVER" : "UPDATE";
    }

    private List<String> changedFields(PreviousIdentity previous, User user) {
        if (previous == null) {
            return List.of("identity", "status", "profile");
        }
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(previous.externalId(), user.getExternalId())) changed.add("externalId");
        if (!Objects.equals(previous.displayName(), user.getDisplayName())) changed.add("displayName");
        if (!Objects.equals(previous.email(), user.getEmail())) changed.add("workEmail");
        if (!Objects.equals(previous.jobTitle(), user.getJobTitle())) changed.add("jobTitle");
        if (!Objects.equals(previous.preferredLocale(), user.getPreferredLocale())) {
            changed.add("preferredLocale");
        }
        if (!Objects.equals(previous.status(), user.getStatus())) changed.add("status");
        return List.copyOf(changed);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Identity lifecycle summary serialization failed.",
                    exception);
        }
    }

    private static String normalizedEmail(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            return EmailAddressNormalizer.requireValid(email);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "The workforce email is invalid.");
        }
    }

    private static String status(String workerStatus) {
        return switch (workerStatus) {
            case "ACTIVE" -> "ACTIVE";
            case "PENDING" -> "INVITED";
            case "LEAVE" -> "SUSPENDED";
            case "TERMINATED" -> "INACTIVE";
            default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        };
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record LifecycleControls(
            int sessionsRevoked,
            int directRolesRemoved,
            int groupMembershipsRemoved) {
        private static final LifecycleControls NONE = new LifecycleControls(0, 0, 0);
    }

    private record PreviousIdentity(
            String externalId,
            String displayName,
            String email,
            String jobTitle,
            String preferredLocale,
            String status) {
        private static PreviousIdentity from(User user) {
            return new PreviousIdentity(
                    user.getExternalId(), user.getDisplayName(), user.getEmail(),
                    user.getJobTitle(), user.getPreferredLocale(), user.getStatus());
        }

        private Map<String, Object> auditSnapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("status", status);
            snapshot.put("hasEmail", email != null);
            snapshot.put("hasJobTitle", jobTitle != null);
            snapshot.put("preferredLocale", preferredLocale);
            return snapshot;
        }
    }
}
