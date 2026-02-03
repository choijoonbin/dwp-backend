package com.dwp.services.synapsex.service.scope;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.scope.ScopeResolver;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 스코프 강제 적용. 쓰기/액션 시 대상 리소스가 스코프 내인지 검사.
 * 스코프 밖이면 403 OUT_OF_SCOPE + 감사로그(outcome=DENIED).
 * Drill-down: company/currency 파라미터 검증 및 자동 적용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeEnforcementService {

    private final ScopeResolver scopeResolver;
    private final AuditWriter auditWriter;

    /**
     * company 파라미터 검증 및 적용. 요청 없으면 scope 전체, 있으면 검증 후 반환.
     * scope 밖 company 요청 시 400 INVALID_INPUT_VALUE.
     */
    public List<String> resolveCompanyFilter(Long tenantId, Long profileId, List<String> requestedCompany) {
        Set<String> scopeBukrs = scopeResolver.resolveEnabledBukrs(tenantId, profileId);
        if (scopeBukrs.isEmpty()) return List.of();
        if (requestedCompany == null || requestedCompany.isEmpty()) {
            return List.copyOf(scopeBukrs);
        }
        Set<String> requested = requestedCompany.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
        Set<String> outOfScope = requested.stream()
                .filter(b -> !scopeBukrs.contains(b))
                .collect(Collectors.toSet());
        if (!outOfScope.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Scope 밖 회사코드: " + outOfScope + ". 허용 범위: " + scopeBukrs);
        }
        return List.copyOf(requested);
    }

    /**
     * currency 파라미터 검증 및 적용.
     */
    public List<String> resolveCurrencyFilter(Long tenantId, Long profileId, List<String> requestedCurrency) {
        Set<String> scopeCurrencies = scopeResolver.resolveEnabledCurrencies(tenantId, profileId);
        if (scopeCurrencies.isEmpty()) return List.of();
        if (requestedCurrency == null || requestedCurrency.isEmpty()) {
            return List.copyOf(scopeCurrencies);
        }
        Set<String> requested = requestedCurrency.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
        Set<String> outOfScope = requested.stream()
                .filter(c -> !scopeCurrencies.contains(c))
                .collect(Collectors.toSet());
        if (!outOfScope.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Scope 밖 통화: " + outOfScope);
        }
        return List.copyOf(requested);
    }

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
