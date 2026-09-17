package com.dwp.services.platform.home.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DwaionHomeWorkloadAssertionSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void bindsTheExactBodyRecipientAuthorityAndDeadlineToDwp1() throws Exception {
        Instant issued = Instant.parse("2026-09-17T01:02:03Z");
        UUID nonce = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        HomeRuntimeContext context = context(issued, "corr-one", "00-"
                + "1".repeat(32) + "-" + "2".repeat(16) + "-01", "vendor=value");
        byte[] body = "{\"schemaVersion\":1,\"widgets\":[]}".getBytes(StandardCharsets.UTF_8);
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", SECRET, mapper,
                Clock.fixed(issued, ZoneOffset.UTC), () -> nonce);

        String assertion = signer.sign(
                context, OffsetDateTime.ofInstant(issued.plusMillis(900), ZoneOffset.UTC), body);
        String[] segments = assertion.split("\\.");
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(segments[1]));

        assertThat(segments).hasSize(3);
        assertThat(claims.get("iss").asText()).isEqualTo("dwp-platform-server");
        assertThat(claims.get("aud").asText()).isEqualTo("dwp-agent-home");
        assertThat(claims.get("htu").asText())
                .isEqualTo("/internal/home/v1/widget-data:batch");
        assertThat(claims.get("sub").asText()).isEqualTo("82");
        assertThat(claims.get("tid").asText()).isEqualTo("71");
        assertThat(claims.get("cid").asText()).isEqualTo("corr-one");
        assertThat(claims.get("traceparent").asText()).isEqualTo(context.traceparent());
        assertThat(claims.get("tracestate").asText()).isEqualTo(context.tracestate());
        assertThat(claims.get("permissions").toString())
                .isEqualTo("[\"APP.ASK:VIEW\",\"APP.DWAION_ARTIFACTS:VIEW\"]");
        assertThat(claims.get("exp").asLong()).isEqualTo(issued.getEpochSecond() + 1);
        assertThat(claims.get("jti").asText()).isEqualTo(nonce.toString());
        assertThat(claims.get("bodySha256").asText()).isEqualTo(
                java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(segments[2]).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((segments[0] + "." + segments[1])
                        .getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void rejectsWeakConfigurationAndAnElapsedSubsecondDeadline() {
        assertThatThrownBy(() -> new DwaionHomeWorkloadAssertionSigner(
                "bad key", SECRET, mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", "short", mapper))
                .isInstanceOf(IllegalArgumentException.class);

        Instant issued = Instant.parse("2026-09-17T01:02:03.900Z");
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", SECRET, mapper,
                Clock.fixed(issued, ZoneOffset.UTC), UUID::randomUUID);
        assertThatThrownBy(() -> signer.sign(
                context(issued, "corr-two", null, null),
                OffsetDateTime.ofInstant(issued.minusMillis(900), ZoneOffset.UTC), new byte[0]))
                .isInstanceOf(WidgetProviderException.class)
                .extracting("reasonCode").isEqualTo("PROVIDER_DEADLINE_EXPIRED");
    }

    @Test
    void matchesTheSharedJavaToPythonGoldenAssertion() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(Path.of(
                "../contracts/home-runtime/dwaion-signed-workload.v1.json")));
        Instant issued = Instant.parse(fixture.get("issuedAt").asText());
        JsonNode headers = fixture.get("headers");
        HomeRuntimeContext context = new HomeRuntimeContext(
                Long.parseLong(headers.get("X-DWP-Tenant-ID").asText()),
                Long.parseLong(headers.get("X-DWP-User-ID").asText()), null,
                java.util.Set.of("APP.DWAION_ARTIFACTS:VIEW", "APP.ASK:VIEW"),
                java.util.Set.of("MEMBER"), java.util.Set.of("team-a"),
                headers.get("X-DWP-Current-Decision-Revision").asText(),
                OffsetDateTime.parse(headers.get("X-DWP-Current-Revalidate-At").asText()),
                "ko-KR", "Asia/Seoul", "fixture-fingerprint",
                headers.get("X-Correlation-ID").asText(),
                headers.get("traceparent").asText(), headers.get("tracestate").asText());
        byte[] body = fixture.get("requestBodyUtf8").asText().getBytes(StandardCharsets.UTF_8);
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                fixture.get("keyId").asText(), fixture.get("testOnlySigningSecret").asText(),
                mapper, Clock.fixed(issued, ZoneOffset.UTC),
                () -> UUID.fromString(fixture.get("nonce").asText()));

        String assertion = signer.sign(context,
                OffsetDateTime.parse(headers.get("X-DWP-Home-Deadline-At").asText()), body);

        assertThat(assertion).isEqualTo(fixture.get("assertion").asText());
        assertThat(java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        assertion.getBytes(StandardCharsets.US_ASCII))))
                .isEqualTo(fixture.get("assertionSha256").asText());
        assertThat(fixture.at("/definition/version").asText())
                .isEqualTo(DwaionHomeWorkloadProtocol.DEFINITION_VERSION);
        assertThat(fixture.at("/definition/manifestHash").asText())
                .isEqualTo(DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH);
    }

    private HomeRuntimeContext context(
            Instant issued, String correlationId, String traceparent, String tracestate) {
        return new HomeRuntimeContext(
                71L, 82L, null,
                java.util.Set.of("APP.DWAION_ARTIFACTS:VIEW", "APP.ASK:VIEW"),
                java.util.Set.of("MEMBER"), java.util.Set.of("team-a"),
                "decision-17", OffsetDateTime.ofInstant(issued.plusSeconds(10), ZoneOffset.UTC),
                "ko-KR", "Asia/Seoul", "fingerprint", correlationId,
                traceparent, tracestate);
    }
}
