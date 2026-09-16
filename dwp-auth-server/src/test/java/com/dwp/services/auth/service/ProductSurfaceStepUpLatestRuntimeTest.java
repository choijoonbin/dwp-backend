package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Actual full-migration, seed-loader, Spring authority and signature runtime proof. */
@Testcontainers(disabledWithoutDocker = true)
class ProductSurfaceStepUpLatestRuntimeTest {
    private static final String KEY = "product-surfaces";
    private static final String ROUTE = "route.approvals.admin.workflow-publish.action";
    private static final String ACR = "urn:dwp:acr:mfa";
    private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-000000000007");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ProductAuthorizationContractRepository repository;
    private static ProductSurfaceStepUpRouteResolver resolver;
    private static ProductSurfaceAuthorityService authorityService;
    private static ProductSurfaceStepUpChallengeService challenges;
    private static AuthSessionService sessions;
    private static KeyPair keys;
    private static long actor;
    private static long tenant;
    private static UUID duty;
    private static long maker;
    private static long checker;
    private static UUID resourceSet;
    private static UUID responsibility;

    @BeforeAll
    static void loadActualLatestSeedAndCurrentIdentity() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        var flyway = Flyway.configure().dataSource(source).locations(
                "filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load();
        flyway.migrate(); flyway.validate();
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "actual-stepup-configuration", java.util.Map.of("dwp.auth.step-up.required-acr", ACR)));
        context.registerBean(javax.sql.DataSource.class, () -> source);
        context.registerBean(KeyPair.class, () -> keys);
        context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class); transactions = context.getBean(TransactionTemplate.class);
        repository = context.getBean(ProductAuthorizationContractRepository.class);
        resolver = context.getBean(ProductSurfaceStepUpRouteResolver.class);
        authorityService = context.getBean(ProductSurfaceAuthorityService.class);
        challenges = context.getBean(ProductSurfaceStepUpChallengeService.class);
        sessions = context.getBean(AuthSessionService.class);
        context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
        assertThat(repository.findActive(KEY)).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(request(null, null))).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        var contracts = context.getBean(ProductAuthorizationContractService.class);
        for (long version = 2; version <= 7; version++) {
            contracts.approve(KEY, version, "test-checker");
            contracts.activate(KEY, version, "test-release", version - 2);
            var resolved = resolver.resolve(request(null, null));
            assertThat(resolved.bundleVersion()).isEqualTo(version);
            assertThat(resolved.bundleChecksum()).isEqualTo(repository.findActive(KEY).orElseThrow().checksum());
            assertThat(resolved.pointerRevision()).isEqualTo(version - 1);
            assertThat(resolved.routeContractKey()).isEqualTo(ROUTE);
        }
        assertThat(repository.findActive(KEY).orElseThrow().version()).isEqualTo(7);
        createExplicitIsolatedScopeDuty();
        when(sessions.requireFreshAssurance(any(), eq(actor), eq(tenant), eq(ACR), eq(600L)))
                .thenAnswer(invocation -> new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", Instant.now().minusSeconds(10), ACR, List.of("mfa", "otp", "pwd")));
    }

    @AfterAll
    static void closeSpring() { if (context != null) context.close(); }

    @Test
    void latestSevenIssuesAnExactlyBoundSignedChallengeUsingCurrentDatabaseAuthority() throws Exception {
        var authority = currentAuthority();
        assertThat(authority.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(authority.requiredAssurance()).isEqualTo(ACR);
        assertThat(authority.effectiveReadOnly()).isFalse();
        assertThat(authority.policyRevision()).contains("policy-7-6-fe9721ef");
        var request = request(authority.contextKey(), authority.scopes().getFirst().key());
        var issued = (ProductSurfaceStepUpChallengeService.Issued) issue(request, revision(authority));
        String[] token = issued.response().challenge().split("\\.");
        var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(keys.getPublic());
        signature.update((token[0] + '.' + token[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(signature.verify(Base64.getUrlDecoder().decode(token[2]))).isTrue();
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(token[1]));
        assertThat(claims.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS);
        assertThat(claims.path("command_contract_key").asText()).isEqualTo(ROUTE);
        assertThat(claims.path("target_id").asText()).isEqualTo(TARGET.toString());
        assertThat(claims.path("target_version").asLong()).isEqualTo(7);
        assertThat(claims.path("context_key").asText()).isEqualTo(authority.contextKey());
        assertThat(claims.path("scope_ref").asText()).isEqualTo(request.contextScopeKey());
        assertThat(claims.path("decision_revision").asText()).isEqualTo(revision(authority));
        assertThat(claims.path("aud").asText()).isEqualTo("dwp-approval-server");
        assertThat(issued.response().expiresAt()).isBeforeOrEqualTo(authority.validUntil().toInstant());
    }

    @Test
    void missingExpectedDecisionRevisionCannotIssueAChallenge() {
        assertThatThrownBy(() -> issue(request(null, null), null))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
    }

    @Test
    void sealedEightDocumentPolicyPathIssuesUsingCurrentScopedPublishDuty() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 8, "test-checker");
            contracts.activate(KEY, 8, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            var now = OffsetDateTime.now();
            var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
            var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER",
                    Long.toString(actor), "APPROVAL_POLICY_PUBLISH", resourceSet, responsibility, "MANUAL",
                    now.minusMinutes(1), now.plusMinutes(20), now.plusMinutes(10),
                    "Explicit isolated document policy publish duty.", maker));
            assignments.approve(tenant, pending.assignmentId(), checker, pending.version(),
                    "Independent isolated document policy duty approval.");
            String routeKey = "route.approvals.admin.document-policy-publish.action";
            var request = documentPolicyRequest(null, null);
            var resolved = resolver.resolve(request);
            assertThat(resolved.bundleVersion()).isEqualTo(8);
            assertThat(resolved.bundleChecksum()).isEqualTo("9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942");
            var authority = currentAuthority(request, routeKey);
            assertThat(authority.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
            assertThat(authority.requiredAssurance()).isEqualTo(ACR);
            assertThat(authority.effectiveReadOnly()).isFalse();
            var bound = documentPolicyRequest(authority.contextKey(), authority.scopes().getFirst().key());
            var issued = (ProductSurfaceStepUpChallengeService.Issued) issue(bound, revision(authority));
            var claims = verifySignature(issued);
            assertThat(claims.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrderElementsOf(ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS);
            assertThat(claims.path("command_contract_key").asText()).isEqualTo(routeKey);
            assertThat(claims.path("activation_policy").asText()).isEqualTo("STEPUP-MGMT-HIGH-V1");
            assertThat(claims.path("acr").asText()).isEqualTo(ACR);
            assertThat(claims.path("capability_contract_key").asText()).isEqualTo("approvals.policy.publish");
            assertThat(claims.path("target_type").asText()).isEqualTo("DOCUMENT_POLICY");
            assertThat(claims.path("target_id").asText()).isEqualTo(TARGET.toString());
            assertThat(claims.path("target_version").asLong()).isEqualTo(7);
            assertThat(claims.path("command_path").asText()).isEqualTo(bound.commandPath());
            assertThat(claims.path("command_method").asText()).isEqualTo("POST");
            assertThat(claims.path("idempotency_key").asText()).isEqualTo(bound.idempotencyKey());
            assertThat(claims.path("context_key").asText()).isEqualTo(authority.contextKey());
            assertThat(claims.path("scope_ref").asText()).isEqualTo(bound.contextScopeKey());
            assertThat(claims.path("decision_revision").asText()).isEqualTo(revision(authority));
            assertThat(claims.path("payload_sha256").asText()).isEqualTo(payloadDigest());
            assertThat(claims.path("aud").asText()).isEqualTo("dwp-approval-server");
            assertThat(issued.response().expiresAt()).isBeforeOrEqualTo(authority.validUntil().toInstant());
            clearInvocations(sessions);
            var noId = changed(bound, "POST", "/api/approvals/v1/admin/document-tools/policy/publish",
                    "DOCUMENT_POLICY", TARGET.toString(), bound.payload());
            assertNoChallenge(noId, revision(authority));
            verifyNoInteractions(sessions);
        });
    }

    @Test
    void sealedNineDocumentPolicyChallengeIsAcceptedOnlyForItsExactOwnerCommand() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 9, "test-checker");
            contracts.activate(KEY, 9, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            assertThat(repository.findActive(KEY).orElseThrow().checksum())
                    .isEqualTo("02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7");
            var now = OffsetDateTime.now();
            var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
            var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER",
                    Long.toString(actor), "APPROVAL_POLICY_PUBLISH", resourceSet, responsibility, "MANUAL",
                    now.minusMinutes(1), now.plusMinutes(20), now.plusMinutes(10),
                    "Explicit isolated genuine9 document policy publish duty.", maker));
            assignments.approve(tenant, pending.assignmentId(), checker, pending.version(),
                    "Independent isolated genuine9 document policy duty approval.");
            String routeKey = "route.approvals.admin.document-policy-publish.action";
            var resolved = resolver.resolve(documentPolicyRequest(null, null));
            assertThat(resolved.bundleVersion()).isEqualTo(9);
            assertThat(resolved.pointerRevision()).isEqualTo(repository.findActivePointer(KEY).orElseThrow().revision());
            var authority = currentAuthority(documentPolicyRequest(null, null), routeKey);
            assertThat(authority.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(authority.effectiveReadOnly()).isFalse();
            assertThat(authority.requiredAssurance()).isEqualTo(ACR);
            assertThat(authority.policyRevision()).startsWith("policy-9-");
            var bound = documentPolicyRequest(authority.contextKey(), authority.scopes().getFirst().key());
            var issued = (ProductSurfaceStepUpChallengeService.Issued) issue(bound, revision(authority));
            var claims = verifySignature(issued);
            assertThat(claims.path("command_contract_key").asText()).isEqualTo(routeKey);
            assertThat(claims.path("capability_contract_key").asText()).isEqualTo("approvals.policy.publish");
            var owner = new com.dwp.services.approval.security.ApprovalStepUpVerifier(JSON,
                    "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                            .encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----",
                    "https://auth.corp.example.com/product-surface-step-up", "dwp-approval-server",
                    "stepup-latest-seven", ACR, 600, 300);
            var binding = ownerBinding(bound, authority, 7, TARGET.toString(), routeKey, payloadDigest());
            assertThat(owner.verify(issued.response().challenge(), binding).binding()).isEqualTo(binding);
            for (var substituted : List.of(
                    ownerBinding(bound, authority, 8, TARGET.toString(), routeKey, payloadDigest()),
                    ownerBinding(bound, authority, 7, UUID.randomUUID().toString(), routeKey, payloadDigest()),
                    ownerBinding(bound, authority, 7, TARGET.toString(), ROUTE, payloadDigest()),
                    ownerBinding(bound, authority, 7, TARGET.toString(), routeKey, "0".repeat(64)))) {
                assertThatThrownBy(() -> owner.verify(issued.response().challenge(), substituted))
                        .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
            }
            for (var invalid : List.of(
                    changed(bound, "POST", bound.commandPath(), "DOCUMENT_POLICY", UUID.randomUUID().toString(), bound.payload()),
                    changed(bound, "POST", bound.commandPath(), "WORKFLOW", bound.targetId(), bound.payload()),
                    changed(bound, "POST", bound.commandPath(), "DOCUMENT_POLICY", bound.targetId(), JSON.createObjectNode().put("expectedVersion", 8)),
                    changed(bound, "POST", bound.commandPath().replace("policies/" + TARGET, "policy"), "DOCUMENT_POLICY", bound.targetId(), bound.payload()))) {
                clearInvocations(sessions);
                assertThatThrownBy(() -> issue(invalid, revision(authority))).isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
                verifyNoInteractions(sessions);
            }
        });
    }

    private static com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding ownerBinding(
            ProductSurfaceStepUpDtos.IssueRequest request, ProductSurfaceAuthorityDtos.AuthorityResult authority,
            long version, String target, String route, String payloadHash) {
        return new com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding(actor, tenant, route,
                request.contextKey(), "STEPUP-MGMT-HIGH-V1", "approvals.policy.publish", request.contextScopeKey(),
                "DOCUMENT_POLICY", target, version, "POST", request.commandPath(), request.idempotencyKey(),
                payloadHash, revision(authority));
    }

    @Test
    void sealedEightAdminCandidatesRequireIndependentSourceViewWithinCurrentFormOwnerScopes() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 8, "test-checker");
            contracts.activate(KEY, 8, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            String routeKey = "route.approvals.admin.form-field-candidates.data";
            var owner = authorityService.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, actor,
                    "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    "route.approvals.admin.forms-workflow-reference.data", null, null, null, null, List.of()));
            assertThat(owner.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            var request = new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, actor, "approvals",
                    "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL, routeKey,
                    null, null, null, null, List.of());
            assertThat(authorityService.evaluate(request).decision()).isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE tenant_id=? AND key=? AND enabled=true",
                    Long.class, tenant, "ACTION.APPROVAL_FORM_USER_DIRECTORY")).isEqualTo(1);
            explicitGrant("ACTION.APPROVAL_FORM_USER_DIRECTORY", "VIEW");
            var current = authorityService.evaluate(request);
            assertThat(current.decision()).as("Actual v8 admin Source VIEW with current independent FORM VIEW: %s",
                    current.reasonCode()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(current.scopes()).extracting(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                    .containsExactlyElementsOf(owner.scopes().stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key).toList());
            assertThat(current.effectiveReadOnly()).isTrue();
        });
    }

    @Test
    void sealedEightSignedAdminLookupChecksCurrentSourceAndOwnerBeforeReplayOrDirectoryReads() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 8, "test-checker");
            contracts.activate(KEY, 8, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            explicitGrant("ACTION.APPROVAL_FORM_USER_DIRECTORY", "VIEW");
            var current = authorityService.evaluate(adminCandidates(actor, null, null));
            assertThat(current.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            try (var redis = new org.testcontainers.containers.GenericContainer<>(
                    org.testcontainers.utility.DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
                redis.start();
                var connection = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        redis.getHost(), redis.getMappedPort(6379));
                connection.afterPropertiesSet();
                try {
                    var redisKeys = new org.springframework.data.redis.core.StringRedisTemplate(connection);
                    var clock = java.time.Clock.systemUTC();
                    var proofs = new ApprovalFormUserSourceProofVerifier(JSON,
                            new com.nimbusds.jose.jwk.JWKSet(ApprovalFormUserProofTestSupport.KEY.toPublicJWK()).toString(), clock);
                    var factories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
                            org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(
                                    context.getBean(jakarta.persistence.EntityManagerFactory.class)));
                    var actual = new ApprovalFormUserCurrentAuthorityAdapter(
                            context.getBean(ProductAuthorizationIdentityEvidenceService.class), authorityService,
                            factories.getRepository(com.dwp.services.auth.repository.UserRepository.class),
                            factories.getRepository(com.dwp.services.auth.repository.TenantRepository.class), clock);
                    var ports = mock(org.springframework.beans.factory.ObjectProvider.class);
                    when(ports.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(actual));
                    var directory = mock(com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository.class);
                    when(directory.search(tenant, "Kim", 10)).thenReturn(List.of());
                    var service = new ApprovalFormUserDirectoryService(JSON, proofs,
                            new ApprovalFormUserProofReplayStore(redisKeys, clock), ports, directory);
                    var result = service.search(adminSearchBody(proofs, current, node -> { }));
                    assertThat(result.authRevision()).isEqualTo(current.authRevision());
                    assertThat(result.policyRevision()).isEqualTo(current.policyRevision());
                    clearInvocations(directory);
                    var consumed = redisKeys.keys("*");
                    for (java.util.function.Consumer<java.util.Map<String, Object>> mutation :
                            List.<java.util.function.Consumer<java.util.Map<String, Object>>>of(
                                    node -> node.put("contextScopeKey", "RS_OTHER"),
                                    node -> node.put("contextKey", "caller-context"),
                                    node -> node.put("routeContractKey", "route.approvals.admin.form-user-candidates.data"),
                                    node -> node.put("purpose", ApprovalFormReferenceProofVerifier.PURPOSE))) {
                        assertThatThrownBy(() -> service.search(adminSearchBody(proofs, current, mutation)))
                                .isInstanceOf(BaseException.class);
                    }
                    assertThat(redisKeys.keys("*")).isEqualTo(consumed);
                    verifyNoInteractions(directory);
                    var grants = new com.dwp.services.auth.repository.PrincipalResourceGrantRepository(jdbc);
                    var source = grants.findBySource(tenant, "ADMIN_DIRECT", "isolated-ACTION.APPROVAL_FORM_USER_DIRECTORY").orElseThrow();
                    assertThat(grants.revoke(tenant, source.sourceType(), source.sourceRef(), checker,
                            "Explicit source revocation regression", source.version())).isTrue();
                    assertAdminLookupDenied(service, adminSearchBody(proofs, current, node -> { }));
                    assertThat(redisKeys.keys("*")).isEqualTo(consumed);
                    verifyNoInteractions(directory);
                    assertThat(grants.reactivate(tenant, source.sourceType(), source.sourceRef(),
                            OffsetDateTime.now().plusMinutes(10), "Restore isolated source to test owner independently",
                            checker, source.version() + 1)).isTrue();
                    jdbc.update("""
                            UPDATE com_admin_scoped_duty_assignments SET lifecycle_state='REVOKED',
                                   revoked_at=CURRENT_TIMESTAMP, revoked_by=?, revocation_reason='Current owner revocation'
                             WHERE scoped_duty_assignment_id=?
                            """, checker, duty);
                    assertThat(context.getBean(ProductAuthorizationIdentityEvidenceService.class).load(tenant, actor)
                            .hasPermission(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION)).isTrue();
                    assertThat(authorityService.evaluate(adminCandidates(actor, null, null)).decision())
                            .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
                    assertAdminLookupDenied(service, adminSearchBody(proofs, current, node -> { }));
                    assertThat(redisKeys.keys("*")).isEqualTo(consumed);
                    verifyNoInteractions(directory);
                } finally { connection.destroy(); }
            }
        });
    }

    @Test
    void sourceViewWithAppViewAloneCannotEnterAdminAndCurrentOwnerCannotSubstituteOtherScope() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 8, "test-checker");
            contracts.activate(KEY, 8, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            explicitGrant("ACTION.APPROVAL_FORM_USER_DIRECTORY", "VIEW");
            var current = authorityService.evaluate(adminCandidates(actor, null, null));
            assertThat(current.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(authorityService.evaluate(adminCandidates(actor, current.contextKey(), "RS_OTHER")).decision())
                    .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(authorityService.evaluate(adminCandidates(actor, "other-context", current.scopes().getFirst().key())).decision())
                    .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            grantTo(maker, "APP.APPROVALS", "VIEW");
            grantTo(maker, "ACTION.APPROVAL_FORM_USER_DIRECTORY", "VIEW");
            var identity = context.getBean(ProductAuthorizationIdentityEvidenceService.class).load(tenant, maker);
            assertThat(identity.hasPermission("APP.APPROVALS:VIEW")).isTrue();
            assertThat(identity.hasPermission(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION)).isTrue();
            assertThat(identity.hasPermission("ADMIN.APPROVAL_DESIGN:VIEW")).isFalse();
            assertThat(authorityService.evaluate(adminCandidates(maker, null, null)).decision())
                    .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        });
    }

    @Test
    void existingCriticalPolicyMapsConfiguredAcrButPreservesThePeopleEligibilitySigningGuard() {
        rollback(() -> {
            // This canonical HCM capability has no production Auth catalog/grants yet.
            jdbc.update("INSERT INTO com_resources(tenant_id,type,key,name,enabled) VALUES(?,'ACTION',?,'Isolated controlled export',true)",
                    tenant, "ACTION.WORKFORCE_CONTROLLED_EXPORT");
            explicitGrant("ACTION.WORKFORCE_CONTROLLED_EXPORT", "EXPORT");
            explicitGrant("APP.HCM", "VIEW");
            var payload = JSON.createObjectNode().put("dataset", "employees").put("population", "approved");
            var request = new ProductSurfaceStepUpDtos.IssueRequest("POST",
                    "/api/people/v1/workforce/exports", null, null, "EXPORT_DATASET",
                    "employees:approved", 7L, "critical-original-key", payload, null, "/workforce");
            var resolved = resolver.resolve(request);
            assertThat(resolved.routeContractKey()).isEqualTo("route.hcm.management.controlled-export-create.action");
            var authority = currentAuthority(request, resolved.routeContractKey());
            assertThat(authority.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(authority.requestPolicyRef()).isEqualTo("STEPUP-MGMT-CRITICAL-V1");
            assertThat(authority.requiredAssurance()).isEqualTo(ACR);
            assertThat(authority.requiresProductEligibility()).isTrue();
            var bound = new ProductSurfaceStepUpDtos.IssueRequest(request.commandMethod(), request.commandPath(),
                    authority.contextKey(), authority.scopes().getFirst().key(), request.targetType(),
                    request.targetId(), request.expectedObjectVersion(), request.idempotencyKey(),
                    payload, null, request.returnTo());
            clearInvocations(sessions);
            assertThatThrownBy(() -> issue(bound, revision(authority))).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
            verifyNoInteractions(sessions);
        });
    }

    @Test
    void revokedCurrentDutyCannotIssueEvenWithThePreviouslyValidRevisionAndScope() {
        var previous = currentAuthority();
        rollback(() -> {
            jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments SET lifecycle_state='REVOKED',
                           revoked_at=CURRENT_TIMESTAMP, revoked_by=?, revocation_reason='Runtime revocation regression'
                     WHERE scoped_duty_assignment_id=?
                    """, actor, duty);
            clearInvocations(sessions);
            assertThat(currentAuthority().decision()).isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertNoChallenge(request(previous.contextKey(), previous.scopes().getFirst().key()), revision(previous));
            verifyNoInteractions(sessions);
        });
    }

    @Test
    void currentIdentityRevisionChangeDoesNotAcceptThePreviousContext() {
        var previous = currentAuthority();
        rollback(() -> {
            jdbc.update("UPDATE com_admin_scoped_duty_assignments SET version=version+1 WHERE scoped_duty_assignment_id=?", duty);
            assertThat(currentAuthority().authRevision()).isNotEqualTo(previous.authRevision());
            clearInvocations(sessions);
            assertNoChallenge(request(previous.contextKey(), previous.scopes().getFirst().key()), revision(previous));
            verifyNoInteractions(sessions);
        });
    }

    @Test
    void badTargetsVersionsAndUnregisteredPathsFailBeforeSessionSigning() {
        var authority = currentAuthority(); var valid = request(authority.contextKey(), authority.scopes().getFirst().key());
        ObjectNode body = JSON.createObjectNode().put("expectedVersion", 8);
        for (var invalid : List.of(
                changed(valid, "POST", valid.commandPath(), "FORM", valid.targetId(), valid.payload()),
                changed(valid, "POST", valid.commandPath(), "WORKFLOW", UUID.randomUUID().toString(), valid.payload()),
                changed(valid, "POST", valid.commandPath(), "WORKFLOW", valid.targetId(), body),
                changed(valid, "PUT", valid.commandPath(), "WORKFLOW", valid.targetId(), valid.payload()),
                changed(valid, "POST", valid.commandPath() + "/", "WORKFLOW", valid.targetId(), valid.payload()),
                changed(valid, "POST", valid.commandPath() + "?x=1", "WORKFLOW", valid.targetId(), valid.payload()),
                changed(valid, "POST", valid.commandPath().replace("publish", "unknown"), "WORKFLOW", valid.targetId(), valid.payload()))) {
            clearInvocations(sessions);
            assertThatThrownBy(() -> issue(invalid, revision(authority))).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
            verifyNoInteractions(sessions);
        }
    }

    @Test
    void immutableDatabaseChecksumAndDescriptorUpdatesRemainRejected() {
        var bundle = repository.findActive(KEY).orElseThrow();
        rollback(() -> assertThatThrownBy(() -> jdbc.update(
                "UPDATE auth_product_authorization_bundle SET checksum=? WHERE bundle_id=?", "a".repeat(64), bundle.bundleId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class));
        rollback(() -> assertThatThrownBy(() -> jdbc.update(
                "UPDATE auth_governed_route_contract SET descriptor=jsonb_set(descriptor,'{lifecycleState}', '\"RETIRED\"') WHERE bundle_id=? AND route_contract_key=?",
                bundle.bundleId(), ROUTE)).isInstanceOf(org.springframework.dao.DataAccessException.class));
    }

    @Test
    void genuineFourteenResolvesTheInheritedExactRouteFromTheLatestSealedRegistry() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 14L, "test-checker");
            contracts.activate(KEY, 14L, "test-release",
                    repository.findActivePointer(KEY).orElseThrow().revision());

            var resolved = resolver.resolve(request(null, null));

            assertThat(resolved.bundleVersion()).isEqualTo(14L);
            assertThat(resolved.bundleChecksum()).isEqualTo(
                    "7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336");
            assertThat(resolved.routeContractKey()).isEqualTo(ROUTE);
            assertThat(resolved.ownerServiceKey()).isEqualTo("approval");
            assertThat(resolved.audience()).isEqualTo("dwp-approval-server");
        });
    }

    @Test
    void genuineFourteenAllowsOnlyNonPublishingReviewRejectionWithoutStepUp() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 14L, "test-checker");
            contracts.activate(KEY, 14L, "test-release",
                    repository.findActivePointer(KEY).orElseThrow().revision());

            var rejection = authorityService.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                    tenant, actor, "approvals", "approvals.admin",
                    ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    "route.approvals.admin.form-publish-review-reject.action",
                    null, null, null, null, List.of()));
            var publication = authorityService.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                    tenant, actor, "approvals", "approvals.admin",
                    ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    "route.approvals.admin.form-reviewed-publish.action",
                    null, null, null, null, List.of()));

            assertThat(rejection.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(rejection.requestPolicyRef()).isNull();
            assertThat(rejection.effectiveReadOnly()).isFalse();
            assertThat(publication.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(publication.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        });
    }

    @Test
    void arbitrarySeventeenCannotResolveOrIssueFromAnActualActiveDatabasePointer() {
        for (long version : List.of(17L)) {
            rollback(() -> {
                jdbc.update("""
                        INSERT INTO auth_product_authorization_bundle(bundle_key,version,bundle_status,
                                schema_version,checksum_algorithm,checksum,owner)
                        VALUES(?,?,'DRAFT',1,'SHA-256',?,'untrusted-test-only')
                        """, KEY, version, "9".repeat(64));
                var contracts = context.getBean(ProductAuthorizationContractService.class);
                contracts.approve(KEY, version, "test-checker");
                contracts.activate(KEY, version, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
                assertThat(repository.findActive(KEY).orElseThrow().version()).isEqualTo(version);
                clearInvocations(sessions);
                assertThatThrownBy(() -> issue(request(null, null), "previous-owner-revision"))
                        .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
                verifyNoInteractions(sessions);
            });
        }
        assertThat(repository.findActive(KEY).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void genuineNineRejectsActualChecksumRouteCapabilityAndCompletionCorruptionBeforeSigning() {
        for (String mutation : List.of("checksum", "route", "capability", "completion")) rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 9, "test-checker");
            contracts.activate(KEY, 9, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            var bundle = repository.findActive(KEY).orElseThrow();
            // Disposable immutable-row corruption fixture only; rollback restores each trigger and row.
            switch (mutation) {
                case "checksum" -> {
                    jdbc.execute("ALTER TABLE auth_product_authorization_bundle DISABLE TRIGGER USER");
                    jdbc.update("UPDATE auth_product_authorization_bundle SET checksum=? WHERE bundle_id=?", "9".repeat(64), bundle.bundleId());
                }
                case "route" -> {
                    jdbc.execute("ALTER TABLE auth_governed_route_contract DISABLE TRIGGER USER");
                    jdbc.update("UPDATE auth_governed_route_contract SET descriptor=jsonb_set(descriptor,'{stepUpCommandBindings,0,targetIdPathParameter}', '\"requestId\"') WHERE bundle_id=? AND route_contract_key=?",
                            bundle.bundleId(), "route.approvals.admin.document-policy-publish.action");
                }
                case "capability" -> {
                    jdbc.execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
                    jdbc.update("UPDATE auth_product_capability_contract SET descriptor=jsonb_set(descriptor,'{activationPolicy}', '\"UNTRUSTED-POLICY\"') WHERE bundle_id=? AND contract_key=?",
                            bundle.bundleId(), "approvals.policy.publish");
                }
                case "completion" -> {
                    jdbc.execute("ALTER TABLE auth_product_authority_endpoint DISABLE TRIGGER USER");
                    jdbc.update("DELETE FROM auth_product_authority_endpoint WHERE bundle_id=?", bundle.bundleId());
                }
                default -> throw new AssertionError("Unknown corruption fixture");
            }
            clearInvocations(sessions);
            assertThatThrownBy(() -> issue(documentPolicyRequest(null, null), "original-current-revision"))
                    .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
            verifyNoInteractions(sessions);
        });
    }

    @Test
    void genuineNineWithoutInstalledSealAndPointerDriftCannotResolve() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 9, "test-checker");
            contracts.activate(KEY, 9, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            assertThatThrownBy(() -> new ProductSurfaceStepUpRouteResolver(repository).resolve(documentPolicyRequest(null, null)))
                    .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
            var observed = org.mockito.Mockito.spy(repository);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                var pointer = invocation.callRealMethod();
                // Drift after the helper's post-read, before the resolver's final current-pointer read.
                if (calls.incrementAndGet() == 3) jdbc.update("UPDATE auth_product_authorization_active SET revision=revision+1, activated_at=activated_at+interval '1 millisecond' WHERE bundle_key=?", KEY);
                return pointer;
            }).when(observed).findActivePointer(KEY);
            var changing = new ProductSurfaceStepUpRouteResolver(observed, jdbc, context.getBean(ProductAuthorizationContractValidator.class), JSON);
            assertThatThrownBy(() -> changing.resolve(documentPolicyRequest(null, null)))
                    .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
            assertThat(calls.get()).isEqualTo(4);
        });
    }

    @Test
    void otherContextAndScopeCannotReplaceTheCurrentOwnerAuthority() {
        var authority = currentAuthority();
        clearInvocations(sessions);
        assertNoChallenge(request("caller-substituted-context", authority.scopes().getFirst().key()), revision(authority));
        assertNoChallenge(request(authority.contextKey(), "RS_OTHER"), revision(authority));
        verifyNoInteractions(sessions);
    }

    @Test
    void signerRetainsIndependentGlobalScopeGrantAndDecisionGuards() throws Exception {
        var authority = currentAuthority();
        assertThat(authority.effectiveReadOnly()).isFalse();
        assertThat(authority.requiredAssurance()).isEqualTo(ACR);
        var original = request(authority.contextKey(), authority.scopes().getFirst().key());
        // Only one field of the actual current DB authority is changed per negative case.
        for (String guard : List.of("global", "scope", "grant", "allowed", "sod")) {
            ObjectNode document = JSON.valueToTree(authority);
            switch (guard) {
                case "global" -> document.put("effectiveReadOnly", true);
                case "scope" -> ((ObjectNode) document.path("scopes").get(0)).put("readOnly", true);
                case "grant" -> ((ObjectNode) document.path("effectiveGrants").get(0)).put("readOnly", true);
                case "allowed" -> document.put("decision", "ALLOWED");
                case "sod" -> document.put("decision", "SOD_CONFLICT");
                default -> throw new AssertionError("Unclassified guard");
            }
            var negative = JSON.treeToValue(document, ProductSurfaceAuthorityDtos.AuthorityResult.class);
            var source = mock(ProductSurfaceAuthorityService.class); when(source.evaluate(any())).thenReturn(negative);
            var signer = new ProductSurfaceStepUpChallengeService(resolver, source, sessions, mock(OidcService.class),
                    mock(StepUpBrowserBindingService.class), JSON, java.time.Clock.systemUTC(), keys.getPrivate(),
                    "https://auth.corp.example.com/product-surface-step-up", "stepup-latest-seven", ACR,
                    java.util.Set.of("dwp-approval-server"), 600, 300);
            clearInvocations(sessions);
            assertThatThrownBy(() -> issue(signer, original, revision(authority))).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo("sod".equals(guard) ? ErrorCode.SOD_CONFLICT : ErrorCode.FORBIDDEN));
            verifyNoInteractions(sessions);
        }
    }

    private static ProductSurfaceAuthorityDtos.AuthorityResult currentAuthority() {
        return currentAuthority(request(null, null), ROUTE);
    }

    @Test
    void genuineTenIssuesExactSignatureAndBothRetentionHighChallengesAndRejectsSubstitution() {
        rollback(() -> {
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve(KEY, 10, "test-checker");
            contracts.activate(KEY, 10, "test-release", repository.findActivePointer(KEY).orElseThrow().revision());
            assertThat(repository.findActive(KEY).orElseThrow().checksum())
                    .isEqualTo("1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b");
            for (String code : List.of("APPROVAL_POLICY_PUBLISH", "APPROVAL_OPERATIONS_EXECUTE")) {
                var now = OffsetDateTime.now();
                var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
                var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER",
                        Long.toString(actor), code, resourceSet, responsibility, "MANUAL", now.minusMinutes(1),
                        now.plusMinutes(20), now.plusMinutes(10), "Disposable installed10 HIGH duty.", maker));
                assignments.approve(tenant, pending.assignmentId(), checker, pending.version(), "Independent disposable checker.");
            }
            for (String resource : List.of("APP.APPROVALS", "ACTION.APPROVAL_REQUEST")) explicitGrant(resource, "VIEW");
            explicitGrant("ACTION.APPROVAL_SIGNATURE", "SIGN");
            var owner = new com.dwp.services.approval.security.ApprovalStepUpVerifier(JSON,
                    "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                            .encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----",
                    "https://auth.corp.example.com/product-surface-step-up", "dwp-approval-server",
                    "stepup-latest-seven", ACR, 600, 300);
            for (String[] operation : List.of(
                    new String[]{"signature-sign.action","work","APPROVAL_SIGNATURE_REQUEST","approvals.work.signature.sign",
                            "/v1/signature-requests/" + TARGET + "/sign"},
                    new String[]{"retention-policy-publish.action","admin","RETENTION_POLICY","approvals.policy.publish",
                            "/v1/admin/retention/policies/" + TARGET + "/publish"},
                    new String[]{"retention-record-claim.action","admin","RETENTION_RECORD","approvals.operations.execute",
                            "/v1/admin/retention/records/" + TARGET + "/claims"})) {
                String route = "route.approvals." + operation[1] + '.' + operation[0];
                var initial = tenRequest(operation, null, null);
                var resolved = resolver.resolve(initial);
                assertThat(resolved.bundleVersion()).isEqualTo(10);
                assertThat(resolved.routeContractKey()).isEqualTo(route);
                assertThat(resolved.targetType()).isEqualTo(operation[2]);
                assertThat(resolved.expectedObjectVersionSource()).isEqualTo("COMMAND_BODY");
                assertThat(resolved.expectedObjectVersionName()).isEqualTo("expectedVersion");
                var authority = currentAuthority(initial, route);
                assertThat(authority.decision()).as(route + ':' + authority.reasonCode())
                        .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
                assertThat(authority.requiredAssurance()).isEqualTo(ACR);
                assertThat(authority.effectiveReadOnly()).isFalse();
                assertThat(authority.policyRevision()).startsWith("policy-10-");
                var bound = tenRequest(operation, authority.contextKey(), authority.scopes().getFirst().key());
                var issued = (ProductSurfaceStepUpChallengeService.Issued) issue(bound, revision(authority));
                var claims = verifySignature(issued);
                assertThat(claims.path("command_contract_key").asText()).isEqualTo(route);
                assertThat(claims.path("target_type").asText()).isEqualTo(operation[2]);
                assertThat(claims.path("capability_contract_key").asText()).isEqualTo(operation[3]);
                var binding = tenOwnerBinding(bound, authority, route, operation[3], operation[2],
                        TARGET.toString(), 7, payloadDigest(), bound.commandPath());
                assertThat(owner.verify(issued.response().challenge(), binding).binding()).isEqualTo(binding);
                for (var altered : List.of(
                        tenOwnerBinding(bound, authority, route, operation[3], operation[2], UUID.randomUUID().toString(), 7, payloadDigest(), bound.commandPath()),
                        tenOwnerBinding(bound, authority, route, operation[3], operation[2], TARGET.toString(), 8, payloadDigest(), bound.commandPath()),
                        tenOwnerBinding(bound, authority, ROUTE, operation[3], operation[2], TARGET.toString(), 7, payloadDigest(), bound.commandPath()),
                        tenOwnerBinding(bound, authority, route, operation[3], operation[2], TARGET.toString(), 7, "0".repeat(64), bound.commandPath()),
                        tenOwnerBinding(bound, authority, route, operation[3], operation[2], TARGET.toString(), 7, payloadDigest(), operation[4]))) {
                    assertThatThrownBy(() -> owner.verify(issued.response().challenge(), altered))
                            .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
                }
                for (var altered : List.of(
                        changed(bound, "POST", bound.commandPath(), operation[2], UUID.randomUUID().toString(), bound.payload()),
                        changed(bound, "POST", bound.commandPath(), "WORKFLOW", bound.targetId(), bound.payload()),
                        changed(bound, "POST", bound.commandPath(), operation[2], bound.targetId(), JSON.createObjectNode().put("expectedVersion", 8)),
                        changed(bound, "POST", operation[4], operation[2], bound.targetId(), bound.payload()))) {
                    clearInvocations(sessions);
                    assertThatThrownBy(() -> issue(altered, revision(authority))).isInstanceOf(BaseException.class);
                    verifyNoInteractions(sessions);
                }
            }
        });
    }

    private static ProductSurfaceStepUpDtos.IssueRequest tenRequest(String[] operation, String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST", "/api/approvals" + operation[4], contextKey, scope,
                operation[2], TARGET.toString(), 7L, "source10-" + operation[0],
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    private static com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding tenOwnerBinding(
            ProductSurfaceStepUpDtos.IssueRequest request, ProductSurfaceAuthorityDtos.AuthorityResult authority,
            String route, String capability, String type, String target, long version, String hash, String path) {
        return new com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding(actor, tenant, route,
                request.contextKey(), "STEPUP-MGMT-HIGH-V1", capability, request.contextScopeKey(), type,
                target, version, "POST", path, request.idempotencyKey(), hash, revision(authority));
    }

    private static ProductSurfaceAuthorityDtos.AuthorityResult currentAuthority(
            ProductSurfaceStepUpDtos.IssueRequest request, String routeKey) {
        var route = resolver.resolve(request);
        return authorityService.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, actor, route.productKey(),
                route.surfaceKey(), ProductSurfaceAuthorityDtos.AccessMode.NORMAL, routeKey,
                null, null, null, null, List.of()));
    }

    private static void createExplicitIsolatedScopeDuty() {
        tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
        actor = newUser("publisher"); long requester = newUser("maker"); checker = newUser("checker"); maker = requester;
        UUID set = jdbc.queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS' AND lifecycle_state='ACTIVE'", UUID.class, tenant);
        responsibility = UUID.randomUUID(); resourceSet = set; var now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,
                    responsibility_code,resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,
                    justification,approved_by,approved_at,decision_reason)
                VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)
                """, responsibility, tenant, Long.toString(actor), set, now.minusMinutes(1), now.plusHours(1),
                now.plusMinutes(30), "Explicit isolated approval configuration scope.", checker, "Independent scoped responsibility approval.");
        var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
        var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER", Long.toString(actor),
                "APPROVAL_DESIGN_PUBLISH", set, responsibility, "MANUAL", now.minusMinutes(1), now.plusMinutes(20),
                now.plusMinutes(10), "Explicit isolated publish duty with no implicit grants.", requester));
        duty = assignments.approve(tenant, pending.assignmentId(), checker, pending.version(), "Independent scoped publish duty approval.").assignmentId();
        assertThat(context.getBean(ScopedAdminDutyEvidenceService.class).effectiveDuties(tenant, actor))
                .extracting(ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode).containsExactly("APPROVAL_DESIGN_PUBLISH");
    }

    private static long newUser(String name) {
        return jdbc.queryForObject("INSERT INTO com_users(tenant_id,display_name,email,status) VALUES(?,?,?,'ACTIVE') RETURNING user_id",
                Long.class, tenant, "Isolated " + name, "stepup-" + name + "@isolated.test");
    }

    private static ProductSurfaceStepUpDtos.IssueRequest request(String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST", "/api/approvals/v1/admin/workflows/" + TARGET + "/publish",
                contextKey, scope, "WORKFLOW", TARGET.toString(), 7L, "latest-seven-original-key",
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    private static ProductSurfaceStepUpDtos.IssueRequest documentPolicyRequest(String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST",
                "/api/approvals/v1/admin/document-tools/policies/" + TARGET + "/publish",
                contextKey, scope, "DOCUMENT_POLICY", TARGET.toString(), 7L, "document-policy-original-key",
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    private static void explicitGrant(String resource, String permission) {
        grantTo(actor, resource, permission);
    }

    private static void grantTo(long user, String resource, String permission) {
        Long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=?",
                Long.class, tenant, resource);
        Long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?",
                Long.class, permission);
        new com.dwp.services.auth.repository.PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER",
                Long.toString(user), resourceId, permissionId, "ADMIN_DIRECT",
                "isolated-" + resource + (user == actor ? "" : "-" + user),
                OffsetDateTime.now().plusMinutes(10), "Explicit isolated current-authority runtime grant.", checker);
    }

    private static ProductSurfaceAuthorityDtos.EvaluateRequest adminCandidates(long user, String contextKey, String scope) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, user, "approvals", "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, "route.approvals.admin.form-field-candidates.data",
                contextKey, scope, null, null, List.of());
    }

    private static String adminSearchBody(ApprovalFormUserSourceProofVerifier proofs,
            ProductSurfaceAuthorityDtos.AuthorityResult current,
            java.util.function.Consumer<java.util.Map<String, Object>> mutation) {
        try {
            var body = java.util.Map.of("query", "Kim", "size", 10);
            var claims = ApprovalFormUserProofTestSupport.claims("SEARCH", proofs.requestDigest("SEARCH", body));
            var now = Instant.now();
            claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(30).getEpochSecond()); claims.put("jti", UUID.randomUUID().toString());
            claims.put("tenantId", tenant); claims.put("actorId", actor);
            claims.put("routeContractKey", "route.approvals.admin.form-field-candidates.data");
            claims.put("referencePurpose", "FORM_PREVIEW"); claims.put("contextKey", current.contextKey());
            claims.put("contextScopeKey", current.scopes().getFirst().key()); claims.put("decisionRevision", revision(current));
            mutation.accept(claims);
            return JSON.writeValueAsString(java.util.Map.of("sourceProof", ApprovalFormUserProofTestSupport.sign(claims),
                    "query", "Kim", "size", 10));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void assertAdminLookupDenied(ApprovalFormUserDirectoryService service, String body) {
        assertThatThrownBy(() -> service.search(body)).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static JsonNode verifySignature(ProductSurfaceStepUpChallengeService.Issued issued) {
        try {
            String[] token = issued.response().challenge().split("\\.");
            var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(keys.getPublic());
            signature.update((token[0] + '.' + token[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(signature.verify(Base64.getUrlDecoder().decode(token[2]))).isTrue();
            return JSON.readTree(Base64.getUrlDecoder().decode(token[1]));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String payloadDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("{\"expectedVersion\":7}".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static ProductSurfaceStepUpDtos.IssueRequest changed(ProductSurfaceStepUpDtos.IssueRequest original,
            String method, String path, String type, String target, JsonNode payload) {
        return new ProductSurfaceStepUpDtos.IssueRequest(method, path, original.contextKey(), original.contextScopeKey(),
                type, target, original.expectedObjectVersion(), original.idempotencyKey(), payload, null, original.returnTo());
    }

    private static ProductSurfaceStepUpChallengeService.Outcome issue(ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        return issue(challenges, request, revision);
    }

    private static ProductSurfaceStepUpChallengeService.Outcome issue(ProductSurfaceStepUpChallengeService signer,
            ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        var jwt = Jwt.withTokenValue("verified-session").header("alg", "RS256").subject(Long.toString(actor))
                .claim("jti", "verified-jti").claim("sid", UUID.randomUUID().toString()).build();
        var parsed = context.getBean(ProductSurfaceStepUpRequestParser.class).parse(JSON.valueToTree(request).toString());
        return signer.issue(actor, tenant, jwt, parsed, revision, new MockHttpServletResponse());
    }

    private static String revision(ProductSurfaceAuthorityDtos.AuthorityResult authority) {
        try {
            return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",
                    Long.toString(tenant), Long.toString(actor), "NORMAL", authority.authRevision(), authority.policyRevision(),
                    "", "", "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void assertNoChallenge(ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        assertThatThrownBy(() -> issue(request, revision)).isInstanceOf(BaseException.class);
    }

    private static void rollback(Runnable work) {
        transactions.executeWithoutResult(status -> { try { work.run(); } finally { status.setRollbackOnly(); } });
    }
}
