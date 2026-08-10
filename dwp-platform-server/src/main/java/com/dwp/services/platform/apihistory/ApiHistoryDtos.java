package com.dwp.services.platform.apihistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiHistoryDtos {

    private ApiHistoryDtos() {
    }

    public record EventResponse(
            UUID historyId,
            Instant occurredAt,
            Instant completedAt,
            Instant ingestedAt,
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
            int statusCode,
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

    public record EventPage(
            List<EventResponse> content,
            String nextCursor,
            int size) {
    }

    public record Summary(
            long totalRequests,
            long successfulRequests,
            long clientErrorRequests,
            long serverErrorRequests,
            double errorRate,
            long p50DurationMs,
            long p95DurationMs,
            long p99DurationMs,
            double requestsPerMinute,
            int activeRoutesOrServices) {
    }

    public record TrendPoint(
            Instant bucket,
            long totalRequests,
            long clientErrors,
            long serverErrors,
            long p95DurationMs) {
    }

    public record RouteMetric(
            String routeId,
            String serviceName,
            String httpMethod,
            String routeTemplate,
            long totalRequests,
            long serverErrors,
            double errorRate,
            long p95DurationMs) {
    }

    public record StatusMetric(
            String statusFamily,
            long count) {
    }

    public record Overview(
            ApiHistoryWindow window,
            String observationPoint,
            Instant from,
            Instant to,
            Instant generatedAt,
            Summary summary,
            List<TrendPoint> trend,
            List<RouteMetric> topRoutes,
            List<StatusMetric> statusDistribution) {
    }

    public record TraceDetail(
            EventResponse selected,
            List<EventResponse> trace) {
    }
}
