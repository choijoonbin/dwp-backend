package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityWorkloadCredentialProvider;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease;
import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityWorkloadCredentialProvider.CredentialRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Produces the two Auth-owned halves of the identity rotation-readiness pair. */
public final class Hrm013IdentityGrantRotationReadiness {
    static final String SCHEMA = "HrisServiceGrantRotationReadinessReceiptV1";
    static final String PROOF_TYPE = "DWP-HRM013-ROTATION-READINESS-JWT";
    static final String PROOF_PURPOSE = "HRIS_SERVICE_GRANT_ROTATION_READINESS";
    private static final String AUTH_WORKLOAD = "dwp-auth-server";
    private static final String TENANT_CONTEXT = "dwp.auth.hrm013.tenant";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate repeatableRead;
    private final ReadinessAuthority authority;
    private final Clock clock;
    private final ObjectMapper mapper;

    public Hrm013IdentityGrantRotationReadiness(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ReadinessAuthority authority, Clock clock) {
        this(jdbc, manager, authority, clock, new ObjectMapper());
    }

    public Hrm013IdentityGrantRotationReadiness(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ReadinessAuthority authority, Clock clock, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        repeatableRead = new TransactionTemplate(
                Objects.requireNonNull(manager, "manager"));
        repeatableRead.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(30);
    }

    /**
     * Observes either Auth OWNER dispatch readiness or Auth CALLER receipt readiness.
     * The People service supplies the complementary role receipt for each target.
     */
    public ReadinessItem observe(
            HrisServiceGrantAuthorityV1.ResolveRequest query,
            HrisServiceGrantAuthorityV1.GrantAuthority signingAuthority,
            HrisServiceGrantAuthorityV1.ServiceRole serviceRole,
            UUID correlationId,
            Hrm013IdentityWorkloadCredentialProvider credentials) {
        Objects.requireNonNull(credentials, "credentials");
        CredentialRole credentialRole = serviceRole
                == HrisServiceGrantAuthorityV1.ServiceRole.OWNER
                        ? CredentialRole.OWNER : CredentialRole.CALLER;
        AtomicGrantCredentialLease lease = credentialRole == CredentialRole.OWNER
                ? credentials.requireOwnerCurrent(signingAuthority)
                : credentials.requireCurrent(signingAuthority);
        return observe(new ReadinessRequest(query, signingAuthority, serviceRole,
                new ReadinessSigner() {
                    @Override public String keyId() {
                        return lease.businessSigningKeyId();
                    }
                    @Override public byte[] sign(byte[] exactInput) throws Exception {
                        return lease.businessPrivateKeyHandle().sign(exactInput);
                    }
                    @Override public void recheckCurrent(
                            HrisServiceGrantAuthorityV1.GrantAuthority authority,
                            HrisServiceGrantAuthorityV1.ServiceRole role) {
                        if (role != serviceRole) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        credentials.recheckCurrent(authority, lease);
                    }
                }, correlationId));
    }

    public ReadinessItem observe(ReadinessRequest request) {
        requireRequest(request);
        request.signer().recheckCurrent(
                request.signingAuthority(), request.serviceRole());
        var before = requireObservation(request,
                authority.observe(request.query(), request.correlationId()));
        LocalDrain local = scan(request);
        requireReady(local, before);
        request.signer().recheckCurrent(
                request.signingAuthority(), request.serviceRole());
        var after = requireObservation(request,
                authority.observe(request.query(), request.correlationId()));
        if (!before.equals(after)) throw Hrm013SafeErrorV1.invalidState();

        SignedReadiness signed = signedItem(request, after, local);
        request.signer().recheckCurrent(
                request.signingAuthority(), request.serviceRole());
        audit(request, signed.evidenceSha256(), after.quiesceFenceId(),
                local.observedAt());
        return signed.item();
    }

    private static void requireRequest(ReadinessRequest request) {
        Objects.requireNonNull(request, "request");
        var query = request.query();
        var signing = request.signingAuthority();
        HrisServiceGrantAuthorityV1.TargetTuple expectedTarget;
        HrisServiceGrantAuthorityV1.ServiceRole expectedRole;
        if (query.targetTuple().equals(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH)) {
            expectedTarget = HrisServiceGrantQuiesceService.IDENTITY_DISPATCH;
            expectedRole = HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        } else if (query.targetTuple().equals(
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT)) {
            expectedTarget = HrisServiceGrantQuiesceService.IDENTITY_RECEIPT;
            expectedRole = HrisServiceGrantAuthorityV1.ServiceRole.CALLER;
        } else {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        String serviceWorkload = expectedRole == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? signing.callerWorkloadId() : signing.ownerWorkloadId();
        String keyId = expectedRole == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? signing.callerSigningKeyId() : signing.ownerSigningKeyId();
        if (!expectedTarget.equals(query.targetTuple())
                || request.serviceRole() != expectedRole
                || !AUTH_WORKLOAD.equals(query.requestingWorkloadId())
                || !AUTH_WORKLOAD.equals(serviceWorkload)
                || !keyId.equals(request.signer().keyId())
                || signing.tenantId() != query.tenantId()
                || !signing.bindingId().equals(query.targetBindingId())
                || !signing.callerWorkloadId().equals(query.targetCallerWorkloadId())
                || !signing.ownerWorkloadId().equals(query.targetOwnerWorkloadId())
                || !signing.purpose().equals(query.targetPurpose())
                || !signing.callerSpiffeId().equals(
                        "spiffe://dwp/service/" + signing.callerWorkloadId())
                || !signing.ownerSpiffeId().equals(
                        "spiffe://dwp/service/" + signing.ownerWorkloadId())
                || signing.tenantGrantRevision() != query.signedTenantGrantRevision()
                || signing.authorizationRevision() != query.signedAuthorizationRevision()
                || query.resolutionMode()
                        != HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE
                || query.drainFields() != null
                || !query.correlationId().equals(request.correlationId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private HrisServiceGrantAuthorityV1.ReadinessObservation requireObservation(
            ReadinessRequest request, HrisServiceGrantAuthorityV1.ResolveResponse response) {
        if (response == null
                || response.status() != HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED
                || !response.correlationId().equals(request.correlationId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var value = response.readinessObservation();
        var query = request.query();
        var signing = request.signingAuthority();
        if (value == null || value.tenantId() != query.tenantId()
                || !value.grantSetId().equals(signing.grantSetId())
                || !value.bindingId().equals(query.targetBindingId())
                || !value.callerWorkloadId().equals(query.targetCallerWorkloadId())
                || !value.ownerWorkloadId().equals(query.targetOwnerWorkloadId())
                || !value.purpose().equals(query.targetPurpose())
                || value.tenantGrantRevision() != query.signedTenantGrantRevision()
                || value.authorizationRevision() != query.signedAuthorizationRevision()
                || value.attestingServiceRole() != request.serviceRole()
                || value.readinessEligibleAt().isAfter(clock.instant())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return value;
    }

    private LocalDrain scan(ReadinessRequest request) {
        LocalDrain result = repeatableRead.execute(status -> scanInTransaction(request));
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    private LocalDrain scanInTransaction(ReadinessRequest request) {
        long tenantId = request.query().tenantId();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenant", tenantId);
        jdbc.queryForObject("SELECT set_config(:context, CAST(:tenant AS TEXT), true)",
                parameters.addValue("context", TENANT_CONTEXT), String.class);

        String pendingSql;
        String activitySql;
        if (request.serviceRole() == HrisServiceGrantAuthorityV1.ServiceRole.OWNER) {
            pendingSql = """
                    SELECT count(*) AS count, MIN(admitted_at) AS oldest
                      FROM auth_hrm013_identity_task_dispatch_inbox
                     WHERE tenant_id=:tenant AND lifecycle_state='RECEIVED'
                    """;
            activitySql = """
                    SELECT MAX(GREATEST(admitted_at,
                               COALESCE(completed_at, admitted_at), next_execution_at))
                      FROM auth_hrm013_identity_task_dispatch_inbox
                     WHERE tenant_id=:tenant
                    """;
        } else {
            pendingSql = """
                    SELECT count(*) AS count, MIN(created_at) AS oldest
                      FROM auth_hrm013_identity_task_receipt_outbox
                     WHERE tenant_id=:tenant AND delivery_state <> 'DELIVERED'
                    """;
            activitySql = """
                    SELECT MAX(GREATEST(created_at, next_attempt_at,
                               COALESCE(delivered_at, created_at)))
                      FROM auth_hrm013_identity_task_receipt_outbox
                     WHERE tenant_id=:tenant
                    """;
        }
        Pending pending = jdbc.queryForObject(pendingSql, parameters,
                (row, ignored) -> new Pending(row.getInt("count"),
                        row.getTimestamp("oldest") == null ? null
                                : row.getTimestamp("oldest").toInstant()));
        Instant observedAt = jdbc.queryForObject(
                "SELECT transaction_timestamp()", parameters,
                (row, ignored) -> row.getTimestamp(1).toInstant());
        Instant quietSince = jdbc.queryForObject(activitySql, parameters,
                (row, ignored) -> row.getTimestamp(1) == null
                        ? Instant.EPOCH : row.getTimestamp(1).toInstant());
        if (pending == null || observedAt == null || quietSince == null) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        return new LocalDrain(
                pending.count(), pending.oldest(), quietSince, observedAt);
    }

    private static void requireReady(LocalDrain local,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation) {
        if (local.pendingCount() != 0 || local.oldestPendingAt() != null
                || local.observedAt().isBefore(observation.readinessEligibleAt())) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private SignedReadiness signedItem(ReadinessRequest request,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            LocalDrain local) {
        Instant issuedAt = local.observedAt().plusSeconds(1)
                .truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(120);
        Map<String, Object> receipt = evidence(request, observation);
        receipt.put("schemaName", SCHEMA);
        receipt.put("schemaVersion", 1);
        receipt.put("pendingCount", 0);
        receipt.put("oldestPendingAt", null);
        receipt.put("observedAt", local.observedAt().toString());
        receipt.put("expiresAt", expiresAt.toString());
        receipt.put("correlationId", request.correlationId().toString());
        String evidenceSha256 = Hrm013WorkloadProofV1.sha256(canonical(receipt));
        receipt.put("evidenceSha256", evidenceSha256);
        byte[] receiptBytes = canonical(receipt);

        Map<String, Object> claims = evidence(request, observation);
        String workload = request.serviceRole()
                == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                        ? observation.callerWorkloadId()
                        : observation.ownerWorkloadId();
        claims.put("iss", workload);
        claims.put("aud", AUTH_WORKLOAD);
        claims.put("sub", workload);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        UUID proofId = UUID.randomUUID();
        UUID nonce;
        do {
            nonce = UUID.randomUUID();
        } while (nonce.equals(proofId));
        claims.put("jti", proofId.toString());
        claims.put("requestNonce", nonce.toString());
        claims.put("evidenceSha256", evidenceSha256);
        claims.put("correlationId", request.correlationId().toString());
        claims.put("proofPurpose", PROOF_PURPOSE);
        byte[] claimBytes = canonical(claims);
        byte[] header = canonical(Map.of(
                "alg", "RS256", "typ", PROOF_TYPE,
                "kid", request.signer().keyId()));
        String encodedHeader = base64(header);
        String signingInput = encodedHeader + "." + base64(claimBytes);
        try {
            byte[] signature = request.signer().sign(
                    signingInput.getBytes(StandardCharsets.US_ASCII));
            return new SignedReadiness(new ReadinessItem(
                    receiptBytes, claimBytes,
                    encodedHeader + ".." + base64(signature)), evidenceSha256);
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private static Map<String, Object> evidence(ReadinessRequest request,
            HrisServiceGrantAuthorityV1.ReadinessObservation value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", value.tenantId());
        result.put("bindingId", value.bindingId());
        result.put("callerWorkloadId", value.callerWorkloadId());
        result.put("ownerWorkloadId", value.ownerWorkloadId());
        result.put("bindingPurpose", value.purpose());
        result.put("serviceWorkloadId", request.serviceRole()
                == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                        ? value.callerWorkloadId() : value.ownerWorkloadId());
        result.put("attestingServiceRole", request.serviceRole().name());
        result.put("oldGrantSetId", request.signingAuthority().grantSetId().toString());
        result.put("quiesceFenceId", value.quiesceFenceId().toString());
        result.put("quiesceRevision", value.quiesceRevision());
        result.put("quiesceEffectiveAt", value.quiesceEffectiveAt().toString());
        result.put("observedDrainActivityRevision", value.drainActivityRevision());
        result.put("drainQuietSince", value.drainQuietSince().toString());
        result.put("readinessEligibleAt", value.readinessEligibleAt().toString());
        result.put("readinessObservationSha256", value.readinessObservationSha256());
        result.put("observedGrantRevision", request.signingAuthority().grantRevision());
        result.put("tenantGrantRevision", value.tenantGrantRevision());
        result.put("authorizationRevision", value.authorizationRevision());
        return result;
    }

    private byte[] canonical(Map<String, Object> value) {
        try {
            return Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                    mapper, mapper.writeValueAsBytes(value));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void audit(ReadinessRequest request, String evidenceSha256,
            UUID fenceId, Instant observedAt) {
        repeatableRead.executeWithoutResult(status -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("context", TENANT_CONTEXT)
                    .addValue("tenant", request.query().tenantId());
            jdbc.queryForObject(
                    "SELECT set_config(:context, CAST(:tenant AS TEXT), true)",
                    parameters, String.class);
            jdbc.update("""
                    INSERT INTO auth_hrm013_identity_task_audit_events(
                      audit_event_id,tenant_id,dispatch_event_id,action_code,
                      outcome_code,evidence_sha256,correlation_id,recorded_at)
                    VALUES(:audit,:tenant,:fence,'ROTATION_READINESS_OBSERVED',
                      'SUCCESS',:evidence,:correlation,:recorded)
                    """, parameters.addValue("audit", UUID.randomUUID())
                    .addValue("fence", fenceId)
                    .addValue("evidence", evidenceSha256)
                    .addValue("correlation", request.correlationId())
                    .addValue("recorded", Timestamp.from(observedAt)));
        });
    }

    @FunctionalInterface
    public interface ReadinessAuthority {
        HrisServiceGrantAuthorityV1.ResolveResponse observe(
                HrisServiceGrantAuthorityV1.ResolveRequest query, UUID correlationId);
    }

    public interface ReadinessSigner {
        String keyId();
        byte[] sign(byte[] exactInput) throws Exception;
        void recheckCurrent(HrisServiceGrantAuthorityV1.GrantAuthority authority,
                HrisServiceGrantAuthorityV1.ServiceRole role);
    }

    public record ReadinessRequest(
            HrisServiceGrantAuthorityV1.ResolveRequest query,
            HrisServiceGrantAuthorityV1.GrantAuthority signingAuthority,
            HrisServiceGrantAuthorityV1.ServiceRole serviceRole,
            ReadinessSigner signer, UUID correlationId) {
        public ReadinessRequest {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(signingAuthority, "signingAuthority");
            Objects.requireNonNull(serviceRole, "serviceRole");
            Objects.requireNonNull(signer, "signer");
            Objects.requireNonNull(correlationId, "correlationId");
        }

    }

    private record Pending(int count, Instant oldest) { }
    private record SignedReadiness(ReadinessItem item, String evidenceSha256) { }

    public record LocalDrain(int pendingCount, Instant oldestPendingAt,
            Instant quietSince, Instant observedAt) {
        public LocalDrain {
            if (pendingCount < 0
                    || (pendingCount == 0) != (oldestPendingAt == null)) {
                throw new IllegalArgumentException("pending evidence");
            }
            Objects.requireNonNull(quietSince, "quietSince");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }
}
