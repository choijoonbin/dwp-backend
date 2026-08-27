package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProviderSupportPostReviewEvidenceService {

    private final ProviderSupportPostReviewEvidenceRepository repository;
    private final ProviderSupportSessionLifecycleService lifecycleService;

    public ProviderSupportPostReviewEvidenceService(
            ProviderSupportPostReviewEvidenceRepository repository,
            ProviderSupportSessionLifecycleService lifecycleService) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
    }

    @Transactional(readOnly = true)
    public ProviderSupportPostReviewEvidenceDtos.Evidence evidence(UUID requestId) {
        ProviderRequestContext.requirePermission("SUPPORT_POST_REVIEW");
        try {
            lifecycleService.expireElapsedSessions();
            ProviderRequestContext.Actor actor = ProviderRequestContext.require();
            ProviderSupportPostReviewEvidenceRepository.Context context = repository.context(requestId)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            if (context.requesterOperatorId().equals(actor.operatorId())) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "An independent operator must perform the post-access review.");
            }
            ProviderSupportPostReviewEvidenceRepository.Statistics statistics =
                    repository.statistics(context);
            List<ProviderSupportPostReviewEvidenceRepository.EvidenceRow> rows =
                    repository.events(context);
            return evidence(context, statistics, rows);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Post-access evidence is temporarily unavailable.",
                    exception);
        }
    }

    ProviderSupportPostReviewEvidenceDtos.Evidence evidence(
            ProviderSupportPostReviewEvidenceRepository.Context context,
            ProviderSupportPostReviewEvidenceRepository.Statistics statistics,
            List<ProviderSupportPostReviewEvidenceRepository.EvidenceRow> rows) {
        long mixedTenantRows = rows.stream()
                .filter(row -> !context.tenantId().equals(row.tenantId()))
                .count();
        long mixedSessionRows = rows.stream()
                .filter(row -> !context.supportSessionId().equals(row.supportSessionId()))
                .count();
        long invalidCorrelationRows = rows.stream()
                .filter(row -> !ProviderSupportPostReviewEvidencePolicy.canonicalCorrelation(
                        row.correlationId()))
                .count();
        boolean terminal = List.of("COMPLETED", "REVIEWED").contains(context.requestState())
                && List.of("REVOKED", "EXPIRED").contains(context.sessionState())
                && context.completedAt() != null;
        boolean countsReconcile = statistics.totalCount()
                == statistics.allowedCount() + statistics.deniedCount() + statistics.invalidCount();
        boolean complete = terminal
                && countsReconcile
                && statistics.invalidCount() == 0
                && statistics.crossTenantCount() == 0
                && mixedTenantRows == 0
                && mixedSessionRows == 0
                && invalidCorrelationRows == 0
                && rows.size() == Math.min(
                        statistics.allowedCount() + statistics.deniedCount(),
                        ProviderSupportPostReviewEvidenceRepository.DISPLAY_LIMIT);
        boolean noUseConfirmed = complete && statistics.allowedCount() == 0;
        String readiness = !complete
                ? "INCOMPLETE"
                : noUseConfirmed ? "READY_NO_USE" : "READY_WITH_USE";
        List<String> anomalies = new ArrayList<>();
        if (statistics.deniedCount() > 0) anomalies.add("DENIED_ATTEMPTS");
        if (statistics.invalidCount() > 0) anomalies.add("MALFORMED_EVIDENCE");
        if (statistics.crossTenantCount() + mixedTenantRows > 0) {
            anomalies.add("TENANT_BINDING_MISMATCH");
        }
        if (mixedSessionRows > 0) anomalies.add("SESSION_BINDING_MISMATCH");
        if (invalidCorrelationRows > 0) anomalies.add("INVALID_CORRELATION_EVIDENCE");
        List<ProviderSupportPostReviewEvidenceDtos.Event> events = complete
                ? rows.stream().map(this::event).toList()
                : List.of();
        return new ProviderSupportPostReviewEvidenceDtos.Evidence(
                context.requestId(), context.supportSessionId(), context.tenantId(),
                context.sessionState(), context.startedAt(), context.completedAt(),
                List.copyOf(context.grantedScopes()), List.copyOf(statistics.observedScopes()),
                statistics.totalCount(), statistics.allowedCount(), statistics.deniedCount(),
                complete,
                statistics.allowedCount() + statistics.deniedCount() > rows.size(),
                noUseConfirmed, readiness, List.copyOf(anomalies), events);
    }

    private ProviderSupportPostReviewEvidenceDtos.Event event(
            ProviderSupportPostReviewEvidenceRepository.EvidenceRow row) {
        return new ProviderSupportPostReviewEvidenceDtos.Event(
                row.auditEventId(), row.occurredAt(), row.decision(), row.method(),
                row.routeTemplate(), row.scope(), row.outcome(), row.reasonCode(),
                row.correlationId());
    }
}
