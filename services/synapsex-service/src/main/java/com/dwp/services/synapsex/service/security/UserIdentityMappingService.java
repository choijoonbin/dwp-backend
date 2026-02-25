package com.dwp.services.synapsex.service.security;

import com.dwp.services.synapsex.client.AuthServerUserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SAP 사용자 식별자(usnam/principal) -> 내부 user_id 매핑 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentityMappingService {

    private final AuthServerUserClient authServerUserClient;

    public Long resolveUserId(Long tenantId, String loginId) {
        if (tenantId == null || loginId == null || loginId.isBlank()) {
            return null;
        }
        try {
            var response = authServerUserClient.resolveUserId(tenantId, loginId.trim());
            return response != null ? response.getData() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve userId by loginId tenantId={} loginId={}: {}", tenantId, loginId, e.getMessage());
            return null;
        }
    }
}
