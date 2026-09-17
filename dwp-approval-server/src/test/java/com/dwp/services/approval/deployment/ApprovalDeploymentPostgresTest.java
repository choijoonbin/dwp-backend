package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDeploymentPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T05:00:00Z");
    private static final String FORM_HASH = "a".repeat(64);
    private static final String WORKFLOW_HASH = "b".repeat(64);
    private static final String ATTESTOR_ISSUER = "urn:dwp:deployment-health";
    private static final String ATTESTOR_IDENTITY = "deployment-health-provider-prod";
    private static final String ATTESTOR_KEY_ID = "deployment-ed25519-2026-09";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ObjectMapper mapper;
    private KeyPair attestorKey;
    private ApprovalDeploymentService service;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc.execute("SELECT seed_approval_tenant(42)");
        jdbc.execute("SELECT seed_approval_tenant(43)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new ObjectMapper().findAndRegisterModules();
        attestorKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ApprovalDeploymentAttestationVerifier verifier =
                new ApprovalDeploymentAttestationVerifier(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                        new ApprovalDeploymentAttestationVerifier.TrustedAttestor(
                                ATTESTOR_ISSUER, ATTESTOR_IDENTITY,
                                ATTESTOR_KEY_ID, attestorKey.getPublic()));
        service = new ApprovalDeploymentService(
                new ApprovalDeploymentRepository(
                        new NamedParameterJdbcTemplate(dataSource), mapper),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), verifier);
    }

    @Test
    void packageManifestIsImmutableScopedAndProducesAnExactDiff() {
        PackageCommand firstCommand = deploymentPackage("APR.FINANCE", 1, false);
        PackageRecord first = tx(() -> service.createPackage(
                maker(), firstCommand));
        PackageRecord replay = tx(() -> service.createPackage(
                maker(), firstCommand));
        PackageRecord second = tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.FINANCE", 2, true)));

        assertThat(replay.manifestSha256()).isEqualTo(first.manifestSha256());
        assertThat(first.rollbackDisposition()).isEqualTo(RollbackDisposition.REVERSIBLE);
        assertThat(second.rollbackDisposition()).isEqualTo(RollbackDisposition.CONDITIONAL);

        PackageDiff diff = tx(() -> service.diff(
                viewer(), first.packageId(), second.packageId()));
        assertThat(diff.changedAssets()).contains("workflow:finance");
        assertThat(diff.introducesExternalSideEffects()).isTrue();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_deployment_packages SET display_name = 'Changed'
                 WHERE package_id = ?
                """, first.packageId())).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> tx(() -> service.packageById(
                foreignViewer(), first.packageId())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void packageIdempotencyKeyIsBoundToTheExactCanonicalCommand() {
        PackageCommand command = deploymentPackage("APR.IDEMPOTENT", 1, false);
        GovernedCommand governed = makerCommand("package-idempotency-100");

        PackageRecord created = tx(() -> service.createPackage(
                maker(), command, governed));
        PackageRecord replay = tx(() -> service.createPackage(
                maker(), command, governed));

        assertThat(replay.packageId()).isEqualTo(created.packageId());
        assertThatThrownBy(() -> tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.OTHER", 1, false), governed)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void makerCheckerPromotionPreservesUnknownAndRestoresOnlyThePackageHead() {
        PackageRecord first = tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.RELEASE", 1, false)));
        Promotion firstPromotion = promoteHealthy(first.packageId(), "release-one");
        assertThat(firstPromotion.status()).isEqualTo("ACTIVE");

        PackageRecord second = tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.RELEASE", 2, true)));
        Promotion requested = tx(() -> service.requestPromotion(
                maker(), promotion(second.packageId()), makerCommand("release-two")));

        assertThatThrownBy(() -> tx(() -> service.approve(
                makerReviewer(), requested.promotionId(), 0,
                "Maker must not approve this deployment.",
                stepUp(17, "maker-review"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        Promotion approved = tx(() -> service.approve(
                checker(), requested.promotionId(), 0,
                "Independent review confirms the package manifest.",
                stepUp(18, "approve-two")));
        Promotion activating = tx(() -> service.beginActivation(
                operator(), approved.promotionId(), approved.version(),
                stepUp(19, "activate-two")));
        Promotion unknown = tx(() -> service.recordActivationEvidence(
                evidenceOperator(), activating.promotionId(), activating.version(),
                evidence(
                        activating.promotionId(), activating.version(),
                        "ACTIVATION", HealthOutcome.UNKNOWN, "unknown-two"),
                stepUp(20, "evidence-unknown")));
        assertThat(unknown.status()).isEqualTo("UNKNOWN");

        Promotion active = tx(() -> service.recordActivationEvidence(
                evidenceOperator(), unknown.promotionId(), unknown.version(),
                evidence(
                        unknown.promotionId(), unknown.version(),
                        "CANARY_HEALTH", HealthOutcome.HEALTHY, "healthy-two"),
                stepUp(20, "evidence-healthy")));
        assertThat(active.status()).isEqualTo("ACTIVE");

        RollbackFeasibility feasibility = tx(() -> service.rollbackFeasibility(
                viewer(), active.promotionId()));
        assertThat(feasibility.status()).isEqualTo("CONDITIONAL");
        assertThat(feasibility.externalSideEffectsUndone()).isFalse();
        assertThat(feasibility.externalSideEffectsStatus()).isEqualTo("NOT_ASSERTED");
        assertThat(feasibility.previousPackageId()).isEqualTo(first.packageId());

        Promotion rollback = tx(() -> service.requestRollback(
                rollbackOperator(), active.promotionId(), active.version(),
                stepUp(21, "rollback-two")));
        Promotion restored = tx(() -> service.recordRollbackEvidence(
                evidenceOperator(), rollback.promotionId(), rollback.version(),
                evidence(
                        rollback.promotionId(), rollback.version(),
                        "ROLLBACK", HealthOutcome.HEALTHY, "rollback-health"),
                stepUp(20, "rollback-evidence")));

        assertThat(restored.status()).isEqualTo("PACKAGE_HEAD_RESTORED");
        UUID activePackage = jdbc.queryForObject("""
                SELECT active_package_id FROM apr_deployment_environment_heads
                 WHERE tenant_id = 42 AND management_resource_set_key = 'RS_APPROVALS'
                   AND environment = 'TEST'
                """, UUID.class);
        assertThat(activePackage).isEqualTo(first.packageId());
        assertThat(jdbc.queryForObject("""
                SELECT event_payload ->> 'externalSideEffectsStatus'
                  FROM apr_deployment_journal
                 WHERE promotion_id = ? AND event_type = 'ROLLBACK_EVIDENCE_RECORDED'
                """, String.class, restored.promotionId())).isEqualTo("NOT_ASSERTED");
    }

    @Test
    void staleStepUpAndStaleVersionsFailBeforeChangingDeploymentState() {
        PackageRecord pack = tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.STALE", 1, false)));
        Promotion requested = tx(() -> service.requestPromotion(
                maker(), promotion(pack.packageId()), makerCommand("stale-request")));

        GovernedCommand stale = new GovernedCommand(
                18, "stale-review", "scope:approvals", "decision-1",
                "verified:stale-step-up-evidence", NOW.minusSeconds(3_600),
                NOW.plusSeconds(60));
        assertThatThrownBy(() -> tx(() -> service.approve(
                checker(), requested.promotionId(), 0,
                "Independent review has sufficient detail.", stale)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        Promotion approved = tx(() -> service.approve(
                checker(), requested.promotionId(), 0,
                "Independent review has sufficient detail.",
                stepUp(18, "fresh-review")));
        assertThatThrownBy(() -> tx(() -> service.beginActivation(
                operator(), approved.promotionId(), 0,
                stepUp(19, "stale-version"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(tx(() -> service.packageById(viewer(), pack.packageId())))
                .isNotNull();
    }

    @Test
    void invalidHealthAttestationPreservesActivationAndEnvironmentState() {
        PackageRecord pack = tx(() -> service.createPackage(
                maker(), deploymentPackage("APR.UNTRUSTED", 1, false)));
        Promotion requested = tx(() -> service.requestPromotion(
                maker(), promotion(pack.packageId()), makerCommand("untrusted-request")));
        Promotion approved = tx(() -> service.approve(
                checker(), requested.promotionId(), requested.version(),
                "Independent review confirms the package manifest.",
                stepUp(18, "untrusted-approve")));
        Promotion activating = tx(() -> service.beginActivation(
                operator(), approved.promotionId(), approved.version(),
                stepUp(19, "untrusted-activate")));
        ExternalHealthEvidenceSubmission signed = evidence(
                activating.promotionId(), activating.version(),
                "CANARY_HEALTH", HealthOutcome.HEALTHY, "untrusted-health");
        ExternalHealthEvidenceSubmission tampered = new ExternalHealthEvidenceSubmission(
                signed.evidenceId(), signed.evidenceType(), signed.outcome(),
                signed.externalReference(), "e".repeat(64), signed.sourceGeneratedAt(),
                signed.evidencePayloadBase64Url(), signed.evidenceSignatureBase64Url());

        assertThatThrownBy(() -> tx(() -> service.recordActivationEvidence(
                evidenceOperator(), activating.promotionId(), activating.version(), tampered,
                stepUp(20, "untrusted-evidence"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(tx(() -> service.promotionById(
                viewer(), activating.promotionId())).status())
                .isEqualTo("ACTIVATING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_deployment_evidence
                 WHERE tenant_id=42 AND management_resource_set_key='RS_APPROVALS'
                   AND promotion_id=?
                """, Long.class, activating.promotionId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_deployment_environment_heads
                 WHERE tenant_id=42 AND management_resource_set_key='RS_APPROVALS'
                   AND environment='TEST' AND active_package_id IS NOT NULL
                """, Long.class)).isZero();
    }

    private Promotion promoteHealthy(UUID packageId, String key) {
        Promotion requested = tx(() -> service.requestPromotion(
                maker(), promotion(packageId), makerCommand(key)));
        Promotion approved = tx(() -> service.approve(
                checker(), requested.promotionId(), requested.version(),
                "Independent package review is complete.",
                stepUp(18, key + "-approve")));
        Promotion activating = tx(() -> service.beginActivation(
                operator(), approved.promotionId(), approved.version(),
                stepUp(19, key + "-activate")));
        return tx(() -> service.recordActivationEvidence(
                evidenceOperator(), activating.promotionId(), activating.version(),
                evidence(
                        activating.promotionId(), activating.version(),
                        "CANARY_HEALTH", HealthOutcome.HEALTHY, key + "-health"),
                stepUp(20, key + "-evidence")));
    }

    private PackageCommand deploymentPackage(String key, int version, boolean external) {
        return packageWithId(UUID.randomUUID(), key, version, external);
    }

    private PackageCommand packageWithId(
            UUID packageId,
            String key,
            int version,
            boolean external) {
        String workflowHash = external ? "c".repeat(64) : WORKFLOW_HASH;
        Asset form = new Asset(
                "form:finance", AssetType.FORM, UUID.randomUUID(),
                Integer.toString(version), FORM_HASH,
                RollbackDisposition.REVERSIBLE, false);
        Asset workflow = new Asset(
                "workflow:finance", AssetType.WORKFLOW, UUID.randomUUID(),
                Integer.toString(version), workflowHash,
                external ? RollbackDisposition.CONDITIONAL : RollbackDisposition.REVERSIBLE,
                external);
        return new PackageCommand(
                packageId, key, version, "Finance approval package " + version,
                List.of(form, workflow),
                List.of(new Dependency(
                        "workflow:finance", "form:finance", FORM_HASH, false)));
    }

    private PromotionCommand promotion(UUID packageId) {
        return new PromotionCommand(
                UUID.randomUUID(), packageId,
                Environment.DEVELOPMENT, Environment.TEST);
    }

    private ExternalHealthEvidenceSubmission evidence(
            UUID promotionId,
            long expectedVersion,
            String type,
            HealthOutcome outcome,
            String suffix) {
        try {
            UUID evidenceId = UUID.randomUUID();
            String reference = "provider://deployment/" + suffix;
            String payloadSha256 = "d".repeat(64);
            var claims = new ApprovalDeploymentAttestationVerifier.Claims(
                    ATTESTOR_ISSUER, ATTESTOR_IDENTITY, ATTESTOR_KEY_ID,
                    ApprovalDeploymentAttestationVerifier.AUDIENCE,
                    ApprovalDeploymentAttestationVerifier.PURPOSE,
                    42, "RS_APPROVALS", promotionId, expectedVersion,
                    evidenceId, type, outcome.name(), reference, payloadSha256,
                    NOW, NOW.minusSeconds(10), NOW.plusSeconds(240),
                    "nonce-" + UUID.randomUUID());
            byte[] payload = mapper.writeValueAsBytes(claims);
            return new ExternalHealthEvidenceSubmission(
                    evidenceId, type, outcome, reference, payloadSha256, NOW,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                    sign(payload, attestorKey.getPrivate()));
        } catch (Exception exception) {
            throw new IllegalStateException("Test evidence signing failed.", exception);
        }
    }

    private String sign(byte[] payload, PrivateKey key) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.sign());
    }

    private GovernedCommand makerCommand(String key) {
        return new GovernedCommand(17, key, null, null, null, null, null);
    }

    private GovernedCommand stepUp(long actor, String key) {
        return new GovernedCommand(
                actor, key, "scope:approvals", "decision-" + key,
                "verified:step-up-evidence-" + key,
                NOW.minusSeconds(30), NOW.plusSeconds(300));
    }

    private Scope maker() {
        return scope(17, Capability.CREATE_PACKAGE, Capability.REQUEST_PROMOTION);
    }

    private Scope makerReviewer() {
        return scope(17, Capability.REVIEW_PROMOTION);
    }

    private Scope checker() {
        return scope(18, Capability.REVIEW_PROMOTION);
    }

    private Scope operator() {
        return scope(19, Capability.ACTIVATE);
    }

    private Scope evidenceOperator() {
        return scope(20, Capability.RECORD_EXTERNAL_EVIDENCE);
    }

    private Scope rollbackOperator() {
        return scope(21, Capability.REQUEST_ROLLBACK);
    }

    private Scope viewer() {
        return scope(22, Capability.VIEW);
    }

    private Scope foreignViewer() {
        return new Scope(43, "RS_APPROVALS", 22, Set.of(Capability.VIEW));
    }

    private Scope scope(long actor, Capability... capabilities) {
        return new Scope(42, "RS_APPROVALS", actor, Set.of(capabilities));
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
