package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
