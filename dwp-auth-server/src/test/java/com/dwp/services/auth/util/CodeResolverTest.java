package com.dwp.services.auth.util;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.repository.CodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CodeResolver 테스트
 */
@ExtendWith(MockitoExtension.class)
class CodeResolverTest {
    
    @Mock
    private CodeRepository codeRepository;
    
    @InjectMocks
    private CodeResolver codeResolver;
    
    @BeforeEach
    void setUp() {
        // 캐시 초기화
        codeResolver.clearCache();
    }
    
    @Test
    void validate_ValidCode_ReturnsTrue() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        String code = "MENU";
        
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code(code)
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode));
        
        // When
        boolean result = codeResolver.validate(groupKey, code);
        
        // Then
        assertTrue(result);
        verify(codeRepository, times(1)).findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey);
    }
    
    @Test
    void validate_InvalidCode_ReturnsFalse() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        String code = "INVALID";
        
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code("MENU")
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode));
        
        // When
        boolean result = codeResolver.validate(groupKey, code);
        
        // Then
        assertFalse(result);
    }
    
    @Test
    void validate_NullGroupKey_ReturnsFalse() {
        // When
        boolean result = codeResolver.validate(null, "MENU");
        
        // Then
        assertFalse(result);
        verify(codeRepository, never()).findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(any());
    }
    
    @Test
    void validate_NullCode_ReturnsFalse() {
        // When
        boolean result = codeResolver.validate("RESOURCE_TYPE", null);
        
        // Then
        assertFalse(result);
    }
    
    @Test
    void require_ValidCode_DoesNotThrow() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        String code = "MENU";
        
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code(code)
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode));
        
        // When & Then
        assertDoesNotThrow(() -> codeResolver.require(groupKey, code));
    }
    
    @Test
    void require_InvalidCode_ThrowsException() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        String code = "INVALID";
        
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code("MENU")
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode));
        
        // When & Then
        BaseException exception = assertThrows(BaseException.class, 
                () -> codeResolver.require(groupKey, code));
        
        assertEquals(ErrorCode.INVALID_CODE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Invalid code"));
    }
    
    @Test
    void getCodes_ReturnsActiveCodes() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code("MENU")
                .isActive(true)
                .build();
        
        Code uiCode = Code.builder()
                .groupKey(groupKey)
                .code("UI_COMPONENT")
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode, uiCode));
        
        // When
        List<String> codes = codeResolver.getCodes(groupKey);
        
        // Then
        assertEquals(2, codes.size());
        assertTrue(codes.contains("MENU"));
        assertTrue(codes.contains("UI_COMPONENT"));
    }
    
    @Test
    void clearCache_ClearsAllCache() {
        // Given
        String groupKey = "RESOURCE_TYPE";
        Code menuCode = Code.builder()
                .groupKey(groupKey)
                .code("MENU")
                .isActive(true)
                .build();
        
        when(codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey))
                .thenReturn(Arrays.asList(menuCode));
        
        // 캐시에 데이터 로드
        codeResolver.validate(groupKey, "MENU");
        verify(codeRepository, times(1)).findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey);
        
        // When
        codeResolver.clearCache();
        
        // Then - 캐시가 비워졌으므로 다시 조회해야 함
        codeResolver.validate(groupKey, "MENU");
        verify(codeRepository, times(2)).findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey);
    }
}
