package com.dwp.services.platform.communication;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.announcement.Announcement;
import com.dwp.services.platform.announcement.AnnouncementAudienceType;
import com.dwp.services.platform.announcement.AnnouncementLifecycle;
import com.dwp.services.platform.announcement.AnnouncementRepository;
import com.dwp.services.platform.announcement.AnnouncementSeverity;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
    private CommunicationActionQuery actionQuery;
    @Mock
    private PlatformRoutePredicateEvaluator predicateEvaluator;

    private CommunicationService service;

    @BeforeEach
    void setUp() {
        service = new CommunicationService(repository, jdbc, actionQuery, predicateEvaluator);
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

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void actionSliceIncludesCriticalUnreadStoryBeyondTheRegularNineStorySample() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Announcement> announcements = java.util.stream.LongStream.rangeClosed(1, 9)
                .mapToObj(id -> announcement(id, AnnouncementSeverity.INFO, false, now.minusMinutes(id)))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        announcements.add(announcement(10L, AnnouncementSeverity.CRITICAL, false, now.minusMinutes(10)));
        when(repository.findActive(
                eq(7L), any(OffsetDateTime.class), eq(List.of("WORKSPACE_MEMBER")), any(Pageable.class)))
                .thenReturn(announcements);
        when(actionQuery.snapshot(
                eq(7L), eq(11L), eq(List.of("WORKSPACE_MEMBER")), any(OffsetDateTime.class), eq(8)))
                .thenReturn(new CommunicationActionQuery.ActionSnapshot(
                        new CommunicationDtos.FeedSummary(10, 10, 0, 0, 1, 1),
                        List.of(10L)));
        when(repository.findByTenantIdAndAnnouncementIdIn(7L, List.of(10L)))
                .thenReturn(List.of(announcements.get(9)));
        doReturn(List.of()).when(jdbc).query(
                anyString(), any(RowMapper.class), any(Object[].class));

        CommunicationDtos.FeedResponse result = service.feed(
                7L, 11L, "WORKSPACE_MEMBER", "ko-KR", "for-you", null, null, 8);

        assertThat(result.featured().communicationId()).isEqualTo(1L);
        assertThat(result.items())
                .extracting(CommunicationDtos.CommunicationItem::communicationId)
                .containsExactly(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(result.actionableItems())
                .extracting(CommunicationDtos.CommunicationItem::communicationId)
                .containsExactly(10L);
        assertThat(result.summary()).isEqualTo(
                new CommunicationDtos.FeedSummary(10, 10, 0, 0, 1, 1));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void requiredScopeKeepsAcknowledgementSemanticsWhileGlobalActionableUsesASetUnion() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Announcement> announcements = List.of(
                announcement(1L, AnnouncementSeverity.CRITICAL, false, now.minusMinutes(1)),
                announcement(2L, AnnouncementSeverity.CRITICAL, true, now.minusMinutes(2)),
                announcement(3L, AnnouncementSeverity.INFO, true, now.minusMinutes(3)));
        when(repository.findActive(
                eq(7L), any(OffsetDateTime.class), eq(List.of("WORKSPACE_MEMBER")), any(Pageable.class)))
                .thenReturn(announcements);
        when(actionQuery.snapshot(
                eq(7L), eq(11L), eq(List.of("WORKSPACE_MEMBER")), any(OffsetDateTime.class), eq(8)))
                .thenReturn(new CommunicationActionQuery.ActionSnapshot(
                        new CommunicationDtos.FeedSummary(3, 3, 2, 0, 2, 3),
                        List.of(1L, 2L, 3L)));
        when(repository.findByTenantIdAndAnnouncementIdIn(7L, List.of(1L, 2L, 3L)))
                .thenReturn(announcements);
        doReturn(List.of()).when(jdbc).query(
                anyString(), any(RowMapper.class), any(Object[].class));

        CommunicationDtos.FeedResponse forYou = service.feed(
                7L, 11L, "WORKSPACE_MEMBER", "ko-KR", "for-you", null, null, 8);
        CommunicationDtos.FeedResponse required = service.feed(
                7L, 11L, "WORKSPACE_MEMBER", "ko-KR", "required", null, null, 8);

        assertThat(forYou.summary().required()).isEqualTo(2);
        assertThat(forYou.summary().criticalUnread()).isEqualTo(2);
        assertThat(forYou.summary().actionable()).isEqualTo(3);
        assertThat(required.actionableItems())
                .extracting(CommunicationDtos.CommunicationItem::communicationId)
                .containsExactly(1L, 2L, 3L);
        assertThat(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(required.featured()),
                        required.items().stream()))
                .extracting(CommunicationDtos.CommunicationItem::communicationId)
                .containsExactly(2L, 3L);
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

    private Announcement announcement(
            long id,
            AnnouncementSeverity severity,
            boolean acknowledgementRequired,
            OffsetDateTime publishedAt) {
        return Announcement.builder()
                .announcementId(id)
                .tenantId(7L)
                .title("Story " + id)
                .message("Summary " + id)
                .severity(severity)
                .lifecycleState(AnnouncementLifecycle.PUBLISHED)
                .audienceType(AnnouncementAudienceType.ALL)
                .startsAt(publishedAt.minusDays(1))
                .endsAt(publishedAt.plusDays(1))
                .publishedAt(publishedAt)
                .acknowledgementRequired(acknowledgementRequired)
                .build();
    }
}
