package com.dwp.services.main.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.main.client.AuthServerAuditClient;
import com.dwp.services.main.client.InternalAuditLogRequest;
import com.dwp.services.main.dto.HitlApproveResult;
import com.dwp.services.main.dto.HitlRejectResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * HITL (Human-In-The-Loop) Manager
 * 
 * 승인 대기 중인 에이전트 요청을 관리하고,
 * 승인/거절 시 에이전트 세션에 신호를 보냅니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // RedisTemplate과 ObjectMapper는 @RequiredArgsConstructor로 주입되므로 null이 아님
public class HitlManager {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthServerAuditClient authServerAuditClient;
    
    // Redis 키 패턴
    private static final String HITL_REQUEST_PREFIX = "hitl:request:";
    private static final String HITL_SESSION_PREFIX = "hitl:session:";
    private static final long HITL_REQUEST_TTL = 30; // 30분
    private static final long HITL_SESSION_TTL = 60; // 60분
    
    /**
     * HITL 승인 요청 저장
     * 
     * @param requestId 요청 ID
     * @param sessionId 에이전트 세션 ID
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @param actionType 작업 유형 (예: "delete", "send_email")
     * @param context 작업 컨텍스트 (JSON)
     * @param taskId AgentTask의 taskId (선택, 맵핑용)
     * @return 저장된 요청 ID
     */
    public String saveApprovalRequest(
            String requestId,
            String sessionId,
            String userId,
            String tenantId,
            String actionType,
            Map<String, Object> context,
            String taskId) {
        
        // Null 체크
        if (sessionId == null || userId == null || tenantId == null || actionType == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Required parameters cannot be null");
        }
        
        try {
            // 요청 ID 생성 (없는 경우)
            String finalRequestId = requestId != null ? requestId : UUID.randomUUID().toString();
            
            // 요청 데이터 구성 (taskId 포함하여 AgentTask와 맵핑 가능하도록)
            Map<String, Object> requestDataMap = new java.util.HashMap<>();
            requestDataMap.put("requestId", finalRequestId);
            requestDataMap.put("sessionId", sessionId);
            requestDataMap.put("userId", userId);
            requestDataMap.put("tenantId", tenantId);
            requestDataMap.put("actionType", actionType);
            requestDataMap.put("context", context != null ? context : Map.of());
            requestDataMap.put("status", "pending");
            requestDataMap.put("createdAt", System.currentTimeMillis());
            
            // AgentTask와의 맵핑을 위한 taskId 추가 (있는 경우)
            if (taskId != null && !taskId.isEmpty()) {
                requestDataMap.put("taskId", taskId);
                log.debug("HITL request linked to AgentTask: taskId={}, requestId={}", taskId, finalRequestId);
            }
            
            Map<String, Object> requestData = requestDataMap;
            
            String requestKey = HITL_REQUEST_PREFIX + requestData.get("requestId");
            String requestJson = objectMapper.writeValueAsString(requestData);
            
            // Redis에 저장 (TTL: 30분)
            redisTemplate.opsForValue().set(
                    requestKey,
                    requestJson,
                    HITL_REQUEST_TTL,
                    TimeUnit.MINUTES
            );
            
            // 세션 정보 저장 (에이전트가 신호를 받을 수 있도록)
            String sessionKey = HITL_SESSION_PREFIX + sessionId;
            String requestIdValue = finalRequestId;
            redisTemplate.opsForValue().set(
                    sessionKey,
                    requestIdValue,
                    HITL_SESSION_TTL,
                    TimeUnit.MINUTES
            );
            
            // taskId가 있는 경우, taskId -> requestId 맵핑도 저장 (에이전트 재시작 시 복구용)
            if (taskId != null && !taskId.isEmpty()) {
                String taskMappingKey = "hitl:task:" + taskId;
                redisTemplate.opsForValue().set(
                        taskMappingKey,
                        finalRequestId,
                        HITL_SESSION_TTL,
                        TimeUnit.MINUTES
                );
                log.debug("HITL task mapping saved: taskId={} -> requestId={}", taskId, finalRequestId);
            }
            
            log.info("HITL approval request saved: requestId={}, sessionId={}, actionType={}, taskId={}",
                    finalRequestId, sessionId, actionType, taskId != null ? taskId : "N/A");

            recordHitlAudit(finalRequestId, sessionId, tenantId, userId, "HITL_REQUEST", requestDataMap, null);

            return finalRequestId;
            
        } catch (JsonProcessingException e) {
            log.error("Failed to save HITL approval request: requestId={}", requestId, e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to save approval request");
        }
    }
    
    /**
     * HITL 승인 요청 조회
     * 
     * @param requestId 요청 ID
     * @return 요청 데이터 (JSON 문자열)
     */
    public String getApprovalRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Request ID cannot be null or empty");
        }
        String requestKey = HITL_REQUEST_PREFIX + requestId;
        String requestJson = redisTemplate.opsForValue().get(requestKey);
        
        if (requestJson == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Approval request not found: " + requestId);
        }
        
        return requestJson;
    }
    
    /**
     * HITL 승인 처리
     *
     * 이미 approved/rejected인 경우 Redis·신호 없이 기존 sessionId 반환(멱등).
     * headerTenantId와 Redis 저장 tenantId가 다르면 403 TENANT_MISMATCH.
     *
     * @param requestId 요청 ID
     * @param userId 승인한 사용자 ID
     * @param headerTenantId 요청 헤더 X-Tenant-ID
     * @return 세션 ID (에이전트에게 신호 전송용)
     */
    public HitlApproveResult approve(String requestId, String userId, Long headerTenantId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Request ID cannot be null or empty");
        }
        if (userId == null || userId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "User ID cannot be null or empty");
        }
        if (headerTenantId == null) {
            throw new BaseException(ErrorCode.TENANT_MISSING, "X-Tenant-ID is required");
        }
        try {
            String requestJson = getApprovalRequest(requestId);
            @SuppressWarnings("unchecked")
            Map<String, Object> requestData = objectMapper.readValue(requestJson, Map.class);

            Long storedTenantId = getTenantIdFromRequestData(requestData);
            if (storedTenantId != null && !storedTenantId.equals(headerTenantId)) {
                log.warn("HITL approve tenant mismatch: requestId={}, headerTenantId={}, storedTenantId={}",
                        requestId, headerTenantId, storedTenantId);
                throw new BaseException(ErrorCode.TENANT_MISMATCH, "테넌트 정보가 일치하지 않습니다.");
            }

            String status = String.valueOf(requestData.get("status"));
            String sessionId = requestData.get("sessionId").toString();

            // 멱등: 이미 승인/거절된 경우 업데이트·신호 없이 기존 상태 반환 (FE는 409로 수신 후 성공으로 처리)
            if ("approved".equals(status) || "rejected".equals(status)) {
                log.info("HITL approve idempotent: requestId={}, status={}, sessionId={}", requestId, status, sessionId);
                return HitlApproveResult.builder()
                        .sessionId(sessionId)
                        .status(status)
                        .alreadyProcessed(true)
                        .build();
            }

            requestData.put("status", "approved");
            requestData.put("approvedBy", userId);
            requestData.put("approvedAt", System.currentTimeMillis());
            
            String updatedJson = objectMapper.writeValueAsString(requestData);
            String requestKey = HITL_REQUEST_PREFIX + requestId;
            
            redisTemplate.opsForValue().set(
                    requestKey,
                    updatedJson,
                    HITL_REQUEST_TTL,
                    TimeUnit.MINUTES
            );
            
            String signalKey = "hitl:signal:" + sessionId;
            long timestampSeconds = System.currentTimeMillis() / 1000;
            Map<String, Object> signal = Map.of(
                    "type", "approval",
                    "requestId", requestId,
                    "status", "approved",
                    "timestamp", timestampSeconds
            );
            String signalJson = objectMapper.writeValueAsString(signal);
            Duration signalTtl = Duration.ofMinutes(5);
            redisTemplate.opsForValue().set(signalKey, signalJson, signalTtl);
            String channel = "hitl:channel:" + sessionId;
            redisTemplate.convertAndSend(channel, signalJson);
            
            log.info("HITL request approved: requestId={}, sessionId={}, approvedBy={}",
                    requestId, sessionId, userId);

            recordHitlAudit(requestId, sessionId, headerTenantId, userId, "HITL_APPROVE", requestData, null);
            
            return HitlApproveResult.builder()
                    .sessionId(sessionId)
                    .status("approved")
                    .alreadyProcessed(false)
                    .build();
            
        } catch (BaseException e) {
            throw e;
        } catch (JsonProcessingException e) {
            log.error("Failed to approve HITL request: requestId={}", requestId, e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to approve request");
        }
    }
    
    /**
     * HITL 거절 처리
     *
     * 이미 approved/rejected인 경우 Redis·신호 없이 기존 sessionId 반환(멱등).
     * headerTenantId와 Redis 저장 tenantId가 다르면 403 TENANT_MISMATCH.
     * @return 거절 결과 (sessionId, status, reason, alreadyProcessed). 이미 처리된 경우 409 반환용
     */
    public HitlRejectResult reject(String requestId, String userId, String reason, Long headerTenantId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Request ID cannot be null or empty");
        }
        if (userId == null || userId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "User ID cannot be null or empty");
        }
        if (headerTenantId == null) {
            throw new BaseException(ErrorCode.TENANT_MISSING, "X-Tenant-ID is required");
        }
        try {
            String requestJson = getApprovalRequest(requestId);
            @SuppressWarnings("unchecked")
            Map<String, Object> requestData = objectMapper.readValue(requestJson, Map.class);

            Long storedTenantId = getTenantIdFromRequestData(requestData);
            if (storedTenantId != null && !storedTenantId.equals(headerTenantId)) {
                log.warn("HITL reject tenant mismatch: requestId={}, headerTenantId={}, storedTenantId={}",
                        requestId, headerTenantId, storedTenantId);
                throw new BaseException(ErrorCode.TENANT_MISMATCH, "테넌트 정보가 일치하지 않습니다.");
            }

            String status = String.valueOf(requestData.get("status"));
            String sessionId = requestData.get("sessionId").toString();

            if ("approved".equals(status) || "rejected".equals(status)) {
                log.info("HITL reject idempotent: requestId={}, status={}, sessionId={}", requestId, status, sessionId);
                String storedReason = requestData.get("reason") != null ? requestData.get("reason").toString() : null;
                return HitlRejectResult.builder()
                        .sessionId(sessionId)
                        .status(status)
                        .reason(storedReason)
                        .alreadyProcessed(true)
                        .build();
            }

            requestData.put("status", "rejected");
            requestData.put("rejectedBy", userId);
            requestData.put("rejectedAt", System.currentTimeMillis());
            requestData.put("reason", reason != null ? reason : "User rejected");
            
            String updatedJson = objectMapper.writeValueAsString(requestData);
            String requestKey = HITL_REQUEST_PREFIX + requestId;
            redisTemplate.opsForValue().set(requestKey, updatedJson, HITL_REQUEST_TTL, TimeUnit.MINUTES);
            
            String signalKey = "hitl:signal:" + sessionId;
            long timestampSeconds = System.currentTimeMillis() / 1000;
            Map<String, Object> signal = Map.of(
                    "type", "rejection",
                    "requestId", requestId,
                    "status", "rejected",
                    "reason", reason != null ? reason : "User rejected",
                    "timestamp", timestampSeconds
            );
            String signalJson = objectMapper.writeValueAsString(signal);
            Duration signalTtl = Duration.ofMinutes(5);
            redisTemplate.opsForValue().set(signalKey, signalJson, signalTtl);
            String channel = "hitl:channel:" + sessionId;
            redisTemplate.convertAndSend(channel, signalJson);
            
            log.info("HITL request rejected: requestId={}, sessionId={}, rejectedBy={}, reason={}",
                    requestId, sessionId, userId, reason);

            recordHitlAudit(requestId, sessionId, headerTenantId, userId, "HITL_REJECT", requestData,
                    Map.of("reason", reason != null ? reason : "User rejected"));
            
            return HitlRejectResult.builder()
                    .sessionId(sessionId)
                    .status("rejected")
                    .reason(reason != null ? reason : "User rejected")
                    .alreadyProcessed(false)
                    .build();
            
        } catch (BaseException e) {
            throw e;
        } catch (JsonProcessingException e) {
            log.error("Failed to reject HITL request: requestId={}", requestId, e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to reject request");
        }
    }
    
    /**
     * HITL 신호 조회 (에이전트가 사용)
     * 
     * @param sessionId 세션 ID
     * @return 신호 데이터 (JSON 문자열) 또는 null
     */
    public String getSignal(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Session ID cannot be null or empty");
        }
        String signalKey = "hitl:signal:" + sessionId;
        return redisTemplate.opsForValue().get(signalKey);
    }
    
    /**
     * taskId로 HITL 요청 ID 조회
     * 
     * 에이전트 재시작 시 AgentTask의 taskId를 통해 승인 대기 중인 HITL 요청을 찾을 수 있습니다.
     * 
     * @param taskId AgentTask의 taskId
     * @return HITL 요청 ID 또는 null
     */
    public String getRequestIdByTaskId(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return null;
        }
        String taskMappingKey = "hitl:task:" + taskId;
        String requestId = redisTemplate.opsForValue().get(taskMappingKey);
        if (requestId != null) {
            log.debug("Found HITL request for taskId: taskId={}, requestId={}", taskId, requestId);
        }
        return requestId;
    }
    
    /**
     * HITL 요청 데이터에서 taskId 추출
     * 
     * @param requestId HITL 요청 ID
     * @return taskId 또는 null
     */
    public String getTaskIdFromRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return null;
        }
        try {
            String requestJson = getApprovalRequest(requestId);
            @SuppressWarnings("unchecked")
            Map<String, Object> requestData = objectMapper.readValue(requestJson, Map.class);
            Object taskIdObj = requestData.get("taskId");
            return taskIdObj != null ? taskIdObj.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to extract taskId from HITL request: requestId={}", requestId, e);
            return null;
        }
    }

    private Long getTenantIdFromRequestData(Map<String, Object> requestData) {
        Object t = requestData.get("tenantId");
        if (t == null) return null;
        if (t instanceof Number) return ((Number) t).longValue();
        try {
            return Long.parseLong(t.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void recordHitlAudit(String requestId, String sessionId, Object tenantIdObj, String userId,
                                 String action, Map<String, Object> requestData, Map<String, Object> extraMetadata) {
        Long tenantId = null;
        if (tenantIdObj instanceof Number) {
            tenantId = ((Number) tenantIdObj).longValue();
        } else if (tenantIdObj != null) {
            try {
                tenantId = Long.parseLong(tenantIdObj.toString());
            } catch (NumberFormatException ignored) { }
        }
        if (tenantId == null) return;
        Long actorUserId = null;
        try {
            actorUserId = Long.parseLong(userId);
        } catch (NumberFormatException ignored) { }
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("sessionId", sessionId);
        metadata.put("actionType", requestData != null ? requestData.get("actionType") : null);
        metadata.put("timestamp", System.currentTimeMillis());
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        try {
            authServerAuditClient.recordAuditLog(InternalAuditLogRequest.builder()
                    .tenantId(tenantId)
                    .actorUserId(actorUserId)
                    .action(action)
                    .resourceType("HITL")
                    .resourceId(null)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record HITL audit log: requestId={}, action={}", requestId, action, e);
        }
    }
}
