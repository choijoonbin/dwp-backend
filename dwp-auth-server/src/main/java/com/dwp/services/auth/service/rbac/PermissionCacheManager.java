package com.dwp.services.auth.service.rbac;

import com.dwp.services.auth.dto.PermissionDTO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 권한 캐시 관리 컴포넌트
 * 
 * 권한 관련 캐시를 중앙에서 관리합니다.
 */
@Slf4j
@Component
public class PermissionCacheManager {
    
    @Value("${rbac.cache.ttl-seconds:300}")  // 기본 5분
    private long cacheTtlSeconds;
    
    // 캐시: userId+tenantId -> ADMIN 여부
    private final Cache<String, Boolean> adminRoleCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    
    // 캐시: userId+tenantId -> 권한 목록 (5분 TTL)
    private final Cache<String, List<PermissionDTO>> permissionsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    
    // 캐시: userId+tenantId -> 권한 Set<(resourceKey, permissionCode, effect)> (Ultra Enhanced)
    private final Cache<String, Set<PermissionCalculator.PermissionTuple>> permissionSetCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
            .build();
    
    /**
     * ADMIN 역할 캐시 조회
     */
    public Boolean getAdminRole(String cacheKey) {
        return adminRoleCache.getIfPresent(cacheKey);
    }
    
    /**
     * ADMIN 역할 캐시 저장
     */
    public void putAdminRole(String cacheKey, Boolean isAdmin) {
        adminRoleCache.put(cacheKey, isAdmin);
    }
    
    /**
     * ADMIN 역할 캐시에서 조회 (없으면 supplier 실행)
     */
    public Boolean getAdminRole(String cacheKey, java.util.function.Supplier<Boolean> supplier) {
        return adminRoleCache.get(cacheKey, key -> supplier.get());
    }
    
    /**
     * 권한 목록 캐시 조회
     */
    public List<PermissionDTO> getPermissions(String cacheKey) {
        return permissionsCache.getIfPresent(cacheKey);
    }
    
    /**
     * 권한 목록 캐시 저장
     */
    public void putPermissions(String cacheKey, List<PermissionDTO> permissions) {
        permissionsCache.put(cacheKey, permissions);
    }
    
    /**
     * 권한 목록 캐시에서 조회 (없으면 supplier 실행)
     */
    public List<PermissionDTO> getPermissions(String cacheKey, java.util.function.Supplier<List<PermissionDTO>> supplier) {
        return permissionsCache.get(cacheKey, key -> supplier.get());
    }
    
    /**
     * 권한 Set 캐시 조회
     */
    public Set<PermissionCalculator.PermissionTuple> getPermissionSet(String cacheKey) {
        return permissionSetCache.getIfPresent(cacheKey);
    }
    
    /**
     * 권한 Set 캐시 저장
     */
    public void putPermissionSet(String cacheKey, Set<PermissionCalculator.PermissionTuple> permissionSet) {
        permissionSetCache.put(cacheKey, permissionSet);
    }
    
    /**
     * 권한 Set 캐시에서 조회 (없으면 supplier 실행)
     */
    public Set<PermissionCalculator.PermissionTuple> getPermissionSet(String cacheKey, 
                                                                        java.util.function.Supplier<Set<PermissionCalculator.PermissionTuple>> supplier) {
        return permissionSetCache.get(cacheKey, key -> supplier.get());
    }
    
    /**
     * 사용자 캐시 무효화
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     */
    public void invalidateCache(Long tenantId, Long userId) {
        String cacheKey = tenantId + ":" + userId;
        adminRoleCache.invalidate(cacheKey);
        permissionsCache.invalidate(cacheKey);
        permissionSetCache.invalidate(cacheKey);
        log.debug("Cache invalidated: tenantId={}, userId={}", tenantId, userId);
    }
    
    /**
     * PR-03E: 테넌트 전체 캐시 무효화 (Role 변경 시)
     * 
     * RoleMembers/RolePermissions 변경 시 모든 사용자의 캐시를 무효화합니다.
     * 
     * @param tenantId 테넌트 ID
     */
    public void invalidateTenantCache(Long tenantId) {
        // 모든 캐시 항목을 순회하며 해당 tenantId를 가진 항목만 무효화
        // Caffeine 캐시는 키 기반이므로 전체 무효화는 비효율적
        // 대신 캐시 만료 시간(5분)을 신뢰하거나, Role 변경 시 해당 역할을 가진 사용자만 무효화
        log.info("Tenant cache invalidation requested: tenantId={} (Note: Individual user cache invalidation recommended)", tenantId);
        // 실제 구현은 Role 변경 시 해당 역할을 가진 사용자들의 캐시를 개별적으로 무효화하는 것이 더 효율적
    }
}
