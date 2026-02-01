package com.dwp.services.main.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.main.dto.HitlApproveResult;
import com.dwp.services.main.dto.HitlRejectResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL approve/reject 멱등 동작 테스트 (409 정책)
 * - 이미 처리된 requestId에 대해 alreadyProcessed=true 반환
 * - Redis 업데이트 및 신호 발행 없음
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HitlManager idempotent (409)")
class HitlManagerIdempotentTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private com.dwp.services.main.client.AuthServerAuditClient authServerAuditClient;

    private HitlManager hitlManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        hitlManager = new HitlManager(redisTemplate, objectMapper, authServerAuditClient);
    }

    @Test
    @DisplayName("approve: 이미 approved인 경우 alreadyProcessed=true 반환, Redis set 미호출")
    void approve_whenAlreadyApproved_returnsAlreadyProcessedAndDoesNotUpdateRedis() throws Exception {
        String requestId = "req-1";
        String sessionId = "session-1";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "approved",
                "approvedBy", "user-1"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        HitlApproveResult result = hitlManager.approve(requestId, "user-1", 1L);

        assertThat(result.isAlreadyProcessed()).isTrue();
        assertThat(result.getStatus()).isEqualTo("approved");
        assertThat(result.getSessionId()).isEqualTo(sessionId);
        verify(valueOps).get(eq("hitl:request:" + requestId));
    }

    @Test
    @DisplayName("approve: 이미 rejected인 경우 alreadyProcessed=true 반환")
    void approve_whenAlreadyRejected_returnsAlreadyProcessed() throws Exception {
        String requestId = "req-2";
        String sessionId = "session-2";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "rejected",
                "rejectedBy", "user-1"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        HitlApproveResult result = hitlManager.approve(requestId, "user-1", 1L);

        assertThat(result.isAlreadyProcessed()).isTrue();
        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("reject: 이미 rejected인 경우 alreadyProcessed=true, 저장된 reason 반환")
    void reject_whenAlreadyRejected_returnsAlreadyProcessedWithStoredReason() throws Exception {
        String requestId = "req-3";
        String sessionId = "session-3";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "rejected",
                "reason", "Previously rejected reason"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        HitlRejectResult result = hitlManager.reject(requestId, "user-1", "new reason", 1L);

        assertThat(result.isAlreadyProcessed()).isTrue();
        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getReason()).isEqualTo("Previously rejected reason");
        assertThat(result.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("reject: tenant 불일치 시 TENANT_MISMATCH 예외")
    void reject_whenTenantMismatch_throwsTenantMismatch() throws Exception {
        String requestId = "req-4";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", "session-4",
                "tenantId", 999L,
                "status", "pending"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        org.junit.jupiter.api.Assertions.assertThrows(BaseException.class, () ->
                hitlManager.reject(requestId, "user-1", "reason", 1L));
    }
}
