package com.dwp.services.platform.home.overview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.communication.CommunicationDtos;
import com.dwp.services.platform.communication.CommunicationService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.dwp.services.platform.workspace.WorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HomeOverviewServiceTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private CalendarService calendarService;
    @Mock
    private CommunicationService communicationService;
    @Mock
    private HomeRecommendationFeedbackRepository feedbackRepository;
    @Mock
    private PlatformAuditService auditService;

    private HomeOverviewService service;

    @BeforeEach
    void setUp() {
        service = new HomeOverviewService(
                workspaceService,
                calendarService,
                communicationService,
                feedbackRepository,
                auditService,
                new SimpleMeterRegistry());
    }

    @Test
    void composesUserScopedSignalsAndExplainsRecommendations() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID personId = UUID.randomUUID();
        when(feedbackRepository.suppressedKeys(1L, 10L)).thenReturn(Set.of());
        when(workspaceService.workQueue(1L, 10L, "WORK:VIEW", "ko-KR"))
                .thenReturn(new WorkspaceDtos.WorkQueue(
                        new WorkspaceDtos.WorkSummary(3, 1, 1, 1, 0), List.of(), now));
        when(workspaceService.activity(1L, 10L, "WORK:VIEW", "ko-KR"))
                .thenReturn(new WorkspaceDtos.ActivityFeed(List.of(), now));
        when(calendarService.home(1L, 10L, personId, "Asia/Seoul", "ko-KR"))
                .thenReturn(new CalendarDtos.HomeResponse(
                        LocalDate.now(), "Asia/Seoul", null, List.of(),
                        new CalendarDtos.HomeMetrics(2, 90, 60, 600, 0, 1, 3),
                        List.of(), List.of(), now));
        when(communicationService.feed(
                eq(1L), eq(10L), eq("TENANT_ADMIN"), eq("ko-KR"),
                eq("for-you"), isNull(), isNull(), anyInt()))
                .thenReturn(new CommunicationDtos.FeedResponse(
                        null, List.of(), new CommunicationDtos.FeedSummary(2, 1, 1, 0), now));

        HomeOverviewDtos.HomeOverviewResponse result = service.overview(
                1L, 10L, personId, "WORK:VIEW", "TENANT_ADMIN", "ko-KR", "Asia/Seoul");

        assertThat(result.audience().profile()).isEqualTo("OPERATOR");
        assertThat(result.work().status()).isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
        assertThat(result.recommendationSection().status())
                .isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
        assertThat(result.recommendationSection().data())
                .extracting(HomeOverviewDtos.Recommendation::key)
                .containsExactly("work-due-soon", "calendar-responses", "required-communications");
        assertThat(result.recommendationSection().data())
                .allMatch(recommendation -> recommendation.source().startsWith("DWP_"));
        assertThat(result.recommendations()).isEqualTo(result.recommendationSection().data());
    }

    @Test
    void degradesOneSectionWithoutFailingTheWholeHome() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID personId = UUID.randomUUID();
        when(feedbackRepository.suppressedKeys(1L, 7L)).thenReturn(Set.of());
        when(workspaceService.workQueue(any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        when(workspaceService.activity(any(), any(), anyString(), anyString()))
                .thenReturn(new WorkspaceDtos.ActivityFeed(List.of(), now));
        when(calendarService.home(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new CalendarDtos.HomeResponse(
                        LocalDate.now(), "Asia/Seoul", null, List.of(),
                        new CalendarDtos.HomeMetrics(0, 0, 600, 600, 0, 0, 0),
                        List.of(), List.of(), now));
        when(communicationService.feed(
                any(), any(), anyString(), anyString(), anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(new CommunicationDtos.FeedResponse(
                        null, List.of(), new CommunicationDtos.FeedSummary(0, 0, 0, 0), now));

        HomeOverviewDtos.HomeOverviewResponse result = service.overview(
                1L, 7L, personId, "NONE", "WORKSPACE_MEMBER", "en-US", "Asia/Seoul");

        assertThat(result.work().status()).isEqualTo(HomeOverviewDtos.SectionStatus.FORBIDDEN);
        assertThat(result.work().data()).isNull();
        assertThat(result.calendar().status()).isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
        assertThat(result.activity().status()).isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
        assertThat(result.recommendationSection().status())
                .isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
    }

    @Test
    void calculatesRecommendationsFromAvailableSourcesDuringAPartialOutage() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID personId = UUID.randomUUID();
        when(feedbackRepository.suppressedKeys(1L, 7L)).thenReturn(Set.of());
        when(workspaceService.workQueue(any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR));
        when(workspaceService.activity(any(), any(), anyString(), anyString()))
                .thenReturn(new WorkspaceDtos.ActivityFeed(List.of(), now));
        when(calendarService.home(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new CalendarDtos.HomeResponse(
                        LocalDate.now(), "Asia/Seoul", null, List.of(),
                        new CalendarDtos.HomeMetrics(1, 0, 600, 600, 0, 1, 0),
                        List.of(), List.of(), now));
        when(communicationService.feed(
                any(), any(), anyString(), anyString(), anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(new CommunicationDtos.FeedResponse(
                        null, List.of(), new CommunicationDtos.FeedSummary(0, 0, 0, 0), now));

        HomeOverviewDtos.HomeOverviewResponse result = service.overview(
                1L, 7L, personId, "NONE", "WORKSPACE_MEMBER", "en-US", "Asia/Seoul");

        assertThat(result.recommendationSection().status())
                .isEqualTo(HomeOverviewDtos.SectionStatus.AVAILABLE);
        assertThat(result.recommendationSection().reason())
                .isEqualTo("PARTIAL_SOURCE_UNAVAILABLE");
        assertThat(result.recommendationSection().data())
                .extracting(HomeOverviewDtos.Recommendation::key)
                .containsExactly("calendar-responses");
    }

    @Test
    void marksRecommendationsUnavailableWhenNoRecoverableSourceDataExists() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID personId = UUID.randomUUID();
        when(workspaceService.workQueue(any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR));
        when(workspaceService.activity(any(), any(), anyString(), anyString()))
                .thenReturn(new WorkspaceDtos.ActivityFeed(List.of(), now));
        when(calendarService.home(any(), any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR));
        when(communicationService.feed(
                any(), any(), anyString(), anyString(), anyString(), isNull(), isNull(), anyInt()))
                .thenThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR));

        HomeOverviewDtos.HomeOverviewResponse result = service.overview(
                1L, 7L, personId, "NONE", "WORKSPACE_MEMBER", "en-US", "Asia/Seoul");

        assertThat(result.recommendationSection().status())
                .isEqualTo(HomeOverviewDtos.SectionStatus.UNAVAILABLE);
        assertThat(result.recommendationSection().data()).isNull();
        assertThat(result.recommendationSection().reason()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void marksRecommendationsForbiddenWhenEverySourceIsOutsideThePermissionScope() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID personId = UUID.randomUUID();
        when(workspaceService.workQueue(any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        when(workspaceService.activity(any(), any(), anyString(), anyString()))
                .thenReturn(new WorkspaceDtos.ActivityFeed(List.of(), now));
        when(calendarService.home(any(), any(), any(), anyString(), anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        when(communicationService.feed(
                any(), any(), anyString(), anyString(), anyString(), isNull(), isNull(), anyInt()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        HomeOverviewDtos.HomeOverviewResponse result = service.overview(
                1L, 7L, personId, "NONE", "WORKSPACE_MEMBER", "en-US", "Asia/Seoul");

        assertThat(result.recommendationSection().status())
                .isEqualTo(HomeOverviewDtos.SectionStatus.FORBIDDEN);
        assertThat(result.recommendationSection().reason()).isEqualTo("SOURCE_FORBIDDEN");
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void recordsExplicitRecommendationFeedbackWithAuditEvidence() {
        HomeOverviewDtos.RecommendationFeedbackRequest request =
                new HomeOverviewDtos.RecommendationFeedbackRequest(
                        "NOT_RELEVANT");

        HomeOverviewDtos.RecommendationFeedbackResponse result = service.recordFeedback(
                1L, 10L, "work-due-soon", "corr-home-feedback", request);

        assertThat(result.feedbackType()).isEqualTo("NOT_RELEVANT");
        verify(feedbackRepository).save(
                1L, 10L, "work-due-soon", "NOT_RELEVANT", "DWP_WORKSPACE", "home-rules-2026.08");
        verify(auditService).success(
                eq(1L),
                eq(10L),
                eq("home.recommendation.feedback.recorded"),
                eq("HOME_RECOMMENDATION"),
                eq("work-due-soon"),
                eq("corr-home-feedback"),
                any(),
                any());
    }

    @Test
    void rejectsFeedbackForAnUnknownRecommendationKey() {
        HomeOverviewDtos.RecommendationFeedbackRequest request =
                new HomeOverviewDtos.RecommendationFeedbackRequest("DISMISSED");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordFeedback(
                        1L, 10L, "unregistered-recommendation", "corr-home-feedback", request))
                .isInstanceOf(BaseException.class)
                .satisfies(exception -> assertThat(((BaseException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
