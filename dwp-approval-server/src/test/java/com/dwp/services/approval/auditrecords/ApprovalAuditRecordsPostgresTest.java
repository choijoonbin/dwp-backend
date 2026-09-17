package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalAuditRecordsPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");
    private static final String ATTESTOR_ISSUER = "urn:dwp:trusted-archive";
    private static final String ATTESTOR_IDENTITY = "archive-attestor-prod";
    private static final String ATTESTOR_KEY_ID = "archive-ed25519-2026-09";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private KeyPair attestorKey;
    private ApprovalAuditService service;
    private UUID requestId;

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
        mapper = new ObjectMapper().findAndRegisterModules();
        attestorKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ApprovalAuditExternalAttestationVerifier attestationVerifier =
                new ApprovalAuditExternalAttestationVerifier(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                        new ApprovalAuditExternalAttestationVerifier.TrustedAttestor(
                                ATTESTOR_ISSUER, ATTESTOR_IDENTITY,
                                ATTESTOR_KEY_ID, attestorKey.getPublic()));
        service = new ApprovalAuditService(
                new ApprovalAuditRepository(
                        new NamedParameterJdbcTemplate(dataSource), mapper),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), attestationVerifier);
        requestId = insertRequest(42, "AUDIT-42-1");
        insertEvent(42, requestId, "TASK_APPROVED", "17");
        jdbc.update("""
                INSERT INTO apr_document_heads (
                    tenant_id, request_id, hold_active, pending_hold_id,
                    retain_until)
                VALUES (42, ?, TRUE, NULL, ?)
                """, requestId, java.sql.Timestamp.from(NOW.plusSeconds(86_400)));
        UUID foreign = insertRequest(43, "AUDIT-43-1");
        insertEvent(43, foreign, "TASK_REJECTED", "99");
    }

    @Test
    void explorerIsTenantScopedAndAppliesRoleRedaction() {
        SearchFilter filter = filter(20);

        SearchPage metadata = service.search(scope(), filter, AccessLevel.METADATA);
        SearchPage auditor = service.search(scope(), filter, AccessLevel.AUDITOR);

        assertThat(metadata.events()).hasSize(1);
        assertThat(metadata.events().getFirst().actor().identifier()).isNull();
        assertThat(metadata.events().getFirst().message()).isNull();
        assertThat(metadata.events().getFirst().evidence())
                .containsEntry("stepKey", "finance-review")
                .containsEntry("accessToken", "[REDACTED]")
                .doesNotContainKey("payload");
        assertThat(metadata.events().getFirst().retention().status())
                .isEqualTo("LEGAL_HOLD_ACTIVE");

        assertThat(auditor.events()).hasSize(1);
        assertThat(auditor.events().getFirst().actor().identifier()).isEqualTo("17");
        assertThat(auditor.events().getFirst().evidence().toString())
                .contains("amount=4200000")
                .doesNotContain("secret-value");
    }

    @Test
    void metadataTextSearchCannotInferHiddenMessageContent() {
        String secret = "project-helios-acquisition-omega";
        insertEvent(42, requestId, "TASK_COMMENTED", "18", secret);
        SearchFilter secretFilter = filter(20, secret);

        SearchPage firstAttempt = service.search(
                scope(), secretFilter, AccessLevel.METADATA);
        SearchPage repeatedAttempt = service.search(
                scope(), secretFilter, AccessLevel.METADATA);
        SearchPage contentViewer = service.search(
                scope(), secretFilter, AccessLevel.AUDITOR);

        assertThat(firstAttempt.events()).isEmpty();
        assertThat(repeatedAttempt.events()).isEmpty();
        assertThat(contentViewer.events()).hasSize(1);
        assertThat(contentViewer.events().getFirst().message()).isEqualTo(secret);

        ExportReceipt metadataExport = service.export(
                scope(), UUID.randomUUID(), secretFilter, AccessLevel.METADATA);
        assertThat(metadataExport.manifest().eventCount()).isZero();
    }

    @Test
    void savedViewsAndExportsRemainScopedAndDoNotFabricateExternalAssurance() throws Exception {
        UUID savedViewId = UUID.randomUUID();
        SavedView saved = service.createSavedView(
                scope(), savedViewId, "High risk decisions",
                Visibility.PERSONAL, filter(50));
        SavedView savedReplay = service.createSavedView(
                scope(), savedViewId, "High risk decisions",
                Visibility.PERSONAL, filter(50));

        assertThat(saved.savedViewId()).isEqualTo(savedViewId);
        assertThat(savedReplay).isEqualTo(saved);
        assertThat(service.savedViews(scope())).extracting(SavedView::name)
                .containsExactly("High risk decisions");

        UUID exportId = UUID.randomUUID();
        ExportReceipt exported = service.export(
                scope(), exportId, filter(50), AccessLevel.AUDITOR);
        ExportReceipt exportedReplay = service.export(
                scope(), exportId, filter(50), AccessLevel.AUDITOR);

        assertThat(exported.status()).isEqualTo("COMPLETE");
        assertThat(exportedReplay.manifestSha256()).isEqualTo(exported.manifestSha256());
        assertThat(exported.integrityStatus()).isEqualTo("DIGEST_VERIFIED");
        assertThat(exported.externalAttestationType()).isNull();
        assertThat(exported.manifest().eventCount()).isEqualTo(1);
        assertThat(exported.manifest().retentionSummary())
                .containsEntry("LEGAL_HOLD_ACTIVE", 1);
        assertThat(exported.manifest().assuranceBoundaries())
                .anySatisfy(boundary -> assertThat(boundary).contains("No WORM"));

        ExternalAttestationSubmission submitted = signedAttestation(
                exportId, "WORM", "archive://evidence/42",
                attestorKey.getPrivate());
        ExportReceipt attested = service.linkVerifiedExternalAttestation(
                scope(), exportId, 0, submitted);
        assertThat(attested.integrityStatus()).isEqualTo("EXTERNAL_ATTESTED");
        assertThat(attested.externalAttestationReference())
                .isEqualTo("archive://evidence/42");
        assertThat(attested.externalAttestationIssuer()).isEqualTo(ATTESTOR_ISSUER);
        assertThat(attested.externalAttestorIdentity()).isEqualTo(ATTESTOR_IDENTITY);
        assertThat(attested.externalAttestationKeyId()).isEqualTo(ATTESTOR_KEY_ID);
        assertThat(attested.externalVerificationReference())
                .matches("verified:[a-f0-9]{64}");
        ExportReceipt attestationReplay = service.linkVerifiedExternalAttestation(
                scope(), exportId, 0, submitted);
        assertThat(attestationReplay.version()).isEqualTo(attested.version());

        SearchFilter changed = new SearchFilter(
                NOW.minusSeconds(7_200), NOW.plusSeconds(30),
                Set.of(), Set.of(), null, null, 50, null);
        assertThatThrownBy(() -> service.export(
                scope(), exportId, changed, AccessLevel.AUDITOR))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void untrustedAttestationCannotPromoteOrMutateTheExport() throws Exception {
        UUID exportId = UUID.randomUUID();
        service.export(scope(), exportId, filter(50), AccessLevel.AUDITOR);
        KeyPair untrusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ExternalAttestationSubmission submitted = signedAttestation(
                exportId, "WORM", "archive://untrusted/42", untrusted.getPrivate());

        assertThatThrownBy(() -> service.linkVerifiedExternalAttestation(
                scope(), exportId, 0, submitted))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        ExportReceipt unchanged = service.exportReceipt(scope(), exportId);
        assertThat(unchanged.integrityStatus()).isEqualTo("DIGEST_VERIFIED");
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.externalAttestationType()).isNull();
        assertThat(unchanged.externalAttestationIssuer()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_audit_export_jobs
                 WHERE export_id = ? AND integrity_status = 'EXTERNAL_ATTESTED'
                """, Integer.class, exportId)).isZero();
    }

    @Test
    void missingPrivilegesAndStaleExportVersionsFailClosed() throws Exception {
        Scope metadataOnly = new Scope(
                42, "RS_APPROVALS", 17,
                Set.of(Capability.VIEW));
        assertThatThrownBy(() -> service.search(
                metadataOnly, filter(20), AccessLevel.AUDITOR))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        UUID exportId = UUID.randomUUID();
        service.export(scope(), exportId, filter(50), AccessLevel.AUDITOR);
        service.linkVerifiedExternalAttestation(
                scope(), exportId, 0,
                signedAttestation(
                        exportId, "KMS_SIGNATURE", "kms://evidence/42",
                        attestorKey.getPrivate()));
        assertThatThrownBy(() -> service.linkVerifiedExternalAttestation(
                scope(), exportId, 0,
                signedAttestation(
                        exportId, "WORM", "archive://stale",
                        attestorKey.getPrivate())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    private Scope scope() {
        return new Scope(
                42, "RS_APPROVALS", 17,
                Set.of(
                        Capability.VIEW, Capability.VIEW_PRIVILEGED,
                        Capability.VIEW_AUDITOR, Capability.MANAGE_PERSONAL_VIEWS,
                        Capability.MANAGE_SHARED_VIEWS, Capability.EXPORT,
                        Capability.LINK_EXTERNAL_ATTESTATION));
    }

    private SearchFilter filter(int limit) {
        return filter(limit, null);
    }

    private SearchFilter filter(int limit, String text) {
        return new SearchFilter(
                NOW.minusSeconds(86_400), NOW.plusSeconds(30),
                Set.of(), Set.of(), null, text, limit, null);
    }

    private UUID insertRequest(long tenantId, String number) {
        UUID workflow = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id = ? ORDER BY version_number LIMIT 1
                """, UUID.class, tenantId);
        UUID form = jdbc.queryForObject("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id = ? ORDER BY version_number LIMIT 1
                """, UUID.class, tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id, title,
                    requester_user_id, status, submitted_at, due_at,
                    completed_at, management_resource_set_key,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'Audit request', 17, 'APPROVED',
                    ?, ?, ?, 'RS_APPROVALS', ?, ?)
                """, id, tenantId, number, workflow, form,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW.minusSeconds(1_800)),
                java.sql.Timestamp.from(NOW.minusSeconds(1_200)),
                java.sql.Timestamp.from(NOW.minusSeconds(4_000)),
                java.sql.Timestamp.from(NOW.minusSeconds(1_200)));
        return id;
    }

    private void insertEvent(long tenantId, UUID request, String type, String actor) {
        insertEvent(tenantId, request, type, actor, "Reviewed request");
    }

    private void insertEvent(
            long tenantId,
            UUID request,
            String type,
            String actor,
            String message) {
        jdbc.update("""
                INSERT INTO apr_request_events (
                    event_id, tenant_id, request_id, event_type,
                    actor_type, actor_id, outcome, message, event_data, occurred_at)
                VALUES (?, ?, ?, ?, 'USER', ?, 'SUCCESS', ?,
                    CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), tenantId, request, type, actor, message,
                mapper.valueToTree(Map.of(
                        "stepKey", "finance-review",
                        "accessToken", "secret-value",
                        "payload", Map.of("amount", 4_200_000))).toString(),
                java.sql.Timestamp.from(NOW.minusSeconds(600)));
    }

    private ExternalAttestationSubmission signedAttestation(
            UUID exportId,
            String type,
            String reference,
            PrivateKey privateKey) throws Exception {
        ExportReceipt export = service.exportReceipt(scope(), exportId);
        var claims = new ApprovalAuditExternalAttestationVerifier.AttestationClaims(
                ATTESTOR_ISSUER, ATTESTOR_IDENTITY, ATTESTOR_KEY_ID,
                ApprovalAuditExternalAttestationVerifier.AUDIENCE,
                ApprovalAuditExternalAttestationVerifier.PURPOSE,
                42, "RS_APPROVALS", exportId, export.manifestSha256(),
                type, reference, NOW, NOW, NOW.plusSeconds(240),
                "nonce-attestation-100");
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(payload);
        return new ExternalAttestationSubmission(
                type, reference, NOW,
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }
}
