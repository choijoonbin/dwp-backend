package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessReviewServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final AccessReviewService service = new AccessReviewService(jdbc, audit);

    @Test
    void tenantScopeCannotCarryAResourceReference() {
        var request = new AccessReviewDtos.CreateCampaignRequest(
                "Quarterly access certification",
                "Review privileged and inherited access before quarter close.",
                "TENANT",
                42L,
                "TENANT_ADMIN",
                null,
                Instant.now().plusSeconds(86_400));

        assertThatThrownBy(() -> service.createCampaign(1L, 10L, "corr-1", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("scope");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void namedReviewerStrategyRequiresAReviewer() {
        var request = new AccessReviewDtos.CreateCampaignRequest(
                "Privileged role review",
                null,
                "TENANT",
                null,
                "NAMED_REVIEWER",
                null,
                Instant.now().plusSeconds(86_400));

        assertThatThrownBy(() -> service.createCampaign(1L, 10L, "corr-2", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("reviewer");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantAdminCampaignDecisionNeverPublishesAWorkItemEvent() throws Exception {
        AccessReviewWorkItemOutboxPublisher events =
                mock(AccessReviewWorkItemOutboxPublisher.class);
        AccessReviewService eventAwareService = new AccessReviewService(jdbc, audit, events);
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ResultSet campaign = tenantAdminCampaign(campaignId);
        when(jdbc.query(
                contains("GROUP BY campaign.access_review_campaign_id"),
                any(RowMapper.class),
                eq(1L),
                eq(campaignId)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(campaign, 0));
                });
        when(jdbc.query(
                contains("SELECT access_source_type"),
                any(RowMapper.class),
                eq(1L),
                eq(campaignId),
                eq(itemId)))
                .thenReturn(List.of("DIRECT"));
        ResultSet item = decidedItem(itemId);
        when(jdbc.query(
                contains("SELECT item.access_review_item_id"),
                any(RowMapper.class),
                eq(1L),
                eq(campaignId),
                eq(itemId)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(item, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var request = new AccessReviewDtos.DecisionRequest(
                "APPROVE", "Access remains required for assigned responsibilities.", 0L);

        AccessReviewDtos.ItemSummary decided = eventAwareService.decide(
                1L, 10L, true, "corr-3", campaignId, itemId, request);

        assertThat(decided.itemId()).isEqualTo(itemId);
        assertThat(decided.decision()).isEqualTo("APPROVE");
        assertThat(decided.version()).isEqualTo(1L);
        verifyNoInteractions(events);
    }

    private ResultSet tenantAdminCampaign(UUID campaignId) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("access_review_campaign_id", UUID.class)).thenReturn(campaignId);
        when(result.getString("name")).thenReturn("Quarterly access certification");
        when(result.getString("scope_type")).thenReturn("TENANT");
        when(result.getString("reviewer_strategy")).thenReturn("TENANT_ADMIN");
        when(result.getString("lifecycle_state")).thenReturn("ACTIVE");
        when(result.getTimestamp("due_at"))
                .thenReturn(Timestamp.from(Instant.now().plusSeconds(3_600)));
        when(result.wasNull()).thenReturn(true);
        return result;
    }

    private ResultSet decidedItem(UUID itemId) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("access_review_item_id", UUID.class)).thenReturn(itemId);
        when(result.getString("decision")).thenReturn("APPROVE");
        when(result.getString("remediation_state")).thenReturn("NOT_REQUIRED");
        when(result.getLong("version")).thenReturn(1L);
        when(result.wasNull()).thenReturn(true);
        return result;
    }
}
