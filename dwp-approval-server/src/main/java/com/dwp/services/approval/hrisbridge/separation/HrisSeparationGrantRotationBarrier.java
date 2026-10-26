package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Produces one exact, role-scoped zero-pending readiness item for the Approval
 * side of the HRM-013 three-binding rotation group.
 */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class HrisSeparationGrantRotationBarrier {
    static final String REQUESTER_BINDING = "SVC-AUT-HRM-013";
    private static final String REQUESTER_PURPOSE =
            "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE";
    private static final String SCHEMA =
            "HrisServiceGrantRotationReadinessReceiptV1";
    private static final String PROOF_TYPE =
            "DWP-HRM013-ROTATION-READINESS-JWT";
    private static final String PROOF_PURPOSE =
            "HRIS_SERVICE_GRANT_ROTATION_READINESS";

    record ReadinessItem(byte[] receiptBytes, byte[] proofClaimBytes,
            String readinessProof) {
        ReadinessItem {
            receiptBytes = receiptBytes.clone();
            proofClaimBytes = proofClaimBytes.clone();
            Objects.requireNonNull(readinessProof);
        }
        @Override public byte[] receiptBytes() { return receiptBytes.clone(); }
        @Override public byte[] proofClaimBytes() { return proofClaimBytes.clone(); }
    }

    private record Scan(long pendingCount, Instant oldestPendingAt,
            Instant observedAt) { }
    private record GrantProjection(UUID grantId,
            long tenantRevision, long authorizationRevision, String decision,
            String signingKeyId) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationRlsScope scope;
    private final TransactionTemplate repeatableRead;
    private final HrisSeparationWorkloadCredentialProvider credentials;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final ObjectMapper mapper;

    HrisSeparationGrantRotationBarrier(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            HrisSeparationWorkloadCredentialProvider credentials,
            AuthHrm013ServiceAuthorityClient authority, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.scope = new HrisSeparationRlsScope(jdbc);
        this.repeatableRead = new TransactionTemplate(manager);
        this.repeatableRead.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.credentials = Objects.requireNonNull(credentials);
        this.authority = Objects.requireNonNull(authority);
        this.mapper = Objects.requireNonNull(mapper);
    }

    ReadinessItem attest(long tenantId, String bindingId, UUID correlationId) {
        Objects.requireNonNull(correlationId);
        var snapshot = credentials.current(tenantId, bindingId,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING);
        var lease = snapshot.lease();
        requireApprovalRole(lease);
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                lease.tenantId(), HrisSeparationPersistenceModels.APPROVAL_WORKLOAD,
                lease.callerWorkloadId(), lease.ownerWorkloadId(), lease.bindingId(),
                lease.purpose(),
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                lease.tenantGrantRevision(), lease.authorizationRevision(),
                correlationId, null);
        var response = authority.resolve(request, snapshot);
        if (response.status()
                    != HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED
                || response.readinessObservation() == null) {
            throw HrisSeparationProtocolException.unavailable();
        }
        var observation = response.readinessObservation();
        requireObservation(lease, observation);
        Scan scan = repeatableRead.execute(status -> scan(lease, observation));
        if (scan == null || scan.pendingCount() != 0
                || scan.oldestPendingAt() != null
                || scan.observedAt().isBefore(observation.readinessEligibleAt())) {
            throw HrisSeparationProtocolException.unavailable();
        }
        return sign(snapshot, observation, scan, correlationId);
    }

    private Scan scan(HrisSeparationWorkloadCredentialProvider.Lease lease,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation) {
        scope.bind(lease.tenantId());
        var grantRows = jdbc.query("""
                SELECT grant_id,tenant_grant_revision,
                       authorization_revision,decision,approval_signing_key_id
                  FROM apr_hrm013_tenant_grants
                 WHERE tenant_id=:tenant
                 ORDER BY grant_revision DESC LIMIT 1
                 FOR SHARE
                """, new MapSqlParameterSource("tenant", lease.tenantId()),
                (row, number) -> new GrantProjection(
                        row.getObject("grant_id", UUID.class),
                        row.getLong("tenant_grant_revision"),
                        row.getLong("authorization_revision"),
                        row.getString("decision"),
                        row.getString("approval_signing_key_id")));
        if (grantRows.size() != 1) throw HrisSeparationProtocolException.unavailable();
        GrantProjection grant = grantRows.getFirst();
        if (!lease.grantSetId().equals(grant.grantId())
                || lease.tenantGrantRevision() != grant.tenantRevision()
                || lease.authorizationRevision() != grant.authorizationRevision()
                || !"ADMIT".equals(grant.decision())
                || !lease.businessSigningKeyId().equals(grant.signingKeyId())) {
            throw HrisSeparationProtocolException.unavailable();
        }
        Pending pending = pending(lease);
        Instant observedAt = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT transaction_timestamp()", Map.of(), Timestamp.class)).toInstant();
        return new Scan(pending.count(), pending.oldest(), observedAt);
    }

    private record Pending(long count, Instant oldest) { }

    private Pending pending(HrisSeparationWorkloadCredentialProvider.Lease lease) {
        if (HrisSeparationPersistenceModels.REQUEST_BINDING.equals(lease.bindingId())
                && lease.serviceRole()
                    == HrisSeparationWorkloadCredentialProvider.ServiceRole.OWNER) {
            return queryPending("""
                    SELECT count(*) AS pending,min(binding.created_at) AS oldest
                      FROM apr_hrm013_waiver_request_bindings binding
                      JOIN apr_requests request
                        ON request.tenant_id=binding.tenant_id
                       AND request.request_id=binding.approval_request_id
                      LEFT JOIN apr_hrm013_waiver_decision_outbox outbox
                        ON outbox.tenant_id=binding.tenant_id
                       AND outbox.approval_request_id=binding.approval_request_id
                     WHERE binding.tenant_id=:tenant AND binding.grant_id=:grant
                       AND binding.tenant_grant_revision=:tenantRevision
                       AND binding.authorization_revision=:authorizationRevision
                       AND (request.status NOT IN ('APPROVED','REJECTED')
                            OR outbox.outbox_id IS NULL
                            OR outbox.delivery_state<>'DELIVERED')
                    """, lease);
        }
        if (HrisSeparationPersistenceModels.CALLBACK_BINDING.equals(lease.bindingId())
                && lease.serviceRole()
                    == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER) {
            return queryPending("""
                    SELECT count(*) AS pending,min(created_at) AS oldest
                      FROM apr_hrm013_waiver_decision_outbox
                     WHERE tenant_id=:tenant AND grant_id=:grant
                       AND tenant_grant_revision=:tenantRevision
                       AND authorization_revision=:authorizationRevision
                       AND delivery_state<>'DELIVERED'
                    """, lease);
        }
        if (REQUESTER_BINDING.equals(lease.bindingId())
                && REQUESTER_PURPOSE.equals(lease.purpose())
                && lease.serviceRole()
                    == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER) {
            // DEPENDENT_READ is synchronous and creates no Approval row. The
            // Auth observation's 65-second quiet interval closes in-flight work.
            return new Pending(0, null);
        }
        throw HrisSeparationProtocolException.denied();
    }

    private Pending queryPending(String sql,
            HrisSeparationWorkloadCredentialProvider.Lease lease) {
        var values = jdbc.query(sql, parameters(lease), (row, number) ->
                new Pending(row.getLong("pending"),
                        row.getTimestamp("oldest") == null ? null
                                : row.getTimestamp("oldest").toInstant()));
        if (values.size() != 1) throw HrisSeparationProtocolException.unavailable();
        return values.getFirst();
    }

    private ReadinessItem sign(HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            Scan scan, UUID correlationId) {
        var lease = snapshot.lease();
        Instant issuedAt = scan.observedAt().getNano() == 0
                ? scan.observedAt() : scan.observedAt().plusSeconds(1)
                        .truncatedTo(ChronoUnit.SECONDS);
        Instant leaseLimit = lease.expiresAt().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(300).isBefore(leaseLimit)
                ? issuedAt.plusSeconds(300) : leaseLimit;
        if (!expiresAt.isAfter(issuedAt)) {
            throw HrisSeparationProtocolException.unavailable();
        }

        ObjectNode receipt = common(observation, lease.grantRevision(),
                snapshot.lease(), correlationId);
        receipt.put("schemaName", SCHEMA); receipt.put("schemaVersion", 1);
        receipt.put("pendingCount", 0);
        receipt.putNull("oldestPendingAt");
        receipt.put("observedAt", scan.observedAt().toString());
        receipt.put("expiresAt", expiresAt.toString());
        String evidence = Hrm013WorkloadProofV1.sha256(canonical(receipt));
        receipt.put("evidenceSha256", evidence);
        byte[] receiptBytes = canonical(receipt);

        ObjectNode claims = common(observation, lease.grantRevision(),
                snapshot.lease(), correlationId);
        String workload = serviceWorkload(lease);
        claims.put("iss", workload); claims.put("aud", "dwp-auth-server");
        claims.put("sub", workload); claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        UUID jti = UUID.randomUUID(); UUID nonce;
        do { nonce = UUID.randomUUID(); } while (jti.equals(nonce));
        claims.put("jti", jti.toString());
        claims.put("requestNonce", nonce.toString());
        claims.put("evidenceSha256", evidence);
        claims.put("proofPurpose", PROOF_PURPOSE);
        byte[] claimBytes = canonical(claims);
        return new ReadinessItem(receiptBytes, claimBytes,
                detached(snapshot, claimBytes));
    }

    private ObjectNode common(
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            long grantRevision, HrisSeparationWorkloadCredentialProvider.Lease lease,
            UUID correlationId) {
        ObjectNode value = mapper.createObjectNode();
        value.put("tenantId", observation.tenantId());
        value.put("bindingId", observation.bindingId());
        value.put("callerWorkloadId", observation.callerWorkloadId());
        value.put("ownerWorkloadId", observation.ownerWorkloadId());
        value.put("bindingPurpose", observation.purpose());
        value.put("serviceWorkloadId", serviceWorkload(lease));
        value.put("attestingServiceRole", observation.attestingServiceRole().name());
        value.put("oldGrantSetId", observation.grantSetId().toString());
        value.put("quiesceFenceId", observation.quiesceFenceId().toString());
        value.put("quiesceRevision", observation.quiesceRevision());
        value.put("quiesceEffectiveAt", observation.quiesceEffectiveAt().toString());
        value.put("observedDrainActivityRevision", observation.drainActivityRevision());
        value.put("drainQuietSince", observation.drainQuietSince().toString());
        value.put("readinessEligibleAt", observation.readinessEligibleAt().toString());
        value.put("readinessObservationSha256",
                observation.readinessObservationSha256());
        value.put("observedGrantRevision", grantRevision);
        value.put("tenantGrantRevision", observation.tenantGrantRevision());
        value.put("authorizationRevision", observation.authorizationRevision());
        value.put("correlationId", correlationId.toString());
        return value;
    }

    private String detached(HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            byte[] claims) {
        try {
            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "RS256"); header.put("typ", PROOF_TYPE);
            header.put("kid", snapshot.lease().businessSigningKeyId());
            String protectedHeader = base64(canonical(header));
            String signingInput = protectedHeader + "." + base64(claims);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(snapshot.businessPrivateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return protectedHeader + ".." + base64(signer.sign());
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private byte[] canonical(ObjectNode node) {
        try {
            return Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                    mapper, mapper.writeValueAsBytes(node));
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private static void requireApprovalRole(
            HrisSeparationWorkloadCredentialProvider.Lease lease) {
        if (!HrisSeparationPersistenceModels.APPROVAL_WORKLOAD.equals(
                    serviceWorkload(lease))
                || (!HrisSeparationPersistenceModels.REQUEST_BINDING.equals(
                            lease.bindingId())
                    && !HrisSeparationPersistenceModels.CALLBACK_BINDING.equals(
                            lease.bindingId())
                    && !REQUESTER_BINDING.equals(lease.bindingId()))) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    private static void requireObservation(
            HrisSeparationWorkloadCredentialProvider.Lease lease,
            HrisServiceGrantAuthorityV1.ReadinessObservation value) {
        HrisServiceGrantAuthorityV1.ServiceRole role = lease.serviceRole()
                == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER
                ? HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                : HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        if (value.tenantId() != lease.tenantId()
                || !value.grantSetId().equals(lease.grantSetId())
                || value.tenantGrantRevision() != lease.tenantGrantRevision()
                || value.authorizationRevision() != lease.authorizationRevision()
                || !value.callerWorkloadId().equals(lease.callerWorkloadId())
                || !value.ownerWorkloadId().equals(lease.ownerWorkloadId())
                || !value.bindingId().equals(lease.bindingId())
                || !value.purpose().equals(lease.purpose())
                || value.attestingServiceRole() != role) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private static String serviceWorkload(
            HrisSeparationWorkloadCredentialProvider.Lease lease) {
        return lease.serviceRole()
                == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER
                ? lease.callerWorkloadId() : lease.ownerWorkloadId();
    }

    private static MapSqlParameterSource parameters(
            HrisSeparationWorkloadCredentialProvider.Lease lease) {
        return new MapSqlParameterSource("tenant", lease.tenantId())
                .addValue("grant", lease.grantSetId())
                .addValue("tenantRevision", lease.tenantGrantRevision())
                .addValue("authorizationRevision", lease.authorizationRevision());
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
