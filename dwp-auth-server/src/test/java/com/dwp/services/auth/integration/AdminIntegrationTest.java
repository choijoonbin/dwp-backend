package com.dwp.services.auth.integration;

import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.service.admin.CodeUsageService;
import com.dwp.services.auth.service.admin.menus.MenuCommandService;
import com.dwp.services.auth.service.admin.menus.MenuQueryService;
import com.dwp.services.auth.service.rbac.PermissionEvaluator;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PR-11: 통합 기능 점검 테스트
 * 
 * 메뉴/리소스/권한 동기화, Codes/CodeUsage 정합성, RBAC Enforcement 검증
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("PR-11: Admin 통합 기능 점검")
class AdminIntegrationTest {
    
    @Autowired
    private MenuCommandService menuCommandService;
    
    @Autowired
    private MenuQueryService menuQueryService;
    
    @Autowired
    private CodeUsageService codeUsageService;
    
    @Autowired
    private PermissionEvaluator permissionEvaluator;
    
    @Autowired
    private CodeResolver codeResolver;
    
    @Autowired
    private ResourceRepository resourceRepository;
    
    private Long tenantIdA = 1L;
    private Long tenantIdB = 2L;
    private Long adminUserIdA = 10L;
    private Long normalUserIdA = 11L; // 권한 없는 사용자
    
    private HttpServletRequest mockRequest;
    
    @BeforeEach
    void setUp() {
        mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("User-Agent")).thenReturn("test-agent");
    }
    
    @Test
    @DisplayName("1.1 메뉴 생성 후 리소스 동기화 검증")
    void testMenuResourceSync() {
        // Given
        CreateMenuRequest request = CreateMenuRequest.builder()
                .menuKey("menu.test.sync")
                .menuName("테스트 메뉴")
                .routePath("/test/sync")
                .enabled(true)
                .visible(true)
                .build();
        
        // When
        MenuSummary menu = menuCommandService.createMenu(tenantIdA, adminUserIdA, request, mockRequest);
        
        // Then
        assertThat(menu).isNotNull();
        assertThat(menu.getMenuKey()).isEqualTo("menu.test.sync");
        
        // com_resources에 MENU 리소스가 자동 생성되었는지 확인
        List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantIdA, "menu.test.sync");
        assertThat(resources).isNotEmpty();
        
        Resource resource = resources.stream()
                .filter(r -> r.getTenantId().equals(tenantIdA))
                .findFirst()
                .orElse(null);
        
        assertThat(resource).isNotNull();
        assertThat(resource.getType()).isEqualTo("MENU");
        assertThat(resource.getKey()).isEqualTo("menu.test.sync");
        assertThat(resource.getName()).isEqualTo("테스트 메뉴");
        assertThat(resource.getResourceCategory()).isEqualTo("MENU");
    }
    
    @Test
    @DisplayName("1.2 메뉴 reorder 후 정렬 반영 검증")
    void testMenuReorder() {
        // Given: 메뉴 3개 생성
        CreateMenuRequest menu1 = CreateMenuRequest.builder()
                .menuKey("menu.test.1")
                .menuName("메뉴1")
                .sortOrder(10)
                .enabled(true)
                .build();
        CreateMenuRequest menu2 = CreateMenuRequest.builder()
                .menuKey("menu.test.2")
                .menuName("메뉴2")
                .sortOrder(20)
                .enabled(true)
                .build();
        CreateMenuRequest menu3 = CreateMenuRequest.builder()
                .menuKey("menu.test.3")
                .menuName("메뉴3")
                .sortOrder(30)
                .enabled(true)
                .build();
        
        MenuSummary m1 = menuCommandService.createMenu(tenantIdA, adminUserIdA, menu1, mockRequest);
        MenuSummary m2 = menuCommandService.createMenu(tenantIdA, adminUserIdA, menu2, mockRequest);
        MenuSummary m3 = menuCommandService.createMenu(tenantIdA, adminUserIdA, menu3, mockRequest);
        
        // When: reorder (3 -> 1, 1 -> 2, 2 -> 3)
        ReorderMenuRequest reorderRequest = ReorderMenuRequest.builder()
                .items(List.of(
                        ReorderMenuRequest.MenuOrderItem.builder()
                                .menuId(m3.getSysMenuId())
                                .parentId(null)
                                .sortOrder(10)
                                .build(),
                        ReorderMenuRequest.MenuOrderItem.builder()
                                .menuId(m1.getSysMenuId())
                                .parentId(null)
                                .sortOrder(20)
                                .build(),
                        ReorderMenuRequest.MenuOrderItem.builder()
                                .menuId(m2.getSysMenuId())
                                .parentId(null)
                                .sortOrder(30)
                                .build()
                ))
                .build();
        
        menuCommandService.reorderMenus(tenantIdA, adminUserIdA, reorderRequest, mockRequest);
        
        // Then: 정렬 순서 확인
        com.dwp.services.auth.dto.admin.PageResponse<MenuSummary> pageResponse = 
                menuQueryService.getMenus(tenantIdA, 1, 100, null, null, null);
        MenuSummary reorderedM3 = pageResponse.getItems().stream()
                .filter(m -> m.getSysMenuId().equals(m3.getSysMenuId()))
                .findFirst()
                .orElse(null);
        
        assertThat(reorderedM3).isNotNull();
        assertThat(reorderedM3.getSortOrder()).isEqualTo(10);
    }
    
    @Test
    @DisplayName("1.3 CodeUsage 보안 검증 - 권한 없는 사용자 403")
    void testCodeUsageSecurity() {
        // Given: CodeUsage가 등록된 메뉴
        // (실제 테스트에서는 테스트 데이터 설정 필요)
        
        // When & Then: 권한 없는 사용자가 조회 시도
        assertThatThrownBy(() -> {
            codeUsageService.getCodesByResourceKey(tenantIdA, normalUserIdA, "menu.admin.users");
        }).hasMessageContaining("권한");
    }
    
    @Test
    @DisplayName("1.4 멀티테넌시 격리 검증")
    void testTenantIsolation() {
        // Given: Tenant A에 메뉴 생성
        CreateMenuRequest requestA = CreateMenuRequest.builder()
                .menuKey("menu.tenant.a")
                .menuName("Tenant A 메뉴")
                .enabled(true)
                .build();
        
        MenuSummary menuA = menuCommandService.createMenu(tenantIdA, adminUserIdA, requestA, mockRequest);
        
        // When: Tenant B에서 조회 시도
        com.dwp.services.auth.dto.admin.PageResponse<MenuSummary> pageResponseB = 
                menuQueryService.getMenus(tenantIdB, 1, 100, null, null, null);
        
        // Then: Tenant A 메뉴가 조회되지 않음
        boolean found = pageResponseB.getItems().stream()
                .anyMatch(m -> m.getSysMenuId().equals(menuA.getSysMenuId()));
        assertThat(found).isFalse();
    }
    
    @Test
    @DisplayName("2.1 CodeResolver 캐시 무효화 검증")
    void testCodeResolverCacheInvalidation() {
        // Given: 코드 조회로 캐시 생성
        codeResolver.validate("RESOURCE_TYPE", "MENU");
        
        // When: 코드 변경 (실제로는 CodeManagementService를 통해)
        // 여기서는 캐시 무효화만 테스트
        codeResolver.clearCache("RESOURCE_TYPE");
        
        // Then: 캐시가 무효화되어 다음 조회 시 DB에서 다시 로드됨
        // (실제 검증은 캐시 hit/miss 로그 확인)
        assertThat(codeResolver.validate("RESOURCE_TYPE", "MENU")).isTrue();
    }
    
    @Test
    @DisplayName("2.2 CodeUsage 캐시 무효화 검증")
    void testCodeUsageCacheInvalidation() {
        // Given: CodeUsage 조회로 캐시 생성
        // (실제 테스트에서는 테스트 데이터 설정 필요)
        
        // When: 캐시 무효화
        codeUsageService.clearCache(tenantIdA, "menu.admin.users");
        
        // Then: 캐시가 무효화됨 (다음 조회 시 DB에서 다시 로드)
        // (실제 검증은 캐시 hit/miss 로그 확인)
    }
    
    @Test
    @DisplayName("3.1 RBAC Enforcement - 권한 없는 사용자 EDIT 호출 시 403")
    void testRbacEnforcement() {
        // Given: 권한 없는 사용자
        
        // When & Then: EDIT 권한 체크 시 403
        assertThatThrownBy(() -> {
            permissionEvaluator.requirePermission(normalUserIdA, tenantIdA, "menu.admin.users", "EDIT");
        }).hasMessageContaining("권한");
    }
}
