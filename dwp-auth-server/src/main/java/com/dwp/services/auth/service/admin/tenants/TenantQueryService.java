package com.dwp.services.auth.service.admin.tenants;

import com.dwp.services.auth.dto.admin.TenantSummaryDto;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tenant Selector용 조회 서비스.
 * 로그인한 사용자가 속한 Tenant 목록만 반환.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantQueryService {

    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;

    /**
     * 사용자가 접근 가능한 Tenant 목록 반환.
     * UserAccount의 tenant_id 기준으로 distinct 조회.
     */
    public List<TenantSummaryDto> getTenantsForUser(Long userId) {
        List<Long> tenantIds = userAccountRepository.findByUserId(userId).stream()
                .map(ua -> ua.getTenantId())
                .distinct()
                .toList();
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        return tenantRepository.findByTenantIdInAndStatus(tenantIds, "ACTIVE").stream()
                .map(TenantSummaryDto::from)
                .collect(Collectors.toList());
    }
}
