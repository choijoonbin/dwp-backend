package com.dwp.services.synapsex.service.security;

import com.dwp.services.synapsex.client.AuthServerPermissionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 전표/케이스 소유권 조회 제어용 접근 판정.
 * - ADMIN 역할: 전체 조회 허용
 * - 그 외: 본인 소유 데이터만 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnershipAccessService {

    private final AuthServerPermissionClient permissionClient;

    public boolean isAdmin(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return false;
        }
        try {
            var response = permissionClient.isAdmin(tenantId, userId);
            return response != null && Boolean.TRUE.equals(response.getData());
        } catch (Exception e) {
            log.warn("Failed to check admin role tenantId={} userId={}: {}", tenantId, userId, e.getMessage());
            return false;
        }
    }
}
