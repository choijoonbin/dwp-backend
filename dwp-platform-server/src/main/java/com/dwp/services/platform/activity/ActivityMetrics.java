package com.dwp.services.platform.activity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Low-cardinality Activity read telemetry. Never tag tenant, user, object or event identifiers. */
@Component
final class ActivityMetrics {
    private static final String METRIC = "dwp.activity.read.duration";
    private static final Set<String> SOURCE_AVAILABLE = Set.of("READY", "SYNCING");
    private final MeterRegistry registry;
    private final boolean enabled;

    ActivityMetrics(
            MeterRegistry registry,
            @Value("${dwp.platform.activity.metrics.enabled:false}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    WorkspaceDtos.ActivityFeed list(Supplier<WorkspaceDtos.ActivityFeed> read) {
        return observe("LIST", read, value -> value.events().isEmpty() ? "EMPTY" : "SUCCESS");
    }

    WorkspaceDtos.ActivityEvent detail(Supplier<WorkspaceDtos.ActivityEvent> read) {
        return observe("DETAIL", read, ignored -> "SUCCESS");
    }

    WorkspaceDtos.ExecutionSummary summary(Supplier<WorkspaceDtos.ExecutionSummary> read) {
        return observe("SUMMARY", read, value -> value.total() == 0 ? "EMPTY" : "SUCCESS");
    }

    ActivityEvidenceDtos.Evidence evidence(Supplier<ActivityEvidenceDtos.Evidence> read) {
        return observe("EVIDENCE", read, ActivityMetrics::evidenceResult);
    }

    ActivityEvidenceDtos.Evidence agentEvidence(Supplier<ActivityEvidenceDtos.Evidence> read) {
        return observe("AGENT_EVIDENCE", read, ActivityMetrics::evidenceResult);
    }

    ActivityEvidenceDtos.SourceStatuses sources(
            Supplier<ActivityEvidenceDtos.SourceStatuses> read) {
        return observe("SOURCE_STATUS", read, ActivityMetrics::sourceResult);
    }

    private <T> T observe(String operation, Supplier<T> read, Function<T, String> result) {
        if (!enabled) return read.get();
        long started = System.nanoTime();
        String boundedResult = "ERROR";
        try {
            T value = read.get();
            boundedResult = result.apply(value);
            return value;
        } catch (RuntimeException error) {
            boundedResult = failureResult(error);
            throw error;
        } finally {
            Timer.builder(METRIC)
                    .description("Authorized Activity read latency by bounded operation and result")
                    .publishPercentileHistogram()
                    .tag("operation", operation)
                    .tag("result", boundedResult)
                    .register(registry)
                    .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        }
    }

    private static String evidenceResult(ActivityEvidenceDtos.Evidence evidence) {
        if ("RESTRICTED".equals(evidence.auditAccess())) return "RESTRICTED";
        return switch (evidence.integrityStatus()) {
            case "VERIFIED" -> "SUCCESS";
            case "PENDING" -> "PENDING";
            case "FAILED" -> "FAILED";
            default -> "UNAVAILABLE";
        };
    }

    private static String sourceResult(ActivityEvidenceDtos.SourceStatuses value) {
        if (value.sources().isEmpty()) return "EMPTY";
        if (value.sources().stream().anyMatch(source -> "STALE".equals(source.status()))) {
            return "STALE";
        }
        long available = value.sources().stream()
                .filter(source -> SOURCE_AVAILABLE.contains(source.status())).count();
        if (available == value.sources().size()
                && value.sources().stream().allMatch(source -> "READY".equals(source.status()))) {
            return "SUCCESS";
        }
        return available > 0 ? "PARTIAL" : "UNAVAILABLE";
    }

    private static String failureResult(RuntimeException error) {
        if (!(error instanceof BaseException base)) return "ERROR";
        HttpStatus status = base.getErrorCode().getHttpStatus();
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) return "FORBIDDEN";
        if (status == HttpStatus.NOT_FOUND) return "NOT_FOUND";
        return status.is4xxClientError() ? "INVALID" : "ERROR";
    }
}
