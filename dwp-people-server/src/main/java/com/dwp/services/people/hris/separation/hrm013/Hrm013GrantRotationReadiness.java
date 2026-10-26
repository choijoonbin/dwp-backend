package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
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

/** Creates Auth-verifiable, role-specific zero-pending readiness evidence. */
public final class Hrm013GrantRotationReadiness {
    static final String SCHEMA = "HrisServiceGrantRotationReadinessReceiptV1";
    static final String PROOF_TYPE = "DWP-HRM013-ROTATION-READINESS-JWT";
    static final String PROOF_PURPOSE = "HRIS_SERVICE_GRANT_ROTATION_READINESS";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate repeatableRead;
    private final ReadinessAuthority authority;
    private final Clock clock;
    private final ObjectMapper mapper;

    public Hrm013GrantRotationReadiness(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ReadinessAuthority authority, Clock clock) {
        this(jdbc, manager, authority, clock, new ObjectMapper());
    }

    public Hrm013GrantRotationReadiness(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ReadinessAuthority authority, Clock clock, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
        repeatableRead = new TransactionTemplate(manager);
        repeatableRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(30);
    }

    public ReadinessItem observe(ReadinessRequest request) {
        requireRequest(request);
        var before = requireRemote(request,
                authority.observe(request.query(), request.correlationId()));
        LocalDrain finalScan = scan(request.query());
        requireReady(finalScan, before);
        var after = requireRemote(request,
                authority.observe(request.query(), request.correlationId()));
        if (!before.equals(after)) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        ReadinessItem item = signedItem(request, after, finalScan);
        audit(request, item, finalScan.observedAt());
        return item;
    }

    private void requireRequest(ReadinessRequest request) {
        Objects.requireNonNull(request);
        var query = request.query(); var signing = request.signingAuthority();
        String roleWorkload = request.serviceRole()
                == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? signing.grant().callerWorkloadId() : signing.ownerWorkloadId();
        String keyId = request.serviceRole() == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? signing.callerSigningKeyId() : signing.ownerSigningKeyId();
        HrisServiceGrantAuthorityV1.ServiceRole expectedRole =
                "dwp-people-server".equals(signing.grant().callerWorkloadId())
                        ? HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                        : "dwp-people-server".equals(signing.ownerWorkloadId())
                                ? HrisServiceGrantAuthorityV1.ServiceRole.OWNER : null;
        if (request.serviceRole() != expectedRole
                || !"dwp-people-server".equals(roleWorkload)
                || !request.signer().keyId().equals(keyId)
                || !signing.callerSpiffeId().equals("spiffe://dwp/service/"
                + signing.grant().callerWorkloadId())
                || !signing.ownerSpiffeId().equals("spiffe://dwp/service/"
                + signing.ownerWorkloadId())
                || signing.grant().tenantId() != query.tenantId()
                || !signing.grant().bindingId().equals(query.bindingId())
                || !signing.grant().callerWorkloadId().equals(query.callerWorkloadId())
                || !signing.grant().purpose().equals(query.purpose())
                || signing.grant().tenantGrantRevision()
                != query.signedTenantGrantRevision()
                || signing.grant().authorizationRevision()
                != query.signedAuthorizationRevision()
                || query.resolutionMode()
                != HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE
                || query.drainFields() != null) throw Hrm013SafeErrorV1.authorityDenied();
    }

    private HrisServiceGrantAuthorityV1.ReadinessObservation requireRemote(
            ReadinessRequest request, HrisServiceGrantAuthorityV1.ResolveResponse response) {
        if (response == null || response.status()
                != HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED
                || !response.correlationId().equals(request.correlationId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var value = response.readinessObservation();
        var query = request.query(); var signing = request.signingAuthority();
        if (value.tenantId() != query.tenantId()
                || !value.grantSetId().equals(signing.grantSetId())
                || !value.bindingId().equals(query.bindingId())
                || !value.callerWorkloadId().equals(query.callerWorkloadId())
                || !value.ownerWorkloadId().equals(signing.ownerWorkloadId())
                || !value.purpose().equals(query.purpose())
                || value.tenantGrantRevision() != query.signedTenantGrantRevision()
                || value.authorizationRevision() != query.signedAuthorizationRevision()
                || value.attestingServiceRole() != request.serviceRole()
                || value.readinessEligibleAt().isAfter(clock.instant())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return value;
    }

    private LocalDrain scan(Hrm013ServiceAuthorityPort.Query query) {
        LocalDrain result = repeatableRead.execute(status -> scanInTransaction(query));
        if (result == null) throw Hrm013SafeErrorV1.invalidState();
        return result;
    }

    private LocalDrain scanInTransaction(Hrm013ServiceAuthorityPort.Query query) {
        Hrm013TenantScope.setLocal(jdbc, query.tenantId());
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tenant", query.tenantId()).addValue("binding", query.bindingId())
                .addValue("caller", query.callerWorkloadId())
                .addValue("purpose", query.purpose());
        String pending; String activity;
        if ("SVC-PEP-APR-002".equals(query.bindingId())) {
            pending = "SELECT count(*) count,MIN(available_at) oldest FROM "
                    + "ppl_separation_waiver_request_outbox WHERE tenant_id=:tenant "
                    + "AND dispatch_state <> 'ACCEPTED'";
            activity = "SELECT MAX(updated_at) FROM ppl_separation_waiver_request_outbox "
                    + "WHERE tenant_id=:tenant";
        } else if (java.util.Set.of("SVC-PEP-TIM-004", "SVC-PEP-PAY-002",
                "SVC-AUT-HRM-014").contains(query.bindingId())) {
            String type = switch (query.bindingId()) {
                case "SVC-PEP-TIM-004" ->
                        "SeparationTimeFinalizationTaskDispatchRequested.v1";
                case "SVC-PEP-PAY-002" ->
                        "SeparationPayrollSettlementTaskDispatchRequested.v1";
                default -> "SeparationIdentityAccessRevocationTaskDispatchRequested.v1";
            }; p.addValue("eventType", type);
            pending = "SELECT count(*) count,MIN(occurred_at) oldest FROM "
                    + "sys_people_outbox_events WHERE tenant_id=:tenant "
                    + "AND aggregate_type='SEPARATION_TASK' AND event_type=:eventType "
                    + "AND published_at IS NULL";
            activity = "SELECT MAX(GREATEST(occurred_at,COALESCE(published_at,occurred_at),"
                    + "COALESCE(dead_lettered_at,occurred_at))) FROM sys_people_outbox_events "
                    + "WHERE tenant_id=:tenant AND aggregate_type='SEPARATION_TASK' "
                    + "AND event_type=:eventType";
        } else if (java.util.Set.of("SVC-PEP-HRM-008", "SVC-PEP-HRM-009",
                "SVC-PEP-HRM-010").contains(query.bindingId())) {
            pending = "SELECT count(*) count,MIN(created_at) oldest FROM "
                    + "ppl_separation_callback_authorities WHERE tenant_id=:tenant "
                    + "AND binding_id=:binding AND caller_workload_id=:caller "
                    + "AND purpose_code=:purpose AND decision='ADMIT' AND closed_at IS NULL";
            activity = "SELECT MAX(GREATEST(created_at,COALESCE(closed_at,created_at))) FROM "
                    + "ppl_separation_callback_authorities WHERE tenant_id=:tenant "
                    + "AND binding_id=:binding AND caller_workload_id=:caller "
                    + "AND purpose_code=:purpose";
        } else throw Hrm013SafeErrorV1.authorityDenied();
        Pending count = jdbc.queryForObject(pending, p, (row, ignored) -> new Pending(
                row.getInt("count"), row.getTimestamp("oldest") == null ? null
                : row.getTimestamp("oldest").toInstant()));
        Instant observed = jdbc.queryForObject("SELECT transaction_timestamp()", p,
                (row, ignored) -> row.getTimestamp(1).toInstant());
        Instant quiet = jdbc.queryForObject(activity, p, (row, ignored) ->
                row.getTimestamp(1) == null ? Instant.EPOCH : row.getTimestamp(1).toInstant());
        if (count == null || observed == null || quiet == null) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        return new LocalDrain(count.count(), count.oldest(), quiet, observed);
    }

    private static void requireReady(
            LocalDrain value,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation) {
        if (value.pendingCount() != 0 || value.oldestPendingAt() != null
                || value.observedAt().isBefore(observation.readinessEligibleAt())) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private ReadinessItem signedItem(ReadinessRequest request,
            HrisServiceGrantAuthorityV1.ReadinessObservation remote,
            LocalDrain local) {
        Instant issued = local.observedAt().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plusSeconds(120);
        Map<String, Object> receipt = evidence(request, remote);
        receipt.put("schemaName", SCHEMA); receipt.put("schemaVersion", 1);
        receipt.put("pendingCount", 0); receipt.put("oldestPendingAt", null);
        receipt.put("observedAt", local.observedAt().toString());
        receipt.put("expiresAt", expires.toString());
        receipt.put("correlationId", request.correlationId().toString());
        String evidence = Hrm013WorkloadProofV1.sha256(canonical(receipt));
        receipt.put("evidenceSha256", evidence);
        byte[] receiptBytes = canonical(receipt);
        Map<String, Object> claims = evidence(request, remote);
        String workload = request.serviceRole()
                == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? remote.callerWorkloadId() : remote.ownerWorkloadId();
        claims.put("iss", workload); claims.put("aud", "dwp-auth-server");
        claims.put("sub", workload); claims.put("iat", issued.getEpochSecond());
        claims.put("nbf", issued.getEpochSecond()); claims.put("exp", expires.getEpochSecond());
        UUID proofId = UUID.randomUUID();
        UUID requestNonce;
        do requestNonce = UUID.randomUUID(); while (requestNonce.equals(proofId));
        claims.put("jti", proofId.toString());
        claims.put("requestNonce", requestNonce.toString());
        claims.put("evidenceSha256", evidence);
        claims.put("correlationId", request.correlationId().toString());
        claims.put("proofPurpose", PROOF_PURPOSE);
        byte[] claimBytes = canonical(claims);
        byte[] header = canonical(Map.of("alg", "RS256", "typ", PROOF_TYPE,
                "kid", request.signer().keyId()));
        String encodedHeader = base64(header);
        String input = encodedHeader + "." + base64(claimBytes);
        try {
            String proof = encodedHeader + ".." + base64(request.signer().sign(
                    input.getBytes(StandardCharsets.US_ASCII)));
            return new ReadinessItem(receiptBytes, claimBytes, proof, evidence,
                    request.correlationId());
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private Map<String, Object> evidence(ReadinessRequest request,
            HrisServiceGrantAuthorityV1.ReadinessObservation value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", value.tenantId()); result.put("bindingId", value.bindingId());
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

    private void audit(ReadinessRequest request, ReadinessItem item, Instant occurredAt) {
        repeatableRead.executeWithoutResult(status -> {
            Hrm013TenantScope.setLocal(jdbc, request.query().tenantId());
            jdbc.update("""
                    INSERT INTO sys_people_audit_events(
                        audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                        target_id,outcome,correlation_id,after_snapshot,occurred_at)
                    VALUES(:audit,:tenant,'SERVICE','dwp-people-server',
                        'HRM013_ROTATION_READINESS_OBSERVED','SERVICE_GRANT',:target,
                        'SUCCESS',:correlation,
                        CAST(:snapshot AS jsonb),:occurred)
                    """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                    .addValue("tenant", request.query().tenantId())
                    .addValue("target", request.query().bindingId())
                    .addValue("correlation", request.correlationId())
                    .addValue("snapshot", new String(
                            item.receiptBytes(), StandardCharsets.UTF_8))
                    .addValue("occurred", Timestamp.from(occurredAt)));
        });
    }

    @FunctionalInterface public interface ReadinessAuthority {
        HrisServiceGrantAuthorityV1.ResolveResponse observe(
                Hrm013ServiceAuthorityPort.Query oldLeaseQuery, UUID correlationId);
    }
    public interface ReadinessSigner {
        String keyId();
        byte[] sign(byte[] input) throws Exception;
    }
    public record ReadinessRequest(
            Hrm013ServiceAuthorityPort.Query query,
            Hrm013ServiceAuthorityPort.SigningAuthority signingAuthority,
            HrisServiceGrantAuthorityV1.ServiceRole serviceRole,
            ReadinessSigner signer, UUID correlationId) {
        public ReadinessRequest {
            Objects.requireNonNull(query); Objects.requireNonNull(signingAuthority);
            Objects.requireNonNull(serviceRole); Objects.requireNonNull(signer);
            Objects.requireNonNull(correlationId);
        }
    }
    private record Pending(int count, Instant oldest) { }
    public record LocalDrain(int pendingCount, Instant oldestPendingAt,
            Instant quietSince, Instant observedAt) {
        public LocalDrain {
            if (pendingCount < 0 || (pendingCount == 0) != (oldestPendingAt == null))
                throw new IllegalArgumentException("pending evidence");
            Objects.requireNonNull(quietSince); Objects.requireNonNull(observedAt);
        }
        boolean sameEvidence(LocalDrain other) {
            return other != null && pendingCount == other.pendingCount
                    && Objects.equals(oldestPendingAt, other.oldestPendingAt)
                    && quietSince.equals(other.quietSince);
        }
    }
    public record ReadinessItem(byte[] receiptBytes, byte[] proofClaimBytes,
            String readinessProof, String evidenceSha256, UUID correlationId) {
        public ReadinessItem {
            receiptBytes = receiptBytes.clone(); proofClaimBytes = proofClaimBytes.clone();
            Objects.requireNonNull(readinessProof); Objects.requireNonNull(correlationId);
            if (!evidenceSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("evidenceSha256");
        }
        @Override public byte[] receiptBytes() { return receiptBytes.clone(); }
        @Override public byte[] proofClaimBytes() { return proofClaimBytes.clone(); }
    }
}
