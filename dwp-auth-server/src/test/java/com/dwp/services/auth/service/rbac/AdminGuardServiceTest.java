package com.dwp.services.auth.service.rbac;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.util.CodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * AdminGuardService 테스트 (BE Sub-Prompt A Enhanced)
 * 
 * 검증 항목:
 * - isAdmin() 메서드 동작 확인
 * - canAccess() 메서드 동작 확인 (확장 포인트)
 * - getPermissions() 메서드 동작 확인
 * - 캐시 동작 확인
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)  // 불필요한 stubbing 허용 (캐시로 인한 복잡성)
@DisplayName("AdminGuardService 테스트")
@SuppressWarnings("null")
class AdminGuardServiceTest {

    @Mock
    private RoleMemberRepository roleMemberRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private CodeResolver codeResolver;
    
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminGuardService adminGuardService;

    @BeforeEach
    void setUp() {
        // CodeResolver 기본 동작 설정 (모든 테스트에서 사용)
        // getAdminRoleCode()에서 getCodes()와 require()만 호출하므로 이 두 개만 Mock 설정
        when(codeResolver.getCodes("ROLE_CODE")).thenReturn(Arrays.asList("ADMIN", "USER", "MANAGER"));
        doNothing().when(codeResolver).require("ROLE_CODE", "ADMIN");
        // CodeResolver 기본 동작 (PERMISSION_CODE, EFFECT_TYPE)
        doNothing().when(codeResolver).require(anyString(), anyString());
    }

    @Test
    @DisplayName("isAdmin() - ADMIN 역할 있으면 true")
    void testIsAdminReturnsTrue() {
        // Given: ADMIN 역할을 가진 사용자
        Long tenantId = 1L;
        Long userId = 1L;
        Long roleId = 1L;

        Role adminRole = Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("ADMIN")
                .name("관리자")
                .build();

        // 캐시 무효화 (다른 테스트와 격리)
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(adminRole));

        // When
        boolean result = adminGuardService.isAdmin(tenantId, userId);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isAdmin() - ADMIN 역할 없으면 false")
    void testIsAdminReturnsFalse() {
        // Given: ADMIN 역할이 없는 사용자
        Long tenantId = 1L;
        Long userId = 2L;

        // 캐시 무효화 (다른 테스트와 격리)
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Collections.emptyList());

        // When
        boolean result = adminGuardService.isAdmin(tenantId, userId);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("requireAdminRole() - ADMIN 없으면 FORBIDDEN 예외")
    void testRequireAdminRoleThrowsException() {
        // Given: ADMIN 역할이 없는 사용자
        Long tenantId = 1L;
        Long userId = 3L;  // 다른 userId 사용 (캐시 격리)

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Collections.emptyList());

        // When/Then: FORBIDDEN 예외 발생
        assertThatThrownBy(() -> adminGuardService.requireAdminRole(tenantId, userId))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("canAccess() - ADMIN이면 모든 권한 허용")
    void testCanAccessAdminHasAllPermissions() {
        // Given: ADMIN 역할을 가진 사용자
        Long tenantId = 1L;
        Long userId = 4L;  // 다른 userId 사용 (캐시 격리)
        Long roleId = 1L;

        Role adminRole = Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("ADMIN")
                .build();

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(adminRole));

        // When: ADMIN이면 resourceKey/permissionCode와 관계없이 허용
        boolean result = adminGuardService.canAccess(userId, tenantId, "menu.admin.users", "USE");

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canAccess() - ADMIN 아니면 resourceKey+permissionCode 기반 검사")
    void testCanAccessNonAdminChecksPermission() {
        // Given: ADMIN이 아닌 사용자, 권한 있음
        Long tenantId = 1L;
        Long userId = 5L;  // 다른 userId 사용 (캐시 격리)
        Long roleId = 2L;
        Long resourceId = 10L;
        Long permissionId = 1L;

        Role userRole = Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("USER")
                .build();

        Resource resource = Resource.builder()
                .resourceId(resourceId)
                .tenantId(tenantId)
                .key("menu.admin.users")
                .name("사용자 관리")
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .code("USE")
                .name("사용")
                .build();

        RolePermission rolePermission = RolePermission.builder()
                .roleId(roleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect("ALLOW")
                .build();

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(userRole));
        when(resourceRepository.findByTenantIdAndKey(tenantId, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(permissionRepository.findByCode("USE"))
                .thenReturn(java.util.Optional.of(permission));
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(rolePermission));

        // When
        boolean result = adminGuardService.canAccess(userId, tenantId, "menu.admin.users", "USE");

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("getPermissions() - 권한 목록 조회")
    void testGetPermissions() {
        // Given: 권한이 있는 사용자
        Long tenantId = 1L;
        Long userId = 6L;  // 다른 userId 사용 (캐시 격리)
        Long roleId = 1L;
        Long resourceId = 10L;
        Long permissionId = 1L;

        RolePermission rolePermission = RolePermission.builder()
                .roleId(roleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect("ALLOW")
                .build();

        Resource resource = Resource.builder()
                .resourceId(resourceId)
                .tenantId(tenantId)
                .key("menu.admin.users")
                .name("사용자 관리")
                .type("MENU")
                .resourceCategory("MENU")
                .resourceKind("PAGE")
                .trackingEnabled(true)
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .code("USE")
                .name("사용")
                .build();

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(rolePermission));
        when(resourceRepository.findByResourceIdIn(anyList()))
                .thenReturn(Arrays.asList(resource));
        when(permissionRepository.findByPermissionIdIn(anyList()))
                .thenReturn(Arrays.asList(permission));

        // When
        List<PermissionDTO> permissions = adminGuardService.getPermissions(userId, tenantId);

        // Then
        assertThat(permissions).isNotEmpty();
        assertThat(permissions.get(0).getResourceKey()).isEqualTo("menu.admin.users");
        assertThat(permissions.get(0).getPermissionCode()).isEqualTo("USE");
    }

    @Test
    @DisplayName("invalidateCache() - 캐시 무효화")
    void testInvalidateCache() {
        // Given: 캐시에 데이터가 있는 상태
        Long tenantId = 1L;
        Long userId = 1L;

        // When: 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);

        // Then: 예외 없이 완료 (캐시 무효화 확인은 실제 동작으로 검증)
        // 캐시는 내부 구현이므로 직접 검증하기 어려움
    }
    
    @Test
    @DisplayName("canAccess() - DENY 우선: ALLOW+DENY 공존 시 DENY 승리 (403)")
    void testCanAccessDenyTakesPriority() {
        // Given: ADMIN이 아닌 사용자, ALLOW + DENY 공존
        Long tenantId = 1L;
        Long userId = 7L;  // 다른 userId 사용 (캐시 격리)
        Long roleId = 2L;
        Long resourceId = 10L;
        Long permissionId = 1L;

        Role userRole = Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("USER")
                .build();

        Resource resource = Resource.builder()
                .resourceId(resourceId)
                .tenantId(tenantId)
                .key("menu.admin.users")
                .name("사용자 관리")
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .code("VIEW")
                .name("조회")
                .build();

        // ALLOW와 DENY 모두 존재
        RolePermission allowPermission = RolePermission.builder()
                .roleId(roleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect("ALLOW")
                .build();
        
        RolePermission denyPermission = RolePermission.builder()
                .roleId(roleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect("DENY")
                .build();

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        User user = User.builder()
                .userId(userId)
                .tenantId(tenantId)
                .primaryDepartmentId(null)  // 부서 없음
                .build();
        
        when(userRepository.findByTenantIdAndUserId(tenantId, userId))
                .thenReturn(java.util.Optional.of(user));
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(userRole));
        when(resourceRepository.findByTenantIdAndKey(tenantId, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(permissionRepository.findByCode("VIEW"))
                .thenReturn(java.util.Optional.of(permission));
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(allowPermission, denyPermission));  // ALLOW와 DENY 모두

        // When: DENY 우선 정책 적용
        boolean result = adminGuardService.canAccess(userId, tenantId, "menu.admin.users", "VIEW");

        // Then: DENY 우선으로 거부
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("canAccess() - 부서 role 포함: 사용자 직접 role 없지만 부서 role 있으면 권한 부여")
    void testCanAccessWithDepartmentRole() {
        // Given: 사용자 직접 role 없지만 부서 role 있음
        Long tenantId = 1L;
        Long userId = 8L;  // 다른 userId 사용 (캐시 격리)
        Long departmentId = 100L;
        Long departmentRoleId = 3L;
        Long resourceId = 10L;
        Long permissionId = 1L;

        Role departmentRole = Role.builder()
                .roleId(departmentRoleId)
                .tenantId(tenantId)
                .code("MANAGER")
                .build();

        Resource resource = Resource.builder()
                .resourceId(resourceId)
                .tenantId(tenantId)
                .key("menu.admin.users")
                .name("사용자 관리")
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .code("VIEW")
                .name("조회")
                .build();

        RolePermission rolePermission = RolePermission.builder()
                .roleId(departmentRoleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect("ALLOW")
                .build();

        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        User user = User.builder()
                .userId(userId)
                .tenantId(tenantId)
                .primaryDepartmentId(departmentId)  // 부서 있음
                .build();
        
        when(userRepository.findByTenantIdAndUserId(tenantId, userId))
                .thenReturn(java.util.Optional.of(user));
        // 사용자 직접 role 없음
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Collections.emptyList());
        // 부서 role 있음
        when(roleMemberRepository.findAllRoleIdsByTenantIdAndUserIdAndDepartmentId(
                tenantId, userId, departmentId))
                .thenReturn(Arrays.asList(departmentRoleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(departmentRoleId)))
                .thenReturn(Arrays.asList(departmentRole));
        when(resourceRepository.findByTenantIdAndKey(tenantId, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(permissionRepository.findByCode("VIEW"))
                .thenReturn(java.util.Optional.of(permission));
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, Arrays.asList(departmentRoleId)))
                .thenReturn(Arrays.asList(rolePermission));

        // When: 부서 role 포함하여 권한 검사
        boolean result = adminGuardService.canAccess(userId, tenantId, "menu.admin.users", "VIEW");

        // Then: 부서 role로 인해 권한 부여됨
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("canAccess() - 권한 없으면 거부")
    void testCanAccessNoPermissionDenied() {
        // Given: ADMIN이 아닌 사용자, 권한 없음
        Long tenantId = 1L;
        Long userId = 9L;  // 다른 userId 사용 (캐시 격리)
        Long roleId = 2L;
        Long resourceId = 10L;
        Long permissionId = 1L;

        Role userRole = Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("USER")
                .build();

        Resource resource = Resource.builder()
                .resourceId(resourceId)
                .tenantId(tenantId)
                .key("menu.admin.users")
                .name("사용자 관리")
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .code("VIEW")
                .name("조회")
                .build();

        // 권한 없음 (rolePermission 없음)
        // 캐시 무효화
        adminGuardService.invalidateCache(tenantId, userId);
        
        User user = User.builder()
                .userId(userId)
                .tenantId(tenantId)
                .primaryDepartmentId(null)
                .build();
        
        when(userRepository.findByTenantIdAndUserId(tenantId, userId))
                .thenReturn(java.util.Optional.of(user));
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Arrays.asList(roleId));
        when(roleRepository.findByRoleIdIn(Arrays.asList(roleId)))
                .thenReturn(Arrays.asList(userRole));
        when(resourceRepository.findByTenantIdAndKey(tenantId, "menu.admin.users"))
                .thenReturn(Arrays.asList(resource));
        when(permissionRepository.findByCode("VIEW"))
                .thenReturn(java.util.Optional.of(permission));
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, Arrays.asList(roleId)))
                .thenReturn(Collections.emptyList());  // 권한 없음

        // When
        boolean result = adminGuardService.canAccess(userId, tenantId, "menu.admin.users", "VIEW");

        // Then: 권한 없어서 거부
        assertThat(result).isFalse();
    }
}
