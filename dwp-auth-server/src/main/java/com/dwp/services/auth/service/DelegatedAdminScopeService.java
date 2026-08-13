package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.PrivilegedAccessDtos;
import com.dwp.services.auth.entity.DelegatedAdminScope;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.DelegatedAdminScopeRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DelegatedAdminScopeService {

    private final DelegatedAdminScopeRepository scopeRepository;
    private final UserRepository userRepository;
    private final IdentityAuditService auditService;

    public DelegatedAdminScopeService(
            DelegatedAdminScopeRepository scopeRepository,
            UserRepository userRepository,
            IdentityAuditService auditService) {
        this.scopeRepository = scopeRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PrivilegedAccessDtos.DelegatedScopeSummary> scopes(Long tenantId) {
        List<DelegatedAdminScope> scopes = scopeRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<Long, User> users = userRepository.findByTenantIdAndUserIdIn(
                        tenantId,
                        scopes.stream()
                                .map(DelegatedAdminScope::getAdministratorUserId)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        return scopes.stream().map(scope -> summary(scope, users.get(
                scope.getAdministratorUserId()))).toList();
    }

    @Transactional
    public PrivilegedAccessDtos.DelegatedScopeSummary create(
            Long tenantId,
            Long actorId,
            String correlationId,
            PrivilegedAccessDtos.CreateDelegatedScopeRequest request) {
        if (actorId.equals(request.administratorUserId())) {
            denied(tenantId, actorId, correlationId, "SELF_DELEGATION_NOT_ALLOWED");
            throw new BaseException(ErrorCode.FORBIDDEN, "Administrators cannot delegate scope to themselves.");
        }
        User administrator = userRepository
                .findByUserIdAndTenantId(request.administratorUserId(), tenantId)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String scopeRef = normalizedScopeRef(request.scopeType(), request.scopeRef());
        if (request.validFrom() != null && request.validTo() != null
                && !request.validTo().isAfter(request.validFrom())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The delegation window is invalid.");
        }
        DelegatedAdminScope scope = DelegatedAdminScope.builder()
                .delegatedAdminScopeId(UUID.randomUUID())
                .tenantId(tenantId)
                .administratorUserId(administrator.getUserId())
                .scopeType(request.scopeType())
                .scopeRef(scopeRef)
                .actionCode(request.actionCode())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .lifecycleState("ACTIVE")
                .justification(request.justification().trim())
                .build();
        scope.setCreatedBy(actorId);
        scope.setUpdatedBy(actorId);
        try {
            scope = scopeRepository.saveAndFlush(scope);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The administrator already has this active delegated scope.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "access.delegated-scope.created",
                "DELEGATED_ADMIN_SCOPE", scope.getDelegatedAdminScopeId().toString(),
                correlationId, null, snapshot(scope));
        return summary(scope, administrator);
    }

    @Transactional
    public PrivilegedAccessDtos.DelegatedScopeSummary revoke(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID scopeId,
            Long expectedVersion) {
        DelegatedAdminScope scope = scopeRepository
                .findByDelegatedAdminScopeIdAndTenantId(scopeId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(scope.getVersion(), expectedVersion);
        if (!"ACTIVE".equals(scope.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The delegated scope is not active.");
        }
        Map<String, Object> before = snapshot(scope);
        scope.setLifecycleState("REVOKED");
        scope.setUpdatedBy(actorId);
        scope = scopeRepository.saveAndFlush(scope);
        auditService.success(
                tenantId, actorId, "access.delegated-scope.revoked",
                "DELEGATED_ADMIN_SCOPE", scopeId.toString(), correlationId,
                before, snapshot(scope));
        User administrator = userRepository
                .findByUserIdAndTenantId(scope.getAdministratorUserId(), tenantId)
                .orElse(null);
        return summary(scope, administrator);
    }

    @Transactional(readOnly = true)
    public void require(
            Long tenantId,
            Long administratorUserId,
            String actionCode,
            String targetScopeType,
            String targetScopeRef) {
        List<DelegatedAdminScope> configured = scopeRepository.findActiveForAdministrator(
                tenantId, administratorUserId, Instant.now());
        if (configured.isEmpty()) return;
        boolean allowed = configured.stream()
                .filter(scope -> actionCode.equals(scope.getActionCode()))
                .anyMatch(scope -> covers(scope, targetScopeType, targetScopeRef));
        if (!allowed) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The requested action is outside the administrator's delegated scope.");
        }
    }

    private boolean covers(
            DelegatedAdminScope configured,
            String targetScopeType,
            String targetScopeRef) {
        if ("TENANT".equals(configured.getScopeType())) return true;
        return Objects.equals(configured.getScopeType(), targetScopeType)
                && Objects.equals(configured.getScopeRef(), targetScopeRef);
    }

    private String normalizedScopeRef(String scopeType, String scopeRef) {
        String normalized = scopeRef == null || scopeRef.isBlank() ? null : scopeRef.trim();
        if (("TENANT".equals(scopeType) && normalized != null)
                || (!"TENANT".equals(scopeType) && normalized == null)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The delegated scope is invalid.");
        }
        return normalized;
    }

    private PrivilegedAccessDtos.DelegatedScopeSummary summary(
            DelegatedAdminScope scope,
            User administrator) {
        return new PrivilegedAccessDtos.DelegatedScopeSummary(
                scope.getDelegatedAdminScopeId(),
                scope.getAdministratorUserId(),
                administrator == null ? null : administrator.getDisplayName(),
                scope.getScopeType(),
                scope.getScopeRef(),
                scope.getActionCode(),
                scope.getValidFrom(),
                scope.getValidTo(),
                scope.getLifecycleState(),
                scope.getJustification(),
                valueOrZero(scope.getVersion()));
    }

    private Map<String, Object> snapshot(DelegatedAdminScope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("administratorUserId", scope.getAdministratorUserId());
        result.put("scopeType", scope.getScopeType());
        result.put("scopeRef", scope.getScopeRef());
        result.put("actionCode", scope.getActionCode());
        result.put("lifecycleState", scope.getLifecycleState());
        result.put("validFrom", scope.getValidFrom());
        result.put("validTo", scope.getValidTo());
        result.put("version", valueOrZero(scope.getVersion()));
        return result;
    }

    private void denied(Long tenantId, Long actorId, String correlationId, String reason) {
        auditService.denied(
                tenantId, actorId, "access.delegated-scope.rejected",
                "USER", actorId.toString(), correlationId, reason, Map.of());
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Delegated access data changed after it was loaded. Refresh and try again.");
        }
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
