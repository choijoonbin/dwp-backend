package com.dwp.services.main.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.main.client.AuthServerAuditClient;
import com.dwp.services.main.client.InternalAuditLogRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL 감사 로그 커버리지 테스트
 * - HITL_REQUEST: saveApprovalRequest에서 기록
 * - HITL_APPROVE: approve 최초 처리 시 기록, 멱등 재호출 시 추가 기록 없음
 * - HITL_REJECT: reject 최초 처리 시 기록
 * - tenant mismatch 시 감사 호출 없음 (예외 선행)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HitlManager audit log coverage")
class HitlManagerAuditTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private AuthServerAuditClient authServerAuditClient;

    private HitlManager hitlManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        hitlManager = new HitlManager(redisTemplate, objectMapper, authServerAuditClient);
    }

    @Test
    @DisplayName("saveApprovalRequest: HITL_REQUEST 감사 기록 호출됨")
    void saveApprovalRequest_recordsHitlRequestAudit() {
        hitlManager.saveApprovalRequest(
                null,
                "session-1",
                "100",
                "1",
                "send_email",
                Map.of("to", "user@example.com"),
                null
        );

        ArgumentCaptor<InternalAuditLogRequest> captor = ArgumentCaptor.forClass(InternalAuditLogRequest.class);
        verify(authServerAuditClient).recordAuditLog(captor.capture());
        InternalAuditLogRequest req = captor.getValue();
        assertThat(req.getAction()).isEqualTo("HITL_REQUEST");
        assertThat(req.getTenantId()).isEqualTo(1L);
        assertThat(req.getResourceType()).isEqualTo("HITL");
        assertThat(req.getMetadata()).containsKey("requestId").containsKey("sessionId");
    }

    @Test
    @DisplayName("approve 최초 처리 시 HITL_APPROVE 감사 기록, 멱등 재호출 시 추가 기록 없음")
    void approve_firstCall_recordsAudit_idempotentRetry_doesNotRecordAgain() throws Exception {
        String requestId = "req-approve";
        String sessionId = "session-approve";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "pending",
                "actionType", "delete"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        hitlManager.approve(requestId, "100", 1L);

        ArgumentCaptor<InternalAuditLogRequest> captor = ArgumentCaptor.forClass(InternalAuditLogRequest.class);
        verify(authServerAuditClient).recordAuditLog(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("HITL_APPROVE");

        // 멱등: 같은 requestId로 재호출 (이미 approved 상태로 Redis 반환)
        String approvedJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "approved",
                "approvedBy", "100"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(approvedJson);
        hitlManager.approve(requestId, "100", 1L);

        // recordAuditLog는 최초 1회만 호출됨 (멱등 재호출 시 추가 기록 없음)
        verify(authServerAuditClient, times(1)).recordAuditLog(any(InternalAuditLogRequest.class));
    }

    @Test
    @DisplayName("reject 최초 처리 시 HITL_REJECT 감사 기록")
    void reject_firstCall_recordsHitlRejectAudit() throws Exception {
        String requestId = "req-reject";
        String sessionId = "session-reject";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", sessionId,
                "tenantId", 1L,
                "status", "pending"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        hitlManager.reject(requestId, "100", "Not allowed", 1L);

        ArgumentCaptor<InternalAuditLogRequest> captor = ArgumentCaptor.forClass(InternalAuditLogRequest.class);
        verify(authServerAuditClient).recordAuditLog(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("HITL_REJECT");
        assertThat(captor.getValue().getMetadata()).containsEntry("reason", "Not allowed");
    }

    @Test
    @DisplayName("tenant mismatch 시 감사 호출 없음 (예외로 조기 반환)")
    void approve_whenTenantMismatch_doesNotCallAudit() throws Exception {
        String requestId = "req-tenant-mismatch";
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "sessionId", "session-x",
                "tenantId", 999L,
                "status", "pending"
        ));
        when(valueOps.get("hitl:request:" + requestId)).thenReturn(requestJson);

        try {
            hitlManager.approve(requestId, "100", 1L);
        } catch (BaseException ignored) { }

        verify(authServerAuditClient, never()).recordAuditLog(any(InternalAuditLogRequest.class));
    }
}
