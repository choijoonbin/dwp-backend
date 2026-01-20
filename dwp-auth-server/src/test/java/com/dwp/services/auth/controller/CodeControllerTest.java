package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.CodeGroupResponse;
import com.dwp.services.auth.dto.CodeResponse;
import com.dwp.services.auth.service.CodeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        
        when(codeManagementService.getAllGroups()).thenReturn(groups);
        
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
}
