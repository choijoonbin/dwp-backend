package com.dwp.services.platform.communication;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.announcement.Announcement;
import com.dwp.services.platform.announcement.AnnouncementAudienceType;
import com.dwp.services.platform.announcement.AnnouncementLifecycle;
import com.dwp.services.platform.announcement.AnnouncementRepository;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunicationServiceTest {

    @Mock
    private AnnouncementRepository repository;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformRoutePredicateEvaluator predicateEvaluator;

    private CommunicationService service;

    @BeforeEach
    void setUp() {
        service = new CommunicationService(repository, jdbc, predicateEvaluator);
    }

    @Test
    void impressionEvidenceDoesNotMarkAStoryAsOpened() {
        when(predicateEvaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .thenReturn(visibleAnnouncement());

        service.recordInteraction(7L, 11L, "WORKSPACE_MEMBER", 91L, "impression");

        verify(jdbc).update(
                argThat(sql -> sql.contains("first_seen_at") && !sql.contains("first_opened_at")),
                eq(7L), eq(91L), eq(11L));
    }

    @Test
    void intentionalOpenCreatesReaderStateAndClearsDismissal() {
        when(predicateEvaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .thenReturn(visibleAnnouncement());

        service.recordInteraction(7L, 11L, "WORKSPACE_MEMBER", 91L, "open");

        verify(jdbc).update(
                argThat(sql -> sql.contains("first_opened_at") && sql.contains("dismissed_at = NULL")),
                eq(7L), eq(91L), eq(11L));
    }

    @Test
    void ordinaryStoryCannotCreateAcknowledgementEvidence() {
        when(predicateEvaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .thenReturn(visibleAnnouncement());

        assertThatThrownBy(() -> service.acknowledge(
                7L, 11L, "WORKSPACE_MEMBER", 91L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(jdbc, never()).update(
                argThat(sql -> sql.contains("acknowledged_at")),
                eq(7L), eq(91L), eq(11L));
    }

    @Test
    void roleTargetedStoryIsHiddenFromOtherRoles() {
        when(predicateEvaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.recordInteraction(
                7L, 11L, "WORKSPACE_MEMBER", 91L, "open"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void reactionUpsertKeepsOneCurrentReactionPerReader() {
        when(predicateEvaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .thenReturn(visibleAnnouncement());

        service.updateReaction(
                7L,
                11L,
                "WORKSPACE_MEMBER",
                91L,
                new CommunicationDtos.ReactionRequest(CommunicationReaction.INSIGHTFUL));

        verify(jdbc).update(
                argThat(sql -> sql.contains("sys_announcement_reactions")
                        && sql.contains("ON CONFLICT")),
                eq(7L), eq(91L), eq(11L), eq("INSIGHTFUL"));
    }

    private Announcement visibleAnnouncement() {
        return Announcement.builder()
                .announcementId(91L)
                .tenantId(7L)
                .title("Story")
                .message("Summary")
                .lifecycleState(AnnouncementLifecycle.PUBLISHED)
                .audienceType(AnnouncementAudienceType.ALL)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
                .endsAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))
                .acknowledgementRequired(false)
                .build();
    }
}
