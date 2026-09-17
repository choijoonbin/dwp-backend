package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.AdmissionClaim;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.SuppressionMatch;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        return admittedRecipient(
                tenantId,
                userId,
                request,
                contract,
                now,
                NotificationAttentionDecision.none());
    }

    public boolean admittedRecipient(
            long tenantId,
            long userId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            Instant now,
            NotificationAttentionDecision attention) {
        return admittedRecipient(
                tenantId, userId, request, contract, now, attention, false, true);
    }

    boolean admittedRecipient(
            long tenantId,
            long userId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            Instant now,
            NotificationAttentionDecision attention,
            boolean collapsed,
            boolean deliveryEnabled) {
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
        NotificationQualityFactContext qualityFact = NotificationQualityFactContext.from(
                tenantId, request, contract, collapsed);
        if (attention.suppress()) {
            complete(
                    tenantId,
                    claim.receiptId(),
                    "SUPPRESSED",
                    attention.reasonCode(),
                    null,
                    null,
                    attention,
                    qualityFact);
            return false;
        }
        if (!deliveryEnabled) {
            complete(
                    tenantId,
                    claim.receiptId(),
                    "SUPPRESSED",
                    "USER_CHANNEL_DISABLED",
                    null,
                    null,
                    attention,
                    qualityFact);
            return false;
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
            complete(
                    tenantId,
                    claim.receiptId(),
                    "SUPPRESSED",
                    "ACTIVE_SUPPRESSION",
                    suppression.suppressionId(),
                    null,
                    attention,
                    qualityFact);
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
                complete(
                        tenantId,
                        claim.receiptId(),
                        "RATE_LIMITED",
                        "MAX_PER_WINDOW",
                        null,
                        windowStartedAt,
                        attention,
                        qualityFact);
                return false;
            }
        }
        complete(
                tenantId,
                claim.receiptId(),
                "ADMITTED",
                suppression == null ? "POLICY_ADMITTED" : "CRITICAL_BYPASS",
                suppression == null ? null : suppression.suppressionId(),
                windowStartedAt,
                attention,
                qualityFact);
        return true;
    }

    private void complete(
            long tenantId,
            UUID receiptId,
            String decision,
            String reasonCode,
            UUID suppressionId,
            Instant windowStartedAt,
            NotificationAttentionDecision attention,
            NotificationQualityFactContext qualityFact) {
        if (attention.matched()) {
            repository.complete(
                    tenantId,
                    receiptId,
                    decision,
                    reasonCode,
                    suppressionId,
                    windowStartedAt,
                    attention);
        } else {
            repository.complete(
                    tenantId,
                    receiptId,
                    decision,
                    reasonCode,
                    suppressionId,
                    windowStartedAt);
        }
        repository.appendQualityFact(tenantId, receiptId, qualityFact);
    }

    static Instant windowStart(Instant now, Duration duration) {
        long seconds = duration.getSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }
}
