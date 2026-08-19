package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProposalStatus;
import static com.dwp.services.platform.mail.MailTypes.ProposalType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailAiActionCatalogTest {

    @Test
    void everyActionHasAnExplicitGovernedPolicy() {
        assertThat(MailAiActionCatalog.supportedActions())
                .containsExactlyInAnyOrder(ProposalType.values());
    }

    @Test
    void rejectsAProposalWhoseTargetWasChangedOutsideTheGovernedPolicy() {
        MailDtos.ActionProposal proposal = proposal(
                ProposalType.CREATE_CALENDAR_EVENT,
                "APP.HCM", "VIEW", "/hr/absence?request=open", "MEDIUM");

        assertThatThrownBy(() -> MailAiActionCatalog.validate(proposal))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("governed action policy");
    }

    @Test
    void keepsMailNotificationsInsideTheMailBoundary() {
        MailAiActionCatalog.Policy policy = MailAiActionCatalog.validate(proposal(
                ProposalType.ESCALATE_NOTIFICATION,
                "APP.MAIL", "UPDATE", "/mail/inbox", "LOW"));

        assertThat(policy.crossApplication()).isFalse();
    }

    @Test
    void rejectsUnknownContractVersionsAndIncompletePayloads() {
        MailDtos.ActionProposal unknownVersion = proposal(
                ProposalType.CREATE_CALENDAR_EVENT,
                "APP.CALENDAR", "CREATE", "/calendar/schedule", "MEDIUM",
                2, calendarPayload());
        assertThatThrownBy(() -> MailAiActionCatalog.validate(unknownVersion))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("contract version");

        MailDtos.ActionProposal incompletePayload = proposal(
                ProposalType.CREATE_CALENDAR_EVENT,
                "APP.CALENDAR", "CREATE", "/calendar/schedule", "MEDIUM",
                1, Map.of("requiresConfirmation", true));
        assertThatThrownBy(() -> MailAiActionCatalog.validate(incompletePayload))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("payload");
    }

    private MailDtos.ActionProposal proposal(
            ProposalType type,
            String resourceKey,
            String permissionCode,
            String route,
            String risk) {
        return proposal(type, resourceKey, permissionCode, route, risk, 1, payload(type));
    }

    private MailDtos.ActionProposal proposal(
            ProposalType type,
            String resourceKey,
            String permissionCode,
            String route,
            String risk,
            int contractVersion,
            Map<String, Object> payload) {
        return new MailDtos.ActionProposal(
                UUID.randomUUID(), UUID.randomUUID(), type, contractVersion,
                ProposalStatus.PROPOSED,
                "제안", "근거가 있는 제안입니다.", List.of(Map.of("messageId", "mail-1")),
                payload, new BigDecimal("0.9100"), risk,
                resourceKey, permissionCode, route, OffsetDateTime.now().plusHours(1), 0L);
    }

    private Map<String, Object> payload(ProposalType type) {
        return switch (type) {
            case DRAFT_REPLY -> Map.of(
                    "tone", "PROFESSIONAL", "language", "ko", "requiresConfirmation", true);
            case CREATE_CALENDAR_EVENT -> calendarPayload();
            case CREATE_LEAVE_REQUEST -> Map.of(
                    "durationDays", 1, "requiresConfirmation", true);
            case CREATE_TASK -> Map.of("priority", "HIGH", "requiresConfirmation", true);
            case ESCALATE_NOTIFICATION -> Map.of(
                    "channel", "IN_APP", "urgency", "URGENT", "requiresConfirmation", true);
        };
    }

    private Map<String, Object> calendarPayload() {
        return Map.of(
                "durationMinutes", 30,
                "timeZone", "Asia/Seoul",
                "requiresConfirmation", true);
    }
}
