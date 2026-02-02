package com.dwp.services.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin 엔드포인트 정책 레지스트리 (Ultra Enhanced: Endpoint Policy Registry)
 * 
 * HTTP Endpoint 단위로 필요한 권한을 명시적으로 맵핑합니다.
 * 
 * 사용 예시:
 * - GET /api/admin/users → menu.admin.users + VIEW
 * - POST /api/admin/users → menu.admin.users + EDIT
 * - DELETE /api/admin/users/{id} → menu.admin.users + EXECUTE
 */
@Slf4j
@Component
public class AdminEndpointPolicyRegistry {
    
    /**
     * 엔드포인트별 필요한 권한 정의
     */
    @Getter
    @Builder
    public static class RequiredPermission {
        private String resourceKey;      // 예: menu.admin.users
        private String permissionCode;   // 예: VIEW, USE, EDIT, EXECUTE
        private String effectRequired;  // 기본 "ALLOW" (DENY 우선 정책은 추후)
    }
    
    /**
     * 정책 키: method + pathPattern
     */
    private static class PolicyKey {
        private final String method;
        private final Pattern pathPattern;
        
        public PolicyKey(String method, Pattern pathPattern) {
            this.method = method;
            this.pathPattern = pathPattern;
        }
        
        public boolean matches(String method, String path) {
            return this.method.equalsIgnoreCase(method) && pathPattern.matcher(path).matches();
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PolicyKey policyKey = (PolicyKey) o;
            return method.equals(policyKey.method) && pathPattern.pattern().equals(policyKey.pathPattern.pattern());
        }
        
        @Override
        public int hashCode() {
            return method.hashCode() * 31 + pathPattern.pattern().hashCode();
        }
    }
    
    // 정책 모드: RELAX (기본, admin만 통과) 또는 STRICT (policy 없으면 deny)
    public enum PolicyMode {
        RELAX,  // policy 없으면 admin만 통과
        STRICT  // policy 없으면 deny (TODO)
    }
    
    private PolicyMode mode = PolicyMode.RELAX;  // 기본값
    
    // 엔드포인트 정책 매핑: (method, pathPattern) -> RequiredPermission[]
    private final Map<PolicyKey, List<RequiredPermission>> endpointPolicies = new HashMap<>();
    
    /**
     * 엔드포인트 정책 등록
     * 
     * @param method HTTP 메서드 (GET, POST, PATCH, DELETE 등)
     * @param pathPattern 경로 패턴 (정규식, 예: "^/api/admin/users$")
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @param permissionCode 권한 코드 (예: VIEW, USE, EDIT, EXECUTE)
     */
    public void registerPolicy(String method, String pathPattern, String resourceKey, String permissionCode) {
        Pattern pattern = Pattern.compile(pathPattern);
        PolicyKey key = new PolicyKey(method, pattern);
        
        RequiredPermission permission = RequiredPermission.builder()
                .resourceKey(resourceKey)
                .permissionCode(permissionCode)
                .effectRequired("ALLOW")  // 기본값
                .build();
        
        endpointPolicies.computeIfAbsent(key, k -> new ArrayList<>()).add(permission);
        log.debug("Policy registered: {} {} -> {} {}", method, pathPattern, resourceKey, permissionCode);
    }
    
    /**
     * 엔드포인트에 필요한 권한 조회
     * 
     * @param method HTTP 메서드
     * @param path 요청 경로
     * @return 필요한 권한 목록 (없으면 빈 리스트)
     */
    public List<RequiredPermission> findPolicies(String method, String path) {
        return endpointPolicies.entrySet().stream()
                .filter(entry -> entry.getKey().matches(method, path))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }
    
    /**
     * 정책 모드 설정
     */
    public void setMode(PolicyMode mode) {
        this.mode = mode;
        log.info("Policy mode changed to: {}", mode);
    }
    
    /**
     * 현재 정책 모드 조회
     */
    public PolicyMode getMode() {
        return mode;
    }
    
    /**
     * 정책 초기화 (초기 세트 등록)
     * 
     * 최소 운영 수준 기준으로 반드시 등록해야 하는 정책들:
     * - Code Management
     * - Code Usage Management
     * - Users
     * - Roles
     * - Resources
     */
    @PostConstruct
    public void initializePolicies() {
        log.info("Initializing Endpoint Policy Registry...");
        
        // ========================================
        // Code Management
        // ========================================
        registerPolicy("GET", "^/api/admin/codes/groups$", "menu.admin.codes", "VIEW");
        registerPolicy("GET", "^/api/admin/codes/groups/\\d+$", "menu.admin.codes", "VIEW");
        registerPolicy("POST", "^/api/admin/codes/groups$", "menu.admin.codes", "EDIT");
        registerPolicy("PATCH", "^/api/admin/codes/groups/\\d+$", "menu.admin.codes", "EDIT");
        registerPolicy("DELETE", "^/api/admin/codes/groups/\\d+$", "menu.admin.codes", "EXECUTE");
        
        registerPolicy("GET", "^/api/admin/codes$", "menu.admin.codes", "VIEW");
        registerPolicy("GET", "^/api/admin/codes/\\d+$", "menu.admin.codes", "VIEW");
        registerPolicy("POST", "^/api/admin/codes$", "menu.admin.codes", "EDIT");
        registerPolicy("PATCH", "^/api/admin/codes/\\d+$", "menu.admin.codes", "EDIT");
        registerPolicy("PUT", "^/api/admin/codes/\\d+$", "menu.admin.codes", "EDIT");  // PUT도 지원
        registerPolicy("DELETE", "^/api/admin/codes/\\d+$", "menu.admin.codes", "EXECUTE");
        
        // ========================================
        // Code Usage Management
        // ========================================
        registerPolicy("GET", "^/api/admin/code-usages$", "menu.admin.code-usages", "VIEW");
        registerPolicy("GET", "^/api/admin/code-usages/\\d+$", "menu.admin.code-usages", "VIEW");
        registerPolicy("POST", "^/api/admin/code-usages$", "menu.admin.code-usages", "EDIT");
        registerPolicy("PATCH", "^/api/admin/code-usages/\\d+$", "menu.admin.code-usages", "EDIT");
        registerPolicy("PUT", "^/api/admin/code-usages/\\d+$", "menu.admin.code-usages", "EDIT");  // PUT도 지원
        registerPolicy("DELETE", "^/api/admin/code-usages/\\d+$", "menu.admin.code-usages", "EXECUTE");
        
        // ========================================
        // Users
        // ========================================
        registerPolicy("GET", "^/api/admin/users$", "menu.admin.users", "VIEW");
        registerPolicy("GET", "^/api/admin/users/\\d+$", "menu.admin.users", "VIEW");
        registerPolicy("POST", "^/api/admin/users$", "menu.admin.users", "EDIT");
        registerPolicy("PATCH", "^/api/admin/users/\\d+$", "menu.admin.users", "EDIT");
        registerPolicy("PUT", "^/api/admin/users/\\d+$", "menu.admin.users", "EDIT");  // PUT도 지원
        registerPolicy("DELETE", "^/api/admin/users/\\d+$", "menu.admin.users", "EXECUTE");
        
        // ========================================
        // Roles
        // ========================================
        registerPolicy("GET", "^/api/admin/roles$", "menu.admin.roles", "VIEW");
        registerPolicy("GET", "^/api/admin/roles/\\d+$", "menu.admin.roles", "VIEW");
        registerPolicy("POST", "^/api/admin/roles$", "menu.admin.roles", "EDIT");
        registerPolicy("PATCH", "^/api/admin/roles/\\d+$", "menu.admin.roles", "EDIT");
        registerPolicy("PUT", "^/api/admin/roles/\\d+$", "menu.admin.roles", "EDIT");  // PUT도 지원
        registerPolicy("DELETE", "^/api/admin/roles/\\d+$", "menu.admin.roles", "EXECUTE");
        
        // Role Members
        registerPolicy("GET", "^/api/admin/roles/\\d+/members$", "menu.admin.roles", "VIEW");
        registerPolicy("POST", "^/api/admin/roles/\\d+/members$", "menu.admin.roles", "EDIT");
        registerPolicy("DELETE", "^/api/admin/roles/\\d+/members/\\d+$", "menu.admin.roles", "EDIT");
        
        // Role Permissions Bulk
        registerPolicy("GET", "^/api/admin/roles/\\d+/permissions$", "menu.admin.roles", "VIEW");
        registerPolicy("PUT", "^/api/admin/roles/\\d+/permissions$", "menu.admin.roles", "EDIT");
        
        // ========================================
        // Resources
        // ========================================
        registerPolicy("GET", "^/api/admin/resources$", "menu.admin.resources", "VIEW");
        registerPolicy("GET", "^/api/admin/resources/\\d+$", "menu.admin.resources", "VIEW");
        registerPolicy("GET", "^/api/admin/resources/tree$", "menu.admin.resources", "VIEW");  // P1-7
        registerPolicy("POST", "^/api/admin/resources$", "menu.admin.resources", "EDIT");
        registerPolicy("PATCH", "^/api/admin/resources/\\d+$", "menu.admin.resources", "EDIT");
        registerPolicy("PUT", "^/api/admin/resources/\\d+$", "menu.admin.resources", "EDIT");  // PUT도 지원
        registerPolicy("DELETE", "^/api/admin/resources/\\d+$", "menu.admin.resources", "EXECUTE");
        
        // ========================================
        // P1-7: Menus (menu.admin.menus)
        // ========================================
        registerPolicy("GET", "^/api/admin/menus$", "menu.admin.menus", "VIEW");
        registerPolicy("GET", "^/api/admin/menus/tree$", "menu.admin.menus", "VIEW");
        registerPolicy("POST", "^/api/admin/menus$", "menu.admin.menus", "EDIT");
        registerPolicy("PATCH", "^/api/admin/menus/\\d+$", "menu.admin.menus", "EDIT");
        registerPolicy("DELETE", "^/api/admin/menus/\\d+$", "menu.admin.menus", "EXECUTE");
        registerPolicy("PUT", "^/api/admin/menus/reorder$", "menu.admin.menus", "EXECUTE");
        
        // ========================================
        // Tenant Selector (menu.admin.users VIEW로 통과 - Admin 진입 시 Tenant 목록 필요)
        // ========================================
        registerPolicy("GET", "^/api/admin/tenants$", "menu.admin.users", "VIEW");

        // ========================================
        // P1-7: Audit Logs (menu.admin.audit)
        // ========================================
        registerPolicy("GET", "^/api/admin/audit-logs$", "menu.admin.audit", "VIEW");
        registerPolicy("GET", "^/api/admin/audit-logs/\\d+$", "menu.admin.audit", "VIEW");
        registerPolicy("GET", "^/api/admin/audit-logs/export$", "menu.admin.audit", "VIEW");  // P1-9
        registerPolicy("POST", "^/api/admin/audit-logs/export$", "menu.admin.audit", "VIEW");
        
        log.info("Endpoint Policy Registry initialized with {} policies", endpointPolicies.size());
    }
}
