package com.dwp.services.synapsex.config;

/**
 * Aura Feign 호출 시 배치/비동기 등 요청 컨텍스트가 없을 때 테넌트 ID를 전달하기 위한 스레드 로컬.
 * RequestInterceptor에서 X-Internal-Service-Key 주입 시 X-Tenant-ID 보강에 사용.
 */
public final class AuraTenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }

    private AuraTenantContext() {}
}
