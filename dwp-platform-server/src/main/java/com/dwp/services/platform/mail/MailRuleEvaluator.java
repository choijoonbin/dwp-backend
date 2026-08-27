package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.Locale;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;

@Component
class MailRuleEvaluator {

    boolean matches(
            MailOrganizationDtos.RuleSummary rule,
            MailOrganizationQueryRepository.RuleCandidate candidate) {
        return rule.matchMode() == RuleMatchMode.ALL
                ? rule.conditions().stream().allMatch(condition -> matches(condition, candidate))
                : rule.conditions().stream().anyMatch(condition -> matches(condition, candidate));
    }

    void validate(MailOrganizationDtos.RuleCondition condition) {
        if (condition.field() == RuleField.HAS_ATTACHMENT) {
            if (condition.operator() != RuleOperator.IS
                    || !("true".equalsIgnoreCase(condition.value())
                    || "false".equalsIgnoreCase(condition.value()))) {
                throw new IllegalArgumentException(
                        "Attachment conditions require IS with true or false.");
            }
            return;
        }
        if (condition.field() == RuleField.IMPORTANCE) {
            if (condition.operator() != RuleOperator.IS
                    && condition.operator() != RuleOperator.EQUALS) {
                throw new IllegalArgumentException(
                        "Importance conditions require IS or EQUALS.");
            }
            MailTypes.Importance.valueOf(condition.value().trim().toUpperCase(Locale.ROOT));
            return;
        }
        if (condition.operator() == RuleOperator.IS) {
            throw new IllegalArgumentException("Text conditions do not support IS.");
        }
    }

    private boolean matches(
            MailOrganizationDtos.RuleCondition condition,
            MailOrganizationQueryRepository.RuleCandidate candidate) {
        String actual = switch (condition.field()) {
            case SENDER -> candidate.sender();
            case RECIPIENT -> candidate.recipient();
            case SUBJECT -> candidate.subject();
            case BODY -> candidate.body();
            case HAS_ATTACHMENT -> Boolean.toString(candidate.attachments());
            case IMPORTANCE -> candidate.importance().name();
        };
        String left = normalize(actual);
        String right = normalize(condition.value());
        return switch (condition.operator()) {
            case CONTAINS -> left.contains(right);
            case EQUALS, IS -> left.equals(right);
            case STARTS_WITH -> left.startsWith(right);
            case ENDS_WITH -> left.endsWith(right);
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
