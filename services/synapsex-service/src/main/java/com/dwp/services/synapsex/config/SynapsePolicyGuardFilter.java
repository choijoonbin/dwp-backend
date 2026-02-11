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
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /synapse/policies/** RBAC 강제.
 * AuthServerPermissionClient로 menu.knowledge-policy.policies 권한 검증.
 * - GET: VIEW (OPERATOR 조회 가능)
 * - PATCH, POST, PUT: EDIT (SYNAPSEX_ADMIN, ADMIN만 수정 가능)
 *
 * test 프로파일에서는 비활성화.
 */
@Slf4j
@Component
@Order(-99)
@RequiredArgsConstructor
@Profile("!test")
public class SynapsePolicyGuardFilter extends OncePerRequestFilter {

    private static final String RESOURCE_KEY = "menu.knowledge-policy.policies";

    private final AuthServerPermissionClient permissionClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/synapse/policies")) {
            filterChain.doFilter(request, response);
            return;
        }

        Long tenantId = parseLongHeader(request, HeaderConstants.X_TENANT_ID);
        Long userId = parseLongHeader(request, HeaderConstants.X_USER_ID);

        if (tenantId == null || userId == null) {
            log.warn("SynapsePolicyGuard: missing tenantId or userId path={}", path);
            throw new BaseException(ErrorCode.AUTH_REQUIRED, "X-Tenant-ID, X-User-ID 헤더가 필요합니다.");
        }

        String method = request.getMethod();
        String permissionCode = ("PATCH".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))
                ? "EDIT"
                : "VIEW";

        try {
            var result = permissionClient.check(tenantId, userId, RESOURCE_KEY, permissionCode);
            if (result == null || result.getData() == null || !result.getData()) {
                log.warn("SynapsePolicyGuard: access denied tenantId={} userId={} path={} method={} permission={}",
                        tenantId, userId, path, method, permissionCode);
                throw new BaseException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("SynapsePolicyGuard: permission check failed path={}", path, e);
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
