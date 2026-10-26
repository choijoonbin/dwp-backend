package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisSeparationApprovalBridgePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void authenticatedAuthorityDenyAndUnavailableRemainTyped() {
        var auth = mock(AuthHrm013ServiceAuthorityClient.class);
        var resolver = new HrisSeparationApprovalAuthorityResolver(auth,
                mock(Hrm013RequesterIdentityClient.class),
                mock(NamedParameterJdbcTemplate.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        var selector = new Hrm013WorkloadProofV1.UntrustedSelector(42,
                new Hrm013WorkloadProofV1.BusinessAuthority(
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 1, 1),
                null);
        for (var expected : java.util.List.of(
                HrisServiceGrantAuthorityV1.Status.REVOKE,
                HrisServiceGrantAuthorityV1.Status.UNAVAILABLE)) {
            when(auth.resolveWithSnapshot(any())).thenReturn(
                    new AuthHrm013ServiceAuthorityClient.ResolvedAttempt(
                            HrisServiceGrantAuthorityV1.ResolveResponse.opaque(
                                    expected, UUID.randomUUID()),
                            null, Instant.now().plusSeconds(30)));
            var session = resolver.begin();
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    HrisSeparationProtocolException.class,
                    () -> session.resolve(Hrm013WorkloadProofV1.apr002(),
                            selector, "people-key-1"));
            assertThat(session.failure()).isSameAs(failure);
            assertThat(failure.failure().errorCode()).isEqualTo(
                    expected == HrisServiceGrantAuthorityV1.Status.REVOKE
                            ? com.dwp.platform.contracts.hris.separation.v1
                                    .Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED
                            : com.dwp.platform.contracts.hris.separation.v1
                                    .Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
        }
    }

    @Test
    void localProjectionFailureIsTypedForInitialAndCommitRecheck() {
        for (String phase : java.util.List.of("INITIAL_VERIFY", "COMMIT_RECHECK")) {
            var denied = org.assertj.core.api.Assertions.catchThrowableOfType(
                    HrisSeparationProtocolException.class,
                    () -> HrisSeparationApprovalAuthorityResolver
                            .requireLocalProjection(() -> {
                                throw new IllegalStateException(phase);
                            }));
            assertThat(denied.failure().errorCode()).isEqualTo(
                    com.dwp.platform.contracts.hris.separation.v1
                            .Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);

            var unavailable = org.assertj.core.api.Assertions.catchThrowableOfType(
                    HrisSeparationProtocolException.class,
                    () -> HrisSeparationApprovalAuthorityResolver
                            .requireLocalProjection(() -> {
                                throw new org.springframework.dao
                                        .DataAccessResourceFailureException(phase);
                            }));
            assertThat(unavailable.failure().errorCode()).isEqualTo(
                    com.dwp.platform.contracts.hris.separation.v1
                            .Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
        }
    }

    @Test
    void drainAuthorityRecheckAcceptsAFreshActivityOnTheSameFenceOnly()
            throws Exception {
        var authority = mock(HrisServiceGrantAuthorityV1.GrantAuthority.class);
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var signing = new Hrm013WorkloadProofV1.VerifiedSigningKey(
                keys.generateKeyPair().getPublic(), "people-key-1",
                "dwp-people-server");
        UUID fence = UUID.randomUUID();
        Instant effective = Instant.parse("2026-10-27T01:00:00Z");
        var admitted = drain(fence, effective, 41,
                effective.plusSeconds(1));
        var rechecked = drain(fence, effective, 42,
                effective.plusSeconds(2));
        var lease = mock(HrisSeparationWorkloadCredentialProvider.Lease.class);
        Instant expiry = effective.plusSeconds(20);
        var first = new HrisSeparationApprovalAuthorityResolver.Resolution(
                authority, null, admitted, null, signing, lease, null, expiry);
        var second = new HrisSeparationApprovalAuthorityResolver.Resolution(
                authority, null, rechecked, null, signing, lease, null, expiry);

        assertThat(first.sameAuthority(second)).isTrue();
        assertThatThrownBy(() -> HrisSeparationApprovalAuthorityResolver
                .requireUnchangedCurrent(second, second))
                .isInstanceOf(HrisSeparationProtocolException.class);
        assertThat(first.sameAuthority(new HrisSeparationApprovalAuthorityResolver.Resolution(
                authority, null, admitted, null, signing, lease, null, expiry)))
                .isFalse();
        var drifted = new HrisSeparationApprovalAuthorityResolver.Resolution(
                authority, null, drain(UUID.randomUUID(), effective, 42,
                        effective.plusSeconds(2)), null, signing, lease, null,
                expiry);
        var drift = org.assertj.core.api.Assertions.catchThrowableOfType(
                HrisSeparationProtocolException.class,
                () -> HrisSeparationApprovalAuthorityResolver
                        .requireUnchangedCurrent(first, drifted));
        assertThat(drift.failure().errorCode()).isEqualTo(
                com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1
                        .ErrorCode.AUTHORITY_DENIED);
    }

    @Test
    void localProjectionRejectsAOneFieldSupersedesMismatch() {
        UUID grant = UUID.randomUUID();
        UUID supersedes = UUID.randomUUID();
        var projection = new TenantGrant(grant, 42, 7, 11, 12,
                GrantDecision.ADMIT, "people-key-1", "approval-key-1",
                supersedes, "0".repeat(64), Instant.parse("2026-10-27T01:00:00Z"));
        var authority = mock(HrisServiceGrantAuthorityV1.GrantAuthority.class);
        when(authority.tenantId()).thenReturn(42L);
        when(authority.grantSetId()).thenReturn(grant);
        when(authority.tenantGrantRevision()).thenReturn(11L);
        when(authority.authorizationRevision()).thenReturn(12L);
        when(authority.callerSigningKeyId()).thenReturn("people-key-1");
        when(authority.ownerSigningKeyId()).thenReturn("approval-key-1");
        when(authority.supersedesGrantSetId()).thenReturn(supersedes);

        assertThatCode(() -> HrisSeparationApprovalAuthorityResolver
                .requireProjection(projection, authority)).doesNotThrowAnyException();
        when(authority.supersedesGrantSetId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> HrisSeparationApprovalAuthorityResolver
                .requireProjection(projection, authority))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }

    @Test
    void exactApr002ReplayReturnsTheOriginalReceiptAndRejectsByteDrift() {
        var fixture = new Hrm013ApprovalPostgresFixture(POSTGRES, 42601);
        var binding = fixture.binding(1);
        var first = fixture.bindings.saveOrReplay(binding);
        var replay = fixture.bindings.replay(fixture.tenant,
                binding.approvalRequestId(), binding.exactRequestBody(),
                binding.commandDigest()).orElseThrow();

        assertThat(first.replayed()).isFalse();
        assertThat(replay.exactResponseBody())
                .containsExactly(binding.exactResponseBody());
        byte[] changed = binding.exactRequestBody();
        changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> fixture.bindings.replay(fixture.tenant,
                binding.approvalRequestId(), changed, binding.commandDigest()))
                .isInstanceOf(HrisSeparationProtocolException.class)
                .hasMessageContaining("DIGEST_CONFLICT");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM apr_hrm013_waiver_request_bindings",
                Long.class)).isEqualTo(1L);
    }

    private static HrisServiceGrantAuthorityV1.DrainAdmission drain(
            UUID fence, Instant effective, long revision, Instant recordedAt) {
        return new HrisServiceGrantAuthorityV1.DrainAdmission(
                fence, 3, effective,
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, revision,
                recordedAt, recordedAt.plusSeconds(30),
                recordedAt.plusSeconds(65));
    }
}

/** Shared disposable V59 fixture used only by the authorized HRM-013 tests. */
final class Hrm013ApprovalPostgresFixture {
    final long tenant;
    final JdbcTemplate jdbc;
    final NamedParameterJdbcTemplate named;
    final DataSourceTransactionManager manager;
    final HrisSeparationGrantStore grants;
    final HrisSeparationWaiverBindingStore bindings;
    final HrisSeparationDecisionOutboxStore decisions;
    final TenantGrant grant;
    final UUID workflowVersion;
    final UUID formVersion;

    Hrm013ApprovalPostgresFixture(PostgreSQLContainer<?> postgres, long tenant) {
        this.tenant = tenant;
        var source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        var flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, tenant);
        workflowVersion = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,workflow_version_id LIMIT 1
                """, UUID.class, tenant);
        formVersion = jdbc.queryForObject("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,form_version_id LIMIT 1
                """, UUID.class, tenant);
        named = new NamedParameterJdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        grants = new HrisSeparationGrantStore(named, manager);
        bindings = new HrisSeparationWaiverBindingStore(named, manager, grants);
        decisions = new HrisSeparationDecisionOutboxStore(named, manager, grants);
        grant = grants.append(new GrantWrite(uuid(8001), tenant, 1, 101, 201,
                GrantDecision.ADMIT, "people-key-1", "approval-key-1", null,
                instant(1)));
    }

    RequestBindingWrite binding(int suffix) {
        UUID requestId = uuid(100 + suffix);
        UUID caseId = uuid(200 + suffix);
        UUID taskId = uuid(300 + suffix);
        UUID maker = uuid(400 + suffix);
        UUID requester = uuid(500 + suffix);
        UUID correlation = uuid(600 + suffix);
        Instant requestedAt = instant(100 + suffix);
        var sealed = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1(
                        requestId, caseId, taskId, "EXIT_CLEARANCE", maker,
                        requester, "POLICY_EXCEPTION", 1, 0, requestedAt,
                        "0".repeat(64), correlation));
        byte[] exact = Hrm013SeparationBridgeV1.encode(sealed);
        String bodyDigest = Hrm013SeparationBridgeV1.requestBodySha256(exact);
        String command = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.APR_002,
                Hrm013SeparationBridgeV1.APR_002.path(null, null), tenant,
                bodyDigest);
        byte[] response = Hrm013SeparationBridgeV1.encode(
                new Hrm013SeparationBridgeV1
                        .SeparationWaiverApprovalSubmissionReceiptV1(
                        requestId, 1, "PENDING", command, correlation,
                        requestedAt.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,
                    workflow_version_id,form_version_id,title,requester_user_id,
                    requester_person_public_id,status)
                VALUES(?,?,?, ?,?,'Separation waiver',99,?,'IN_REVIEW')
                """, requestId, tenant, "HRM013-" + suffix,
                workflowVersion, formVersion, requester);
        return new RequestBindingWrite(uuid(1000 + suffix), tenant,
                grant.grantId(), grant.tenantGrantRevision(),
                grant.authorizationRevision(), requestId, caseId, taskId,
                "EXIT_CLEARANCE", maker, requester, "POLICY_EXCEPTION", 1, 0,
                workflowVersion, formVersion, 1, correlation,
                sealed.payloadSha256(), exact, bodyDigest, command, response,
                Hrm013SeparationBridgeV1.sha256(response), requestedAt,
                requestedAt.plusSeconds(1));
    }

    DecisionWrite decision(RequestBindingWrite binding, int suffix,
            boolean approved) {
        UUID actor = uuid(2000 + suffix);
        UUID event = com.dwp.platform.contracts.hris.separation.v1
                .Hrm013WorkloadProofV1.terminalChildEventId(
                        tenant, binding.approvalRequestId(), 1, CALLBACK_BINDING);
        Instant occurred = instant(500 + suffix);
        UUID evidenceId = approved ? uuid(3000 + suffix) : null;
        UUID nonce = approved ? uuid(4000 + suffix) : null;
        Instant verified = approved ? occurred.minusSeconds(30) : null;
        Instant consumed = approved ? occurred.minusSeconds(1) : null;
        var draft = new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                event, binding.approvalRequestId(), binding.casePublicId(),
                binding.taskPublicId(), approved ? "APPROVED" : "REJECTED",
                actor, 1, 1, binding.reasonCode(),
                binding.approvalRequestPayloadSha256(), evidenceId,
                approved ? PEOPLE_WORKLOAD : null,
                approved ? CALLBACK_PURPOSE : null, nonce,
                approved ? "0".repeat(64) : null, verified, consumed, occurred,
                "0".repeat(64), binding.originalCorrelationId());
        String evidenceDigest = approved
                ? Hrm013SeparationBridgeV1.stepUpEvidenceSha256(tenant, draft)
                : null;
        var sealed = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        event, binding.approvalRequestId(), binding.casePublicId(),
                        binding.taskPublicId(), approved ? "APPROVED" : "REJECTED",
                        actor, 1, 1, binding.reasonCode(),
                        binding.approvalRequestPayloadSha256(), evidenceId,
                        approved ? PEOPLE_WORKLOAD : null,
                        approved ? CALLBACK_PURPOSE : null, nonce, evidenceDigest,
                        verified, consumed, occurred, "0".repeat(64),
                        binding.originalCorrelationId()));
        byte[] exact = Hrm013SeparationBridgeV1.encode(sealed);
        String bodyDigest = Hrm013SeparationBridgeV1.requestBodySha256(exact);
        String path = Hrm013SeparationBridgeV1.HRM_010.path(
                binding.casePublicId(), binding.taskPublicId());
        StepUpEvidence evidence = approved ? new StepUpEvidence(
                evidenceId, PEOPLE_WORKLOAD, CALLBACK_PURPOSE, nonce,
                evidenceDigest, verified, consumed) : null;
        return new DecisionWrite(uuid(5000 + suffix), tenant, grant.grantId(),
                grant.tenantGrantRevision(), grant.authorizationRevision(),
                binding.approvalRequestId(), binding.casePublicId(),
                binding.taskPublicId(), event,
                approved ? WaiverDecision.APPROVED : WaiverDecision.REJECTED,
                actor, binding.caseMakerPublicId(),
                binding.requestedByActorPublicId(), binding.reasonCode(), evidence,
                occurred, binding.originalCorrelationId(),
                binding.approvalRequestPayloadSha256(), sealed.payloadSha256(),
                exact, bodyDigest, Hrm013SeparationBridgeV1.commandDigest(
                        Hrm013SeparationBridgeV1.HRM_010, path, tenant,
                        bodyDigest), Instant.now(), Instant.now());
    }

    static Instant instant(int suffix) {
        return Instant.parse("2026-10-26T01:00:00Z").plusSeconds(suffix)
                .truncatedTo(ChronoUnit.MICROS);
    }

    static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
