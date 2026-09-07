package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingTranscriptAccessControllerContractTest {

    @Test
    void exposesPublicBodyBoundTranscriptQueryWithoutSearchTermsInTheUrl() throws Exception {
        RequestMapping root = MeetingTranscriptAccessController.class
                .getAnnotation(RequestMapping.class);
        var method = MeetingTranscriptAccessController.class.getDeclaredMethod(
                "query", UUID.class, UUID.class,
                MeetingTranscriptAccessDtos.QueryCommand.class, String.class);

        assertThat(root.value()).containsExactly(
                "/v1/meetings/{meetingId}/artifacts/{artifactId}/transcript");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/query");
        assertThat(MeetingTranscriptAccessController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isFalse();
    }

    @Test
    void transcriptResponseIsNeverStoredSniffedOrReferred() {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        var request = new MeetingTranscriptAccessDtos.QueryCommand(3L, 0, 25, "decision");
        var result = new MeetingTranscriptAccessDtos.QueryResponse(
                artifactId, 3L,
                List.of(new MeetingTranscriptAccessDtos.SegmentResponse(
                        "segment-1", 1_000, 2_000, "reviewed decision")),
                null, false, true,
                OffsetDateTime.parse("2026-09-05T01:01:00Z"));
        MeetingTranscriptAccessService service = mock(MeetingTranscriptAccessService.class);
        when(service.query(meetingId, artifactId, request, "correlation-1"))
                .thenReturn(result);

        var response = new MeetingTranscriptAccessController(service).query(
                meetingId, artifactId, request, "correlation-1");

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(result);
    }
}
