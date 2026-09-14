package com.dwp.services.auth.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual Auth sources and Redis. Owner workload is explicitly signed fixture data, not an Approval DB proof. */
@Testcontainers
class SystemSlaCurrentAuthorityPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp-owner", "cicero-system-sla");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379).withLabel("dwp-owner", "cicero-system-sla");
    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static long nextTenant = 810000;
    long tenant, role, user;
    UUID person;
    SystemSlaProtocolFixture fixture;
    SystemSlaProofVerifier verifier;
    SystemSlaCurrentAuthority authority;

    @BeforeAll static void start() {
        var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load();
        flyway.migrate(); flyway.validate(); assertThat(flyway.info().pending()).isEmpty();
        jdbc = new JdbcTemplate(source); named = new NamedParameterJdbcTemplate(source);
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @BeforeEach void prepare() {
        tenant = ++nextTenant; user = tenant * 100; person = UUID.randomUUID();
        jdbc.update("INSERT INTO com_tenants(tenant_id,code,name) VALUES(?,?,?)", tenant, "SLA_TEST_" + tenant, "Disposable SYSTEM_SLA tenant");
        subject(user, person); role = jdbc.queryForObject("INSERT INTO com_roles(tenant_id,code,name) VALUES(?,'SLA_REVIEWER','SLA fixture role') RETURNING role_id", Long.class, tenant);
        jdbc.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(?,?,?)", tenant, role, user);
        for (String key : List.of("APP.APPROVALS", "ACTION.APPROVAL_TASK"))
            jdbc.update("INSERT INTO com_resources(tenant_id,type,key,name) VALUES(?,?,?,?)", tenant, key.startsWith("APP.") ? "APP" : "ACTION", key, key);
        var resourceSet = UUID.randomUUID();
        jdbc.update("INSERT INTO com_admin_resource_sets(resource_set_id,tenant_id,resource_set_key,name,resource_type) VALUES(?,?,'RS_APPROVALS','SLA fixture set','APP')", resourceSet, tenant);
        jdbc.update("INSERT INTO com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key) VALUES(?,?,'APP','APP.APPROVALS')", tenant, resourceSet);
        grant("APP.APPROVALS", "VIEW"); grant("ACTION.APPROVAL_TASK", "VIEW"); grant("ACTION.APPROVAL_TASK", "APPROVE");
        fixture = new SystemSlaProtocolFixture(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        verifier = new SystemSlaProofVerifier(fixture.json, fixture.keys(), Clock.systemUTC());
        authority = new SystemSlaCurrentAuthority(new SystemSlaSubjectRepository(named, fixture.json), Clock.systemUTC());
    }
    @Test void realAuthCurrentRoleEntitlementAndOpaqueSetIssueBoundDefaultHttpAttestationAndRejectReplay() throws Exception {
        var exchange = exchange(false); var service = service(authority);
        try (var server = new SystemSlaEmbeddedServer(service, true)) {
            var response = post(server, exchange, null); assertThat(response.statusCode()).isEqualTo(200);
            var body = fixture.json.parse(response.body()); SystemSlaJson.keys(body, Set.of("sourceAttestation"));
            var jwt = SignedJWT.parse(body.get("sourceAttestation").textValue());
            assertThat(jwt.verify(new RSASSAVerifier(fixture.attestation.toPublicJWK()))).isTrue();
            var claims = fixture.json.parse(jwt.getPayload().toBytes()); assertThat(claims.size()).isEqualTo(15);
            assertThat(claims.get("authority").get("authorityRevision").asText()).matches("asla-[a-f0-9]{64}");
            var seat = claims.get("recipients").get(0); assertThat(seat.size()).isEqualTo(7); assertThat(seat.get("eligible").booleanValue()).isTrue();
            assertThat(seat.get("userId").longValue()).isEqualTo(user); assertThat(seat.get("personPublicId").asText()).isEqualTo(person.toString());
            assertThat(post(server, exchange, null).statusCode()).isEqualTo(403);
        }
    }
    @Test void fullFrozenAudienceIncludesDeniedSeatRatherThanTruncatingOrReplacingIt() {
        var exchange = exchange(true); var proof = verifier.verify(exchange.body(), exchange.token());
        var current = authority.current(proof);
        assertThat(current.recipients()).hasSize(2);
        assertThat(current.recipients().stream().map(SystemSlaAuthorityPort.Recipient::userId).toList()).containsExactly(user, user + 1);
        assertThat(current.recipients().getFirst().eligible()).isTrue();
        assertThat(current.recipients().getLast().eligible()).isFalse();
        assertThat(current.recipients().getLast().reason()).isEqualTo("ROLE_MISSING");
        assertThat(current.recipients().stream().map(SystemSlaAuthorityPort.Recipient::expiresAt).toList()).allMatch(expiry -> !expiry.isBefore(current.expiresAt()));
    }
    @Test void revokedAppEntitlementIsExplicitlyDeniedAndChangesAuthorityRevision() {
        var exchange = exchange(false); var proof = verifier.verify(exchange.body(), exchange.token()); var before = authority.current(proof);
        jdbc.update("DELETE FROM com_role_permissions WHERE tenant_id=? AND resource_id=(SELECT resource_id FROM com_resources WHERE tenant_id=? AND key='APP.APPROVALS')", tenant, tenant);
        var after = authority.current(proof); assertThat(after.authorityRevision()).isNotEqualTo(before.authorityRevision());
        assertThat(after.recipients()).hasSize(1); assertThat(after.recipients().getFirst().reason()).isEqualTo("APP_NOT_ENTITLED");
        assertThat(after.recipients().getFirst().eligible()).isFalse();
    }
    @Test void sameCountMembershipReplacementDuringFreshSourceReadRejectsBeforeNonceConsumption() {
        var exchange = exchange(false); var keys = redis.keys("*"); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var observing = service(proof -> {
            var value = authority.current(proof);
            if (calls.incrementAndGet() == 1) {
                jdbc.update("DELETE FROM com_role_members WHERE tenant_id=? AND user_id=?", tenant, user);
                jdbc.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(?,?,?)", tenant, role, user);
            }
            return value;
        });
        denied(ErrorCode.DECISION_REVISION_CONFLICT, () -> observing.evaluate(observing.preverify(exchange.body(), exchange.token())));
        assertThat(redis.keys("*")).isEqualTo(keys);
    }
    @Test void swappedCanonicalPersonCannotReceiveAndCurrentResourceSetRetirementIsExplicit() {
        var exchange = exchange(false); var proof = verifier.verify(exchange.body(), exchange.token());
        jdbc.update("UPDATE com_users SET person_public_id=?,updated_at=clock_timestamp() WHERE tenant_id=? AND user_id=?", UUID.randomUUID(), tenant, user);
        assertThat(authority.current(proof).recipients().getFirst().reason()).isEqualTo("PERSON_CHANGED");
        jdbc.update("UPDATE com_admin_resource_sets SET lifecycle_state='RETIRED',version=version+1 WHERE tenant_id=?", tenant);
        assertThat(authority.current(proof).recipients().getFirst().reason()).isEqualTo("RESOURCE_SET_INACTIVE");
    }
    @Test void defaultDisabledHttpAndBorrowedIdentityHeadersHaveNoNonceWrites() throws Exception {
        var exchange = exchange(false); var keys = redis.keys("*");
        try (var disabled = new SystemSlaEmbeddedServer(service(authority), false); var active = new SystemSlaEmbeddedServer(service(authority), true)) {
            assertThat(post(disabled, exchange, null).statusCode()).isEqualTo(503);
            assertThat(post(active, exchange, "Authorization").statusCode()).isEqualTo(403);
            assertThat(post(active, exchange, "X-DWP-Tenant-ID").statusCode()).isEqualTo(403);
        }
        assertThat(redis.keys("*")).isEqualTo(keys);
    }
    @Test void unavailableRedisRejectsBeforeReadingAuthSubjects() {
        var broken = new LettuceConnectionFactory("127.0.0.1", 1); broken.afterPropertiesSet();
        try {
            var unavailable = new StringRedisTemplate(broken); unavailable.afterPropertiesSet(); var reads = new java.util.concurrent.atomic.AtomicInteger();
            var service = new SystemSlaAuthorityService(() -> verifier, proof -> { reads.incrementAndGet(); return authority.current(proof); },
                    new SystemSlaReplayStore(unavailable, Clock.systemUTC()), new SystemSlaAttestationIssuer(fixture.json, fixture::keys, Clock.systemUTC()), true);
            var exchange = exchange(false); denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> service.evaluate(service.preverify(exchange.body(), exchange.token())));
            assertThat(reads.get()).isZero();
        } finally { broken.destroy(); }
    }
    @Test void completeThousandSeatSignedAudienceRemainsBoundedAndCannotHideMissingSubjects() throws Exception {
        var binding = binding();
        var seats = fixture.json.tree(new ArrayList<>());
        for (int index = 0; index < 1000; index++) ((com.fasterxml.jackson.databind.node.ArrayNode) seats).add(fixture.json.tree(
                Map.of("userId", user + index, "personPublicId", index == 0 ? person.toString() : UUID.randomUUID().toString(), "taskId", UUID.randomUUID().toString(), "taskVersion", 0)));
        binding.set("audience", seats); var exchange = fixture.exchange(fixture.redigest(binding));
        assertThat(exchange.token().length()).isLessThanOrEqualTo(2048); assertThat(exchange.body().length).isLessThanOrEqualTo(524288);
        var service = service(authority); var proof = service.preverify(exchange.body(), exchange.token());
        var current = authority.current(proof); assertThat(current.recipients()).hasSize(1000);
        assertThat(current.recipients().stream().filter(SystemSlaAuthorityPort.Recipient::eligible).count()).isEqualTo(1);
        assertThat(current.recipients().stream().skip(1).allMatch(seat -> "SUBJECT_MISSING".equals(seat.reason()))).isTrue();
        var token = service.evaluate(proof); assertThat(token.length()).isLessThan(524288);
        var claims = fixture.json.parse(SignedJWT.parse(token).getPayload().toBytes()); assertThat(claims.get("recipients").size()).isEqualTo(1000);
    }
    private void subject(long id, UUID publicId) { jdbc.update("INSERT INTO com_users(user_id,tenant_id,display_name,email,identity_plane,person_public_id) VALUES(?,?,?,?,'TENANT',?)", id, tenant, "SLA subject", id + "@sla.test", publicId); }
    private void grant(String resource, String permission) { jdbc.update("INSERT INTO com_role_permissions(tenant_id,role_id,resource_id,permission_id) SELECT ?,?,resource_id,permission_id FROM com_resources,com_permissions WHERE tenant_id=? AND key=? AND code=?", tenant, role, tenant, resource, permission); }
    private SystemSlaAuthorityService service(SystemSlaAuthorityPort port) { return new SystemSlaAuthorityService(() -> verifier, port, new SystemSlaReplayStore(redis, Clock.systemUTC()), new SystemSlaAttestationIssuer(fixture.json, fixture::keys, Clock.systemUTC()), true); }
    private SystemSlaProtocolFixture.Exchange exchange(boolean deniedSeat) {
        var binding = binding();
        var seats = fixture.json.tree(List.of(Map.of("userId", user, "personPublicId", person.toString(), "taskId", UUID.randomUUID().toString(), "taskVersion", 0)));
        if (deniedSeat) {
            var other = UUID.randomUUID(); subject(user + 1, other);
            ((com.fasterxml.jackson.databind.node.ArrayNode) seats).add(fixture.json.tree(Map.of("userId", user + 1, "personPublicId", other.toString(), "taskId", UUID.randomUUID().toString(), "taskVersion", 0)));
        }
        binding.set("audience", seats); return fixture.exchange(fixture.redigest(binding));
    }
    private ObjectNode binding() {
        var binding = (ObjectNode) fixture.binding(); binding.put("tenantId", tenant);
        ((ObjectNode) binding.at("/source/stage")).put("candidateRole", "SLA_REVIEWER");
        ((ObjectNode) binding.at("/source/workflow/definition/stages/0")).put("candidateRole", "SLA_REVIEWER");
        ((ObjectNode) binding.at("/source/workflow")).put("definitionSha256", fixture.json.digest(binding.at("/source/workflow/definition")));
        return binding;
    }
    private static HttpResponse<byte[]> post(SystemSlaEmbeddedServer server, SystemSlaProtocolFixture.Exchange exchange, String borrowed) throws Exception {
        var request = HttpRequest.newBuilder(server.endpoint()).header("Content-Type", "application/json").header("X-DWP-Service-Identity", "dwp-approval-server").header(SystemSlaProtocol.HEADER, exchange.token());
        if (borrowed != null) request.header(borrowed, "borrowed");
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void denied(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable body) { assertThatThrownBy(body).isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code)); }
}
