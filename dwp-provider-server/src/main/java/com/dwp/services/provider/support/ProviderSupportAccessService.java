package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderDtos;
import com.dwp.services.provider.ProviderEstateRepository;
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

@Service
public class ProviderSupportAccessService {

    private final ProviderEstateRepository estateRepository;
    private final ProviderTenantRepository tenantRepository;
    private final ProviderAuditService auditService;

    public ProviderSupportAccessService(
            ProviderEstateRepository estateRepository,
            ProviderTenantRepository tenantRepository,
            ProviderAuditService auditService) {
        this.estateRepository = estateRepository;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    @Transactional
    public ProviderDtos.SupportSessionContext inspect(String sessionToken) {
        return validated(sessionToken).context();
    }

    @Transactional
    public ProviderDtos.SupportSessionContext resolve(
            String sessionToken,
            String method,
            String resourcePath,
            String correlationId) {
        ValidatedSession validated = validated(sessionToken);
        String requiredScope = ProviderSupportAccessPolicy.requiredScope(method, resourcePath);
        if (!validated.scopes().contains(requiredScope)) {
            auditService.failed(
                    "provider.support-session.access-denied",
                    "SUPPORT_SESSION",
                    validated.record().supportSessionId().toString(),
                    validated.tenant().getProviderTenantId(),
                    validated.tenant().getOrganizationId(),
                    correlationId,
                    Map.of("method", method, "resourcePath", resourcePath, "requiredScope", requiredScope));
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session scope is insufficient.");
        }
        if (!estateRepository.touchSupportSession(
                validated.record().supportSessionId(), ProviderRequestContext.require().operatorId())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session is no longer active.");
        }
        auditService.success(
                "provider.support-session.used",
                "SUPPORT_SESSION",
                validated.record().supportSessionId().toString(),
                validated.tenant().getProviderTenantId(),
                validated.tenant().getOrganizationId(),
                correlationId,
                Map.of("method", method, "resourcePath", resourcePath, "scope", requiredScope));
        return validated.context();
    }

    private ValidatedSession validated(String sessionToken) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "An active support session is required.");
        }
        String tokenHash = sha256(sessionToken.trim());
        ProviderEstateRepository.SupportSessionRecord record = estateRepository
                .supportSessionByTokenHash(tokenHash)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "An active support session is required."));
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        if (!constantTimeEquals(record.tokenHash(), tokenHash)
                || !record.operatorId().equals(actor.operatorId())
                || !"ACTIVE".equals(record.lifecycleState())
                || !record.expiresAt().isAfter(Instant.now())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The support session is no longer active.");
        }
        ProviderTenant tenant = tenantRepository.findById(record.tenantId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(tenant.getLifecycleState())
                || !"READY".equals(tenant.getOnboardingState())
                || tenant.getAuthTenantId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The target tenant is not available for support.");
        }
        List<String> scopes = estateRepository.supportSessionScopes(record.supportSessionId());
        ProviderDtos.SupportSessionContext context = new ProviderDtos.SupportSessionContext(
                record.supportSessionId(),
                tenant.getProviderTenantId(),
                tenant.getAuthTenantId(),
                tenant.getTenantKey(),
                tenant.getDisplayName(),
                scopes,
                record.accessMode(),
                record.expiresAt(),
                record.version());
        return new ValidatedSession(record, tenant, scopes, context);
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
            ProviderEstateRepository.SupportSessionRecord record,
            ProviderTenant tenant,
            List<String> scopes,
            ProviderDtos.SupportSessionContext context) {
    }
}
