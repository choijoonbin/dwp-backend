package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.Importance.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailRuleBackfillContinuationTest {

    @Test
    void continuationKeepsTheInitialSnapshotAndAdvancesByImmutableThreadPosition() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        UUID threadId = UUID.randomUUID();
        MailRuleBackfillContinuation.Cursor first =
                MailRuleBackfillContinuation.resolve(accountId, null);
        MailOrganizationQueryRepository.RuleCandidate candidate =
                new MailOrganizationQueryRepository.RuleCandidate(
                        threadId, 3L, "sender@example.com", "owner@example.com",
                        "Subject", "Body", false, NORMAL, createdAt);

        String token = MailRuleBackfillContinuation.encode(first.advance(candidate));
        MailRuleBackfillContinuation.Cursor resumed =
                MailRuleBackfillContinuation.resolve(accountId, token);

        assertThat(resumed.snapshotAt()).isEqualTo(first.snapshotAt());
        assertThat(resumed.afterCreatedAt()).isEqualTo(createdAt);
        assertThat(resumed.afterThreadId()).isEqualTo(threadId);
    }

    @Test
    void continuationFromAnotherAccountFailsClosed() {
        UUID accountId = UUID.randomUUID();
        String token = MailRuleBackfillContinuation.encode(
                MailRuleBackfillContinuation.resolve(accountId, null));

        assertThatThrownBy(() -> MailRuleBackfillContinuation.resolve(UUID.randomUUID(), token))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("continuation token");
    }
}
