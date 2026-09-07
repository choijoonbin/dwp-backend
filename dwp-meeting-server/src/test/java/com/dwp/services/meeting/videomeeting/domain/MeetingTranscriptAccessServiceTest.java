package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptAccessDtos.QueryCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptAccessModels.PreparedQuery;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingTranscriptAccessServiceTest {

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void filtersAndPagesBoundedSegmentsWithoutPersistingTheQuery() {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        MeetingTranscriptSource source = mock(MeetingTranscriptSource.class);
        MeetingIntelligenceOutputValidator validator = mock(
                MeetingIntelligenceOutputValidator.class);
        MeetingTranscriptAccessTransactions transactions = mock(
                MeetingTranscriptAccessTransactions.class);
        PreparedQuery prepared = prepared(meetingId, artifactId);
        when(source.available()).thenReturn(true);
        when(transactions.prepare(any(), any(), any(), anyLong(), any()))
                .thenReturn(prepared);
        when(source.read(any())).thenReturn(List.of(
                segment("s1", 0, 2_000, "첫 번째 결정 사항"),
                segment("s2", 2_000, 4_000, "일반 토론"),
                segment("s3", 4_000, 6_000, "두 번째 결정 사항")));
        MeetingRequestContext.set(subject());

        var result = new MeetingTranscriptAccessService(source, validator, transactions)
                .query(meetingId, artifactId,
                        new QueryCommand(7L, 0, 1, "결정"), "correlation-1");

        assertThat(result.segments()).extracting("segmentId").containsExactly("s1");
        assertThat(result.nextCursor()).isEqualTo(1);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.queryApplied()).isTrue();
        verify(transactions).complete(prepared, 1, true, true);
    }

    @Test
    void rejectsAnAbusiveOneCharacterSearchBeforeBrokerOrDatabaseAccess() {
        MeetingTranscriptSource source = mock(MeetingTranscriptSource.class);
        MeetingTranscriptAccessTransactions transactions = mock(
                MeetingTranscriptAccessTransactions.class);
        MeetingRequestContext.set(subject());

        assertThatThrownBy(() -> new MeetingTranscriptAccessService(
                source, mock(MeetingIntelligenceOutputValidator.class), transactions)
                .query(UUID.randomUUID(), UUID.randomUUID(),
                        new QueryCommand(1L, 0, 25, "a"), null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("search query is invalid");
        verify(source, never()).read(any());
        verify(transactions, never()).prepare(any(), any(), any(), anyLong(), any());
    }

    private PreparedQuery prepared(UUID meetingId, UUID artifactId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T01:00:00Z");
        return new PreparedQuery(
                subject(), null, null,
                new TranscriptArtifact(
                        artifactId, 1L, meetingId, "AVAILABLE", "a".repeat(64),
                        now.plusDays(7), true, "KR", UUID.randomUUID(), "b".repeat(64),
                        "key", "c".repeat(64), now, 1L, 7L,
                        "register", "d".repeat(64), now, 1L, 2L,
                        "STT", "BROKER", null, "BROKER", "opaque"),
                "correlation-1");
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                10L, 1L, UUID.randomUUID(), "Viewer", Set.of("USER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of());
    }

    private TranscriptSegment segment(String id, long start, long end, String text) {
        return new TranscriptSegment(id, start, end, text);
    }
}
