package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;
import static com.dwp.services.platform.mail.MailTypes.Importance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailRuleEvaluatorTest {

    private final MailRuleEvaluator evaluator = new MailRuleEvaluator();

    @Test
    void allModeRequiresEveryConditionAndUsesCaseInsensitiveMatching() {
        MailOrganizationDtos.RuleSummary rule = rule(
                RuleMatchMode.ALL,
                List.of(
                        condition(RuleField.SENDER, RuleOperator.ENDS_WITH, "@example.com"),
                        condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "Project")));
        var candidate = new MailOrganizationQueryRepository.RuleCandidate(
                UUID.randomUUID(), 0, "Partner@Example.com", "member@sk.com",
                "PROJECT kickoff", "Agenda", true, Importance.HIGH);

        assertThat(evaluator.matches(rule, candidate)).isTrue();
    }

    @Test
    void anyModeMatchesTypedAttachmentCondition() {
        MailOrganizationDtos.RuleSummary rule = rule(
                RuleMatchMode.ANY,
                List.of(
                        condition(RuleField.HAS_ATTACHMENT, RuleOperator.IS, "true"),
                        condition(RuleField.SUBJECT, RuleOperator.EQUALS, "unrelated")));
        var candidate = new MailOrganizationQueryRepository.RuleCandidate(
                UUID.randomUUID(), 0, "member@sk.com", "member@sk.com",
                "보고서", "본문", true, Importance.NORMAL);

        assertThat(evaluator.matches(rule, candidate)).isTrue();
    }

    @Test
    void invalidTypedConditionIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> evaluator.validate(
                condition(RuleField.HAS_ATTACHMENT, RuleOperator.CONTAINS, "yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }

    private MailOrganizationDtos.RuleSummary rule(
            RuleMatchMode mode,
            List<MailOrganizationDtos.RuleCondition> conditions) {
        return new MailOrganizationDtos.RuleSummary(
                UUID.randomUUID(), UUID.randomUUID(), "규칙", 100, mode,
                conditions,
                List.of(new MailOrganizationDtos.RuleAction(
                        RuleActionType.MARK_READ, null, null)),
                true, true, ProviderSyncState.LOCAL_ONLY,
                null, 0, 0L);
    }

    private MailOrganizationDtos.RuleCondition condition(
            RuleField field, RuleOperator operator, String value) {
        return new MailOrganizationDtos.RuleCondition(field, operator, value);
    }
}
