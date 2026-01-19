package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 메뉴 Repository
 */
@Repository
public interface MenuRepository extends JpaRepository<Menu, Long> {
    
    /**
     * 테넌트별 메뉴 목록 조회 (활성화된 메뉴만)
     */
    @Query("SELECT m FROM Menu m WHERE m.tenantId = :tenantId AND m.isEnabled = 'Y' AND m.isVisible = 'Y' ORDER BY m.sortOrder ASC")
    List<Menu> findByTenantIdAndActive(@Param("tenantId") Long tenantId);
    
    /**
     * 테넌트별 메뉴 키 목록으로 조회
     */
    @Query("SELECT m FROM Menu m WHERE m.tenantId = :tenantId AND m.menuKey IN :menuKeys AND m.isEnabled = 'Y' AND m.isVisible = 'Y' ORDER BY m.sortOrder ASC")
    List<Menu> findByTenantIdAndMenuKeyIn(@Param("tenantId") Long tenantId, @Param("menuKeys") List<String> menuKeys);
    
    /**
     * 테넌트와 메뉴 키로 단일 조회
     */
    Optional<Menu> findByTenantIdAndMenuKey(Long tenantId, String menuKey);
    
    /**
     * 테넌트별 루트 메뉴 조회 (parent_menu_key가 NULL인 메뉴)
     */
    @Query("SELECT m FROM Menu m WHERE m.tenantId = :tenantId AND m.parentMenuKey IS NULL AND m.isEnabled = 'Y' AND m.isVisible = 'Y' ORDER BY m.sortOrder ASC")
    List<Menu> findRootMenusByTenantId(@Param("tenantId") Long tenantId);
    
    /**
     * 특정 부모 메뉴의 자식 메뉴 조회
     */
    @Query("SELECT m FROM Menu m WHERE m.tenantId = :tenantId AND m.parentMenuKey = :parentMenuKey AND m.isEnabled = 'Y' AND m.isVisible = 'Y' ORDER BY m.sortOrder ASC")
    List<Menu> findByTenantIdAndParentMenuKey(@Param("tenantId") Long tenantId, @Param("parentMenuKey") String parentMenuKey);
}
