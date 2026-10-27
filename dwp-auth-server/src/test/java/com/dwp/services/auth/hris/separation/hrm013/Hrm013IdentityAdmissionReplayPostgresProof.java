package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchStorePostgresTest.*;

/** Signed replay and identity fixtures exercised by the V272 database contract test. */
final class Hrm013IdentityAdmissionReplayPostgresProof {
    private Hrm013IdentityAdmissionReplayPostgresProof() {
    }

    static void proveRealAdmissionReplayRefreshesOwnerProof(
            JdbcTemplate admin) {
        try {
            long tenant = 972_041L;
            long otherTenant = 972_042L;
            createTenant(admin, tenant, "UTC");
            createTenant(admin, otherTenant, "UTC");
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            ReplayKeyMaterial callerV1 = replayKey("people-dispatch-v1");
            ReplayKeyMaterial ownerV1 = replayKey("auth-dispatch-v1");
            ReplayKeyMaterial callerV2 = replayKey("people-dispatch-v2");
            ReplayKeyMaterial ownerV2 = replayKey("auth-dispatch-v2");
            Instant databaseNow = admin.queryForObject(
                    "SELECT clock_timestamp()",
                    (row, ignored) -> row.getTimestamp(1).toInstant());
            MutableReplayClock clock = new MutableReplayClock(
                    databaseNow.minusSeconds(5));
            UUID firstGrantSet = UUID.randomUUID();
            HrisServiceGrantAuthorityV1.GrantAuthority authorityV1 =
                    replayAuthority(tenant, firstGrantSet, null, 1, 31, 47,
                            callerV1, ownerV1, databaseNow.minusSeconds(30));
            HrisServiceGrantAuthorityV1.GrantAuthority authorityV2 =
                    replayAuthority(tenant, UUID.randomUUID(), firstGrantSet,
                            2, 32, 48, callerV2, ownerV2,
                            databaseNow.minusSeconds(10));
            AtomicReference<HrisServiceGrantAuthorityV1.GrantAuthority>
                    currentAuthority = new AtomicReference<>(authorityV1);
            AtomicReference<Hrm013IdentityWorkloadCredentialProvider
                    .AtomicGrantCredentialLease> currentLease = new AtomicReference<>();
            Hrm013IdentityWorkloadCredentialProvider credentials =
                    new Hrm013IdentityWorkloadCredentialProvider(
                            (selectedTenant, binding, purpose) -> currentLease.get(),
                            mapper, clock);
            AtomicInteger ownerV1Signs = new AtomicInteger();
            AtomicInteger ownerV2Signs = new AtomicInteger();
            var leaseV1 = replayOwnerLease(
                    credentials, authorityV1, ownerV1, 1, databaseNow,
                    ownerV1Signs);
            var leaseV2 = replayOwnerLease(
                    credentials, authorityV2, ownerV2, 2, databaseNow,
                    ownerV2Signs);
            currentLease.set(leaseV1);

            HrisServiceGrantQuiesceService authorities =
                    mock(HrisServiceGrantQuiesceService.class);
            AtomicInteger transactionIds = new AtomicInteger();
            when(authorities.prepareLocalVerification(any())).thenAnswer(call -> {
                HrisServiceGrantAuthorityV1.ResolveRequest request =
                        call.getArgument(0);
                return new HrisServiceGrantQuiesceService.LocalVerification(
                        request, currentAuthority.get(),
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER, null,
                        transactionIds.incrementAndGet(), "9".repeat(64));
            });
            when(authorities.authorizeLocalVerified(any(), anyString()))
                    .thenAnswer(call -> {
                        HrisServiceGrantQuiesceService.LocalVerification preparation =
                                call.getArgument(0);
                        String proofSha256 = call.getArgument(1);
                        return new HrisServiceGrantQuiesceService.LocalAdmission(
                                preparation,
                                HrisServiceGrantAuthorityV1.ResolveResponse.admit(
                                        preparation.authority(), null,
                                        preparation.request().correlationId()),
                                proofSha256);
                    });

            PGSimpleDataSource runtimeSource = runtimeSource();
            Hrm013IdentityTaskDispatchStore store =
                    new Hrm013IdentityTaskDispatchStore(
                            new JdbcTemplate(runtimeSource),
                            new DataSourceTransactionManager(runtimeSource),
                            mapper, clock, credentials, authorities);
            UUID casePublicId = UUID.randomUUID();
            UUID taskPublicId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            UUID personPublicId = UUID.randomUUID();
            IdentityFixture identity = seedIdentityFixture(
                    admin, tenant, otherTenant, personPublicId);
            UUID dispatchEventId = Hrm013TaskDispatchV1.dispatchEventId(
                    tenant, taskPublicId, 1,
                    Hrm013TaskDispatchV1.IDENTITY.bindingId());
            var dispatch = Hrm013TaskDispatchV1.seal(
                    new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                            Hrm013TaskDispatchV1.SCHEMA_NAME,
                            Hrm013TaskDispatchV1.SCHEMA_VERSION,
                            dispatchEventId, casePublicId, taskPublicId,
                            Hrm013TaskDispatchV1.IDENTITY.taskType(), 1, 1,
                            databaseNow.minusSeconds(1), "0".repeat(64),
                            correlationId, personPublicId, "TERMINATION",
                            LocalDate.now(ZoneOffset.UTC)));
            byte[] exactBody = Hrm013TaskDispatchV1.encode(
                    Hrm013TaskDispatchV1.IDENTITY, tenant, dispatch);
            String callerCertificate = replayThumbprint("people-certificate");
            var firstRequest = replayRequest(
                    mapper, clock.instant(), 30, authorityV1, callerV1,
                    callerCertificate, dispatch, exactBody);

            Hrm013IdentityTaskDispatchStore.Admission first =
                    store.admit(firstRequest);
            assertThat(first.replayed()).isFalse();
            Instant firstProofExpiry = replayProofExpiry(
                    mapper, first.receiptProof());

            clock.advance(Duration.ofSeconds(31));
            currentAuthority.set(authorityV2);
            currentLease.set(leaseV2);
            var replayRequest = replayRequest(
                    mapper, clock.instant(), 4, authorityV2, callerV2,
                    callerCertificate, dispatch, exactBody);
            Hrm013IdentityTaskDispatchStore.Admission replay =
                    store.admit(replayRequest);

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.exactReceiptBody())
                    .containsExactly(first.exactReceiptBody());
            assertThat(replay.receipt()).isEqualTo(first.receipt());
            assertThat(replay.receipt().requestBodySha256()).isEqualTo(
                    Hrm013TaskDispatchV1.requestBodySha256(exactBody));
            assertThat(replay.receipt().receiptSha256()).isEqualTo(
                    Hrm013TaskDispatchV1.receiptSha256(replay.receipt()));
            assertThat(firstProofExpiry).isBeforeOrEqualTo(clock.instant());
            assertThat(replay.receiptProof()).isNotEqualTo(first.receiptProof());
            assertReplayOwnerProof(mapper, replay, authorityV2, ownerV2,
                    ownerV1, leaseV2.clientCertificateSha256(), clock.instant());
            assertThat(ownerV1Signs).hasValueGreaterThanOrEqualTo(2);
            assertThat(ownerV2Signs).hasValueGreaterThanOrEqualTo(2);

            assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                    runtime.update("""
                            INSERT INTO auth_hrm013_identity_task_audit_events(
                              audit_event_id,tenant_id,dispatch_event_id,
                              action_code,outcome_code,evidence_sha256,
                              correlation_id)
                            VALUES (?, ?, ?, 'IDENTITY_ACCESS_REVOKED',
                                    'SUCCESS', ?, ?)
                            """, UUID.randomUUID(), tenant, dispatchEventId,
                            "7".repeat(64), correlationId)), "42501");

            Hrm013IdentityTaskDispatchStore.Completion completed =
                    store.executeNext(tenant).orElseThrow();
            assertThat(completed.callback().status()).isEqualTo("COMPLETED");
            assertThat(completed.replayed()).isFalse();

            assertRevokedIdentityFixture(admin, identity, dispatchEventId);

            inRuntimeTransaction(tenant, runtime -> {
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_dispatch_inbox
                         WHERE tenant_id=? AND dispatch_event_id=?
                           AND lifecycle_state='COMPLETED'
                        """, Integer.class, tenant, dispatchEventId)).isOne();
                assertThat(runtime.queryForObject("""
                        SELECT admission_receipt_proof
                          FROM auth_hrm013_identity_task_dispatch_inbox
                         WHERE tenant_id=? AND dispatch_event_id=?
                        """, String.class, tenant, dispatchEventId))
                        .isEqualTo(first.receiptProof())
                        .isNotEqualTo(replay.receiptProof());
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_receipt_outbox receipt
                          JOIN auth_hrm013_identity_task_dispatch_inbox inbox
                            ON inbox.tenant_id=receipt.tenant_id
                           AND inbox.dispatch_event_id=receipt.dispatch_event_id
                         WHERE receipt.tenant_id=?
                           AND receipt.dispatch_event_id=?
                           AND receipt.delivery_state='PENDING'
                           AND receipt.created_at=inbox.completed_at
                           AND receipt.next_attempt_at=inbox.completed_at
                           AND (convert_from(receipt.exact_callback_body,'UTF8')::jsonb
                                ->>'occurredAt')::timestamptz=inbox.completed_at
                        """, Integer.class, tenant, dispatchEventId)).isOne();
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_audit_events
                         WHERE tenant_id=? AND dispatch_event_id=?
                           AND action_code='IDENTITY_ACCESS_REVOKED'
                           AND outcome_code='SUCCESS'
                        """, Integer.class, tenant, dispatchEventId)).isOne();
            });
        } catch (Exception failure) {
            throw new AssertionError("real admission replay fixture failed", failure);
        }
    }

    static HrisServiceGrantAuthorityV1.GrantAuthority replayAuthority(
            long tenant, UUID grantSetId, UUID supersedesGrantSetId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            ReplayKeyMaterial caller, ReplayKeyMaterial owner,
            Instant recordedAt) {
        var profile = Hrm013TaskDispatchV1.IDENTITY;
        return new HrisServiceGrantAuthorityV1.GrantAuthority(
                grantSetId, grantRevision, tenant, tenantRevision,
                authorizationRevision, "ADMIT", profile.callerWorkloadId(),
                "spiffe://dwp/service/" + profile.callerWorkloadId(),
                caller.jwk().kid(), caller.jwk(), profile.ownerWorkloadId(),
                "spiffe://dwp/service/" + profile.ownerWorkloadId(),
                owner.jwk().kid(), owner.jwk(), profile.bindingId(),
                profile.purpose(), supersedesGrantSetId,
                Hrm013WorkloadProofV1.sha256(
                        ("grant-" + grantRevision).getBytes(StandardCharsets.UTF_8)),
                recordedAt);
    }

    static IdentityFixture seedIdentityFixture(JdbcTemplate jdbc,
            long tenant, long otherTenant, UUID personPublicId) {
        long subjectUserId = insertUser(
                jdbc, tenant, personPublicId, "TENANT", "revoke-subject");
        long otherTenantUserId = insertUser(
                jdbc, otherTenant, personPublicId, "TENANT", "other-tenant");
        long otherPersonUserId = insertUser(
                jdbc, tenant, UUID.randomUUID(), "TENANT", "other-person");
        long providerUserId = insertUser(
                jdbc, tenant, UUID.randomUUID(), "PROVIDER", "provider-plane");

        seedIdentityResources(jdbc, tenant, subjectUserId, "positive", 2);
        return new IdentityFixture(tenant, otherTenant, subjectUserId,
                otherTenantUserId, otherPersonUserId, providerUserId);
    }

    static void seedIdentityResources(JdbcTemplate jdbc, long tenant,
            long subjectUserId, String prefix, int count) {
        for (int index = 1; index <= count; index++) {
            long accountId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_user_accounts(
                      tenant_id,user_id,provider_type,provider_id,principal,
                      issuer_uri,status)
                    VALUES (?,?,'OIDC',?,?,?,'ACTIVE')
                    RETURNING user_account_id
                    """, Long.class, tenant, subjectUserId,
                    "hrm013-" + prefix + "-idp-" + index,
                    "hrm013-" + prefix + "-subject-" + index + "-"
                            + UUID.randomUUID(),
                    "https://" + prefix + "-issuer" + index + ".example.test"));
            UUID sessionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_auth_sessions(
                      session_id,token_id,tenant_id,user_id,expires_at,
                      session_family_id,session_started_at,issued_at,last_seen_at,
                      idle_expires_at)
                    VALUES (?,?,?,?,statement_timestamp()+INTERVAL '1 hour',?,
                            statement_timestamp(),statement_timestamp(),
                            statement_timestamp(),
                            statement_timestamp()+INTERVAL '30 minutes')
                    """, sessionId, Hrm013WorkloadProofV1.sha256(
                            (prefix + "-session-" + accountId)
                                    .getBytes(StandardCharsets.UTF_8)),
                    tenant, subjectUserId, sessionId);
            insertActivationTokenFixture(
                    jdbc, tenant, subjectUserId, accountId, prefix);

            long roleId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_roles(
                      tenant_id,code,name,role_type,privileged,
                      assignable_to_groups,status)
                    VALUES (?,?,?,'CUSTOM',false,true,'ACTIVE')
                    RETURNING role_id
                    """, Long.class, tenant,
                    "HRM013_" + prefix.toUpperCase() + "_" + index,
                    "HRM013 " + prefix + " role " + index));
            jdbc.update("""
                    INSERT INTO com_role_members(tenant_id,role_id,user_id)
                    VALUES (?,?,?)
                    """, tenant, roleId, subjectUserId);

            long groupId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_groups(
                      tenant_id,group_key,display_name,source_type,status)
                    VALUES (?,?,?,'LOCAL','ACTIVE') RETURNING group_id
                    """, Long.class, tenant,
                    "hrm013-" + prefix + "-group-" + index,
                    "HRM013 " + prefix + " group " + index));
            jdbc.update("""
                    INSERT INTO com_group_members(
                      tenant_id,group_id,user_id,source_type)
                    VALUES (?,?,?,'LOCAL')
                    """, tenant, groupId, subjectUserId);
        }
    }

    static void insertActivationTokenFixture(JdbcTemplate jdbc,
            long tenant, long userId, long accountId, String prefix) {
        TransactionTemplate fixtureTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(
                        Objects.requireNonNull(jdbc.getDataSource())));
        fixtureTransaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role='replica'");
            jdbc.update("""
                    INSERT INTO sys_account_activation_tokens(
                      activation_token_id,tenant_id,user_id,user_account_id,
                      token_hash,lifecycle_state,expires_at)
                    VALUES (?,?,?,?,?,'ACTIVE',
                            statement_timestamp()+INTERVAL '1 hour')
                    """, UUID.randomUUID(), tenant, userId, accountId,
                    Hrm013WorkloadProofV1.sha256(
                            (prefix + "-activation-" + accountId)
                                    .getBytes(StandardCharsets.UTF_8)));
        });
    }

    static void assertRevokedIdentityFixture(JdbcTemplate jdbc,
            IdentityFixture fixture, UUID dispatchEventId) {
        assertThat(jdbc.queryForMap("""
                SELECT status,access_revision,version
                  FROM com_users WHERE tenant_id=? AND user_id=?
                """, fixture.tenantId(), fixture.subjectUserId()))
                .containsEntry("status", "INACTIVE")
                .containsEntry("access_revision", 1L)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_user_accounts
                 WHERE tenant_id=? AND user_id=? AND status='RETIRED'
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_auth_sessions
                 WHERE tenant_id=? AND user_id=? AND revoked_at IS NOT NULL
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_account_activation_tokens
                 WHERE tenant_id=? AND user_id=? AND lifecycle_state='REVOKED'
                   AND revoked_at IS NOT NULL
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_role_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_group_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_users WHERE tenant_id=? AND user_id=?
                """, String.class, fixture.otherTenantId(),
                fixture.otherTenantUserId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_users WHERE tenant_id=? AND user_id=?
                """, String.class, fixture.tenantId(),
                fixture.otherPersonUserId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("""
                SELECT status,identity_plane FROM com_users
                 WHERE tenant_id=? AND user_id=?
                """, fixture.tenantId(), fixture.providerUserId()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("identity_plane", "PROVIDER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_history
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND subject_state='REVOKED'
                """, Integer.class, fixture.tenantId(), dispatchEventId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND action_code='IDENTITY_ACCESS_REVOKED'
                   AND outcome_code='SUCCESS'
                """, Integer.class, fixture.tenantId(), dispatchEventId)).isOne();
    }

    static Hrm013IdentityWorkloadCredentialProvider
            .AtomicGrantCredentialLease replayOwnerLease(
                    Hrm013IdentityWorkloadCredentialProvider provider,
                    HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    ReplayKeyMaterial owner, long leaseVersion, Instant databaseNow,
                    AtomicInteger signCount) {
        Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer = input -> {
            signCount.incrementAndGet();
            return replaySign(owner.keyPair(), input);
        };
        Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls =
                new Hrm013IdentityWorkloadCredentialProvider
                        .NonExportableTlsClient() { };
        String certificate = replayThumbprint(
                "owner-certificate-" + leaseVersion);
        var provisional = new Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease(
                        UUID.randomUUID(), leaseVersion, authority.tenantId(),
                        authority.grantSetId(), authority.grantRevision(),
                        authority.tenantGrantRevision(),
                        authority.authorizationRevision(),
                        Hrm013IdentityWorkloadCredentialProvider
                                .CredentialRole.OWNER,
                        authority.bindingId(), authority.purpose(),
                        authority.callerWorkloadId(), authority.callerSpiffeId(),
                        authority.ownerWorkloadId(), authority.ownerSpiffeId(),
                        authority.ownerWorkloadId(), authority.ownerSigningKeyId(),
                        "kms-version-" + leaseVersion,
                        Hrm013IdentityWorkloadCredentialProvider
                                .publicJwkThumbprint(owner.jwk()),
                        signer, authority.ownerSpiffeId(), certificate, tls,
                        databaseNow.minusSeconds(60),
                        databaseNow.plusSeconds(600), "0".repeat(64));
        return new Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease(
                        provisional.leaseId(), provisional.leaseVersion(),
                        provisional.tenantId(), provisional.grantSetId(),
                        provisional.grantRevision(),
                        provisional.tenantGrantRevision(),
                        provisional.authorizationRevision(),
                        provisional.credentialRole(), provisional.bindingId(),
                        provisional.purpose(), provisional.callerWorkloadId(),
                        provisional.callerSpiffeId(),
                        provisional.ownerWorkloadId(),
                        provisional.ownerSpiffeId(),
                        provisional.serviceWorkloadId(),
                        provisional.businessSigningKeyId(),
                        provisional.businessKeyVersionId(),
                        provisional.businessPublicJwkThumbprint(),
                        provisional.businessPrivateKeyHandle(),
                        provisional.clientSpiffeId(),
                        provisional.clientCertificateSha256(),
                        provisional.tlsClientHandle(), provisional.notBefore(),
                        provisional.expiresAt(), provider.digest(provisional));
    }

    static Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest
            replayRequest(ObjectMapper mapper, Instant issuedAt,
                    long lifetimeSeconds,
                    HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    ReplayKeyMaterial caller, String callerCertificate,
                    Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
                    byte[] exactBody) throws Exception {
        var profile = Hrm013WorkloadProofV1.taskDispatch(
                Hrm013WorkloadProofV1.TaskOwner.IDENTITY);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", profile.callerWorkloadId());
        claims.put("aud", profile.ownerWorkloadId());
        claims.put("sub", profile.callerWorkloadId());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", issuedAt.plusSeconds(lifetimeSeconds).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("requestNonce", UUID.randomUUID().toString());
        claims.put("bindingId", profile.bindingId());
        claims.put("purpose", profile.purpose());
        claims.put("method", profile.method());
        claims.put("path", profile.exactPath());
        claims.put("tenantId", authority.tenantId());
        claims.put("correlationId", dispatch.correlationId().toString());
        claims.put("requestBodySha256",
                Hrm013WorkloadProofV1.sha256(exactBody));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", callerCertificate));
        claims.put("tenantGrantRevision", authority.tenantGrantRevision());
        claims.put("authorizationRevision", authority.authorizationRevision());
        claims.put("grantUseMode",
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK.name());
        claims.put("dispatchEventId", dispatch.dispatchEventId().toString());
        claims.put("casePublicId", dispatch.casePublicId().toString());
        claims.put("taskPublicId", dispatch.taskPublicId().toString());
        claims.put("taskType", dispatch.taskType());
        claims.put("taskOwnerRevision", dispatch.taskOwnerRevision());
        claims.put("payloadSha256", dispatch.payloadSha256());
        String compact = replayCompact(
                mapper, claims, caller.keyPair(), caller.jwk().kid());
        return new Hrm013IdentityTaskDispatchIngressFilter
                .VerifiedTransportRequest(
                        exactBody,
                        Hrm013WorkloadProofV1.headers(
                                profile, compact, dispatch.correlationId()),
                        compact, dispatch.correlationId(),
                        new Hrm013WorkloadProofV1.TransportIdentity(
                                profile.callerWorkloadId(), callerCertificate));
    }

    static String replayCompact(ObjectMapper mapper,
            Map<String, Object> claims, KeyPair signer, String keyId)
            throws Exception {
        Map<String, Object> jose = new LinkedHashMap<>();
        jose.put("typ", "JWT");
        jose.put("kid", keyId);
        jose.put("alg", "RS256");
        String encodedJose = replayEncode(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(jose)));
        String encodedClaims = replayEncode(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(claims)));
        String signingInput = encodedJose + "." + encodedClaims;
        return signingInput + "." + replayEncode(replaySign(
                signer, signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    static void assertReplayOwnerProof(ObjectMapper mapper,
            Hrm013IdentityTaskDispatchStore.Admission replay,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            ReplayKeyMaterial currentOwner, ReplayKeyMaterial formerOwner,
            String currentCertificate, Instant now) throws Exception {
        String[] segments = replay.receiptProof().split("\\.", -1);
        assertThat(segments).hasSize(3);
        var decoder = Base64.getUrlDecoder();
        var header = mapper.readTree(decoder.decode(segments[0]));
        var claims = mapper.readTree(decoder.decode(segments[1]));
        assertThat(header.path("typ").asText())
                .isEqualTo("DWP-HRM013-RECEIPT-JWT");
        assertThat(header.path("kid").asText())
                .isEqualTo(currentOwner.jwk().kid());
        assertThat(claims.path("tenantGrantRevision").asLong())
                .isEqualTo(authority.tenantGrantRevision());
        assertThat(claims.path("authorizationRevision").asLong())
                .isEqualTo(authority.authorizationRevision());
        assertThat(claims.path("responseBodySha256").asText())
                .isEqualTo(Hrm013WorkloadProofV1.sha256(
                        replay.exactReceiptBody()));
        assertThat(claims.path("dispatchEventId").asText())
                .isEqualTo(replay.receipt().dispatchEventId().toString());
        assertThat(claims.path("taskPublicId").asText())
                .isEqualTo(replay.receipt().taskPublicId().toString());
        assertThat(claims.path("correlationId").asText())
                .isEqualTo(replay.receipt().correlationId().toString());
        assertThat(claims.path("cnf").path("x5t#S256").asText())
                .isEqualTo(currentCertificate);
        assertThat(Instant.ofEpochSecond(claims.path("exp").asLong()))
                .isAfter(now);
        byte[] signingInput = (segments[0] + "." + segments[1])
                .getBytes(StandardCharsets.US_ASCII);
        byte[] signature = decoder.decode(segments[2]);
        assertThat(replayVerify(currentOwner.keyPair(), signingInput, signature))
                .isTrue();
        assertThat(replayVerify(formerOwner.keyPair(), signingInput, signature))
                .isFalse();
    }

    static Instant replayProofExpiry(
            ObjectMapper mapper, String compact) throws Exception {
        String[] segments = compact.split("\\.", -1);
        assertThat(segments).hasSize(3);
        return Instant.ofEpochSecond(mapper.readTree(
                Base64.getUrlDecoder().decode(segments[1]))
                .path("exp").asLong());
    }

    static ReplayKeyMaterial replayKey(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        return new ReplayKeyMaterial(keyPair,
                new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                        "RS256", replayUnsigned(
                                publicKey.getPublicExponent().toByteArray()),
                        keyId, "RSA", replayUnsigned(
                                publicKey.getModulus().toByteArray()), "sig"));
    }

    static byte[] replaySign(KeyPair keyPair, byte[] input)
            throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input);
        return signature.sign();
    }

    static boolean replayVerify(KeyPair keyPair, byte[] input,
            byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(keyPair.getPublic());
        signature.update(input);
        return signature.verify(signatureBytes);
    }

    static String replayThumbprint(String value) {
        try {
            return replayEncode(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String replayUnsigned(byte[] value) {
        int offset = value.length > 1 && value[0] == 0 ? 1 : 0;
        return replayEncode(java.util.Arrays.copyOfRange(
                value, offset, value.length));
    }

    static String replayEncode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    record ReplayKeyMaterial(KeyPair keyPair,
            HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) { }

    static final class MutableReplayClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        private MutableReplayClock(Instant now) {
            this(new AtomicReference<>(now), ZoneOffset.UTC);
        }

        private MutableReplayClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId requestedZone) {
            return new MutableReplayClock(now, requestedZone);
        }

        @Override public Instant instant() { return now.get(); }
    }

    record IdentityFixture(long tenantId, long otherTenantId,
            long subjectUserId, long otherTenantUserId, long otherPersonUserId,
            long providerUserId) { }

}
