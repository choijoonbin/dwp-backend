package com.dwp.services.platform.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.ProviderSyncState.LOCAL_ONLY;
import static com.dwp.services.platform.mail.MailOrganizationTypes.RuleMatchMode.ALL;
import static com.dwp.services.platform.mail.MailTypes.ProviderType.DWP_SANDBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailOrganizationServiceTest {

    @Mock private MailQueryRepository mailQueries;
    @Mock private MailOrganizationQueryRepository queries;
    @Mock private MailOrganizationCommandRepository commands;
    @Mock private MailCommandRepository evidence;
    @Mock private MailRuleEvaluator evaluator;

    private MailOrganizationService service;

    @BeforeEach
    void setUp() {
        service = new MailOrganizationService(
                mailQueries, queries, commands, evidence, evaluator);
    }

    @Test
    void reorderRequiresCurrentVersionsAndReturnsTheCommittedAccountOrder() {
        UUID accountId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var first = rule(firstId, accountId, "First", 1, 3L);
        var second = rule(secondId, accountId, "Second", 2, 5L);
        var reorderedSecond = rule(secondId, accountId, "Second", 1, 6L);
        var reorderedFirst = rule(firstId, accountId, "First", 2, 4L);
        when(queries.rule(1L, 7L, secondId)).thenReturn(Optional.of(second));
        when(queries.rule(1L, 7L, firstId)).thenReturn(Optional.of(first));
        when(queries.rules(1L, 7L))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of(reorderedSecond, reorderedFirst));
        when(commands.reorderRule(1L, 7L, secondId, 1, 5L)).thenReturn(1);
        when(commands.reorderRule(1L, 7L, firstId, 2, 3L)).thenReturn(1);
        when(mailQueries.accounts(1L, 7L)).thenReturn(List.of(new MailDtos.AccountSummary(
                accountId, "mina@example.com", "Mina", "PERSONAL", DWP_SANDBOX,
                "ACTIVE", "SYNCED", true)));
        when(queries.folders(1L, 7L)).thenReturn(List.of());
        when(queries.recentRuns(1L, 7L)).thenReturn(List.of());

        MailOrganizationDtos.OrganizationResponse result = service.reorderRules(
                1L, 7L, "corr-order",
                new MailOrganizationDtos.RuleOrderRequest(List.of(
                        new MailOrganizationDtos.RuleOrderItem(secondId, 5L),
                        new MailOrganizationDtos.RuleOrderItem(firstId, 3L))));

        assertThat(result.rules()).extracting(MailOrganizationDtos.RuleSummary::ruleId)
                .containsExactly(secondId, firstId);
        verify(commands).reorderRule(1L, 7L, secondId, 1, 5L);
        verify(commands).reorderRule(1L, 7L, firstId, 2, 3L);
        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.rule.reordered"), eq("MAIL_RULE"),
                eq(secondId.toString()), eq("corr-order"), anyMap(), anyMap());
    }

    private MailOrganizationDtos.RuleSummary rule(
            UUID ruleId, UUID accountId, String name, int priority, long version) {
        return new MailOrganizationDtos.RuleSummary(
                ruleId, accountId, name, priority, ALL, List.of(), List.of(),
                true, true, LOCAL_ONLY, OffsetDateTime.now(), 0, version);
    }
}
