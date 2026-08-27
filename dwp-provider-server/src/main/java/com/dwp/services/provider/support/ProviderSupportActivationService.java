package com.dwp.services.provider.support;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ProviderSupportActivationService {

    private final ProviderSupportSessionRepository repository;
    private final ProviderAuditService auditService;

    public ProviderSupportActivationService(
            ProviderSupportSessionRepository repository,
            ProviderAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public void disable(
            String correlationId,
            ProviderSupportDtos.DisableActivationRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderRequestContext.requirePermission("SUPPORT_ACCESS_REVIEW");
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        String auditCorrelationId = auditService.canonicalCorrelationId(correlationId);
        ProviderSupportSessionRepository.SupportActivationState state =
                repository.disableActivation(
                        actor.operatorId(), request.reason().trim(), auditCorrelationId);
        auditService.success(
                "provider.support-activation.disable-requested", "SUPPORT_CONTROL",
                "STANDARD_JIT", null, null, correlationId,
                Map.of("reason", request.reason().trim(), "enabled", state.enabled(),
                        "controlVersion", state.version()));
    }
}
