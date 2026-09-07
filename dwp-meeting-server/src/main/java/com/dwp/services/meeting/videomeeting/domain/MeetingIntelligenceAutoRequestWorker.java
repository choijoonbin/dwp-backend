package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.AutoRequest;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.RunBinding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "dwp.meeting.intelligence.auto-request",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class MeetingIntelligenceAutoRequestWorker {

    private final MeetingIntelligenceAutoRequestTransactions transactions;
    private final MeetingIntelligenceAutoRequestRepository requests;
    private final VideoMeetingIntelligenceService intelligence;
    private final MeetingIntelligenceAutoRequestProperties properties;

    MeetingIntelligenceAutoRequestWorker(
            MeetingIntelligenceAutoRequestTransactions transactions,
            MeetingIntelligenceAutoRequestRepository requests,
            VideoMeetingIntelligenceService intelligence,
            MeetingIntelligenceAutoRequestProperties properties) {
        this.transactions = transactions;
        this.requests = requests;
        this.intelligence = intelligence;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString =
            "${dwp.meeting.intelligence.auto-request.poll-delay:PT10S}")
    int dispatch() {
        if (!properties.isEnabled() || !properties.valid()) return 0;
        int processed = 0;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            AutoRequest request;
            try {
                request = transactions.claim();
            } catch (RuntimeException unavailable) {
                return processed;
            }
            if (request == null) return processed;
            process(request);
            processed++;
        }
        return processed;
    }

    private void process(AutoRequest request) {
        MeetingRequestContext.Subject subject = subject(request);
        String correlationId = "meeting-auto-intelligence:" + request.requestId();
        boolean executionInvoked = false;
        MeetingRequestContext.set(subject);
        try {
            intelligence.ensureAutomaticExecutionReadiness(request, correlationId);
            executionInvoked = true;
            VideoMeetingIntelligenceDtos.RunResponse response = intelligence.createRun(
                    request.meetingId(),
                    new VideoMeetingIntelligenceDtos.CreateRunCommand(
                            request.sourceArtifactId(), request.outputLanguage(),
                            request.expectedContentPlanVersion()),
                    request.intelligenceIdempotencyKey(), correlationId);
            settle(subject, request, response);
        } catch (RuntimeException failure) {
            recover(subject, request, failure, executionInvoked);
        } finally {
            MeetingRequestContext.clear();
        }
    }

    private void settle(
            MeetingRequestContext.Subject subject,
            AutoRequest request,
            VideoMeetingIntelligenceDtos.RunResponse response) {
        if (response == null || response.runId() == null || response.state() == null) {
            throw new IllegalStateException("Automatic intelligence returned no run evidence.");
        }
        if ("SUCCEEDED".equals(response.state())) {
            transactions.succeed(subject, request, response.runId());
        } else if ("FAILED".equals(response.state())) {
            String failureCode = safeFailureCode(response.failureCode());
            if (retryable(failureCode)) {
                transactions.retry(request, failureCode);
            } else {
                transactions.fail(subject, request, response.runId(), failureCode);
            }
        }
        // RUNNING is intentionally left leased. A crash or concurrent run is reclaimed
        // with the same deterministic idempotency key after this lease expires.
    }

    private void recover(
            MeetingRequestContext.Subject subject,
            AutoRequest request,
            RuntimeException failure,
            boolean executionInvoked) {
        try {
            RunBinding run = requests.runByIdempotency(request).orElse(null);
            if (run != null) {
                if ("SUCCEEDED".equals(run.state())) {
                    transactions.succeed(subject, request, run.runId());
                } else if ("FAILED".equals(run.state())) {
                    String failureCode = safeFailureCode(run.failureCode());
                    if (retryable(failureCode)) {
                        transactions.retry(request, failureCode);
                    } else {
                        transactions.fail(subject, request, run.runId(), failureCode);
                    }
                }
                return;
            }
            if (!executionInvoked || transientFailure(failure)) {
                transactions.retry(request, "DEPENDENCIES_NOT_READY");
            } else {
                transactions.failWithoutRun(subject, request, "GOVERNANCE_POLICY_CHANGED");
            }
        } catch (RuntimeException staleOrUnavailable) {
            failure.addSuppressed(staleOrUnavailable);
        }
    }

    private boolean transientFailure(RuntimeException failure) {
        return !(failure instanceof BaseException base)
                || base.getErrorCode() == ErrorCode.EXTERNAL_SERVICE_ERROR;
    }

    private boolean retryable(String failureCode) {
        return "DEPENDENCIES_NOT_READY".equals(failureCode)
                || "PROVIDER_NOT_READY".equals(failureCode)
                || "PROVIDER_EXECUTION_UNAVAILABLE".equals(failureCode)
                || "TRANSCRIPT_READ_FAILED".equals(failureCode)
                || "REPORT_PROTECTION_FAILED".equals(failureCode);
    }

    private String safeFailureCode(String failureCode) {
        return failureCode != null
                && failureCode.matches("^[A-Z][A-Z0-9_]{2,47}$")
                ? failureCode : "INTELLIGENCE_EXECUTION_FAILED";
    }

    private MeetingRequestContext.Subject subject(AutoRequest request) {
        return new MeetingRequestContext.Subject(
                request.requestedBy(), request.tenantId(), null,
                "Meeting automatic intelligence", Set.of("SYSTEM_AUTO_INTELLIGENCE"),
                Set.of(), Set.of());
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingIntelligenceAutoRequestProperties.class)
class MeetingIntelligenceAutoRequestConfiguration {
}
