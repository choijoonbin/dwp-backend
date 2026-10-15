package com.dwp.services.people.hris.experience;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Atomic, local-only PDX-012 handoff for EmployeeChangeApplied.v1. */
@Service
public class HrisExperienceNotificationHandoff {

    static final String SOURCE = "urn:dwp:people:hrm:dwp-experience";
    static final String EVENT_TYPE =
            "EmployeeChangeAppliedNotificationRequested.v1";
    static final String TYPE_KEY = "HCM.EMPLOYEE_CHANGE_APPLIED";
    static final String TEMPLATE_KEY = "hcm.employee-change.applied";
    static final long TEMPLATE_VERSION = 1L;
    static final String TEMPLATE_MODEL_DIGEST =
            "24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784";

    private final boolean enabled;
    private final HrisExperienceNotificationRepository repository;
    private final HrisExperienceRecipientAuthority recipients;
    private final ObjectMapper mapper;

    @Autowired
    HrisExperienceNotificationHandoff(
            @Value("${dwp.hris.experience.enabled:false}") boolean enabled,
            HrisExperienceNotificationRepository repository,
            HrisExperienceRecipientAuthority recipients,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.repository = repository;
        this.recipients = recipients;
        this.mapper = mapper;
    }

    @Transactional
    public Outcome onApplied(
            Xcon022EmployeeChangeAppliedV1 fact,
            UUID targetPersonPublicId,
            long applyingUserId) {
        if (!enabled) return Outcome.FEATURE_DISABLED;
        if (fact == null || targetPersonPublicId == null || applyingUserId < 1
                || !repository.exactOriginExists(
                        fact.tenantId(), fact.eventId(), fact.requestPublicId())) {
            throw new IllegalStateException(
                    "EmployeeChangeApplied notification origin is not durable.");
        }
        UUID intentId = deterministicIntent(fact.tenantId(), fact.eventId());
        HrisExperienceNotificationRepository.IntentDecision decision =
                new HrisExperienceNotificationRepository.IntentDecision(
                        intentId, fact.tenantId(), fact.eventId(), fact.requestPublicId(),
                        null, applyingUserId, fact.correlationId(), fact.occurredAt());
        Optional<HrisExperienceNotificationRepository.Subject> subject =
                repository.activeSubject(fact.tenantId(), targetPersonPublicId);
        if (subject.isEmpty()) {
            repository.appendOmission(decision, "TARGET_SUBJECT_NOT_ACTIVE");
            return Outcome.OMITTED_NO_ELIGIBLE_RECIPIENT;
        }
        Optional<HrisExperienceRecipientAuthority.VerifiedRecipient> resolved =
                recipients.resolve(
                        fact.tenantId(), targetPersonPublicId, fact.occurredAt());
        if (resolved.isEmpty() || !valid(resolved.get(), fact, targetPersonPublicId)) {
            repository.appendOmission(decision, "NO_AUTHORITATIVE_USER_MAPPING");
            return Outcome.OMITTED_NO_ELIGIBLE_RECIPIENT;
        }
        long recipient = resolved.get().userId();
        if (recipient == applyingUserId) {
            repository.appendOmission(decision, "ACTOR_EXCLUDED");
            return Outcome.OMITTED_ACTOR_EXCLUDED;
        }
        decision = new HrisExperienceNotificationRepository.IntentDecision(
                intentId, fact.tenantId(), fact.eventId(), fact.requestPublicId(),
                recipient, applyingUserId, fact.correlationId(), fact.occurredAt());
        HrisNotificationIntentV1 intent = new HrisNotificationIntentV1(
                intentId, fact.eventId(), fact.tenantId(), "hcm", TYPE_KEY,
                "EMPLOYEE_SELF_SERVICE", List.of(recipient), List.of(applyingUserId),
                "hris:employee-change:" + fact.requestPublicId(),
                locale(subject.get().locale()), "EMPLOYEE_CHANGE_APPLIED", null,
                "person:" + targetPersonPublicId,
                "employee-change:" + fact.requestPublicId(), TEMPLATE_KEY,
                TEMPLATE_VERSION, TEMPLATE_MODEL_DIGEST, "INTERNAL", false,
                null, fact.occurredAt(), "hrm002:" + fact.eventId(),
                fact.correlationId());
        byte[] intentBytes = HrisExperienceDigests.canonicalBytes(mapper, intent);
        repository.appendReady(
                decision, intent,
                HrisExperienceDigests.sha256(
                        new String(intentBytes, StandardCharsets.UTF_8)));
        return Outcome.READY_LOCAL_ONLY;
    }

    private boolean valid(
            HrisExperienceRecipientAuthority.VerifiedRecipient recipient,
            Xcon022EmployeeChangeAppliedV1 fact,
            UUID targetPersonPublicId) {
        return recipient.tenantId() == fact.tenantId()
                && targetPersonPublicId.equals(recipient.personPublicId())
                && recipient.userId() > 0
                && recipient.evidenceRevision() != null
                && recipient.evidenceRevision().matches("[0-9a-f]{64}")
                && recipient.validUntil() != null
                && recipient.validUntil().isAfter(fact.occurredAt());
    }

    private UUID deterministicIntent(long tenantId, UUID sourceEventId) {
        return UUID.nameUUIDFromBytes(("hrm002-notification-intent\n" + tenantId
                + '\n' + sourceEventId).getBytes(StandardCharsets.UTF_8));
    }

    private String locale(String value) {
        String normalized = value == null ? "ko-KR" : value.replace('_', '-');
        return normalized.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
                ? normalized : "ko-KR";
    }

    public enum Outcome {
        FEATURE_DISABLED,
        READY_LOCAL_ONLY,
        OMITTED_NO_ELIGIBLE_RECIPIENT,
        OMITTED_ACTOR_EXCLUDED
    }
}
