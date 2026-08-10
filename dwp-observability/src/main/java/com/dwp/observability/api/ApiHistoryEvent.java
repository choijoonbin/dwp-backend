package com.dwp.observability.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Privacy-minimized HTTP exchange summary. Request bodies, response bodies, query strings,
 * credentials, cookies, and raw network identifiers are deliberately absent.
 */
public record ApiHistoryEvent(
        UUID historyId,
        Instant occurredAt,
        Instant completedAt,
        Long tenantId,
        String actorType,
        String actorId,
        String authType,
        String serviceName,
        String serviceVersion,
        String serviceInstance,
        String environment,
        String observationPoint,
        String routeId,
        String httpMethod,
        String routeTemplate,
        String requestPath,
        String httpScheme,
        String httpProtocol,
        Integer statusCode,
        String outcome,
        long durationMs,
        Long requestSizeBytes,
        Long responseSizeBytes,
        String correlationId,
        String traceId,
        String spanId,
        String parentSpanId,
        String clientAddressHash,
        String userAgentFamily,
        String userAgentHash,
        String errorType,
        String capturePolicyVersion) {
}
