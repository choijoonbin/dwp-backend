package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.AdmissionClaim;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.SuppressionMatch;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.TemplateContract;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationDeliveryAdmissionService {

    private static final String CHANNEL = "IN_APP";

    private final NotificationDeliveryAdmissionRepository repository;
    private final Duration rateWindow;

    public NotificationDeliveryAdmissionService(
            NotificationDeliveryAdmissionRepository repository,
            @Value("${dwp.notification.admission.window:PT1H}") Duration rateWindow) {
        if (rateWindow.compareTo(Duration.ofMinutes(1)) < 0
                || rateWindow.compareTo(Duration.ofDays(1)) > 0
                || rateWindow.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Notification admission window must be between one minute and one day.");
        }
        this.repository = repository;
        this.rateWindow = rateWindow;
    }

    public List<Long> admittedRecipients(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            Instant now) {
        List<Long> admitted = new ArrayList<>();
        for (Long userId : request.recipientUserIds().stream().distinct().toList()) {
            if (admittedRecipient(tenantId, userId, request, contract, now)) {
                admitted.add(userId);
            }
        }
        return List.copyOf(admitted);
    }

    public boolean admittedRecipient(
            long tenantId,
            long userId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            Instant now) {
        AdmissionClaim claim = repository.claim(
                tenantId,
                request.sourceEventId(),
                contract.typeVersionId(),
                userId,
                CHANNEL);
        if (!claim.claimed()) {
            if ("PENDING".equals(claim.decision())) {
                throw new IllegalStateException(
                        "A notification admission decision remained pending.");
            }
            return "ADMITTED".equals(claim.decision());
        }
        SuppressionMatch suppression = repository.matchingSuppression(
                tenantId,
                contract.ownerAppKey(),
                contract.typeKey(),
                CHANNEL,
                now);
        boolean critical = "URGENT".equals(contract.priority())
                || "CRITICAL".equals(contract.urgency());
        if (suppression != null && !(suppression.criticalBypass() && critical)) {
            repository.complete(
                    tenantId,
                    claim.receiptId(),
                    "SUPPRESSED",
                    "ACTIVE_SUPPRESSION",
                    suppression.suppressionId(),
                    null);
            return false;
        }
        Integer maximum = repository.maximumPerWindow(
                tenantId, contract.ownerAppKey(), contract.typeKey(), CHANNEL);
        Instant windowStartedAt = null;
        if (maximum != null) {
            windowStartedAt = windowStart(now, rateWindow);
            if (!repository.incrementWindow(
                    tenantId,
                    userId,
                    contract.typeVersionId(),
                    CHANNEL,
                    windowStartedAt,
                    Math.toIntExact(rateWindow.getSeconds()),
                    maximum)) {
                repository.complete(
                        tenantId,
                        claim.receiptId(),
                        "RATE_LIMITED",
                        "MAX_PER_WINDOW",
                        null,
                        windowStartedAt);
                return false;
            }
        }
        repository.complete(
                tenantId,
                claim.receiptId(),
                "ADMITTED",
                suppression == null ? "POLICY_ADMITTED" : "CRITICAL_BYPASS",
                suppression == null ? null : suppression.suppressionId(),
                windowStartedAt);
        return true;
    }

    static Instant windowStart(Instant now, Duration duration) {
        long seconds = duration.getSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }
}
