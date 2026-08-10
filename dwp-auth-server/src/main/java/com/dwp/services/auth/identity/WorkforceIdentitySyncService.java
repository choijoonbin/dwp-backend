package com.dwp.services.auth.identity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.identity.EmailAddressNormalizer;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.IdentityAccountService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WorkforceIdentitySyncService {

    private static final String HRIS = "HRIS";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final IdentityAccountService identityAccountService;
    private final JdbcTemplate jdbc;

    public WorkforceIdentitySyncService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            IdentityAccountService identityAccountService,
            JdbcTemplate jdbc) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.identityAccountService = identityAccountService;
        this.jdbc = jdbc;
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
}
