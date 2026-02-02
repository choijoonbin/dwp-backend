package com.dwp.services.synapsex.service.scope;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.scope.ScopeResolver;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 스코프 강제 적용. 쓰기/액션 시 대상 리소스가 스코프 내인지 검사.
 * 스코프 밖이면 403 OUT_OF_SCOPE + 감사로그(outcome=DENIED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeEnforcementService {

    private final ScopeResolver scopeResolver;
    private final AuditWriter auditWriter;

    /**
     * bukrs가 스코프 내인지 검사. 밖이면 BaseException(OUT_OF_SCOPE) throw.
     */
    public void requireBukrsInScope(Long tenantId, Long profileId, String bukrs,
                                    String resourceType, String resourceId, Long actorUserId,
                                    String ipAddress, String userAgent, String gatewayRequestId) {
        if (bukrs == null || bukrs.isBlank()) {
            return;  // bukrs 없으면 스코프 검사 생략 (case 등)
        }
        if (!scopeResolver.isBukrsInScope(tenantId, profileId, bukrs)) {
            auditWriter.logScopeDenied(tenantId, actorUserId != null ? actorUserId : 0L,
                    resourceType, resourceId, bukrs, null, "BUKRS out of scope",
                    ipAddress, userAgent, gatewayRequestId);
            throw new BaseException(ErrorCode.OUT_OF_SCOPE, "스코프에서 제외된 회사코드입니다: " + bukrs);
        }
    }

    /**
     * bukrs + currency가 스코프 내인지 검사.
     */
    public void requireBukrsAndCurrencyInScope(Long tenantId, Long profileId, String bukrs, String currency,
                                                String resourceType, String resourceId, Long actorUserId,
                                                String ipAddress, String userAgent, String gatewayRequestId) {
        if (bukrs != null && !bukrs.isBlank() && !scopeResolver.isBukrsInScope(tenantId, profileId, bukrs)) {
            auditWriter.logScopeDenied(tenantId, actorUserId != null ? actorUserId : 0L,
                    resourceType, resourceId, bukrs, currency, "BUKRS out of scope",
                    ipAddress, userAgent, gatewayRequestId);
            throw new BaseException(ErrorCode.OUT_OF_SCOPE, "스코프에서 제외된 회사코드입니다: " + bukrs);
        }
        if (currency != null && !currency.isBlank() && !scopeResolver.isCurrencyInScope(tenantId, profileId, currency)) {
            auditWriter.logScopeDenied(tenantId, actorUserId != null ? actorUserId : 0L,
                    resourceType, resourceId, bukrs, currency, "CURRENCY out of scope",
                    ipAddress, userAgent, gatewayRequestId);
            throw new BaseException(ErrorCode.OUT_OF_SCOPE, "스코프에서 제외된 통화입니다: " + currency);
        }
    }
}
