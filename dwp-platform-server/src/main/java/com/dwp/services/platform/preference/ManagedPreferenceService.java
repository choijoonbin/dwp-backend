package com.dwp.services.platform.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ManagedPreferenceService {

    private static final int MAX_REQUESTED_VALUE_BYTES = 4_096;
    private static final int MAX_REQUESTED_VALUE_DEPTH = 4;
    private static final int MAX_REQUESTED_VALUE_NODES = 50;
    private static final Set<String> REQUEST_STATES = Set.of(
            "ALL", "PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED");

    private final ManagedPreferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformAuditService auditService;

    public ManagedPreferenceService(
            ManagedPreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ManagedPreferenceDtos.ManagedPreferencePolicy policy(Long tenantId) {
        return repository.policy(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ManagedPreferenceDtos.PreferenceExceptionRequest> myRequests(
            Long tenantId, Long userId) {
        return repository.userRequests(tenantId, userId);
    }

    @Transactional
    public ManagedPreferenceDtos.PreferenceExceptionRequest requestException(
            Long tenantId,
            Long userId,
            String correlationId,
            ManagedPreferenceDtos.CreateExceptionRequest request) {
        validateRequestedValue(request.requestedValue());
        ManagedPreferenceDtos.ManagedPreferencePolicy policy = repository.policy(tenantId);
        ManagedPreferenceDtos.ManagedPreferenceRule rule = policy.rules().stream()
                .filter(candidate -> candidate.preferencePath().equals(request.preferencePath()))
                .findFirst()
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The requested preference path is not managed by this tenant policy."));
        if (!rule.exceptionAllowed()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "This managed preference does not allow exception requests.");
        }
        ManagedPreferenceDtos.PreferenceExceptionRequest created = repository.createRequest(
                tenantId, userId, policy, rule, request);
        auditService.success(
                tenantId, userId, "personal-preference.exception-requested",
                "PREFERENCE_EXCEPTION_REQUEST", created.requestId().toString(), correlationId,
                null, snapshot(created));
        return created;
    }

    @Transactional
    public ManagedPreferenceDtos.PreferenceExceptionRequest cancelRequest(
            Long tenantId,
            Long userId,
            String correlationId,
            UUID requestId,
            long version) {
        ManagedPreferenceDtos.PreferenceExceptionRequest before = repository.userRequests(
                        tenantId, userId).stream()
                .filter(request -> request.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        ManagedPreferenceDtos.PreferenceExceptionRequest cancelled = repository.cancelRequest(
                tenantId, userId, requestId, version);
        auditService.success(
                tenantId, userId, "personal-preference.exception-cancelled",
                "PREFERENCE_EXCEPTION_REQUEST", requestId.toString(), correlationId,
                snapshot(before), snapshot(cancelled));
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<ManagedPreferenceDtos.PreferenceExceptionRequest> adminRequests(
            Long tenantId, String state) {
        String normalized = state == null || state.isBlank() ? "ALL" : state.trim().toUpperCase();
        if (!REQUEST_STATES.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown exception request state.");
        }
        return repository.adminRequests(tenantId, normalized);
    }

    @Transactional
    public ManagedPreferenceDtos.PreferenceExceptionRequest decideRequest(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID requestId,
            ManagedPreferenceDtos.DecideExceptionRequest request) {
        ManagedPreferenceDtos.PreferenceExceptionRequest before = repository.adminRequests(
                        tenantId, "ALL").stream()
                .filter(candidate -> candidate.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        ManagedPreferenceDtos.PreferenceExceptionRequest decided = repository.decideRequest(
                tenantId, actorId, requestId, request);
        auditService.success(
                tenantId, actorId, "personal-preference.exception-decided",
                "PREFERENCE_EXCEPTION_REQUEST", requestId.toString(), correlationId,
                snapshot(before), snapshot(decided));
        return decided;
    }

    @Transactional
    public int expireDueRequests() {
        return repository.expireDueRequests();
    }

    private void validateRequestedValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "The requested preference value is required.");
        }
        if (serializedSize(value) > MAX_REQUESTED_VALUE_BYTES
                || depth(value) > MAX_REQUESTED_VALUE_DEPTH
                || nodeCount(value) > MAX_REQUESTED_VALUE_NODES) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The requested preference value exceeds the configured limits.");
        }
    }

    private int serializedSize(JsonNode value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private int depth(JsonNode node) {
        if (!node.isContainerNode() || node.isEmpty()) return 1;
        int maximum = 0;
        for (JsonNode child : node) maximum = Math.max(maximum, depth(child));
        return maximum + 1;
    }

    private int nodeCount(JsonNode node) {
        int count = 1;
        for (JsonNode child : node) count += nodeCount(child);
        return count;
    }

    private Map<String, Object> snapshot(
            ManagedPreferenceDtos.PreferenceExceptionRequest request) {
        return Map.of(
                "requestId", request.requestId(),
                "userId", request.userId(),
                "preferencePath", request.preferencePath(),
                "requestState", request.requestState(),
                "assignedOwnerRef", request.assignedOwnerRef(),
                "version", request.version());
    }
}
