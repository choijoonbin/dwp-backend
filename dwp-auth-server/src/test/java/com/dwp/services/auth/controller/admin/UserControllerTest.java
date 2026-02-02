package com.dwp.services.auth.controller.admin;

import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.users.UserManagementService;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserController 테스트
 */
@WebMvcTest(value = UserController.class, excludeAutoConfiguration = RedisAutoConfiguration.class)
@SuppressWarnings({"null", "removal"})
class UserControllerTest {
    
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public CodeResolver codeResolver() {
            CodeResolver resolver = org.mockito.Mockito.mock(CodeResolver.class);
            // 기본 동작: 모든 코드를 유효하다고 가정
            org.mockito.Mockito.when(resolver.validate(org.mockito.ArgumentMatchers.anyString(), 
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            org.mockito.Mockito.doNothing().when(resolver).require(org.mockito.ArgumentMatchers.anyString(), 
                    org.mockito.ArgumentMatchers.anyString());
            return resolver;
        }
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private UserManagementService userManagementService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    @DisplayName("사용자 목록 조회 - 페이징 + keyword")
    @WithMockUser(roles = "ADMIN")
    void getUsers_WithPagingAndKeyword_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        int page = 1;
        int size = 20;
        String keyword = "홍길동";
        
        UserSummary user1 = UserSummary.builder()
                .comUserId(1L)
                .tenantId(tenantId)
                .userName("홍길동")
                .email("hong@example.com")
                .status("ACTIVE")
                .lastLoginAt(LocalDateTime.now().minusDays(1))
                .build();
        
        PageResponse<UserSummary> pageResponse = PageResponse.<UserSummary>builder()
                .items(Arrays.asList(user1))
                .page(page)
                .size(size)
                .totalItems(1L)
                .totalPages(1)
                .build();
        
        when(userManagementService.getUsers(eq(tenantId), eq(page), eq(size), eq(keyword),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(pageResponse);
        
        // When & Then
        mockMvc.perform(get("/admin/users")
                        .header("X-Tenant-ID", tenantId)
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].comUserId").value(1L))
                .andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
                .andExpect(jsonPath("$.data.items[0].lastLoginAt").exists());
    }
    
    @Test
    @DisplayName("사용자 생성 - LOCAL 계정 + BCrypt 검증")
    @WithMockUser(roles = "ADMIN")
    void createUser_WithLocalAccount_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateUserRequest.LocalAccountRequest localAccount = 
                CreateUserRequest.LocalAccountRequest.builder()
                        .principal("testuser")
                        .password("password123!")
                        .build();
        
        CreateUserRequest request = CreateUserRequest.builder()
                .userName("테스트 사용자")
                .email("test@example.com")
                .departmentId(1L)
                .status("ACTIVE")
                .localAccount(localAccount)
                .build();
        
        UserDetail userDetail = UserDetail.builder()
                .comUserId(1L)
                .tenantId(tenantId)
                .userName("테스트 사용자")
                .email("test@example.com")
                .status("ACTIVE")
                .build();
        
        when(userManagementService.createUser(eq(tenantId), anyLong(), any(CreateUserRequest.class), any()))
                .thenReturn(userDetail);
        
        // When & Then
        mockMvc.perform(post("/admin/users")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comUserId").value(1L))
                .andExpect(jsonPath("$.data.userName").value("테스트 사용자"));
        
        verify(userManagementService, times(1)).createUser(eq(tenantId), anyLong(), any(CreateUserRequest.class), any());
    }
    
    @Test
    @DisplayName("사용자 역할 업데이트 - replace=true")
    @WithMockUser(roles = "ADMIN")
    void updateUserRoles_WithReplaceTrue_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long userId = 1L;
        
        UpdateUserRolesRequest request = UpdateUserRolesRequest.builder()
                .roleIds(Arrays.asList(1L, 2L, 3L))
                .replace(true)
                .build();
        
        doNothing().when(userManagementService).updateUserRoles(
                eq(tenantId), anyLong(), eq(userId), any(UpdateUserRolesRequest.class), any());
        
        // When & Then
        mockMvc.perform(put("/admin/users/{comUserId}/roles", userId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        verify(userManagementService, times(1)).updateUserRoles(
                eq(tenantId), anyLong(), eq(userId), any(UpdateUserRolesRequest.class), any());
    }
    
    @Test
    @DisplayName("사용자 역할 추가")
    @WithMockUser(roles = "ADMIN")
    void addUserRole_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long userId = 1L;
        Long roleId = 2L;
        
        Map<String, Long> request = new HashMap<>();
        request.put("roleId", roleId);
        
        UserRoleInfo roleInfo = UserRoleInfo.builder()
                .comRoleId(roleId)
                .roleCode("MEMBER")
                .roleName("멤버")
                .subjectType("USER")
                .isDepartmentBased(false)
                .assignedAt(LocalDateTime.now())
                .build();
        
        when(userManagementService.addUserRole(eq(tenantId), anyLong(), eq(userId), eq(roleId), any()))
                .thenReturn(roleInfo);
        
        // When & Then
        mockMvc.perform(post("/admin/users/{comUserId}/roles", userId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comRoleId").value(roleId))
                .andExpect(jsonPath("$.data.isDepartmentBased").value(false));
        
        verify(userManagementService, times(1)).addUserRole(
                eq(tenantId), anyLong(), eq(userId), eq(roleId), any());
    }
    
    @Test
    @DisplayName("사용자 역할 삭제")
    @WithMockUser(roles = "ADMIN")
    void removeUserRole_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long userId = 1L;
        Long roleId = 2L;
        
        doNothing().when(userManagementService).removeUserRole(
                eq(tenantId), anyLong(), eq(userId), eq(roleId), any());
        
        // When & Then
        mockMvc.perform(delete("/admin/users/{comUserId}/roles/{comRoleId}", userId, roleId)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        verify(userManagementService, times(1)).removeUserRole(
                eq(tenantId), anyLong(), eq(userId), eq(roleId), any());
    }
    
    @Test
    @DisplayName("PR-02: 사용자 생성 성공")
    @WithMockUser(roles = "ADMIN")
    void createUser_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateUserRequest.LocalAccountRequest localAccount = 
                CreateUserRequest.LocalAccountRequest.builder()
                        .principal("newuser")
                        .password("password123!")
                        .build();
        
        CreateUserRequest request = CreateUserRequest.builder()
                .userName("새 사용자")
                .email("newuser@example.com")
                .departmentId(1L)
                .status("ACTIVE")
                .localAccount(localAccount)
                .build();
        
        UserDetail userDetail = UserDetail.builder()
                .comUserId(100L)
                .tenantId(tenantId)
                .userName("새 사용자")
                .email("newuser@example.com")
                .status("ACTIVE")
                .build();
        
        when(userManagementService.createUser(eq(tenantId), anyLong(), any(CreateUserRequest.class), any()))
                .thenReturn(userDetail);
        
        // When & Then
        mockMvc.perform(post("/admin/users")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comUserId").value(100L))
                .andExpect(jsonPath("$.data.userName").value("새 사용자"))
                .andExpect(jsonPath("$.data.email").value("newuser@example.com"));
        
        verify(userManagementService, times(1)).createUser(eq(tenantId), anyLong(), any(CreateUserRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-02: 중복 principal → 409")
    @WithMockUser(roles = "ADMIN")
    void createUser_DuplicatePrincipal_Returns409() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateUserRequest.LocalAccountRequest localAccount = 
                CreateUserRequest.LocalAccountRequest.builder()
                        .principal("duplicateuser")
                        .password("password123!")
                        .build();
        
        CreateUserRequest request = CreateUserRequest.builder()
                .userName("중복 사용자")
                .email("duplicate@example.com")
                .departmentId(1L)
                .status("ACTIVE")
                .localAccount(localAccount)
                .build();
        
        // 중복 principal로 인한 DUPLICATE_ENTITY 예외 발생
        when(userManagementService.createUser(eq(tenantId), anyLong(), any(CreateUserRequest.class), any()))
                .thenThrow(new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 principal입니다."));
        
        // When & Then
        mockMvc.perform(post("/admin/users")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E3001"));
    }
    
    @Test
    @DisplayName("PR-02: tenant isolation - 다른 tenantId로 조회 불가")
    @WithMockUser(roles = "ADMIN")
    void getUserDetail_DifferentTenant_Returns404() throws Exception {
        // Given
        Long tenantId2 = 2L;
        Long userId = 100L;
        
        // 다른 tenantId로 조회 시도 → 404
        when(userManagementService.getUserDetail(eq(tenantId2), eq(userId)))
                .thenThrow(new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // When & Then
        mockMvc.perform(get("/admin/users/{comUserId}", userId)
                        .header("X-Tenant-ID", tenantId2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E3000"));
        
        verify(userManagementService, times(1)).getUserDetail(eq(tenantId2), eq(userId));
    }
}
