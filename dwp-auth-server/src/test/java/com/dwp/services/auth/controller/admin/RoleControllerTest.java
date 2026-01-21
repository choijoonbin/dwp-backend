package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.roles.RoleManagementService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PR-03G: RoleController 테스트
 */
@WebMvcTest(value = RoleController.class, excludeAutoConfiguration = RedisAutoConfiguration.class)
@SuppressWarnings({"null", "removal"})
class RoleControllerTest {
    
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public CodeResolver codeResolver() {
            CodeResolver resolver = org.mockito.Mockito.mock(CodeResolver.class);
            org.mockito.Mockito.when(resolver.validate(org.mockito.ArgumentMatchers.anyString(), 
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            org.mockito.Mockito.doNothing().when(resolver).require(org.mockito.ArgumentMatchers.anyString(), 
                    org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.when(resolver.getCodes(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(Arrays.asList("ADMIN", "USER", "MANAGER"));
            return resolver;
        }
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private RoleManagementService roleManagementService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    @DisplayName("PR-03G-1: role 생성 성공")
    @WithMockUser(roles = "ADMIN")
    void createRole_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateRoleRequest request = CreateRoleRequest.builder()
                .roleCode("MANAGER")
                .roleName("매니저")
                .description("매니저 역할")
                .build();
        
        RoleDetail roleDetail = RoleDetail.builder()
                .id("100")
                .comRoleId(100L)
                .roleCode("MANAGER")
                .roleName("매니저")
                .status("ACTIVE")
                .description("매니저 역할")
                .createdAt(LocalDateTime.now())
                .memberCount(0)
                .build();
        
        when(roleManagementService.createRole(eq(tenantId), anyLong(), any(CreateRoleRequest.class), any()))
                .thenReturn(roleDetail);
        
        // When & Then
        mockMvc.perform(post("/admin/roles")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comRoleId").value(100L))
                .andExpect(jsonPath("$.data.roleCode").value("MANAGER"))
                .andExpect(jsonPath("$.data.roleName").value("매니저"));
        
        verify(roleManagementService, times(1)).createRole(eq(tenantId), anyLong(), any(CreateRoleRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-03G-2: role 수정 성공")
    @WithMockUser(roles = "ADMIN")
    void updateRole_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long roleId = 100L;
        
        UpdateRoleRequest request = UpdateRoleRequest.builder()
                .roleName("수정된 매니저")
                .description("수정된 설명")
                .build();
        
        RoleDetail roleDetail = RoleDetail.builder()
                .id(String.valueOf(roleId))
                .comRoleId(roleId)
                .roleCode("MANAGER")
                .roleName("수정된 매니저")
                .status("ACTIVE")
                .description("수정된 설명")
                .memberCount(0)
                .build();
        
        when(roleManagementService.updateRole(eq(tenantId), anyLong(), eq(roleId), any(UpdateRoleRequest.class), any()))
                .thenReturn(roleDetail);
        
        // When & Then
        mockMvc.perform(put("/admin/roles/{comRoleId}", roleId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roleName").value("수정된 매니저"));
        
        verify(roleManagementService, times(1)).updateRole(eq(tenantId), anyLong(), eq(roleId), any(UpdateRoleRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-03G-3: member 할당 성공")
    @WithMockUser(roles = "ADMIN")
    void addRoleMember_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long roleId = 100L;
        
        AddRoleMemberRequest request = AddRoleMemberRequest.builder()
                .subjectType("USER")
                .subjectId(200L)
                .build();
        
        RoleMemberView memberView = RoleMemberView.builder()
                .roleMemberId(1L)
                .subjectType("USER")
                .subjectId(200L)
                .subjectName("테스트 사용자")
                .build();
        
        when(roleManagementService.addRoleMember(eq(tenantId), anyLong(), eq(roleId), any(AddRoleMemberRequest.class), any()))
                .thenReturn(memberView);
        
        // When & Then
        mockMvc.perform(post("/admin/roles/{comRoleId}/members", roleId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subjectType").value("USER"))
                .andExpect(jsonPath("$.data.subjectId").value(200L));
        
        verify(roleManagementService, times(1)).addRoleMember(eq(tenantId), anyLong(), eq(roleId), any(AddRoleMemberRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-03G-4: member 할당 중복 → 409")
    @WithMockUser(roles = "ADMIN")
    void addRoleMember_Duplicate_Returns409() throws Exception {
        // Given
        Long tenantId = 1L;
        Long roleId = 100L;
        
        AddRoleMemberRequest request = AddRoleMemberRequest.builder()
                .subjectType("USER")
                .subjectId(200L)
                .build();
        
        // 중복 할당으로 인한 DUPLICATE_ENTITY 예외 발생
        when(roleManagementService.addRoleMember(eq(tenantId), anyLong(), eq(roleId), any(AddRoleMemberRequest.class), any()))
                .thenThrow(new com.dwp.core.exception.BaseException(
                        ErrorCode.DUPLICATE_ENTITY, "이미 할당된 멤버입니다."));
        
        // When & Then
        mockMvc.perform(post("/admin/roles/{comRoleId}/members", roleId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E3001"));
    }
    
    @Test
    @DisplayName("PR-03G-5: permission bulk 저장 성공")
    @WithMockUser(roles = "ADMIN")
    void updateRolePermissions_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long roleId = 100L;
        
        UpdateRolePermissionsRequest.RolePermissionItem item1 = UpdateRolePermissionsRequest.RolePermissionItem.builder()
                .resourceKey("menu.admin.users")
                .permissionCode("VIEW")
                .effect("ALLOW")
                .build();
        
        UpdateRolePermissionsRequest.RolePermissionItem item2 = UpdateRolePermissionsRequest.RolePermissionItem.builder()
                .resourceKey("menu.admin.users")
                .permissionCode("EDIT")
                .effect("ALLOW")
                .build();
        
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.builder()
                .items(Arrays.asList(item1, item2))
                .build();
        
        doNothing().when(roleManagementService).updateRolePermissions(
                eq(tenantId), anyLong(), eq(roleId), any(UpdateRolePermissionsRequest.class), any());
        
        // When & Then
        mockMvc.perform(put("/admin/roles/{comRoleId}/permissions", roleId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        verify(roleManagementService, times(1)).updateRolePermissions(
                eq(tenantId), anyLong(), eq(roleId), any(UpdateRolePermissionsRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-03G-6: role 삭제 충돌 → 409")
    @WithMockUser(roles = "ADMIN")
    void deleteRole_InUse_Returns409() throws Exception {
        // Given
        Long tenantId = 1L;
        Long roleId = 100L;
        
        // 역할이 사용 중 (members/permissions 존재)
        doThrow(new com.dwp.core.exception.BaseException(
                        ErrorCode.ROLE_IN_USE, "역할이 사용 중입니다. 멤버(5명)나 권한(10개)을 먼저 제거해주세요."))
                .when(roleManagementService).deleteRole(eq(tenantId), anyLong(), eq(roleId), any());
        
        // When & Then
        mockMvc.perform(delete("/admin/roles/{comRoleId}", roleId)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E3003"));
    }
}
