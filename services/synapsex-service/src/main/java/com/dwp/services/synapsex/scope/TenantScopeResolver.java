package com.dwp.services.synapsex.scope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Tenant Scope 해석기. FI docs, Open items, Cases, Actions 쿼리 시 적용.
 * Profile-scoped ScopeResolver를 사용하며, profileId 없으면 테넌트 기본 프로파일 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantScopeResolver {

    private final ScopeResolver scopeResolver;

    /**
     * 활성화된 회사코드(BUKRS) Set. 기본 프로파일 기준.
     */
    public Set<String> resolveEnabledBukrs(Long tenantId) {
        return scopeResolver.resolveEnabledBukrs(tenantId, null);
    }

    /**
     * 활성화된 회사코드(BUKRS) Set. 지정 프로파일 기준.
     */
    public Set<String> resolveEnabledBukrs(Long tenantId, Long profileId) {
        return scopeResolver.resolveEnabledBukrs(tenantId, profileId);
    }

    /**
     * 활성화된 통화(WAERS) Set. 기본 프로파일 기준.
     */
    public Set<String> resolveEnabledWaers(Long tenantId) {
        return scopeResolver.resolveEnabledCurrencies(tenantId, null);
    }

    /**
     * 활성화된 통화(WAERS) Set. 지정 프로파일 기준.
     */
    public Set<String> resolveEnabledWaers(Long tenantId, Long profileId) {
        return scopeResolver.resolveEnabledCurrencies(tenantId, profileId);
    }

    /**
     * bukrs가 scope 내에 있는지 검사.
     */
    public boolean isBukrsInScope(Long tenantId, String bukrs) {
        return scopeResolver.isBukrsInScope(tenantId, null, bukrs);
    }

    /**
     * waers가 scope 내에 있는지 검사.
     */
    public boolean isWaersInScope(Long tenantId, String waers) {
        return scopeResolver.isCurrencyInScope(tenantId, null, waers);
    }
}
