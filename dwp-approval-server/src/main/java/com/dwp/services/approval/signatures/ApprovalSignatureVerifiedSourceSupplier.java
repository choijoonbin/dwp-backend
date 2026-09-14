package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Binding;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Production supplier: native owner source and fresh signed Auth evaluation are both mandatory. */
final class ApprovalSignatureVerifiedSourceSupplier implements ApprovalSignatureAuthority.Source {
    private static final String SESSION = ApprovalSignatureVerifiedSourceSupplier.class.getName() + ".session";
    private final boolean enabled;
    private final Supplier<HttpServletRequest> requests;
    private final ApprovalSignatureInstalledSource installed;
    private final Supplier<ApprovalSignatureSourceKeys> keys;
    private final Supplier<ApprovalSignatureAuthorityClient> transport;
    private final Supplier<ApprovalSignatureSourceRepository> sources;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalSignatureCanonical canonical;
    private final ApprovalStepUpVerifier highRisk;
    private final Clock clock;
    ApprovalSignatureVerifiedSourceSupplier(boolean enabled, Supplier<HttpServletRequest> requests, ApprovalSignatureInstalledSource installed,
            Supplier<ApprovalSignatureSourceKeys> keys, Supplier<ApprovalSignatureAuthorityClient> transport,
            Supplier<ApprovalSignatureSourceRepository> sources, NamedParameterJdbcTemplate jdbc, ApprovalSignatureCanonical canonical,
            ApprovalStepUpVerifier highRisk, Clock clock) {
        this.enabled = enabled; this.requests = requests; this.installed = installed; this.keys = keys; this.transport = transport;
        this.sources = sources; this.jdbc = jdbc; this.canonical = canonical; this.highRisk = highRisk; this.clock = clock;
    }
    @Override public String currentSignedAssertion(Binding binding, UUID nonce) {
        if (!enabled || nonce == null) throw unavailable();
        var request = requests.get(); var seal = installed.capture(request, binding);
        // Resolve all dedicated keys/transports before any native SQL, including dependency construction.
        var signing = keys.get(); var client = transport.get(); var ownerSources = sources.get();
        Session session;
        if (request.getAttribute(SESSION) instanceof Session prior) {
            if (!prior.binding.equals(binding) || !prior.seal.equals(seal)) throw conflict(); session = prior;
        } else {
            var head = head(seal, binding);
            var snapshot = ownerSources.capture(seal.actor().tenantId(), seal.actor().userId(), head.resourceSetKey(), head.requestId(), false);
            if (binding.expectedVersion() != null && head.version() != binding.expectedVersion()) throw conflict();
            if (head.sourcePin() != null && !canonical.digest(head.sourcePin()).equals(canonical.digest(snapshot.pin()))) throw conflict();
            Instant deadline = seal.evidence().validUntil().toInstant(); Instant max = clock.instant().plusSeconds(30);
            if (max.isBefore(deadline)) deadline = max;
            session = new Session(binding, seal, head, snapshot, Instant.ofEpochSecond(deadline.getEpochSecond()));
            request.setAttribute(SESSION, session);
        }
        installed.unchanged(session.seal, request, binding);
        var latest = head(seal, binding);
        if (!session.head.requestId().equals(latest.requestId()) || !session.head.resourceSetKey().equals(latest.resourceSetKey())) throw conflict();
        var current = ownerSources.capture(seal.actor().tenantId(), seal.actor().userId(), latest.resourceSetKey(), latest.requestId(), false);
        if (!session.snapshot.sameCurrent(current)) throw conflict();
        String challenge = null;
        if (binding.operation() == Operation.SIGN) {
            challenge = ApprovalSignatureInstalledSource.single(request, "X-DWP-Step-Up-Challenge");
            highRisk.verify(challenge, new ApprovalStepUpVerifier.CommandBinding(seal.actor().userId(), seal.actor().tenantId(), binding.operation().route(),
                    seal.evidence().contextKey(), "STEPUP-MGMT-HIGH-V1", "approvals.work.signature.sign", seal.evidence().contextScopeKey(),
                    "APPROVAL_SIGNATURE_REQUEST", binding.objectId().toString(), binding.expectedVersion(), "POST", "/api/approvals" + binding.operation().path(binding.objectId()),
                    binding.idempotencyKey(), binding.bodySha256(), seal.evidence().revision()));
        }
        var b = new LinkedHashMap<String, Object>(); b.put("operation", binding.operation().name());
        b.put("tenantId", seal.actor().tenantId()); b.put("actorId", seal.actor().userId()); b.put("personPublicId", seal.actor().personPublicId().toString());
        b.put("objectId", binding.objectId().toString()); b.put("objectVersion", binding.expectedVersion()); b.put("idempotencyKey", binding.idempotencyKey());
        b.put("bodySha256", binding.bodySha256()); b.put("contextKey", seal.evidence().contextKey()); b.put("contextScopeKey", seal.evidence().contextScopeKey());
        b.put("resourceSetKey", session.head.resourceSetKey()); b.put("decisionRevision", seal.evidence().revision()); b.put("registrySha256", seal.registrySha256());
        b.put("rolloutState", seal.evidence().rolloutState()); b.put("accessMode", seal.mode()); b.put("authorityValidUntil", session.deadline.toString());
        b.put("nonce", nonce.toString()); b.put("source", canonical.read(canonical.json(session.snapshot.pin()), JsonNode.class));
        b.put("commandBody", binding.commandBody()); b.put("sourceSha256", canonical.digest(session.snapshot.pin())); b.put("stepUpToken", challenge);
        var exchange = new ApprovalSignatureSourceExchange(signing, canonical, clock).issue(b, session.deadline);
        String result = client.evaluate(exchange);
        installed.unchanged(session.seal, request, binding);
        if (!session.snapshot.sameCurrent(ownerSources.capture(seal.actor().tenantId(), seal.actor().userId(), latest.resourceSetKey(), latest.requestId(), false))) throw conflict();
        return result;
    }
    private Head head(ApprovalSignatureInstalledSource.Seal seal, Binding binding) {
        var p = new MapSqlParameterSource().addValue("tenant", seal.actor().tenantId()).addValue("actor", seal.actor().userId()).addValue("id", binding.objectId());
        boolean request = binding.operation() == Operation.CONTEXT || binding.operation() == Operation.CREATE;
        var rows = jdbc.query(request ? """
                SELECT request_id,management_resource_set_key,version,NULL::jsonb AS source_pin FROM apr_requests
                 WHERE tenant_id=:tenant AND request_id=:id AND requester_user_id=:actor AND deleted_at IS NULL AND status='APPROVED'
                """ : """
                SELECT s.request_id,s.resource_set_key AS management_resource_set_key,s.version,s.source_pin
                  FROM apr_self_attestations s JOIN apr_requests r ON r.tenant_id=s.tenant_id AND r.request_id=s.request_id
                 WHERE s.tenant_id=:tenant AND s.signature_request_id=:id AND s.owner_user_id=:actor AND s.signer_user_id=:actor
                   AND s.signer_kind='SELF_ATTESTATION' AND r.requester_user_id=:actor AND r.deleted_at IS NULL
                   AND s.resource_set_key=r.management_resource_set_key
                """, p, (row, n) -> new Head(row.getObject("request_id", UUID.class), row.getString("management_resource_set_key"), row.getLong("version"),
                row.getString("source_pin") == null ? null : canonical.read(row.getString("source_pin"), JsonNode.class)));
        if (rows.size() != 1) throw hidden(); var result = rows.getFirst();
        if (result.resourceSetKey() == null || !result.resourceSetKey().matches("RS_[A-Z0-9_]{1,76}") || result.version() < 0 || result.version() > ApprovalSignatureDtos.MAX_VERSION) throw unavailable();
        return result;
    }
    private record Head(UUID requestId, String resourceSetKey, long version, JsonNode sourcePin) { }
    private static final class Session {
        final Binding binding; final ApprovalSignatureInstalledSource.Seal seal; final Head head;
        final ApprovalSignatureSourceRepository.Snapshot snapshot; final Instant deadline;
        Session(Binding binding, ApprovalSignatureInstalledSource.Seal seal, Head head, ApprovalSignatureSourceRepository.Snapshot snapshot, Instant deadline) {
            this.binding = binding; this.seal = seal; this.head = head; this.snapshot = snapshot; this.deadline = deadline;
        }
    }
}
