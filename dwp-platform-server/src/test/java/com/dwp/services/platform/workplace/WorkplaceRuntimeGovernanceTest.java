package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkplaceRuntimeGovernanceTest {

    private final WorkplaceSpatialGovernanceService governance =
            mock(WorkplaceSpatialGovernanceService.class);
    private final WorkplaceRuntimeGovernance runtime =
            new WorkplaceRuntimeGovernance(governance);

    @Test
    void explicitAccessDenialStopsBookingAtTheRuntimeBoundary() {
        UUID siteId = UUID.randomUUID();
        when(governance.evaluateSiteAccess(
                1L, 7L, "group", siteId, AccessPermission.BOOK))
                .thenReturn(new SiteAccessDecision(
                        siteId, 7L, AccessPermission.BOOK, false,
                        "DENY_EXPLICIT", List.of(UUID.randomUUID()), OffsetDateTime.now()));

        assertThatThrownBy(() -> runtime.requireBookAccess(1L, 7L, "group", siteId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void inheritedPolicyIsConvertedToTheBookingEngineContract() {
        UUID resourceId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        var policy = mapper.createObjectNode()
                .put("bookingWindowDays", 14)
                .put("maximumActiveBookings", 3)
                .put("minimumBookingMinutes", 30)
                .put("maximumBookingMinutes", 480)
                .put("maximumConsecutiveDays", 5)
                .put("workingDayStart", "07:30")
                .put("workingDayEnd", "21:00")
                .put("allowRecurring", true)
                .put("requireCheckIn", true)
                .put("checkInLeadMinutes", 20)
                .put("autoReleaseMinutes", 15)
                .put("allowAssignedDeskLending", true)
                .put("showColleagueNames", false)
                .put("bookingRetentionDays", 730);
        when(governance.previewPolicy(1L, PolicyScopeType.RESOURCE, resourceId))
                .thenReturn(new EffectivePolicyPreview(
                        PolicyScopeType.RESOURCE, resourceId, policy,
                        Map.of(), List.of(), OffsetDateTime.now()));
        WorkplaceCatalogRepository.PolicyRow base = new WorkplaceCatalogRepository.PolicyRow(
                30, 20, 15, 720, 5,
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                false, false, 30, 30, false, true, 365, 9L);

        WorkplaceCatalogRepository.PolicyRow result = runtime.effectivePolicy(
                1L, PolicyScopeType.RESOURCE, resourceId, base);

        assertThat(result.bookingWindowDays()).isEqualTo(14);
        assertThat(result.workingDayStart()).isEqualTo(LocalTime.of(7, 30));
        assertThat(result.allowAssignedDeskLending()).isTrue();
        assertThat(result.showColleagueNames()).isFalse();
        assertThat(result.bookingRetentionDays()).isEqualTo(730);
        assertThat(result.version()).isEqualTo(9L);
    }
}
