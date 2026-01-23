package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.CodeGroupResponse;
import com.dwp.services.auth.dto.CodeResponse;
import com.dwp.services.auth.dto.admin.CodeUsageResponse;
import com.dwp.services.auth.service.CodeManagementService;
import com.dwp.services.auth.service.admin.codeusages.CodeUsageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CodeController 테스트
 */
@WebMvcTest(CodeController.class)
@SuppressWarnings("removal")
class CodeControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private CodeManagementService codeManagementService;
    
    @MockBean
    private CodeUsageService codeUsageService;
    
    @Test
    void getGroups_ReturnsGroupList() throws Exception {
        // Given
        List<CodeGroupResponse> groups = Arrays.asList(
                CodeGroupResponse.builder()
                        .sysCodeGroupId(1L)
                        .groupKey("RESOURCE_TYPE")
                        .groupName("리소스 유형")
                        .isActive(true)
                        .build()
        );
        
        when(codeManagementService.getAllGroups(any(), any(), any())).thenReturn(groups);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].groupKey").value("RESOURCE_TYPE"))
                .andExpect(jsonPath("$.data[0].groupName").value("리소스 유형"));
    }
    
    @Test
    void getCodes_WithGroupKey_ReturnsCodeList() throws Exception {
        // Given
        List<CodeResponse> codes = Arrays.asList(
                CodeResponse.builder()
                        .sysCodeId(1L)
                        .groupKey("RESOURCE_TYPE")
                        .code("MENU")
                        .name("메뉴")
                        .isActive(true)
                        .build()
        );
        
        when(codeManagementService.getCodesByGroup("RESOURCE_TYPE")).thenReturn(codes);
        
        // When & Then
        mockMvc.perform(get("/admin/codes").param("groupKey", "RESOURCE_TYPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("MENU"))
                .andExpect(jsonPath("$.data[0].name").value("메뉴"));
    }
    
    @Test
    void getAllCodes_ReturnsAllCodesByGroup() throws Exception {
        // Given
        Map<String, List<CodeResponse>> allCodes = new HashMap<>();
        allCodes.put("RESOURCE_TYPE", Arrays.asList(
                CodeResponse.builder()
                        .groupKey("RESOURCE_TYPE")
                        .code("MENU")
                        .name("메뉴")
                        .build()
        ));
        
        when(codeManagementService.getAllCodesByGroup()).thenReturn(allCodes);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.RESOURCE_TYPE[0].code").value("MENU"));
    }
    
    @Test
    @DisplayName("/api/admin/codes/usage?resourceKey=menu.admin.monitoring 호출 시 UI_ACTION 포함 확인")
    void getCodesByUsage_IncludesUIAction() throws Exception {
        // Given: menu.admin.monitoring에 UI_ACTION 매핑이 있음
        Long tenantId = 1L;
        String resourceKey = "menu.admin.monitoring";
        
        Map<String, List<CodeUsageResponse.CodeItem>> codesMap = new HashMap<>();
        codesMap.put("UI_ACTION", Arrays.asList(
                CodeUsageResponse.CodeItem.builder()
                        .code("VIEW")
                        .name("조회")
                        .build(),
                CodeUsageResponse.CodeItem.builder()
                        .code("CLICK")
                        .name("클릭")
                        .build()
        ));
        
        CodeUsageResponse response = CodeUsageResponse.builder()
                .codes(codesMap)
                .build();
        
        when(codeUsageService.getCodesByResourceKey(tenantId, resourceKey))
                .thenReturn(response);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/usage")
                        .param("resourceKey", resourceKey)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codes.UI_ACTION").exists())
                .andExpect(jsonPath("$.data.codes.UI_ACTION[0].code").value("VIEW"))
                .andExpect(jsonPath("$.data.codes.UI_ACTION[1].code").value("CLICK"));
    }
}
