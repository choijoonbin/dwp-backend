package com.dwp.services.synapsex.service.case_;

import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import feign.FeignException;
import feign.Request;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * P1.1: CaseTabProxyService DEMO 모드 단위 테스트
 * - Spring/Docker 없이 demoMode 분기 검증
 */
@ExtendWith(MockitoExtension.class)
class CaseTabProxyServiceTest {

    @Mock
    private AuraCaseTabClient auraCaseTabClient;
    @Mock
    private AgentCaseRepository agentCaseRepository;

    private CaseTabProxyService service;

    @BeforeEach
    void setUp() {
        service = new CaseTabProxyService(auraCaseTabClient, agentCaseRepository);
    }

    private void setDemoMode(boolean value) throws Exception {
        Field f = CaseTabProxyService.class.getDeclaredField("demoMode");
        f.setAccessible(true);
        f.set(service, value);
    }

    @Nested
    @DisplayName("DEMO ON - Non-Empty 샘플")
    class DemoModeOnTest {

        @BeforeEach
        void enableDemoMode() throws Exception {
            setDemoMode(true);
        }

        @Test
        @DisplayName("getAnalysis - summary, keyFindings, recommendations")
        void getAnalysis_returnsNonEmpty() {
            when(agentCaseRepository.findByCaseIdAndTenantId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(AgentCase.builder().caseId(85116L).tenantId(1L).build()));

            Object result = service.getAnalysis(1L, 85116L, null, null);

            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> m = (Map<?, ?>) result;
            assertThat(m.get("summary")).isNotNull();
            assertThat((List<?>) m.get("keyFindings")).hasSize(2);
            assertThat((List<?>) m.get("recommendations")).hasSize(2);
        }

        @Test
        @DisplayName("getConfidence - score, factors 3개")
        void getConfidence_returnsNonEmpty() {
            when(agentCaseRepository.findByCaseIdAndTenantId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(AgentCase.builder().caseId(85116L).tenantId(1L).build()));

            Object result = service.getConfidence(1L, 85116L, null, null);

            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> m = (Map<?, ?>) result;
            assertThat(m.get("score")).isEqualTo(72);
            assertThat((List<?>) m.get("factors")).hasSize(3);
        }

        @Test
        @DisplayName("getSimilar - items 2개")
        void getSimilar_returnsNonEmpty() {
            when(agentCaseRepository.findByCaseIdAndTenantId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(AgentCase.builder().caseId(85116L).tenantId(1L).build()));

            Object result = service.getSimilar(1L, 85116L, null, null);

            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> m = (Map<?, ?>) result;
            assertThat((List<?>) m.get("items")).hasSize(2);
        }

        @Test
        @DisplayName("getRagEvidence - items 2개")
        void getRagEvidence_returnsNonEmpty() {
            when(agentCaseRepository.findByCaseIdAndTenantId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(AgentCase.builder().caseId(85116L).tenantId(1L).build()));

            Object result = service.getRagEvidence(1L, 85116L, null, null);

            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> m = (Map<?, ?>) result;
            assertThat((List<?>) m.get("items")).hasSize(2);
        }

        @Test
        @DisplayName("caseId 미존재 시 404")
        void caseNotFound_throws() {
            when(agentCaseRepository.findByCaseIdAndTenantId(999999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAnalysis(1L, 999999L, null, null))
                    .hasMessageContaining("케이스를 찾을 수 없습니다");
        }
    }

    @Nested
    @DisplayName("DEMO OFF - Aura fallback")
    class DemoModeOffTest {

        @BeforeEach
        void disableDemoMode() throws Exception {
            setDemoMode(false);
        }

        @Test
        @DisplayName("case 존재 시 Aura 호출 (Feign 실패 시 empty 반환)")
        void getAnalysis_auraFails_returnsEmpty() {
            when(agentCaseRepository.findByCaseIdAndTenantId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(AgentCase.builder().caseId(85116L).tenantId(1L).build()));
            Request req = Request.create(Request.HttpMethod.GET, "/aura/cases/1/analysis", Collections.emptyMap(), null, new feign.RequestTemplate());
            when(auraCaseTabClient.getAnalysis(anyLong(), anyLong(), any(), any()))
                    .thenThrow(new FeignException.ServiceUnavailable("", req, null, null));

            Object result = service.getAnalysis(1L, 85116L, null, null);

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) result;
            assertThat(m).containsKey("summary");
        }
    }
}
