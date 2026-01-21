package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.services.auth.dto.MenuNode;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.menus.MenuManagementService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PR-05F: AdminMenuController 테스트
 */
@WebMvcTest(value = AdminMenuController.class, excludeAutoConfiguration = RedisAutoConfiguration.class)
@SuppressWarnings({"null", "removal"})
class AdminMenuControllerTest {
    
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
            return resolver;
        }
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private MenuManagementService menuManagementService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    @DisplayName("PR-05F-1: 메뉴 생성 성공")
    @WithMockUser(roles = "ADMIN")
    void createMenu_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateMenuRequest request = CreateMenuRequest.builder()
                .menuKey("menu.test.new")
                .menuName("새 메뉴")
                .routePath("/test/new")
                .icon("solar:test-bold")
                .menuGroup("APPS")
                .sortOrder(100)
                .enabled(true)
                .visible(true)
                .build();
        
        MenuSummary menuSummary = MenuSummary.builder()
                .sysMenuId(100L)
                .menuKey("menu.test.new")
                .menuName("새 메뉴")
                .menuPath("/test/new")
                .menuIcon("solar:test-bold")
                .menuGroup("APPS")
                .sortOrder(100)
                .isEnabled(true)
                .isVisible(true)
                .createdAt(LocalDateTime.now())
                .build();
        
        when(menuManagementService.createMenu(eq(tenantId), anyLong(), any(CreateMenuRequest.class), any()))
                .thenReturn(menuSummary);
        
        // When & Then
        mockMvc.perform(post("/admin/menus")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sysMenuId").value(100L))
                .andExpect(jsonPath("$.data.menuKey").value("menu.test.new"))
                .andExpect(jsonPath("$.data.menuName").value("새 메뉴"));
        
        verify(menuManagementService, times(1)).createMenu(eq(tenantId), anyLong(), any(CreateMenuRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-05F-2: 메뉴 생성 중복 → 409")
    @WithMockUser(roles = "ADMIN")
    void createMenu_Duplicate_Returns409() throws Exception {
        // Given
        Long tenantId = 1L;
        
        CreateMenuRequest request = CreateMenuRequest.builder()
                .menuKey("menu.admin.users")
                .menuName("중복 메뉴")
                .build();
        
        when(menuManagementService.createMenu(eq(tenantId), anyLong(), any(CreateMenuRequest.class), any()))
                .thenThrow(new com.dwp.core.exception.BaseException(
                        ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 메뉴 키입니다."));
        
        // When & Then
        mockMvc.perform(post("/admin/menus")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E3001"));
    }
    
    @Test
    @DisplayName("PR-05F-3: 메뉴 정렬/이동 성공")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        
        ReorderMenuRequest.MenuOrderItem item1 = ReorderMenuRequest.MenuOrderItem.builder()
                .menuId(10L)
                .parentId(null)
                .sortOrder(10)
                .build();
        
        ReorderMenuRequest.MenuOrderItem item2 = ReorderMenuRequest.MenuOrderItem.builder()
                .menuId(11L)
                .parentId(null)
                .sortOrder(20)
                .build();
        
        ReorderMenuRequest request = ReorderMenuRequest.builder()
                .items(Arrays.asList(item1, item2))
                .build();
        
        doNothing().when(menuManagementService).reorderMenus(
                eq(tenantId), anyLong(), any(ReorderMenuRequest.class), any());
        
        // When & Then
        mockMvc.perform(put("/admin/menus/reorder")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        verify(menuManagementService, times(1)).reorderMenus(
                eq(tenantId), anyLong(), any(ReorderMenuRequest.class), any());
    }
    
    @Test
    @DisplayName("PR-05F-4: 메뉴 트리 조회 정렬 보장")
    @WithMockUser(roles = "ADMIN")
    void getMenuTree_Sorted() throws Exception {
        // Given
        Long tenantId = 1L;
        
        MenuNode node1 = MenuNode.builder()
                .menuKey("menu.admin")
                .menuName("Admin")
                .sortOrder(100)
                .build();
        
        MenuNode node2 = MenuNode.builder()
                .menuKey("menu.dashboard")
                .menuName("Dashboard")
                .sortOrder(10)
                .build();
        
        List<MenuNode> tree = Arrays.asList(node2, node1); // sortOrder 순서대로
        
        when(menuManagementService.getMenuTree(tenantId)).thenReturn(tree);
        
        // When & Then
        mockMvc.perform(get("/admin/menus/tree")
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].menuKey").value("menu.dashboard"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(10))
                .andExpect(jsonPath("$.data[1].menuKey").value("menu.admin"))
                .andExpect(jsonPath("$.data[1].sortOrder").value(100));
    }
}
