package com.dwp.services.auth.service.admin;

import com.dwp.services.auth.dto.admin.CodeUsageResponse;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CodeUsageService 테스트 (BE Hotfix: menu.admin.monitoring + UI_ACTION)
 * 
 * 검증 항목:
 * - menu.admin.monitoring 조회 시 응답 codeMap에 UI_ACTION 키가 포함되는지 확인
 * - UI_ACTION 코드 10개 이상 반환되는지 확인
 * - tenant_id 필터 적용되는지 확인
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeUsageService - Monitoring Menu UI_ACTION 테스트")
@SuppressWarnings("null")
class CodeUsageServiceMonitoringTest {

    @Mock
    private CodeUsageRepository codeUsageRepository;

    @Mock
    private CodeRepository codeRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CodeUsageService codeUsageService;

    private Long tenantId = 1L;
    private String resourceKey = "menu.admin.monitoring";

    @BeforeEach
    void setUp() {
        codeUsageService.clearAllCache();
    }

    @Test
    @DisplayName("menu.admin.monitoring 조회 시 UI_ACTION 그룹 포함 확인")
    void testMonitoringMenuIncludesUIActionGroup() {
        // Given: menu.admin.monitoring에 UI_ACTION 매핑이 있음
        List<String> groupKeys = Arrays.asList("UI_ACTION");

        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);

        // UI_ACTION 코드 10개 (표준 액션)
        List<Code> uiActionCodes = Arrays.asList(
                createCode("UI_ACTION", "VIEW", "조회", 10),
                createCode("UI_ACTION", "CLICK", "클릭", 20),
                createCode("UI_ACTION", "EXECUTE", "실행", 30),
                createCode("UI_ACTION", "SCROLL", "스크롤", 40),
                createCode("UI_ACTION", "SEARCH", "검색", 50),
                createCode("UI_ACTION", "FILTER", "필터", 60),
                createCode("UI_ACTION", "DOWNLOAD", "다운로드", 70),
                createCode("UI_ACTION", "OPEN", "열기", 80),
                createCode("UI_ACTION", "CLOSE", "닫기", 90),
                createCode("UI_ACTION", "SUBMIT", "제출", 100)
        );

        when(codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc("UI_ACTION", tenantId))
                .thenReturn(uiActionCodes);

        // When: 코드 조회
        CodeUsageResponse response = codeUsageService.getCodesByResourceKey(tenantId, resourceKey);

        // Then: UI_ACTION 그룹이 포함되어야 함
        assertThat(response).isNotNull();
        assertThat(response.getCodes()).isNotNull();
        assertThat(response.getCodes()).containsKey("UI_ACTION");
        assertThat(response.getCodes().get("UI_ACTION")).hasSize(10);
    }

    @Test
    @DisplayName("UI_ACTION 코드 10개 이상 반환 확인")
    void testUIActionCodesCount() {
        // Given
        List<String> groupKeys = Arrays.asList("UI_ACTION");
        List<Code> uiActionCodes = Arrays.asList(
                createCode("UI_ACTION", "VIEW", "조회", 10),
                createCode("UI_ACTION", "CLICK", "클릭", 20),
                createCode("UI_ACTION", "EXECUTE", "실행", 30),
                createCode("UI_ACTION", "SCROLL", "스크롤", 40),
                createCode("UI_ACTION", "SEARCH", "검색", 50),
                createCode("UI_ACTION", "FILTER", "필터", 60),
                createCode("UI_ACTION", "DOWNLOAD", "다운로드", 70),
                createCode("UI_ACTION", "OPEN", "열기", 80),
                createCode("UI_ACTION", "CLOSE", "닫기", 90),
                createCode("UI_ACTION", "SUBMIT", "제출", 100)
        );

        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);
        when(codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc("UI_ACTION", tenantId))
                .thenReturn(uiActionCodes);

        // When
        CodeUsageResponse response = codeUsageService.getCodesByResourceKey(tenantId, resourceKey);

        // Then: 10개 이상 반환되어야 함
        assertThat(response.getCodes().get("UI_ACTION")).hasSizeGreaterThanOrEqualTo(10);
        
        // 표준 액션 코드 확인
        List<String> codes = response.getCodes().get("UI_ACTION").stream()
                .map(CodeUsageResponse.CodeItem::getCode)
                .toList();
        
        assertThat(codes).contains("VIEW", "CLICK", "EXECUTE", "SCROLL", "SEARCH", 
                "FILTER", "DOWNLOAD", "OPEN", "CLOSE", "SUBMIT");
    }

    @Test
    @DisplayName("tenant_id 필터 적용 확인")
    void testTenantIdFilterApplied() {
        // Given: tenant_id=1로 조회
        List<String> groupKeys = Arrays.asList("UI_ACTION");
        List<Code> codes = Arrays.asList(createCode("UI_ACTION", "VIEW", "조회", 10));

        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey))
                .thenReturn(groupKeys);
        when(codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc("UI_ACTION", tenantId))
                .thenReturn(codes);

        // When
        codeUsageService.getCodesByResourceKey(tenantId, resourceKey);

        // Then: tenant_id가 전달되어야 함
        verify(codeUsageRepository).findEnabledCodeGroupKeysByTenantIdAndResourceKey(eq(tenantId), eq(resourceKey));
        verify(codeRepository).findByGroupKeyAndTenantIdOrderBySortOrderAsc(eq("UI_ACTION"), eq(tenantId));
    }

    @Test
    @DisplayName("다른 테넌트는 격리되어야 함")
    void testTenantIsolation() {
        // Given: tenant_id=2로 조회
        Long otherTenantId = 2L;
        List<String> groupKeys = Arrays.asList("UI_ACTION");

        when(codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(otherTenantId, resourceKey))
                .thenReturn(groupKeys);
        when(codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc("UI_ACTION", otherTenantId))
                .thenReturn(Arrays.asList(createCode("UI_ACTION", "VIEW", "조회", 10)));

        // When
        codeUsageService.getCodesByResourceKey(otherTenantId, resourceKey);

        // Then: 다른 테넌트 ID로 조회됨
        verify(codeUsageRepository).findEnabledCodeGroupKeysByTenantIdAndResourceKey(eq(otherTenantId), eq(resourceKey));
        verify(codeRepository).findByGroupKeyAndTenantIdOrderBySortOrderAsc(eq("UI_ACTION"), eq(otherTenantId));
    }

    private Code createCode(String groupKey, String code, String name, int sortOrder) {
        return Code.builder()
                .groupKey(groupKey)
                .code(code)
                .name(name)
                .sortOrder(sortOrder)
                .isActive(true)
                .tenantId(null)  // 전사 공통 코드
                .build();
    }
}
