package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Service
public class EventCorrelationService {

    private static final Set<String> DOMAINS = Set.of(
            "IDENTITY_ACCESS", "PEOPLE_WORKFORCE", "PROVIDER_OPERATIONS",
            "AI_AUTOMATION", "DATA_GOVERNANCE", "PLATFORM_WORKSPACE");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final EventCorrelationRepository repository;

    public EventCorrelationService(EventCorrelationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public EventEnvelopeDtos.CorrelationPage correlations(
            Long tenantId,
            AuditWindow window,
            String domain,
            String classification,
            String query,
            int page,
            int size) {
        Instant now = Instant.now();
        EventCorrelationCriteria criteria = new EventCorrelationCriteria(
                tenantId,
                now.minus(window.duration()),
                now,
                choice(domain, DOMAINS, "domain"),
                choice(classification, CLASSIFICATIONS, "classification"),
                cleanQuery(query));
        return repository.correlations(
                criteria,
                Math.max(0, page),
                Math.min(100, Math.max(10, size)));
    }

    @Transactional(readOnly = true)
    public EventEnvelopeDtos.CorrelationDetail detail(Long tenantId, String correlationId) {
        String key = cleanCorrelation(correlationId);
        EventEnvelopeDtos.Correlation summary = repository.correlation(tenantId, key)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new EventEnvelopeDtos.CorrelationDetail(
                summary,
                repository.envelopes(tenantId, key));
    }

    private String choice(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Invalid event-correlation " + field + ".");
        }
        return normalized;
    }

    private String cleanQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search query is too long.");
        }
        return normalized;
    }

    private String cleanCorrelation(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid correlation identifier.");
        }
        return value.trim();
    }
}
