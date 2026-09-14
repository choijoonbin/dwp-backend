package com.dwp.services.auth.service;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.*;
import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Actual Auth repositories/signature, real owner filters/controller and separate fresh Approval command DB.
 * MFA/session freshness uses the existing test fixture, not a browser login or an activated storage provider. */
@Testcontainers
class ApprovalRelease9ControllerAuthorityPostgresTest {
    @Container static final PostgreSQLContainer<?> AUTH = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> APPROVAL = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String ROUTE = "route.approvals.admin.attachment-policy-publish.action";
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate authDb, ownerDb;
    static TransactionTemplate commands;
    static ApprovalIdentityDirectory directory;
    static ApprovalAttachmentManagementController controller;
    static ProductSurfaceAuthorityService authorities;
    static ProductSurfaceStepUpChallengeService signer;
    static KeyPair keys;
    static long tenant, maker, publisher, reviewer;
    static ScopedAdminDutyAssignmentService.Assignment publishDuty;
    static jakarta.validation.ValidatorFactory validation;

    @BeforeAll static void freshActualServices() throws Exception {
        var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
        var authSource = new DriverManagerDataSource(AUTH.getJdbcUrl(), AUTH.getUsername(), AUTH.getPassword());
        migrate(authSource, root.resolve("dwp-auth-server/src/main/resources/db/migration"), root.resolve("dwp-core/src/main/resources/db/migration"));
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("actual9-acr",
                java.util.Map.of("dwp.auth.step-up.required-acr", "urn:dwp:acr:mfa")));
        context.registerBean(javax.sql.DataSource.class, () -> authSource); context.registerBean(KeyPair.class, () -> keys);
        context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class); context.refresh();
        authDb = context.getBean(JdbcTemplate.class);
        context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
        var contracts = context.getBean(ProductAuthorizationContractService.class);
        contracts.approve("product-surfaces", 9, "isolated-independent-reviewer");
        contracts.activate("product-surfaces", 9, "isolated-release", 0);
        assertThat(context.getBean(com.dwp.services.auth.repository.ProductAuthorizationContractRepository.class)
                .findActive("product-surfaces").orElseThrow().checksum()).isEqualTo("02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7");
        tenant = authDb.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
        maker = user("maker"); publisher = user("publisher"); reviewer = user("reviewer");
        duty(maker, "APPROVAL_POLICY_DRAFT");
        publishDuty = duty(publisher, "APPROVAL_POLICY_PUBLISH");
        grant(maker, "APP.APPROVALS", "VIEW"); grant(publisher, "APP.APPROVALS", "VIEW");
        authorities = context.getBean(ProductSurfaceAuthorityService.class); signer = context.getBean(ProductSurfaceStepUpChallengeService.class);
        when(context.getBean(AuthSessionService.class).requireFreshAssurance(any(), eq(publisher), eq(tenant), eq("urn:dwp:acr:mfa"), eq(600L)))
                .thenAnswer(ignored -> new AuthSessionService.AssuranceEvidence("OIDC_STEP_UP", Instant.now().minusSeconds(5), "urn:dwp:acr:mfa", List.of("mfa", "otp")));
        var ownerSource = new DriverManagerDataSource(APPROVAL.getJdbcUrl(), APPROVAL.getUsername(), APPROVAL.getPassword());
        migrate(ownerSource, root.resolve("dwp-approval-server/src/main/resources/db/migration"), root.resolve("dwp-core/src/main/resources/db/migration"));
        ownerDb = new JdbcTemplate(ownerSource); var named = new NamedParameterJdbcTemplate(ownerDb);
        commands = new TransactionTemplate(new DataSourceTransactionManager(ownerSource));
        new ApprovalQueryRepository(named, JSON).ensureTenant(tenant);
        directory = actualDirectory();
        var canonical = new ApprovalDocumentCanonical(JSON);
        var current = new ApprovalDocumentAuthority(new ApprovalWorkAuthority(directory), directory, new ApprovalDocumentOwnerRepository(named), canonical);
        validation = Validation.buildDefaultValidatorFactory();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        var verifier = new ApprovalStepUpVerifier(JSON, pem, "https://auth.corp.example.com/product-surface-step-up", "dwp-approval-server",
                "stepup-latest-seven", "urn:dwp:acr:mfa", 600, 900);
        controller = new ApprovalAttachmentManagementController(new ApprovalAttachmentManagementService(current,
                new ApprovalAttachmentPolicyRepository(named, canonical, validation.getValidator()), new ApprovalAttachmentCommands(named, canonical),
                new ApprovalDocumentPublishGuard(verifier, new ApprovalStepUpReplayRepository(named, JSON)),
                new ApprovalAttachmentProviderGate(Optional.empty(), Optional.empty()),
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named, JSON, "dwp-approval-server", "isolated", "isolated"))));
    }
    static void migrate(javax.sql.DataSource source, Path service, Path core) {
        var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + service, "filesystem:" + core).load();
        flyway.migrate(); flyway.validate(); assertThat(flyway.info().pending()).isEmpty();
    }
    @BeforeEach void resetDisposableOwnerState() {
        ownerDb.execute("TRUNCATE apr_attachment_policy_heads,apr_attachment_policy_versions,apr_attachment_policy_publications,apr_attachment_command_receipts,apr_step_up_replay_ledger,sys_audit_outbox CASCADE");
        clear();
    }
    @AfterEach void clear() {
        ApprovalRequestContext.clear();
        assertThat(ApprovalDecisionRevisionContext.current()).isEmpty();
        assertThat(ApprovalPilotAuthorizationContext.current()).isEmpty();
        assertThat(ApprovalManagementScopeContext.current()).isEmpty();
    }
    @AfterAll static void close() { if (validation != null) validation.close(); if (context != null) context.close(); }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void realNativeManagementAndSignedHighPublicationFollowTheFourStateMatrix(String state) throws Exception {
        var initialize = request(maker, "POST", "/v1/admin/attachments/policies", "route.approvals.admin.attachment-policy-initialize.action", state);
        var created = invoke(initialize, () -> controller.initialize(new InitializePolicy(true, "original-initialize")).getData());
        assertThat(created.status()).isEqualTo(200); assertThat(created.value().published().allowUpload()).isFalse();
        var saved = save(created.value(), state);
        var payload = new PublishPolicy(saved.version(), "original-publish", "Independent current policy approval");
        var publish = signedRequest(saved.policyId(), payload, state);
        var before = stateVector();
        var result = invoke(publish, () -> controller.publish(saved.policyId(), payload, publish.getHeader("X-DWP-Step-Up-Challenge"),
                payload.idempotencyKey(), publish.getHeader("X-DWP-Expected-Decision-Revision"), payload.expectedVersion()).getData());
        boolean exact = state.charAt(1) == '1';
        assertThat(result.status()).as(result.denial()).isEqualTo(exact ? 200 : 403);
        if (!exact) assertThat(stateVector()).isEqualTo(before);
        else {
            assertThat(result.value().version()).isEqualTo(saved.version() + 1);
            assertThat(result.value().publishedRevision()).isEqualTo(1); assertThat(result.value().pending()).isNull();
            assertThat(result.value().published().allowUpload()).isFalse(); assertThat(result.value().published().allowDownload()).isFalse();
            assertThat(count("apr_attachment_policy_publications")).isEqualTo(1); assertThat(count("apr_step_up_replay_ledger")).isEqualTo(1);
            assertThat(ownerDb.queryForObject("SELECT maker_user_id FROM apr_attachment_policy_publications", Long.class)).isEqualTo(maker);
            assertThat(ownerDb.queryForObject("SELECT checker_user_id FROM apr_attachment_policy_publications", Long.class)).isEqualTo(publisher);
            var replay = invoke(publish, () -> controller.publish(saved.policyId(), payload, publish.getHeader("X-DWP-Step-Up-Challenge"),
                    payload.idempotencyKey(), publish.getHeader("X-DWP-Expected-Decision-Revision"), payload.expectedVersion()).getData());
            assertThat(replay.status()).isEqualTo(200); assertThat(replay.value()).isEqualTo(result.value());
            assertThat(count("apr_attachment_policy_publications")).isEqualTo(1); assertThat(count("apr_step_up_replay_ledger")).isEqualTo(1);
        }
    }

    @Test void genuineSignedWrongObjectCurrentCasAndBodyTamperingRollBackEveryOwnerWrite() throws Exception {
        var created = invoke(request(maker, "POST", "/v1/admin/attachments/policies", "route.approvals.admin.attachment-policy-initialize.action", "111"),
                () -> controller.initialize(new InitializePolicy(true, "initialize-negative")).getData()).value();
        var head = save(created, "111"); var before = stateVector();
        var input = new PublishPolicy(head.version(), "negative-body", "Original signed review");
        var signed = signedRequest(head.policyId(), input, "111");
        var tampered = new PublishPolicy(input.expectedVersion(), input.idempotencyKey(), "Substituted unsigned review");
        assertThat(invoke(signed, () -> controller.publish(head.policyId(), tampered, signed.getHeader("X-DWP-Step-Up-Challenge"),
                tampered.idempotencyKey(), signed.getHeader("X-DWP-Expected-Decision-Revision"), tampered.expectedVersion()).getData()).status()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH.getHttpStatus().value());
        assertThat(stateVector()).isEqualTo(before);
        var stale = new PublishPolicy(head.version() + 3, "negative-version", "Legitimate signature on stale CAS");
        var staleRequest = signedRequest(head.policyId(), stale, "111");
        assertThat(invoke(staleRequest, () -> controller.publish(head.policyId(), stale, staleRequest.getHeader("X-DWP-Step-Up-Challenge"),
                stale.idempotencyKey(), staleRequest.getHeader("X-DWP-Expected-Decision-Revision"), stale.expectedVersion()).getData()).status()).isEqualTo(409);
        assertThat(stateVector()).isEqualTo(before);
        UUID wrong = UUID.randomUUID(); var wrongRequest = signedRequest(wrong, input, "111");
        assertThat(invoke(wrongRequest, () -> controller.publish(wrong, input, wrongRequest.getHeader("X-DWP-Step-Up-Challenge"),
                input.idempotencyKey(), wrongRequest.getHeader("X-DWP-Expected-Decision-Revision"), input.expectedVersion()).getData()).status()).isIn(403, 404);
        assertThat(stateVector()).isEqualTo(before);
    }

    @Test void currentAuthRevocationAfterSignedPrecheckCannotPublishUsingRetainedTransportEvidence() throws Exception {
        var created = invoke(request(maker, "POST", "/v1/admin/attachments/policies", "route.approvals.admin.attachment-policy-initialize.action", "111"),
                () -> controller.initialize(new InitializePolicy(true, "initialize-revocation")).getData()).value();
        var head = save(created, "111"); var payload = new PublishPolicy(head.version(), "revoke-publish", "Retained proof cannot heal revoked source");
        var publish = signedRequest(head.policyId(), payload, "111"); var before = stateVector();
        var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
        var tx = context.getBean(TransactionTemplate.class);
        tx.executeWithoutResult(status -> {
            assignments.revoke(tenant, publishDuty.assignmentId(), reviewer, publishDuty.version(), "Actual disposable current-source withdrawal");
            assertThat(current(publisher, ROUTE).decision()).isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(invokeUnchecked(publish, () -> controller.publish(head.policyId(), payload, publish.getHeader("X-DWP-Step-Up-Challenge"),
                    payload.idempotencyKey(), publish.getHeader("X-DWP-Expected-Decision-Revision"), payload.expectedVersion()).getData()).status()).isEqualTo(403);
            assertThat(stateVector()).isEqualTo(before); status.setRollbackOnly();
        });
    }

    private static Policy save(Policy current, String state) throws Exception {
        var payload = new SavePolicy(current.version(), "original-save", current.published());
        var response = invoke(request(maker, "PUT", "/v1/admin/attachments/policies/" + current.policyId() + "/draft",
                "route.approvals.admin.attachment-policy-draft.action", state), () -> controller.draft(current.policyId(), payload).getData());
        assertThat(response.status()).isEqualTo(200); return response.value();
    }
    private static MockHttpServletRequest signedRequest(UUID id, PublishPolicy input, String state) throws Exception {
        String path = "/v1/admin/attachments/policies/" + id + "/publish";
        var request = request(publisher, "POST", path, ROUTE, state);
        if (state.charAt(1) == '1') {
            var authority = current(publisher, ROUTE);
            var issue = new ProductSurfaceStepUpDtos.IssueRequest("POST", "/api/approvals" + path, authority.contextKey(),
                    authority.scopes().getFirst().key(), "ATTACHMENT_POLICY", id.toString(), input.expectedVersion(), input.idempotencyKey(), JSON.valueToTree(input), null, "/approvals");
            var parsed = context.getBean(ProductSurfaceStepUpRequestParser.class).parse(JSON.valueToTree(issue).toString());
            var session = Jwt.withTokenValue("fixture-verified-session").header("alg", "RS256").subject(Long.toString(publisher))
                    .claim("jti", UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString()).build();
            var issued = (ProductSurfaceStepUpChallengeService.Issued) signer.issue(publisher, tenant, session, parsed, revision(publisher, authority), new MockHttpServletResponse());
            request.addHeader("X-DWP-Step-Up-Challenge", issued.response().challenge());
        }
        request.addHeader("Idempotency-Key", input.idempotencyKey()); request.addHeader("X-DWP-Expected-Object-Version", input.expectedVersion());
        return request;
    }
    private static MockHttpServletRequest request(long actor, String method, String path, String route, String state) {
        var request = new MockHttpServletRequest(method, path); var identity = context.getBean(ProductAuthorizationIdentityEvidenceService.class).load(tenant, actor);
        request.addHeader("X-DWP-Service-Token", "isolated-gateway"); request.addHeader("X-DWP-User-ID", actor); request.addHeader("X-DWP-Tenant-ID", tenant);
        request.addHeader("X-DWP-Identity-Plane", "TENANT"); request.addHeader("X-DWP-Roles", String.join(",", identity.roles()));
        request.addHeader("X-DWP-Permissions", String.join(",", identity.permissions()));
        request.addHeader("X-DWP-Rollout-State", state); request.addHeader("X-DWP-Rollout-Cohort", "full"); request.addHeader("X-DWP-Rollout-Revision", "rollout-" + "a".repeat(64));
        if (state.charAt(1) == '1') {
            var authority = current(actor, route);
            assertThat(authority.decision()).isIn(ProductSurfaceAuthorityDtos.Decision.ALLOWED, ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            var grant = (ProductSurfaceAuthorityDtos.CapabilityGrant) authority.effectiveGrants().getFirst();
            assertThat(grant.responsibility().resourceSetKey()).isEqualTo("RS_APPROVALS");
            request.addHeader("X-DWP-Resource-Roles", grant.responsibility().code() + "@RS_APPROVALS," + ScopedAuthorityToken.wireToken(grant.capabilityContractKey(), grant.resolvedCapabilityCode(), "RS_APPROVALS"));
            request.addHeader("X-DWP-Route-Contract-Key", route); request.addHeader("X-DWP-Context-Key", authority.contextKey());
            request.addHeader("X-DWP-Context-Scope-Key", authority.scopes().getFirst().key()); request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
            request.addHeader("X-DWP-Current-Decision-Revision", revision(actor, authority)); request.addHeader("X-DWP-Expected-Decision-Revision", revision(actor, authority));
            request.addHeader("X-DWP-Current-Revalidate-At", authority.validUntil().toString());
        }
        return request;
    }
    private static ProductSurfaceAuthorityDtos.AuthorityResult current(long actor, String route) {
        return authorities.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, actor, "approvals", "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, route, null, null, null, null, List.of()));
    }
    private static String revision(long actor, ProductSurfaceAuthorityDtos.AuthorityResult authority) {
        try { return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", Long.toString(tenant),
                Long.toString(actor), "NORMAL", authority.authRevision(), authority.policyRevision(), "", "", "").getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> Invocation<T> invoke(MockHttpServletRequest request, Supplier<T> operation) throws Exception {
        var response = new MockHttpServletResponse(); var value = new AtomicReference<T>(); String denial = null;
        var filter = new ApprovalSecurityFilter("isolated-gateway", "isolated-runtime", true, JSON, new ApprovalPilotPepRegistry(JSON), new ApprovalManagementScopeResolver(), null);
        try { new ApprovalRelease9BoundaryFilter(JSON).doFilter(request, response, (req, res) -> filter.doFilter(req, res,
                (r, s) -> value.set(commands.execute(status -> operation.get())))); }
        catch (BaseException failure) { response.setStatus(failure.getErrorCode().getHttpStatus().value()); denial = failure.getErrorCode().name() + ": " + failure.getMessage(); }
        assertThat(ApprovalPilotAuthorizationContext.current()).isEmpty(); assertThat(ApprovalDecisionRevisionContext.current()).isEmpty();
        return new Invocation<>(response.getStatus(), value.get(), denial);
    }
    private static <T> Invocation<T> invokeUnchecked(MockHttpServletRequest request, Supplier<T> operation) {
        try { return invoke(request, operation); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    record Invocation<T>(int status, T value, String denial) { }
    private static List<Object> stateVector() {
        return List.of(ownerDb.queryForList("SELECT * FROM apr_attachment_policy_heads ORDER BY policy_id"),
                ownerDb.queryForList("SELECT * FROM apr_attachment_policy_versions ORDER BY policy_id,revision"), count("apr_attachment_command_receipts"),
                count("apr_attachment_policy_publications"), count("apr_step_up_replay_ledger"), count("sys_audit_outbox"));
    }
    private static long count(String table) { return ownerDb.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static long user(String name) {
        long user = authDb.queryForObject("INSERT INTO com_users(tenant_id,display_name,email,status,identity_plane,person_public_id) VALUES(?,?,?,'ACTIVE','TENANT',?) RETURNING user_id",
                Long.class, tenant, name, UUID.randomUUID() + "@isolated.test", UUID.randomUUID());
        long role = authDb.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        authDb.update("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, role); return user;
    }
    private static ScopedAdminDutyAssignmentService.Assignment duty(long actor, String code) {
        UUID set = authDb.queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS'", UUID.class, tenant);
        UUID responsibility = UUID.randomUUID(); var now = OffsetDateTime.now();
        authDb.update("INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,responsibility_code,resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,justification,approved_by,approved_at,decision_reason) VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)",
                responsibility, tenant, Long.toString(actor), set, now.minusMinutes(1), now.plusHours(1), now.plusMinutes(30), "Disposable exact responsibility", reviewer, "Independent responsibility");
        var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
        var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER", Long.toString(actor), code, set, responsibility,
                "MANUAL", now.minusMinutes(1), now.plusMinutes(20), now.plusMinutes(10), "Disposable exact duty without auto grant", reviewer));
        return assignments.approve(tenant, pending.assignmentId(), publisher == reviewer ? maker : publisher, pending.version(), "Independent exact duty decision");
    }
    private static void grant(long actor, String resource, String permission) {
        long resourceId = authDb.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=? AND enabled", Long.class, tenant, resource);
        long permissionId = authDb.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?", Long.class, permission);
        new PrincipalResourceGrantRepository(authDb).grant(tenant, "USER", Long.toString(actor), resourceId, permissionId, "ADMIN_DIRECT",
                "isolated-filter-" + UUID.randomUUID(), OffsetDateTime.now().plusMinutes(10), "Explicit disposable app access; catalog is not privilege", reviewer);
    }
    private static ApprovalIdentityDirectory actualDirectory() {
        var auth = context.getBean(AuthService.class);
        return new ApprovalIdentityDirectory() {
            @Override public Subject require(long tenantId, long userId) {
                return authDb.query("SELECT public_id,person_public_id,display_name,status FROM com_users WHERE tenant_id=? AND user_id=? AND identity_plane='TENANT'",
                        (row, ignored) -> new Subject(tenantId, userId, row.getObject("public_id", UUID.class), row.getObject("person_public_id", UUID.class),
                                row.getString("display_name"), null, null, row.getString("status"), auth.getRoleCodes(userId, tenantId),
                                auth.getPermissions(userId, tenantId).stream().filter(permission -> "ALLOW".equals(permission.getEffect()))
                                        .map(permission -> permission.getResourceKey() + ":" + permission.getPermissionCode()).toList()), tenantId, userId)
                        .stream().findFirst().orElseThrow(() -> new BaseException(ErrorCode.FORBIDDEN));
            }
            @Override public List<Subject> search(long tenantId, String query, int limit) { throw new UnsupportedOperationException("Not a directory search profile"); }
            @Override public RoleEligibility requireRole(long tenantId, String roleCode) { throw new UnsupportedOperationException("Not a role population profile"); }
        };
    }
}
