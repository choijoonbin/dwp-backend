package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.*;
import com.dwp.services.auth.entity.*;
import com.dwp.services.auth.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private RoleMemberRepository roleMemberRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private LoginHistoryRepository loginHistoryRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("null")
    void setUp() {
        ReflectionTestUtils.setField(authService, "jwtSecret", "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256");
        ReflectionTestUtils.setField(authService, "tokenExpirationSeconds", 3600L);
    }

    @Test
    @DisplayName("로그인 성공 테스트")
    @SuppressWarnings("null")
    void loginSuccess() {
        // given
        LoginRequest request = new LoginRequest("admin", "admin1234!", "1");
        UserAccount account = UserAccount.builder()
                .userId(1L).tenantId(1L).status("ACTIVE").passwordHash("encodedHash").build();
        User user = User.builder().userId(1L).tenantId(1L).status("ACTIVE").build();

        when(userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));

        // when
        LoginResponse response = authService.login(request);

        // then
        assertNotNull(response.getAccessToken());
        assertEquals("1", response.getUserId());
        assertEquals("1", response.getTenantId());
        verify(loginHistoryRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("로그인 실패 테스트 - 비밀번호 불일치")
    @SuppressWarnings("null")
    void loginFailInvalidPassword() {
        // given
        LoginRequest request = new LoginRequest("admin", "wrongPassword", "1");
        UserAccount account = UserAccount.builder()
                .userId(1L).tenantId(1L).status("ACTIVE").passwordHash("encodedHash").build();

        when(userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // when & then
        assertThrows(BaseException.class, () -> authService.login(request));
        verify(loginHistoryRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("내 정보 조회 테스트")
    void getMeSuccess() {
        // given
        User user = User.builder().userId(1L).tenantId(1L).displayName("Admin").email("admin@test.com").build();
        Tenant tenant = Tenant.builder().tenantId(1L).code("dev").build();
        
        when(userRepository.findByUserIdAndTenantId(1L, 1L)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(1L, 1L)).thenReturn(List.of(1L));
        when(roleRepository.findByRoleIdIn(anyList())).thenReturn(List.of(Role.builder().code("ADMIN").build()));

        // when
        MeResponse response = authService.getMe(1L, 1L);

        // then
        assertEquals("Admin", response.getDisplayName());
        assertEquals("dev", response.getTenantCode());
        assertTrue(response.getRoles().contains("ADMIN"));
    }

    @Test
    @DisplayName("권한 조회 테스트")
    void getPermissionsSuccess() {
        // given
        when(roleMemberRepository.findRoleIdsByTenantIdAndUserId(1L, 1L)).thenReturn(List.of(1L));
        
        RolePermission rp = RolePermission.builder().resourceId(1L).permissionId(1L).effect("ALLOW").build();
        when(rolePermissionRepository.findByTenantIdAndRoleIdIn(1L, List.of(1L))).thenReturn(List.of(rp));
        
        Resource resource = Resource.builder().resourceId(1L).type("MENU").key("menu.test").name("Test Menu").build();
        Permission permission = Permission.builder().permissionId(1L).code("VIEW").name("View").build();
        
        when(resourceRepository.findByResourceIdIn(anyList())).thenReturn(List.of(resource));
        when(permissionRepository.findByPermissionIdIn(anyList())).thenReturn(List.of(permission));

        // when
        List<PermissionDTO> permissions = authService.getMyPermissions(1L, 1L);

        // then
        assertFalse(permissions.isEmpty());
        assertEquals("menu.test", permissions.get(0).getResourceKey());
        assertEquals("ALLOW", permissions.get(0).getEffect());
    }
}
