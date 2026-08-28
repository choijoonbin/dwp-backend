package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;
import static com.dwp.services.platform.mail.MailTypes.Importance;
import static org.assertj.core.api.Assertions.assertThat;

class MailRuleBackfillFingerprintTest {

    private final MailRuleBackfillFingerprint fingerprints = new MailRuleBackfillFingerprint();

    @Test
    void previewFingerprintBindsAccountRuleAndThreadVersions() {
        UUID accountId = UUID.randomUUID();
        var rule = rule(accountId, 3);
        var candidate = candidate(7);

        String original = fingerprints.preview(accountId, List.of(rule), List.of(candidate));

        assertThat(fingerprints.preview(accountId, List.of(rule), List.of(candidate)))
                .isEqualTo(original);
        assertThat(fingerprints.preview(accountId, List.of(rule(accountId, 4)), List.of(candidate)))
                .isNotEqualTo(original);
        assertThat(fingerprints.preview(accountId, List.of(rule), List.of(candidate(8))))
                .isNotEqualTo(original);
        assertThat(fingerprints.preview(UUID.randomUUID(), List.of(rule), List.of(candidate)))
                .isNotEqualTo(original);
    }

    @Test
    void requestFingerprintRejectsCrossAccountReplay() {
        String preview = "a".repeat(64);
        UUID accountId = UUID.randomUUID();

        String original = fingerprints.request(accountId, preview);

        assertThat(fingerprints.request(accountId, preview)).isEqualTo(original);
        assertThat(fingerprints.request(UUID.randomUUID(), preview)).isNotEqualTo(original);
        assertThat(fingerprints.request(accountId, "b".repeat(64))).isNotEqualTo(original);
    }

    private MailOrganizationDtos.RuleSummary rule(UUID accountId, long version) {
        return new MailOrganizationDtos.RuleSummary(
                UUID.randomUUID(), accountId, "Rule", 100, RuleMatchMode.ALL,
                List.of(new MailOrganizationDtos.RuleCondition(
                        RuleField.SUBJECT, RuleOperator.CONTAINS, "project")),
                List.of(new MailOrganizationDtos.RuleAction(
                        RuleActionType.MARK_READ, null, null)),
                true, true, ProviderSyncState.LOCAL_ONLY, null, 0, version);
    }

    private MailOrganizationQueryRepository.RuleCandidate candidate(long version) {
        return new MailOrganizationQueryRepository.RuleCandidate(
                UUID.randomUUID(), version, "sender@example.com", "member@sk.com",
                "Project update", "Body", false, Importance.NORMAL);
    }
}
