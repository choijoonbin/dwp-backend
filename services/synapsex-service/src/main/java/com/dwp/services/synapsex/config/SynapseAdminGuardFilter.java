package com.dwp.services.synapsex.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.client.AuthServerPermissionClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /synapse/admin/** RBAC 강제.
 * auth-server Feign으로 menu.admin.monitoring 권한 검증.
 * - GET: VIEW
 * - POST /detect/run: EXECUTE
 */
@Slf4j
@Component
@Order(-100)
@RequiredArgsConstructor
public class SynapseAdminGuardFilter extends OncePerRequestFilter {

    /** 배치 모니터링 API: menu.admin.batch-monitoring (통합 모니터링과 동일 권한 레벨) */
    private static final String RESOURCE_KEY = "menu.admin.batch-monitoring";

    private final AuthServerPermissionClient permissionClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/synapse/admin/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Long tenantId = parseLongHeader(request, HeaderConstants.X_TENANT_ID);
        Long userId = parseLongHeader(request, HeaderConstants.X_USER_ID);

        if (tenantId == null || userId == null) {
            log.warn("SynapseAdminGuard: missing tenantId or userId path={}", path);
            throw new BaseException(ErrorCode.AUTH_REQUIRED, "X-Tenant-ID, X-User-ID 헤더가 필요합니다.");
        }

        String permissionCode = "VIEW";
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.contains("/detect/run")) {
            permissionCode = "EXECUTE";
        }

        try {
            var result = permissionClient.check(tenantId, userId, RESOURCE_KEY, permissionCode);
            if (result == null || result.getData() == null || !result.getData()) {
                log.warn("SynapseAdminGuard: access denied tenantId={} userId={} path={} permission={}",
                        tenantId, userId, path, permissionCode);
                throw new BaseException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("SynapseAdminGuard: permission check failed path={}", path, e);
            throw new BaseException(ErrorCode.FORBIDDEN, "권한 검증에 실패했습니다.");
        }

        filterChain.doFilter(request, response);
    }

    private Long parseLongHeader(HttpServletRequest request, String headerName) {
        String val = request.getHeader(headerName);
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
