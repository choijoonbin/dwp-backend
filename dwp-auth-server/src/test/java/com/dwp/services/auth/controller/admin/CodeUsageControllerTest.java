package com.dwp.services.auth.controller.admin;

import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.CodeUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CodeUsageController 테스트
 */
@WebMvcTest(CodeUsageController.class)
@SuppressWarnings("null")
class CodeUsageControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private CodeUsageService codeUsageService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void getCodesByUsage_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        String resourceKey = "menu.admin.users";
        
        Map<String, List<CodeUsageResponse.CodeItem>> codesMap = new HashMap<>();
        codesMap.put("SUBJECT_TYPE", Arrays.asList(
                CodeUsageResponse.CodeItem.builder()
                        .sysCodeId(1L)
                        .code("USER")
                        .name("사용자")
                        .enabled(true)
                        .build()
        ));
        
        CodeUsageResponse response = CodeUsageResponse.builder()
                .codes(codesMap)
                .build();
        
        when(codeUsageService.getCodesByResourceKey(tenantId, resourceKey))
                .thenReturn(response);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/usage")
                        .header("X-Tenant-ID", tenantId)
                        .param("resourceKey", resourceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codes.SUBJECT_TYPE").isArray())
                .andExpect(jsonPath("$.data.codes.SUBJECT_TYPE[0].code").value("USER"));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void getCodesByUsage_EmptyMap_WhenNoMapping() throws Exception {
        // Given
        Long tenantId = 1L;
        String resourceKey = "menu.admin.unknown";
        
        CodeUsageResponse response = CodeUsageResponse.builder()
                .codes(new HashMap<>())
                .build();
        
        when(codeUsageService.getCodesByResourceKey(tenantId, resourceKey))
                .thenReturn(response);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/usage")
                        .header("X-Tenant-ID", tenantId)
                        .param("resourceKey", resourceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codes").isEmpty());
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void getCodeGroupKeysByUsage_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        String resourceKey = "menu.admin.users";
        List<String> groupKeys = Arrays.asList("SUBJECT_TYPE", "USER_STATUS", "IDP_PROVIDER_TYPE");
        
        when(codeUsageService.getCodeGroupKeysByResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);
        
        // When & Then
        mockMvc.perform(get("/admin/codes/usage/groups")
                        .header("X-Tenant-ID", tenantId)
                        .param("resourceKey", resourceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").value("SUBJECT_TYPE"));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void getCodeUsages_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        String resourceKey = "menu.admin.users";
        
        CodeUsageSummary summary = CodeUsageSummary.builder()
                .sysCodeUsageId(1L)
                .tenantId(tenantId)
                .resourceKey(resourceKey)
                .codeGroupKey("SUBJECT_TYPE")
                .scope("MENU")
                .enabled(true)
                .build();
        
        PageResponse<CodeUsageSummary> pageResponse = PageResponse.<CodeUsageSummary>builder()
                .items(Arrays.asList(summary))
                .page(1)
                .size(20)
                .totalItems(1L)
                .totalPages(1)
                .build();
        
        when(codeUsageService.getCodeUsages(eq(tenantId), eq(1), eq(20), eq(resourceKey), any()))
                .thenReturn(pageResponse);
        
        // When & Then
        mockMvc.perform(get("/admin/code-usages")
                        .header("X-Tenant-ID", tenantId)
                        .param("page", "1")
                        .param("size", "20")
                        .param("resourceKey", resourceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].resourceKey").value(resourceKey));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void createCodeUsage_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        CreateCodeUsageRequest request = CreateCodeUsageRequest.builder()
                .resourceKey("menu.admin.users")
                .codeGroupKey("SUBJECT_TYPE")
                .scope("MENU")
                .enabled(true)
                .build();
        
        CodeUsageSummary summary = CodeUsageSummary.builder()
                .sysCodeUsageId(1L)
                .tenantId(tenantId)
                .resourceKey(request.getResourceKey())
                .codeGroupKey(request.getCodeGroupKey())
                .scope(request.getScope())
                .enabled(request.getEnabled())
                .build();
        
        when(codeUsageService.createCodeUsage(eq(tenantId), any(), any(), any()))
                .thenReturn(summary);
        
        // When & Then
        mockMvc.perform(post("/admin/code-usages")
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resourceKey").value(request.getResourceKey()));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateCodeUsage_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long sysCodeUsageId = 1L;
        UpdateCodeUsageRequest request = UpdateCodeUsageRequest.builder()
                .enabled(false)
                .remark("비활성화")
                .build();
        
        CodeUsageSummary summary = CodeUsageSummary.builder()
                .sysCodeUsageId(sysCodeUsageId)
                .tenantId(tenantId)
                .enabled(false)
                .remark("비활성화")
                .build();
        
        when(codeUsageService.updateCodeUsage(eq(tenantId), any(), eq(sysCodeUsageId), any(), any()))
                .thenReturn(summary);
        
        // When & Then
        mockMvc.perform(patch("/admin/code-usages/{sysCodeUsageId}", sysCodeUsageId)
                        .header("X-Tenant-ID", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteCodeUsage_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        Long sysCodeUsageId = 1L;
        
        // When & Then
        mockMvc.perform(delete("/admin/code-usages/{sysCodeUsageId}", sysCodeUsageId)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
