package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ProviderSupportAccessService {

    private final ProviderSupportSessionRepository sessionRepository;
    private final ProviderSupportSessionLifecycleService lifecycleService;
    private final ProviderTenantRepository tenantRepository;
    private final ProviderAuditService auditService;
    private final ProviderSupportActivationGate activationGate;

    public ProviderSupportAccessService(
            ProviderSupportSessionRepository sessionRepository,
            ProviderSupportSessionLifecycleService lifecycleService,
            ProviderTenantRepository tenantRepository,
            ProviderAuditService auditService,
            ProviderSupportActivationGate activationGate) {
        this.sessionRepository = sessionRepository;
        this.lifecycleService = lifecycleService;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
        this.activationGate = activationGate;
    }

    @Transactional
    public ProviderSupportDtos.BrowserSessionContext inspect(
            String sessionToken,
            String correlationId) {
        ValidatedSession validated = validated(
                sessionToken,
                "GET",
                "/api/provider/v1/admin/support-session-context",
                correlationId);
        requireActivationEnabled(validated, correlationId, "GET",
                "/api/provider/v1/admin/support-session-context");
        return browserContext(validated);
    }

    @Transactional
    public ProviderSupportDtos.VerifiedSessionContext resolve(
            String sessionToken,
            String method,
            String resourcePath,
            String correlationId) {
        ValidatedSession validated = validated(sessionToken, method, resourcePath, correlationId);
        requireActivationEnabled(validated, correlationId, method, resourcePath);
        String requiredScope;
        try {
            requiredScope = ProviderSupportAccessPolicy.requiredScope(method, resourcePath);
        } catch (BaseException exception) {
            auditDenied(
                    validated.record(), validated.tenant(), correlationId,
                    "RESOURCE_NOT_ALLOWLISTED", method, resourcePath, null);
            throw exception;
        }
        boolean allowed = validated.scopes().contains(requiredScope);
        if (!allowed) {
            auditDenied(
                    validated.record(), validated.tenant(), correlationId,
                    "SCOPE_INSUFFICIENT", method, resourcePath, requiredScope);
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session scope is insufficient.");
        }
        ProviderSupportSessionRepository.SupportSessionTouch touch = sessionRepository.touch(
                validated.record().supportSessionId(),
                ProviderRequestContext.require().operatorId(),
                ProviderRequestContext.require().authSessionId()).orElse(null);
        if (touch == null) {
            auditDenied(
                    validated.record(), validated.tenant(), correlationId,
                    "SESSION_STATE_CHANGED", method, resourcePath, requiredScope);
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session is no longer active.");
        }
        Map<String, Object> useEvidence = new java.util.LinkedHashMap<>();
        useEvidence.put("decision", "ALLOW");
        useEvidence.put("policyId", "PROVIDER_SUPPORT_SESSION_BOUNDARY_V1");
        useEvidence.put("method", auditMethod(method));
        useEvidence.put("routeTemplate", auditRouteTemplate(resourcePath));
        useEvidence.put("scope", requiredScope);
        useEvidence.put("supportSessionId", validated.record().supportSessionId());
        useEvidence.put("actorAuthTenantId", ProviderRequestContext.require().authTenantId());
        useEvidence.put("targetAuthTenantId", touch.authTenantId());
        useEvidence.put("accessMode", validated.record().accessMode());
        useEvidence.put("absoluteExpiresAt", validated.record().absoluteExpiresAt());
        useEvidence.put("effectiveExpiresAt", touch.effectiveExpiresAt());
        useEvidence.put("sessionVersion", validated.record().version());
        auditService.success(
                "provider.support-session.used",
                "SUPPORT_SESSION",
                validated.record().supportSessionId().toString(),
                validated.tenant().getProviderTenantId(),
                validated.tenant().getOrganizationId(),
                correlationId,
                useEvidence);
        return verifiedContext(validated, touch);
    }

    private ValidatedSession validated(
            String sessionToken,
            String method,
            String resourcePath,
            String correlationId) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        if (sessionToken == null || sessionToken.isBlank()) {
            auditDenied(null, null, correlationId,
                    "SUPPORT_CREDENTIAL_MISSING", method, resourcePath, null);
            throw new BaseException(ErrorCode.FORBIDDEN, "An active support session is required.");
        }
        lifecycleService.expireElapsedSessions();
        String tokenHash = sha256(sessionToken.trim());
        ProviderSupportSessionRepository.SupportSessionRecord record = sessionRepository
                .sessionByTokenHash(tokenHash)
                .orElse(null);
        if (record == null) {
            auditDenied(null, null, correlationId,
                    "SUPPORT_CREDENTIAL_INVALID", method, resourcePath, null);
            throw new BaseException(ErrorCode.FORBIDDEN, "An active support session is required.");
        }
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        String invalidReason = !constantTimeEquals(record.tokenHash(), tokenHash)
                ? "SUPPORT_CREDENTIAL_INVALID"
                : !record.operatorId().equals(actor.operatorId())
                        ? "SESSION_ACTOR_MISMATCH"
                        : !Objects.equals(record.originAuthSessionId(), actor.authSessionId())
                                ? "SESSION_AUTH_CONTEXT_MISMATCH"
                                : !"ACTIVE".equals(record.lifecycleState())
                                        ? "SESSION_NOT_ACTIVE"
                                        : !record.effectiveExpiresAt().isAfter(Instant.now())
                                                ? "SESSION_EXPIRED"
                                                : null;
        if (invalidReason != null) {
            ProviderTenant auditTenant = tenantRepository.findById(record.tenantId()).orElse(null);
            auditDenied(record, auditTenant, correlationId,
                    invalidReason, method, resourcePath, null);
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session is no longer active.");
        }
        ProviderTenant tenant = tenantRepository.findById(record.tenantId()).orElse(null);
        if (tenant == null) {
            auditDenied(record, null, correlationId,
                    "TARGET_TENANT_MISSING", method, resourcePath, null);
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (!"ACTIVE".equals(tenant.getLifecycleState())
                || !"READY".equals(tenant.getOnboardingState())
                || tenant.getAuthTenantId() == null) {
            auditDenied(record, tenant, correlationId,
                    "TARGET_TENANT_UNAVAILABLE", method, resourcePath, null);
            throw new BaseException(ErrorCode.INVALID_STATE, "The target tenant is not available for support.");
        }
        List<String> scopes = sessionRepository.scopes(record.supportSessionId());
        if (scopes.size() != sessionRepository.scopeCount(record.supportSessionId())) {
            auditDenied(record, tenant, correlationId,
                    "SESSION_SCOPE_RETIRED", method, resourcePath, null);
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The support session contains a retired scope and is no longer active.");
        }
        return new ValidatedSession(record, tenant, scopes);
    }

    private void requireActivationEnabled(
            ValidatedSession validated,
            String correlationId,
            String method,
            String resourcePath) {
        if (activationGate.enabled()) return;
        auditDenied(
                validated.record(), validated.tenant(), correlationId,
                "SUPPORT_KILL_SWITCH_DISABLED", method, resourcePath, null);
        throw new BaseException(
                ErrorCode.FORBIDDEN,
                "The support session is disabled by an operational safety control.");
    }

    private ProviderSupportDtos.BrowserSessionContext browserContext(ValidatedSession validated) {
        return new ProviderSupportDtos.BrowserSessionContext(
                validated.record().supportSessionId(),
                validated.tenant().getProviderTenantId(),
                validated.tenant().getTenantKey(),
                validated.tenant().getDisplayName(),
                validated.tenant().getEnvironmentKey(),
                validated.tenant().getDataRegion(),
                validated.scopes(),
                validated.record().accessMode(),
                validated.record().effectiveExpiresAt(),
                validated.record().version());
    }

    private ProviderSupportDtos.VerifiedSessionContext verifiedContext(
            ValidatedSession validated,
            ProviderSupportSessionRepository.SupportSessionTouch touch) {
        return new ProviderSupportDtos.VerifiedSessionContext(
                validated.record().supportSessionId(),
                validated.tenant().getProviderTenantId(),
                touch.authTenantId(),
                validated.tenant().getTenantKey(),
                validated.tenant().getDisplayName(),
                validated.scopes(),
                validated.record().accessMode(),
                touch.effectiveExpiresAt(),
                validated.record().version());
    }

    private void auditDenied(
            ProviderSupportSessionRepository.SupportSessionRecord record,
            ProviderTenant tenant,
            String correlationId,
            String reasonCode,
            String method,
            String resourcePath,
            String requiredScope) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("reasonCode", reasonCode);
        snapshot.put("decision", "DENY");
        snapshot.put("policyId", "PROVIDER_SUPPORT_SESSION_BOUNDARY_V1");
        snapshot.put("method", auditMethod(method));
        snapshot.put("routeTemplate", auditRouteTemplate(resourcePath));
        snapshot.put("httpStatus", 403);
        snapshot.put("actorAuthTenantId", ProviderRequestContext.require().authTenantId());
        if (requiredScope != null) snapshot.put("requiredScope", requiredScope);
        if (record != null) {
            snapshot.put("accessMode", record.accessMode());
            snapshot.put("absoluteExpiresAt", record.absoluteExpiresAt());
            snapshot.put("lastUsedAt", record.lastUsedAt());
            snapshot.put("effectiveExpiresAt", record.effectiveExpiresAt());
            snapshot.put("sessionVersion", record.version());
            snapshot.put("authSessionBound", record.originAuthSessionId() != null);
        }
        if (tenant != null && tenant.getAuthTenantId() != null) {
            snapshot.put("targetAuthTenantId", tenant.getAuthTenantId());
        }
        auditService.denied(
                "provider.support-session.access-denied",
                "SUPPORT_SESSION",
                record == null ? "UNRESOLVED" : record.supportSessionId().toString(),
                record == null ? null : record.tenantId(),
                tenant == null ? null : tenant.getOrganizationId(),
                auditCorrelationId(correlationId),
                snapshot);
    }

    private String auditMethod(String method) {
        String value = safe(method).trim().toUpperCase(java.util.Locale.ROOT);
        return value.matches("[A-Z]{3,12}") ? value : "UNKNOWN";
    }

    private String auditRouteTemplate(String resourcePath) {
        String value = safe(resourcePath).trim();
        if (value.equals("/api/platform/v1/admin/tenant-experience-preview")) return value;
        String[] segments = value.split("/", -1);
        if (segments.length >= 3 && "api".equals(segments[1])
                && segments[2].matches("[a-z][a-z0-9-]{0,39}")) {
            return "/api/" + segments[2] + "/**";
        }
        return "/api/**";
    }

    private String auditCorrelationId(String correlationId) {
        String value = safe(correlationId).trim();
        return value.matches("[A-Za-z0-9._:-]{1,128}") ? value : null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private record ValidatedSession(
            ProviderSupportSessionRepository.SupportSessionRecord record,
            ProviderTenant tenant,
            List<String> scopes) {
    }
}
