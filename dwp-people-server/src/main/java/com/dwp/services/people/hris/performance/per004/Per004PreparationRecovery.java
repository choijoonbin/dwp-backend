package com.dwp.services.people.hris.performance.per004;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Quarantine is a negative-authority operation, never a timeout or local absence inference. */
@Component
class Per004PreparationRecovery {
    interface AuthoritativeCreateLookupPort {
        CreateLookupResponse query(CreateLookupRequest exactRequest);
    }
    interface LookupEvidenceVerifier {
        boolean verifies(CreateLookupResponse exactSignedResponse);
    }

    private final JdbcTemplate jdbc;
    private final AuthoritativeCreateLookupPort lookups;
    private final LookupEvidenceVerifier verifier;

    Per004PreparationRecovery(JdbcTemplate jdbc,
            ObjectProvider<AuthoritativeCreateLookupPort> lookups,
            ObjectProvider<LookupEvidenceVerifier> verifier) {
        this.jdbc = jdbc;
        this.lookups = lookups.getIfAvailable();
        this.verifier = verifier.getIfAvailable();
    }

    @Transactional
    boolean quarantineIfFinallyAbsent(CreateIdentity identity) {
        if (identity == null || identity.tenantId() < 1 || identity.configurationId() == null
                || identity.createSubjectPrincipalPublicId() == null
                || !"configuration.version.create".equals(identity.originatingAction())
                || identity.createIdempotencyKey() == null || identity.ownerPreparationReceiptId() == null
                || identity.createRequestDigest() == null
                || !identity.createRequestDigest().matches("^[a-f0-9]{64}$")) return false;
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?::text, TRUE)",
                String.class, identity.tenantId());
        List<UUID> rows = jdbc.query("""
                SELECT artifact_public_id FROM hris_performance.prf_per004_artifact_preparations
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND create_subject_principal_public_id = ? AND originating_action = ?
                   AND create_idempotency_key = ? AND create_request_digest = ?
                   AND preparation_receipt_id = ? AND status = 'PREPARED'
                 FOR UPDATE
                """, (rs, i) -> rs.getObject(1, UUID.class), identity.tenantId(), identity.configurationId(),
                identity.createSubjectPrincipalPublicId(), identity.originatingAction(),
                identity.createIdempotencyKey(), identity.createRequestDigest(),
                identity.ownerPreparationReceiptId());
        if (rows.size() != 1 || lookups == null || verifier == null) return false;
        CreateLookupResponse response;
        try { response = lookups.query(new CreateLookupRequest(identity)); }
        catch (RuntimeException unavailable) { return false; }
        if (response == null || !identity.equals(response.identity())
                || response.state() != LookupState.ABSENT_FINAL
                || response.version() != null || response.platformCreateReceiptId() != null
                || response.durableAdmissionFenceId() == null
                || response.evidenceDigest() == null
                || !response.evidenceDigest().matches("^[a-f0-9]{64}$")
                || response.evaluatedAt() == null
                || response.evaluatedAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30))
                || response.evaluatedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5))
                || response.signedBindingEvidence() == null
                || response.signedBindingEvidence().isBlank()
                || !verifier.verifies(response)) return false;
        return jdbc.update("""
                UPDATE hris_performance.prf_per004_artifact_preparations
                   SET status = 'QUARANTINED', quarantine_fence_id = ?,
                       quarantine_evidence_digest = ?, resolved_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND artifact_public_id = ? AND status = 'PREPARED'
                """, response.durableAdmissionFenceId(), response.evidenceDigest(),
                identity.tenantId(), rows.getFirst()) == 1;
    }
}
