package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofFixture.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.workflowruntime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** No mocked authority positive: full Auth migrations, current Spring authority, role SQL, Redis and signatures. */
class WorkflowRuntimeAuthorityPostgresTest {
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connection;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static WorkflowRuntimeSourceRepository sources;
    private static WorkflowRuntimeIdentityAuthorityBridge identity;
    private static WorkflowRuntimeAuthorityService service;
    private static WorkflowRuntimeProofVerifier verifier;
    private static long tenant;
    private long actor, principal, requester, delegate, role;
    private String code;

    @BeforeAll static void freshFullCurrentAuth() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine"); postgres.start();
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379); redisContainer.start();
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys = generator.generateKeyPair();
        context = new AnnotationConfigApplicationContext(); context.registerBean(javax.sql.DataSource.class, () -> source);
        context.registerBean(KeyPair.class, () -> keys);
        context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class, WorkflowRuntimeIdentityAuthorityBridge.class);
        context.refresh(); jdbc = context.getBean(JdbcTemplate.class);
        context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
        var contracts = context.getBean(ProductAuthorizationContractService.class);
        contracts.approve("product-surfaces", 8, "isolated-checker"); contracts.activate("product-surfaces", 8, "isolated-release", 0);
        var factory = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)));
        sources = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(source), factory.getRepository(RoleMemberRepository.class), JSON);
        identity = context.getBean(WorkflowRuntimeIdentityAuthorityBridge.class); verifier = new WorkflowRuntimeProofVerifier(JSON, keys());
        connection = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379)); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        service = new WorkflowRuntimeAuthorityService(verifier, identity, new WorkflowRuntimeAdmissionLink(verifier, keys()), sources,
                new WorkflowRuntimeReplayStore(redis), new WorkflowRuntimeAttestationIssuer(JSON, keys()), JSON);
        tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
    }
    @AfterAll static void closeEverything() {
        if (context != null) context.close(); if (connection != null) connection.destroy();
        if (redisContainer != null) redisContainer.close(); if (postgres != null) postgres.close();
    }
    @BeforeEach void explicitAuthorityFixtureNotCatalogAutoActivation() {
        actor = newUser("actor"); principal = newUser("principal"); requester = newUser("requester"); delegate = newUser("delegate");
        code = "RUNTIME_REVIEWER_" + actor;
        role = jdbc.queryForObject("INSERT INTO com_roles(tenant_id,code,name,status) VALUES(?,?,?,'ACTIVE') RETURNING role_id", Long.class, tenant, code, "Isolated runtime reviewer");
        member(actor, role); member(principal, role);
        long workspace = jdbc.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        for (long user : List.of(actor, principal, delegate)) member(user, workspace);
        for (long user : List.of(actor, delegate)) grant(user, "ACTION.APPROVAL_FORM", "VIEW");
    }
    @Test void sealedFrozenRecheckReturnsGenuineRoleIdAndPreservesAllCallerSnapshotPins() {
        var binding = candidateBinding(PoolMode.SEALED, actor, 1); var before = binding.deepCopy();
        var envelope = proof(Operation.CANDIDATES, binding); var claims = attest(service.resolve(envelope.transport(), envelope.body()), Operation.CANDIDATES);
        assertThat(binding).isEqualTo(before); assertThat(claims.get("bindingsSha256").asText()).isEqualTo(WorkflowRuntimeJson.sha(JSON.canonical(before)));
        var result = claims.get("result"); assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("role", "complete", "truncated", "members", "memberSetSha256");
        assertThat(result.get("role").get("roleId").asLong()).isEqualTo(role); assertThat(result.get("role").get("roleCode").asText()).isEqualTo(code);
        assertThat(result.get("members")).hasSize(2); assertThat(result.get("complete").asBoolean()).isTrue(); assertThat(result.get("truncated").asBoolean()).isFalse();
        assertThat(claims.get("authority").get("sourceRevision").asText()).startsWith("awr-");
        assertThat(claims.get("authority").get("ownerAuthRevision").asText()).isNotEqualTo(binding.get("owner").get("decisionRevision").asText());
        assertThatThrownBy(() -> service.resolve(envelope.transport(), envelope.body())).isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void forgedNullFrozenPoolFailsBeforeCurrentIdentityOrMembershipOrNonce() {
        var binding = candidateBinding(PoolMode.SEALED, actor, 1); ((ObjectNode) binding.get("stage")).putNull("snapshotSha256");
        var proof = proof(Operation.CANDIDATES, binding); denied(proof, ErrorCode.FORBIDDEN); noNonce(proof);
    }
    @Test void actualDelegationVoterUsesSignedCurrentDbRoleIdNotArbitraryCodeSnapshotAlias() {
        var pool = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, delegate, 1));
        long currentRole = attest(service.resolve(pool.transport(), pool.body()), Operation.CANDIDATES).get("result").get("role").get("roleId").asLong();
        var binding = candidateBinding(PoolMode.SEALED, delegate, 1); binding.set("target", target(delegate, person(delegate), principal, person(principal), currentRole));
        var request = proof(Operation.VOTER, binding); var claims = attest(service.resolve(request.transport(), request.body()), Operation.VOTER);
        assertThat(claims.get("result").get("delegation").get("authorityRoleId").asLong()).isEqualTo(role);
        ((ObjectNode) binding.get("target").get("delegation")).put("authorityRoleId", role + 999); denied(proof(Operation.VOTER, binding), ErrorCode.FORBIDDEN);
    }
    @Test void requesterPersonSodAndRevokedPrincipalRoleRejectWithoutNonceOrCallerWrite() {
        var binding = candidateBinding(PoolMode.SEALED, actor, 1); binding.set("target", target(actor, person(actor), actor, person(actor), role));
        ((ObjectNode) binding.get("stage")).put("requesterUserId", actor).put("requesterPersonPublicId", person(actor).toString());
        var proof = proof(Operation.VOTER, binding); denied(proof, ErrorCode.FORBIDDEN); noNonce(proof);
        binding = candidateBinding(PoolMode.SEALED, delegate, 1); binding.set("target", target(delegate, person(delegate), principal, person(principal), role));
        jdbc.update("DELETE FROM com_role_members WHERE tenant_id=? AND user_id=? AND role_id=?", tenant, principal, role);
        proof = proof(Operation.VOTER, binding); denied(proof, ErrorCode.FORBIDDEN); noNonce(proof);
    }
    @Test void currentFormViewIsIndependentAndRevocationCannotActivatePopulationByTaskApprove() {
        var binding = candidateBinding(PoolMode.SEALED, actor, 1);
        revokeForm(actor);
        var proof = proof(Operation.CANDIDATES, binding); denied(proof, ErrorCode.FORBIDDEN); noNonce(proof);
        assertThat(context.getBean(AuthService.class).getPermissions(actor, tenant)).anyMatch(permission -> permission.getResourceKey().equals("ACTION.APPROVAL_TASK") && permission.getPermissionCode().equals("APPROVE"));
    }
    @Test void informationAdmissionEchoesExactOriginalCommandAndDoesNotRequireFormPopulationGrant() {
        var binding = command(tenant, actor, person(actor));
        revokeForm(actor);
        bindContext((ObjectNode) binding.get("command"), actor);
        var envelope = proof(Operation.INFORMATION_ADMISSION, binding); var claims = attest(service.resolve(envelope.transport(), envelope.body()), Operation.INFORMATION_ADMISSION);
        assertThat(claims.get("result").size()).isEqualTo(15);
        WorkflowRuntimeBindings.INFO_RESULT_FIELDS.forEach(field -> assertThat(JSON.canonical(claims.get("result").get(field))).isEqualTo(JSON.canonical(binding.get("command").get(field))));
        assertThat(claims.get("sub").asText()).isEqualTo(Long.toString(actor));
    }
    @Test void currentOwnerRevocationAfterCryptographicIssueRejectsAndDoesNotConsumeNonce() {
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1));
        jdbc.update("UPDATE com_users SET status='INACTIVE' WHERE tenant_id=? AND user_id=?", tenant, actor);
        denied(envelope, ErrorCode.FORBIDDEN); noNonce(envelope);
    }
    @Test void roleMembershipSwapBetweenPreAndPostWithSameCountFailsClosed() {
        var source = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(jdbc), new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class))).getRepository(RoleMemberRepository.class), JSON) {
            int snapshots;
            @Override public Snapshot snapshot(long currentTenant, java.util.Collection<Long> users) {
                var snapshot = super.snapshot(currentTenant, users);
                if (++snapshots == 1) {
                    jdbc.update("DELETE FROM com_role_members WHERE tenant_id=? AND user_id=? AND role_id=?", tenant, principal, role); member(delegate, role);
                }
                return snapshot;
            }
        };
        var actual = new WorkflowRuntimeAuthorityService(verifier, identity, new WorkflowRuntimeAdmissionLink(verifier, keys()), source,
                new WorkflowRuntimeReplayStore(redis), new WorkflowRuntimeAttestationIssuer(JSON, keys()), JSON);
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1));
        assertThatThrownBy(() -> actual.resolve(envelope.transport(), envelope.body())).isInstanceOf(BaseException.class); noNonce(envelope);
    }
    @Test void memberWithoutCurrentTaskPermissionRejectsWholeCompleteSetRatherThanShrinkingPool() {
        long unprivileged = newUser("member-without-task-grant"); member(unprivileged, role);
        assertThat(sources.completeMembers(tenant, sources.currentRole(tenant, code))).hasSize(3);
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1)); denied(envelope, ErrorCode.FORBIDDEN); noNonce(envelope);
    }
    @Test void sourceGrantExpiryBoundsAttestationAndRedisTtlAcrossIndependentReplicas() {
        var deadline = java.time.Instant.now().plusSeconds(12);
        jdbc.update("UPDATE com_principal_resource_grants SET valid_to=? WHERE tenant_id=? AND principal_type='USER' AND principal_ref=?",
                OffsetDateTime.ofInstant(deadline, java.time.ZoneOffset.UTC), tenant, Long.toString(actor));
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1));
        var claims = attest(service.resolve(envelope.transport(), envelope.body()), Operation.CANDIDATES);
        assertThat(claims.get("exp").asLong()).isLessThanOrEqualTo(deadline.getEpochSecond());
        String prefix = "dwp:auth:approval-workflow-runtime:v1:CANDIDATES:{" + tenant + "}:";
        var verified = verifier.verify(envelope.transport(), envelope.body());
        assertThat(redis.getExpire(prefix + "owner:" + verified.sourceProofJti(), java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 12000L);
        var replica = new WorkflowRuntimeAuthorityService(verifier, identity, new WorkflowRuntimeAdmissionLink(verifier, keys()), sources,
                new WorkflowRuntimeReplayStore(new StringRedisTemplate(connection)), new WorkflowRuntimeAttestationIssuer(JSON, keys()), JSON);
        assertThatThrownBy(() -> replica.resolve(envelope.transport(), envelope.body())).isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void actualDefaultHttpCarriesSixtyFourStagesAndOneThousandCurrentDbMembersThroughInstalledFilter() throws Exception {
        var additions = jdbc.queryForList("""
                INSERT INTO com_users(tenant_id,display_name,email,status,identity_plane,person_public_id)
                SELECT ?, 'Isolated complete member', 'runtime-http-' || gen_random_uuid()::text || '@isolated.test',
                       'ACTIVE','TENANT',gen_random_uuid() FROM generate_series(1,998) RETURNING user_id
                """, Long.class, tenant);
        long workspace = jdbc.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        jdbc.batchUpdate("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", additions.stream()
                .flatMap(user -> java.util.stream.Stream.of(new Object[] {tenant, user, role}, new Object[] {tenant, user, workspace})).toList());
        assertThat(sources.completeMembers(tenant, sources.currentRole(tenant, code))).hasSize(1000);
        var planUsers = new java.util.TreeSet<>(sources.completeMembers(tenant, sources.currentRole(tenant, code))); planUsers.add(requester);
        WorkflowRuntimeQueryPlan.print(jdbc, tenant, planUsers);
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 64));
        try (var server = new WorkflowRuntimeEmbeddedServer(service, true); var client = java.net.http.HttpClient.newHttpClient()) {
            var response = send(client, server.endpoint(), envelope, "POST", Map.of());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var body = JSON.read(response.body()); WorkflowRuntimeJson.exact(body, java.util.Set.of("attestation"));
            var claims = verifier.jwt(body.get("attestation").asText(), MAX_ATTESTATION, keys().attestations(), ATTESTATION_CLAIMS);
            assertThat(claims.get("result").get("members")).hasSize(1000);
            assertThat(response.body()).doesNotContain("@isolated.test", "displayName", "email");
            assertThat(envelope.transport().length()).isLessThanOrEqualTo(2048); assertThat(envelope.sourceProof().length()).isGreaterThan(8192);
            assertThat(send(client, server.endpoint(), envelope, "POST", Map.of()).statusCode()).isEqualTo(403);
            assertThat(send(client, server.endpoint(), envelope, "POST", Map.of("Authorization", "Bearer borrowed")).statusCode()).isEqualTo(401);
            assertThat(send(client, server.endpoint(), envelope, "POST", Map.of("X-DWP-Approval-Form-User-Token", "borrowed")).statusCode()).isEqualTo(401);
            assertThat(send(client, server.endpoint(), envelope, "HEAD", Map.of()).statusCode()).isEqualTo(403);
            assertThat(send(client, java.net.URI.create(server.endpoint() + "?roleId=" + role), envelope, "POST", Map.of()).statusCode()).isEqualTo(403);
            var tampered = new WorkflowRuntimeProofFixture.Envelope(envelope.transport(), new String(envelope.body(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"requestVersion\":4", "\"requestVersion\":5").getBytes(java.nio.charset.StandardCharsets.UTF_8), envelope.sourceProof(), envelope.json(), envelope.ownerClaims(), envelope.transportClaims());
            long before = nonceCount(); assertThat(send(client, server.endpoint(), tampered, "POST", Map.of()).statusCode()).isEqualTo(403); assertThat(nonceCount()).isEqualTo(before);
            System.out.println("NEW runtime actual HTTP: stages=64 members=1000 transportBytes=" + envelope.transport().length()
                    + " ownerProofBytes=" + envelope.sourceProof().length() + " responseBytes=" + response.body().length());
        }
        member(newUser("one-thousand-and-one"), role);
        var overflow = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1)); denied(overflow, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); noNonce(overflow);
    }
    @Test void disabledRuntimeGateRemains503EvenForCurrentSignedOwnerWithoutConsumingNonce() throws Exception {
        var envelope = proof(Operation.CANDIDATES, candidateBinding(PoolMode.SEALED, actor, 1));
        try (var server = new WorkflowRuntimeEmbeddedServer(service, false); var client = java.net.http.HttpClient.newHttpClient()) {
            assertThat(send(client, server.endpoint(), envelope, "POST", Map.of()).statusCode()).isEqualTo(503);
        }
        noNonce(envelope);
    }
    private static java.net.http.HttpResponse<String> send(java.net.http.HttpClient client, java.net.URI endpoint,
            WorkflowRuntimeProofFixture.Envelope proof, String method, Map<String, String> extra) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(30))
                .header(TOKEN_HEADER, proof.transport()).header("X-DWP-Service-Identity", "dwp-approval-server").header("Content-Type", "application/json");
        extra.forEach(request::header);
        return client.send(request.method(method, java.net.http.HttpRequest.BodyPublishers.ofByteArray(proof.body())).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode candidateBinding(PoolMode mode, long currentActor, int stages) {
        var binding = candidate(tenant, currentActor, person(currentActor), requester, person(requester), code, mode, stages);
        bindContext((ObjectNode) binding.get("owner"), currentActor); return binding;
    }
    private void bindContext(ObjectNode owner, long currentActor) {
        var current = context.getBean(ProductSurfaceAuthorityService.class).evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, currentActor,
                "approvals", "approvals.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL, "route.approvals.work.task-decision.action", null, null, null, null, List.of()));
        assertThat(current.decision()).as("Actual current owner: %s", current.reasonCode()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        owner.put("contextKey", current.contextKey()); owner.put("contextScopeKey", current.scopes().getFirst().key());
    }
    private static JsonNode attest(WorkflowRuntimeAttestationIssuer.Response response, Operation operation) {
        var claims = verifier.jwt(response.attestation(), MAX_ATTESTATION, keys().attestations(), ATTESTATION_CLAIMS);
        assertThat(claims.get("iss").asText()).isEqualTo(operation.attestationIssuer()); assertThat(claims.get("aud").asText()).isEqualTo(operation.attestationAudience());
        return claims;
    }
    private static void denied(WorkflowRuntimeProofFixture.Envelope proof, ErrorCode code) {
        assertThatThrownBy(() -> service.resolve(proof.transport(), proof.body())).isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
    private static long nonceCount() { return redis.keys("dwp:auth:approval-workflow-runtime:v1:*").size(); }
    private static void noNonce(WorkflowRuntimeProofFixture.Envelope proof) {
        String prefix = "dwp:auth:approval-workflow-runtime:v1:" + proof.json().get("operation").asText() + ":{" + tenant + "}:";
        assertThat(redis.hasKey(prefix + "owner:" + proof.ownerClaims().get("jti"))).isFalse();
        assertThat(redis.hasKey(prefix + "transport:" + proof.transportClaims().get("jti"))).isFalse();
    }
    private long newUser(String label) {
        UUID person = UUID.randomUUID(); return jdbc.queryForObject("INSERT INTO com_users(tenant_id,display_name,email,status,identity_plane,person_public_id) VALUES(?,?,?,'ACTIVE','TENANT',?) RETURNING user_id",
                Long.class, tenant, "Runtime " + label, "runtime-" + person + "@isolated.test", person);
    }
    private static UUID person(long user) { return jdbc.queryForObject("SELECT person_public_id FROM com_users WHERE tenant_id=? AND user_id=?", UUID.class, tenant, user); }
    private static void member(long user, long role) { jdbc.update("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, role); }
    private void grant(long user, String resource, String permission) {
        long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=?", Long.class, tenant, resource);
        long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?", Long.class, permission);
        new PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER", Long.toString(user), resourceId, permissionId, "ADMIN_DIRECT",
                "runtime-" + actor + '-' + user + '-' + resource, OffsetDateTime.now().plusMinutes(10), "Explicit isolated runtime source grant.", requester);
    }
    private void revokeForm(long user) {
        String source = "runtime-" + actor + '-' + user + "-ACTION.APPROVAL_FORM";
        var grants = new PrincipalResourceGrantRepository(jdbc); var current = grants.findBySource(tenant, "ADMIN_DIRECT", source).orElseThrow();
        assertThat(grants.revoke(tenant, "ADMIN_DIRECT", source, requester, "Actual isolated current FORM VIEW revocation.", current.version())).isTrue();
    }
}
