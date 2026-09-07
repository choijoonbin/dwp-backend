package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingFollowupAssertionVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void consumesThePlatformWorkGoldenAssertionWithEveryBodyAndPrincipalBinding() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(platformFixture()));
        byte[] body = fixture.path("requestBody").asText().getBytes(StandardCharsets.UTF_8);
        Request request = mapper.readValue(body, Request.class);
        Instant issuedAt = Instant.ofEpochSecond(fixture.path("claims").path("iat").asLong());
        var verifier = new MeetingFollowupAssertionVerifier(
                fixture.path("claims").path("kid").asText(),
                fixture.path("testSecretBase64").asText(), mapper,
                Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC));

        var verified = verifier.verify(fixture.path("assertion").asText(), body, request);

        assertThat(verified.keyId()).isEqualTo("work-meeting-test-v1");
        assertThat(verified.tenantId()).isEqualTo(request.tenantId());
        assertThat(verified.actorUserId()).isEqualTo(request.actorUserId());
        assertThat(verified.meetingId()).isEqualTo(request.source().meetingId());
        assertThat(verified.reportId()).isEqualTo(request.source().reportId());
        assertThat(verified.candidateId()).isEqualTo(request.source().candidateId());
        assertThat(verified.action()).isEqualTo("CREATE");
        assertThat(verified.expiresAt()).isEqualTo(issuedAt.plusSeconds(30));
    }

    @Test
    void rejectsBodyMutationWrongSecretAndNonCanonicalCompactEncoding() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(platformFixture()));
        byte[] body = fixture.path("requestBody").asText().getBytes(StandardCharsets.UTF_8);
        Request request = mapper.readValue(body, Request.class);
        Instant issuedAt = Instant.ofEpochSecond(fixture.path("claims").path("iat").asLong());
        var verifier = new MeetingFollowupAssertionVerifier(
                fixture.path("claims").path("kid").asText(),
                fixture.path("testSecretBase64").asText(), mapper,
                Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC));

        assertDenied(() -> verifier.verify(
                fixture.path("assertion").asText(),
                (fixture.path("requestBody").asText() + " ").getBytes(StandardCharsets.UTF_8),
                request));
        assertDenied(() -> new MeetingFollowupAssertionVerifier(
                "work-meeting-test-v1",
                java.util.Base64.getEncoder().encodeToString(new byte[32]), mapper,
                Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC))
                .verify(fixture.path("assertion").asText(), body, request));
        assertDenied(() -> verifier.verify(
                fixture.path("assertion").asText() + "=", body, request));
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

    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BaseException.class)
                .hasMessage("Trusted Platform Work source assertion is required.")
                .hasNoCause();
    }
}
