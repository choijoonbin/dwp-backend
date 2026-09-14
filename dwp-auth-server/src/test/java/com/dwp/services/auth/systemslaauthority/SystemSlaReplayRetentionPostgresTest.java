package com.dwp.services.auth.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A short-lived redundant source must not shorten the lifetime of consumed proof nonces. */
@Testcontainers
class SystemSlaReplayRetentionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = SystemSlaCurrentAuthorityPostgresTest.PG;
    @Container static final GenericContainer<?> REDIS = SystemSlaCurrentAuthorityPostgresTest.REDIS;
    final SystemSlaCurrentAuthorityPostgresTest source = new SystemSlaCurrentAuthorityPostgresTest();
    @BeforeAll static void start() { SystemSlaCurrentAuthorityPostgresTest.start(); }
    @AfterAll static void close() { SystemSlaCurrentAuthorityPostgresTest.close(); }
    @BeforeEach void prepare() { source.prepare(); }

    @Test void consumedHttpProofCannotBeReusedAfterRedundantGroupSourceExpires() throws Exception {
        var jdbc = SystemSlaCurrentAuthorityPostgresTest.jdbc;
        long group = jdbc.queryForObject("INSERT INTO com_groups(tenant_id,group_key,display_name) VALUES(?,'SLA_SHORT_SOURCE','Short source') RETURNING group_id", Long.class, source.tenant);
        jdbc.update("INSERT INTO com_group_members(tenant_id,group_id,user_id) VALUES(?,?,?)", source.tenant, group, source.user);
        var deadline = jdbc.queryForObject("SELECT clock_timestamp()+interval '6 seconds'", java.sql.Timestamp.class).toInstant();
        jdbc.update("INSERT INTO com_group_role_assignments(tenant_id,group_id,role_id,valid_to) VALUES(?,?,?,?)", source.tenant, group, source.role, java.sql.Timestamp.from(deadline));
        var fixture = source.fixture;
        var exchange = exchange();
        var proof = source.verifier.verify(exchange.body(), exchange.token());
        var before = source.authority.current(proof);
        assertThat(before.recipients().getFirst().eligible()).isTrue();
        assertThat(before.expiresAt()).isBefore(proof.expiresAt());
        try (var server = new SystemSlaEmbeddedServer(service(), true)) {
            var first = post(server, exchange); assertThat(first.statusCode()).isEqualTo(200);
            var token = SignedJWT.parse(fixture.json.parse(first.body()).get("sourceAttestation").textValue());
            assertThat(token.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(before.expiresAt());
            while (Instant.now().isBefore(deadline.plusMillis(200))) Thread.sleep(50);
            assertThat(proof.expiresAt()).isAfter(Instant.now());
            var after = source.authority.current(proof);
            assertThat(after.recipients().getFirst().eligible()).isTrue();
            assertThat(after.authorityRevision()).isNotEqualTo(before.authorityRevision());
            assertThat(after.expiresAt()).isAfter(before.expiresAt());
            assertThat(post(server, exchange).statusCode()).as("The same consumed owner/transport JTIs remain rejected for their full signed validity").isEqualTo(403);
        }
    }
    @Test void consumedOwnerProofCannotBeRewrappedAfterShorterTransportExpires() throws Exception {
        var fixture = source.fixture; var exchange = exchange();
        var deadline = Instant.now().plusSeconds(6).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var shortClaims = new LinkedHashMap<>(exchange.transportClaims()); shortClaims.put("exp", deadline.getEpochSecond());
        var firstExchange = new SystemSlaProtocolFixture.Exchange(exchange.body(), fixture.token(fixture.transport, shortClaims), exchange.ownerClaims(), shortClaims);
        var proof = source.verifier.verify(firstExchange.body(), firstExchange.token());
        assertThat(proof.expiresAt()).isEqualTo(deadline);
        try (var server = new SystemSlaEmbeddedServer(service(), true)) {
            assertThat(post(server, firstExchange).statusCode()).isEqualTo(200);
            while (Instant.now().isBefore(deadline.plusMillis(200))) Thread.sleep(50);
            assertThat(fixture.now.plusSeconds(30)).isAfter(Instant.now());
            var freshTransport = new LinkedHashMap<>(exchange.transportClaims()); freshTransport.put("jti", UUID.randomUUID().toString());
            var wrappedAgain = new SystemSlaProtocolFixture.Exchange(exchange.body(), fixture.token(fixture.transport, freshTransport), exchange.ownerClaims(), freshTransport);
            assertThat(source.verifier.verify(wrappedAgain.body(), wrappedAgain.token()).ownerJti()).isEqualTo(proof.ownerJti());
            assertThat(post(server, wrappedAgain).statusCode()).as("Consumed owner JTI is retained until its own signed expiration, not the first transport expiration").isEqualTo(403);
        }
    }
    @Test void concurrentHttpRequestsConsumeTheOwnerAndTransportPairExactlyOnce() throws Exception {
        var exchange = exchange();
        try (var server = new SystemSlaEmbeddedServer(service(), true);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var request = HttpRequest.newBuilder(server.endpoint()).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("X-DWP-Service-Identity", "dwp-approval-server").header(SystemSlaProtocol.HEADER, exchange.token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build();
            var pending = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
            for (int index = 0; index < 8; index++) pending.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
            var statuses = pending.stream().map(value -> value.orTimeout(6, java.util.concurrent.TimeUnit.SECONDS).join().statusCode()).toList();
            assertThat(statuses.stream().filter(value -> value == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(value -> value == 403).count()).isEqualTo(7);
            assertThat(SystemSlaCurrentAuthorityPostgresTest.redis.keys("dwp:approval-system-sla:{" + source.tenant + ":*" )).hasSize(2);
        }
    }
    SystemSlaProtocolFixture.Exchange exchange() {
        var fixture = source.fixture; var binding = (ObjectNode) fixture.binding(); binding.put("tenantId", source.tenant);
        ((ObjectNode) binding.at("/source/stage")).put("candidateRole", "SLA_REVIEWER");
        ((ObjectNode) binding.at("/source/workflow/definition/stages/0")).put("candidateRole", "SLA_REVIEWER");
        ((ObjectNode) binding.at("/source/workflow")).put("definitionSha256", fixture.json.digest(binding.at("/source/workflow/definition")));
        binding.set("audience", fixture.json.tree(List.of(Map.of("userId", source.user, "personPublicId", source.person.toString(), "taskId", UUID.randomUUID().toString(), "taskVersion", 0))));
        return fixture.exchange(fixture.redigest(binding));
    }
    SystemSlaAuthorityService service() {
        return new SystemSlaAuthorityService(() -> source.verifier, source.authority,
                new SystemSlaReplayStore(SystemSlaCurrentAuthorityPostgresTest.redis, Clock.systemUTC()),
                new SystemSlaAttestationIssuer(source.fixture.json, source.fixture::keys, Clock.systemUTC()), true);
    }
    static HttpResponse<byte[]> post(SystemSlaEmbeddedServer server, SystemSlaProtocolFixture.Exchange exchange) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.endpoint()).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(SystemSlaProtocol.HEADER, exchange.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
