package com.dwp.services.platform.workhub.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import static com.dwp.services.platform.workhub.assignment.MeetingFollowupProtocol.*;
import static org.assertj.core.api.Assertions.*;

class MeetingFollowupWorkloadSignerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void matchesIndependentlyGeneratedCrossServiceGoldenFixture() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/workhub/meeting-source-golden-v1.json")) {
            assertThat(input).isNotNull();
            fixture = mapper.readTree(input);
        }
        Request request = mapper.treeToValue(fixture.path("request"), Request.class);
        byte[] body = mapper.writeValueAsBytes(request);
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(fixture.path("requestBody").asText());
        Clock clock = Clock.fixed(Instant.ofEpochSecond(fixture.path("claims").path("iat").asLong()), ZoneOffset.UTC);
        var signer = new MeetingFollowupWorkloadSigner(fixture.path("claims").path("kid").asText(),
                fixture.path("testSecretBase64").asText(), mapper, clock,
                () -> UUID.fromString(fixture.path("claims").path("jti").asText()));
        assertThat(signer.sign(request, body)).isEqualTo(fixture.path("assertion").asText());
    }

    @Test void generatesFreshNonceAndBindsActorSourceActionAndExactBody() throws Exception {
        Request request = new Request(7, 11, new Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                Operation.REASSIGN, 21L, null);
        var signer = new MeetingFollowupWorkloadSigner("work-test", Base64.getEncoder().encodeToString(new byte[32]), mapper);
        byte[] body = mapper.writeValueAsBytes(request);
        String first = signer.sign(request, body);
        String second = signer.sign(request, body);
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(first.split("\\.")[1]));
        JsonNode next = mapper.readTree(Base64.getUrlDecoder().decode(second.split("\\.")[1]));
        assertThat(first).isNotEqualTo(second);
        assertThat(claims.path("jti").asText()).isNotEqualTo(next.path("jti").asText());
        assertThat(claims.path("actorUserId").asLong()).isEqualTo(11);
        assertThat(claims.path("candidateId").asText()).isEqualTo(request.source().candidateId().toString());
        assertThat(claims.path("action").asText()).isEqualTo("REASSIGN");
        assertThat(claims.path("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.path("aud").asText()).isEqualTo(AUDIENCE);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(30);
        assertThat(claims.path("bodySha256").asText()).hasSize(64);
    }

    @Test void rejectsWeakKeysAndIncompletePrincipalBindings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MeetingFollowupWorkloadSigner("work-test",
                Base64.getEncoder().encodeToString(new byte[8]), mapper));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeetingFollowupWorkloadSigner("bad\"kid",
                Base64.getEncoder().encodeToString(new byte[32]), mapper));
        var signer = new MeetingFollowupWorkloadSigner("work-test", Base64.getEncoder().encodeToString(new byte[32]), mapper);
        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(new Request(7, 0,
                new Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), Operation.READ, null, null), new byte[0]));
    }
}
