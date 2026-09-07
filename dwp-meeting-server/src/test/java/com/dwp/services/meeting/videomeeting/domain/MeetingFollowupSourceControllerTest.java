package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceController;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Response;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingFollowupSourceControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JsonNode fixture;
    private byte[] body;
    private String assertion;
    private MeetingFollowupAssertionVerifier verifier;
    private MeetingFollowupSourceIngressService ingress;
    private MeetingFollowupSourceController controller;

    @BeforeEach
    void setup() throws Exception {
        fixture = mapper.readTree(Files.readString(platformFixture()));
        body = fixture.path("requestBody").asText().getBytes(StandardCharsets.UTF_8);
        assertion = fixture.path("assertion").asText();
        Instant issuedAt = Instant.ofEpochSecond(fixture.path("claims").path("iat").asLong());
        ingress = mock(MeetingFollowupSourceIngressService.class);
        verifier = new MeetingFollowupAssertionVerifier(
                fixture.path("claims").path("kid").asText(),
                fixture.path("testSecretBase64").asText(), mapper,
                Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC));
        controller = new MeetingFollowupSourceController(verifier, ingress, mapper);
    }

    @Test
    void realReceiverConsumesPlatformGoldenAndReturnsContentFreeSecurityHeaders() throws Exception {
        Request request = mapper.readValue(body, Request.class);
        Response result = Response.denied(
                request, "AUTHORITY_UNVERIFIED", 3L,
                com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.OriginalAccess.AVAILABLE);
        when(ingress.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(request))).thenReturn(result);
        MockHttpServletRequest servletRequest = request(assertion);

        var response = controller.resolve(body, servletRequest);

        assertThat(response.getBody()).isEqualTo(result);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        var verified = org.mockito.ArgumentCaptor.forClass(
                MeetingFollowupAssertionVerifier.VerifiedAssertion.class);
        verify(ingress).resolve(verified.capture(),
                org.mockito.ArgumentMatchers.eq(request));
        assertThat(verified.getValue().tenantId()).isEqualTo(7L);
        assertThat(verified.getValue().action()).isEqualTo("CREATE");
    }

    @Test
    void validSignedCreateRemainsClosedWithoutCurrentMeetingAuthorityBeforeSourceAccess()
            throws Exception {
        VideoMeetingRepository meetings = mock(VideoMeetingRepository.class);
        VideoMeetingIntelligenceRepository intelligence =
                mock(VideoMeetingIntelligenceRepository.class);
        MeetingIntelligencePayloadProtector protector =
                mock(MeetingIntelligencePayloadProtector.class);
        MeetingFollowupAssertionReplayRepository replay =
                mock(MeetingFollowupAssertionReplayRepository.class);
        MeetingFollowupSourceService source = new MeetingFollowupSourceService(
                meetings, intelligence, protector, new MeetingContentAccessPolicy(),
                new UnavailableMeetingFollowupCurrentAuthority(), mapper,
                Clock.fixed(Instant.parse("2026-09-04T05:00:01Z"), ZoneOffset.UTC));
        MeetingFollowupSourceController receiver = new MeetingFollowupSourceController(
                verifier, new MeetingFollowupSourceIngressService(replay, source), mapper);

        Response result = receiver.resolve(body, request(assertion)).getBody();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denialCode()).isEqualTo("AUTHORITY_UNVERIFIED");
        assertThat(result.approvedTask()).isNull();
        verify(replay).consume(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(meetings, intelligence, protector);
    }

    @Test
    void malformedBodiesAndHeaderAmbiguityAreRejectedBeforeBusinessResolution() {
        for (byte[] invalid : List.of(
                "null".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8),
                "{\"tenantId\":7,\"tenantId\":8}".getBytes(StandardCharsets.UTF_8),
                (fixture.path("requestBody").asText().replaceFirst(
                        "}$", ",\"unexpected\":true}"))
                        .getBytes(StandardCharsets.UTF_8),
                (fixture.path("requestBody").asText() + " {}")
                        .getBytes(StandardCharsets.UTF_8),
                new byte[16_385])) {
            assertDenied(() -> controller.resolve(invalid, request(assertion)));
        }

        MockHttpServletRequest duplicate = request(assertion);
        duplicate.addHeader(MeetingFollowupAssertionVerifier.HEADER, assertion);
        assertDenied(() -> controller.resolve(body, duplicate));
        assertDenied(() -> controller.resolve(body, new MockHttpServletRequest()));
    }

    @Test
    void internalReceiverIsExplicitlyExcludedFromThePublicSchema() {
        assertThat(MeetingFollowupSourceController.class.getAnnotation(Hidden.class)).isNotNull();
    }

    private MockHttpServletRequest request(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", MeetingFollowupAssertionVerifier.PATH);
        request.addHeader(MeetingFollowupAssertionVerifier.HEADER, value);
        return request;
    }

    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BaseException.class)
                .hasMessage("Trusted Platform Work source assertion is required.")
                .hasNoCause();
    }

    private Path platformFixture() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && cursor != null; depth++, cursor = cursor.getParent()) {
            for (Path candidate : List.of(
                    cursor.resolve("dwp-platform-server/src/test/resources/workhub/meeting-source-golden-v1.json"),
                    cursor.resolve("dwp-backend/dwp-platform-server/src/test/resources/workhub/meeting-source-golden-v1.json"))) {
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IllegalStateException("The canonical Platform Work golden fixture was not found.");
    }
}
