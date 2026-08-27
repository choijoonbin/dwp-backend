package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProviderSupportLedgerService {

    private final ProviderTenantRepository tenantRepository;
    private final ProviderSupportLedgerRepository ledgerRepository;
    private final ProviderSupportSessionLifecycleService lifecycleService;

    public ProviderSupportLedgerService(
            ProviderTenantRepository tenantRepository,
            ProviderSupportLedgerRepository ledgerRepository,
            ProviderSupportSessionLifecycleService lifecycleService) {
        this.tenantRepository = tenantRepository;
        this.ledgerRepository = ledgerRepository;
        this.lifecycleService = lifecycleService;
    }

    public List<ProviderSupportDtos.AccessRequestLedgerItem> accessRequests(UUID tenantId) {
        ProviderRequestContext.requirePermission("SUPPORT_ACCESS_READ");
        lifecycleService.expireElapsedSessions();
        requireTenantIfScoped(tenantId);
        return ledgerRepository.accessRequests(tenantId, ProviderRequestContext.require());
    }

    public List<ProviderSupportDtos.SessionLedgerItem> sessions(UUID tenantId) {
        ProviderRequestContext.requirePermission("SUPPORT_ACCESS_READ");
        lifecycleService.expireElapsedSessions();
        requireTenantIfScoped(tenantId);
        return ledgerRepository.sessions(tenantId, ProviderRequestContext.require());
    }

    private void requireTenantIfScoped(UUID tenantId) {
        if (tenantId != null && !tenantRepository.existsById(tenantId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
    }
}
