package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CodeUsageResponse;
import com.dwp.services.auth.dto.admin.CodeUsageSummary;
import com.dwp.services.auth.dto.admin.CreateCodeUsageRequest;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.service.admin.codeusages.CodeUsageService;
import com.dwp.services.auth.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CodeUsageService 테스트
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class CodeUsageServiceTest {
    
    @Mock
    private CodeUsageRepository codeUsageRepository;
    
    @Mock
    private CodeRepository codeRepository;
    
    @Mock
    private AuditLogService auditLogService;
    
    @InjectMocks
    private CodeUsageService codeUsageService;
    
    private Long tenantId;
    private String resourceKey;
    
    @BeforeEach
    void setUp() {
        tenantId = 1L;
        resourceKey = "menu.admin.users";
        codeUsageService.clearAllCache();
    }
    
    @Test
    void getCodesByResourceKey_Success() {
        // Given
        List<String> groupKeys = Arrays.asList("SUBJECT_TYPE", "USER_STATUS");
        
        Code code1 = Code.builder()
                .sysCodeId(1L)
                .groupKey("SUBJECT_TYPE")
                .code("USER")
                .name("사용자")
                .isActive(true)
                .build();
        
        Code code2 = Code.builder()
                .sysCodeId(2L)
                .groupKey("USER_STATUS")
                .code("ACTIVE")
                .name("활성")
                .isActive(true)
                .build();
        
        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc("SUBJECT_TYPE"))
                .thenReturn(Arrays.asList(code1));
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc("USER_STATUS"))
                .thenReturn(Arrays.asList(code2));
        
        // When
        CodeUsageResponse response = codeUsageService.getCodesByResourceKey(tenantId, resourceKey);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getCodes());
        assertEquals(2, response.getCodes().size());
        assertTrue(response.getCodes().containsKey("SUBJECT_TYPE"));
        assertTrue(response.getCodes().containsKey("USER_STATUS"));
        assertEquals(1, response.getCodes().get("SUBJECT_TYPE").size());
        assertEquals("USER", response.getCodes().get("SUBJECT_TYPE").get(0).getCode());
    }
    
    @Test
    void getCodesByResourceKey_EmptyMap_WhenNoMapping() {
        // Given
        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(Arrays.asList());
        
        // When
        CodeUsageResponse response = codeUsageService.getCodesByResourceKey(tenantId, resourceKey);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getCodes());
        assertTrue(response.getCodes().isEmpty());
    }
    
    @Test
    void getCodesByResourceKey_CacheHit() {
        // Given
        List<String> groupKeys = Arrays.asList("SUBJECT_TYPE");
        Code code = Code.builder()
                .sysCodeId(1L)
                .groupKey("SUBJECT_TYPE")
                .code("USER")
                .name("사용자")
                .isActive(true)
                .build();
        
        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc("SUBJECT_TYPE"))
                .thenReturn(Arrays.asList(code));
        
        // 첫 번째 호출
        codeUsageService.getCodesByResourceKey(tenantId, resourceKey);
        
        // When - 두 번째 호출 (캐시 히트)
        CodeUsageResponse response = codeUsageService.getCodesByResourceKey(tenantId, resourceKey);
        
        // Then
        assertNotNull(response);
        verify(codeUsageRepository, times(1)).findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey);
        verify(codeRepository, times(1)).findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc("SUBJECT_TYPE");
    }
    
    @Test
    void createCodeUsage_Success() {
        // Given
        CreateCodeUsageRequest request = CreateCodeUsageRequest.builder()
                .resourceKey(resourceKey)
                .codeGroupKey("SUBJECT_TYPE")
                .scope("MENU")
                .enabled(true)
                .build();
        
        com.dwp.services.auth.entity.CodeUsage codeUsage = com.dwp.services.auth.entity.CodeUsage.builder()
                .sysCodeUsageId(1L)
                .tenantId(tenantId)
                .resourceKey(resourceKey)
                .codeGroupKey("SUBJECT_TYPE")
                .scope("MENU")
                .enabled(true)
                .build();
        
        when(codeUsageRepository.findByTenantIdAndResourceKeyOrderBySortOrderAsc(tenantId, resourceKey))
                .thenReturn(Arrays.asList());
        when(codeUsageRepository.save(any()))
                .thenReturn(codeUsage);
        
        // When
        CodeUsageSummary summary = codeUsageService.createCodeUsage(tenantId, 1L, request, mock(HttpServletRequest.class));
        
        // Then
        assertNotNull(summary);
        assertEquals(resourceKey, summary.getResourceKey());
        assertEquals("SUBJECT_TYPE", summary.getCodeGroupKey());
        verify(codeUsageRepository).save(any());
        verify(auditLogService).recordAuditLog(eq(tenantId), any(), eq("CODE_USAGE_CREATE"), eq("CODE_USAGE"), any(), any(), any(), any());
    }
    
    @Test
    void createCodeUsage_Duplicate_ThrowsException() {
        // Given
        CreateCodeUsageRequest request = CreateCodeUsageRequest.builder()
                .resourceKey(resourceKey)
                .codeGroupKey("SUBJECT_TYPE")
                .build();
        
        com.dwp.services.auth.entity.CodeUsage existing = com.dwp.services.auth.entity.CodeUsage.builder()
                .codeGroupKey("SUBJECT_TYPE")
                .build();
        
        when(codeUsageRepository.findByTenantIdAndResourceKeyOrderBySortOrderAsc(tenantId, resourceKey))
                .thenReturn(Arrays.asList(existing));
        
        // When & Then
        BaseException exception = assertThrows(BaseException.class, () -> {
            codeUsageService.createCodeUsage(tenantId, 1L, request, mock(HttpServletRequest.class));
        });
        
        assertEquals(ErrorCode.DUPLICATE_ENTITY, exception.getErrorCode());
    }
    
    @Test
    void updateCodeUsage_Success() {
        // Given
        Long sysCodeUsageId = 1L;
        com.dwp.services.auth.entity.CodeUsage codeUsage = com.dwp.services.auth.entity.CodeUsage.builder()
                .sysCodeUsageId(sysCodeUsageId)
                .tenantId(tenantId)
                .resourceKey(resourceKey)
                .codeGroupKey("SUBJECT_TYPE")
                .enabled(true)
                .build();
        
        com.dwp.services.auth.dto.admin.UpdateCodeUsageRequest request = 
                com.dwp.services.auth.dto.admin.UpdateCodeUsageRequest.builder()
                        .enabled(false)
                        .build();
        
        when(codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId))
                .thenReturn(Optional.of(codeUsage));
        when(codeUsageRepository.save(any()))
                .thenReturn(codeUsage);
        
        // When
        CodeUsageSummary summary = codeUsageService.updateCodeUsage(tenantId, 1L, sysCodeUsageId, request, mock(HttpServletRequest.class));
        
        // Then
        assertNotNull(summary);
        assertFalse(summary.getEnabled());
        verify(codeUsageRepository).save(any());
    }
    
    @Test
    void updateCodeUsage_NotFound_ThrowsException() {
        // Given
        Long sysCodeUsageId = 999L;
        com.dwp.services.auth.dto.admin.UpdateCodeUsageRequest request = 
                com.dwp.services.auth.dto.admin.UpdateCodeUsageRequest.builder()
                        .enabled(false)
                        .build();
        
        when(codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId))
                .thenReturn(Optional.empty());
        
        // When & Then
        BaseException exception = assertThrows(BaseException.class, () -> {
            codeUsageService.updateCodeUsage(tenantId, 1L, sysCodeUsageId, request, mock(HttpServletRequest.class));
        });
        
        assertEquals(ErrorCode.ENTITY_NOT_FOUND, exception.getErrorCode());
    }
    
    @Test
    void deleteCodeUsage_Success() {
        // Given
        Long sysCodeUsageId = 1L;
        com.dwp.services.auth.entity.CodeUsage codeUsage = com.dwp.services.auth.entity.CodeUsage.builder()
                .sysCodeUsageId(sysCodeUsageId)
                .tenantId(tenantId)
                .resourceKey(resourceKey)
                .build();
        
        when(codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId))
                .thenReturn(Optional.of(codeUsage));
        
        // When
        codeUsageService.deleteCodeUsage(tenantId, 1L, sysCodeUsageId, mock(HttpServletRequest.class));
        
        // Then
        verify(codeUsageRepository).delete(codeUsage);
        verify(auditLogService).recordAuditLog(eq(tenantId), any(), eq("CODE_USAGE_DELETE"), eq("CODE_USAGE"), eq(sysCodeUsageId), any(), isNull(), any());
    }
    
    @Test
    void getCodeUsages_TenantIsolation() {
        // Given
        com.dwp.services.auth.entity.CodeUsage codeUsage1 = com.dwp.services.auth.entity.CodeUsage.builder()
                .sysCodeUsageId(1L)
                .tenantId(tenantId)
                .resourceKey(resourceKey)
                .build();
        
        Page<com.dwp.services.auth.entity.CodeUsage> page = new PageImpl<>(Arrays.asList(codeUsage1));
        
        when(codeUsageRepository.findByTenantIdAndFilters(eq(tenantId), any(), any(), any(), any(), any()))
                .thenReturn(page);
        
        // When
        com.dwp.services.auth.dto.admin.PageResponse<CodeUsageSummary> response = 
                codeUsageService.getCodeUsages(tenantId, 1, 20, null, null, null, null);
        
        // Then
        assertNotNull(response);
        assertEquals(1, response.getItems().size());
        assertEquals(tenantId, response.getItems().get(0).getTenantId());
        verify(codeUsageRepository).findByTenantIdAndFilters(eq(tenantId), any(), any(), any(), any(), any());
    }
}
