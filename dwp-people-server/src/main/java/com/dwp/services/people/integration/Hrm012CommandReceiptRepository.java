package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable receipt-first reservation and marker replay CAS owner. */
@Repository
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm012CommandReceiptRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    Hrm012CommandReceiptRepository(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Hrm012WorkforceIngestionContract.Receipt reserveOrRead(Reservation value) {
        establishTenant(value.tenantId());
        UUID proposed = UUID.randomUUID();
        List<Hrm012WorkforceIngestionContract.Receipt> inserted = jdbc.query("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,result_reference,correlation_id,
                    created_at,updated_at)
                VALUES(:receipt,:tenant,:command,:key,:hash,:actor,:action,
                    :subject,:population,:fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'WORKFORCE_INGESTION_OBJECT',NULL,
                    :expectedVersion,'RECEIVED',CAST(:reference AS jsonb),
                    :correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, parameters(value).addValue("receipt", proposed),
                (result, ignored) -> receipt(result, true));
        if (!inserted.isEmpty()) return inserted.getFirst();
        List<Hrm012WorkforceIngestionContract.Receipt> existing = jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant
                   AND subject_principal_public_id=:subject
                   AND originating_action=:action
                   AND idempotency_key=:key
                 FOR UPDATE
                """, parameters(value), (result, ignored) -> receipt(result, false));
        if (existing.size() != 1) {
            throw Hrm012Problem.conflict("HRM012_RECEIPT_KEY_CONFLICT",
                    "The idempotency key conflicts with another command receipt.");
        }
        Hrm012WorkforceIngestionContract.Receipt receipt = existing.getFirst();
        if (!receipt.requestHash().equals(value.requestHash())) {
            throw Hrm012Problem.conflict("HRM012_IDEMPOTENCY_HASH_CONFLICT",
                    "The idempotency tuple was used with different command bytes.");
        }
        // The winner's complete frozen authority remains authoritative. A concurrent
        // same-hash loser must never overwrite it or conflict merely because current
        // revisions changed between the two pre-receipt resolutions.
        return receipt;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Hrm012WorkforceIngestionContract.Receipt> findExisting(
            long tenantId, UUID personPublicId, String rawIdempotencyKey) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND subject_principal_public_id=:subject
                   AND originating_action=:action AND idempotency_key=:key
                """, params(tenantId).addValue("subject", personPublicId)
                .addValue("action", Hrm012WorkforceIngestionContract.ORIGINATING_ACTION)
                .addValue("key", storedKey(personPublicId, rawIdempotencyKey)),
                (result, ignored) -> receipt(result, false)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Hrm012WorkforceIngestionContract.Receipt> findById(
            long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                """, params(tenantId).addValue("receipt", receiptId),
                (result, ignored) -> receipt(result, false)).stream().findFirst();
    }

    @Transactional
    public void bindMarker(
            long tenantId, UUID receiptId, String jti, String markerDigest,
            Instant expiresAt, Instant now) {
        if (!Hrm012WorkforceIngestionContract.canonical(jti, 512)
                || !Hrm012WorkforceIngestionContract.digest(markerDigest)
                || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw Hrm012Problem.denied("Owner marker binding is invalid or expired.");
        }
        establishTenant(tenantId);
        LockedReceipt locked = lock(tenantId, receiptId);
        Hrm012WorkforceIngestionContract.Receipt receipt = locked.receipt();
        if (receipt.terminal()) throw new TerminalReceiptObserved(receipt);
        Map<String, Object> root = new LinkedHashMap<>(receipt.resultReference());
        if (root.containsKey("hrm012Authority")
                && !(root.get("hrm012Authority") instanceof Map<?, ?>)) {
            throw Hrm012Problem.denied("Owner marker authority evidence is invalid.");
        }
        Map<String, Object> authority = object(root.get("hrm012Authority"));
        if (!authority.isEmpty()
                && !authority.keySet().equals(Set.of("authorityEvidence"))) {
            throw Hrm012Problem.denied("Owner marker evidence shape is invalid.");
        }
        List<Map<String, Object>> evidence = evidence(
                authority.get("authorityEvidence"));
        if (authority.containsKey("authorityEvidence") && evidence.isEmpty()) {
            throw Hrm012Problem.denied("Owner marker evidence is empty.");
        }
        Map<String, Object> current = evidence.isEmpty()
                ? Map.of() : evidence.getFirst();
        if (!current.isEmpty()) validateCurrent(current);
        String currentJti = string(current.get("jti"));
        String currentState = string(current.get("state"));
        Instant currentExpiry = instant(current.get("exp"));
        if (jti.equals(currentJti)) {
            if (!exactSameMarkerRetry(
                    current, jti, markerDigest, expiresAt, now)) {
                throw Hrm012Problem.denied(
                        "Same-jti owner marker retry is not exact and unexpired.");
            }
            // The full current entry is immutable for a same-jti retry. In
            // particular VERIFIED_ALLOWED must never be reset to UNVERIFIED.
            return;
        }
        List<Map<String, Object>> next = new ArrayList<>(2);
        next.add(currentEvidence(jti, expiresAt, markerDigest, now));
        if (currentJti != null) {
            Integer runs = jdbc.queryForObject("""
                    SELECT count(*) FROM ppl_workforce_ingestion_runs
                     WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                    """, params(tenantId).addValue("receipt", receiptId), Integer.class);
            if (!replaceableMarker(currentState, currentExpiry, runs, now)) {
                throw Hrm012Problem.denied("Owner marker replay is not replaceable.");
            }
            // Service ordering guarantees non-VERIFIED states precede every
            // metadata/artifact/payload/mapping access. Zero runs is the durable
            // repository proof that no downstream run/cursor/effect exists.
            next.add(previousEvidence(current, now));
        }
        authority = new LinkedHashMap<>();
        authority.put("authorityEvidence", List.copyOf(next));
        root.put("hrm012Authority", authority);
        updateReference(tenantId, receiptId, locked.updatedAt(), root);
    }

    static boolean replaceableMarker(
            String currentState, Instant currentExpiry, Integer runCount, Instant now) {
        if (runCount == null || runCount != 0 || now == null) return false;
        if ("VERIFIED_ALLOWED".equals(currentState)) return false;
        return (currentExpiry != null && !currentExpiry.isAfter(now))
                || List.of("UNVERIFIED", "DENIED", "TRANSIENT_UNAVAILABLE")
                        .contains(currentState);
    }

    @Transactional
    public void markAuthority(
            long tenantId, UUID receiptId, String jti, String state) {
        if (!List.of("VERIFIED_ALLOWED", "DENIED", "TRANSIENT_UNAVAILABLE")
                .contains(state)) {
            throw new IllegalArgumentException("Unknown marker state.");
        }
        establishTenant(tenantId);
        LockedReceipt locked = lock(tenantId, receiptId);
        Hrm012WorkforceIngestionContract.Receipt receipt = locked.receipt();
        if (receipt.terminal()) throw new TerminalReceiptObserved(receipt);
        Map<String, Object> root = new LinkedHashMap<>(receipt.resultReference());
        if (!(root.get("hrm012Authority") instanceof Map<?, ?>)) {
            throw Hrm012Problem.denied("Owner marker authority evidence is invalid.");
        }
        Map<String, Object> authority = object(root.get("hrm012Authority"));
        if (!authority.keySet().equals(Set.of("authorityEvidence"))) {
            throw Hrm012Problem.denied("Owner marker evidence shape is invalid.");
        }
        List<Map<String, Object>> evidence = evidence(
                authority.get("authorityEvidence"));
        if (evidence.isEmpty()) {
            throw Hrm012Problem.denied("Owner marker evidence is missing.");
        }
        Map<String, Object> current = new LinkedHashMap<>(evidence.getFirst());
        validateCurrent(current);
        if (!jti.equals(string(current.get("jti")))) {
            throw Hrm012Problem.denied("Owner marker CAS lost.");
        }
        String existingState = string(current.get("state"));
        String revision = "VERIFIED_ALLOWED".equals(state)
                ? receipt.frozenAuthority().postReceiptDecisionRevision() : null;
        if ("VERIFIED_ALLOWED".equals(existingState)) {
            if (!"VERIFIED_ALLOWED".equals(state)
                    || !revision.equals(string(current.get("authDecisionRevision")))) {
                throw Hrm012Problem.denied(
                        "Verified owner marker evidence is immutable.");
            }
            return;
        }
        current.put("state", state);
        current.put("authDecisionRevision", revision);
        List<Map<String, Object>> next = new ArrayList<>(evidence);
        next.set(0, current);
        authority.put("authorityEvidence", List.copyOf(next));
        root.put("hrm012Authority", authority);
        updateReference(tenantId, receiptId, locked.updatedAt(), root);
    }

    private void updateReference(
            long tenantId,
            UUID receiptId,
            Instant expectedUpdatedAt,
            Map<String, Object> value) {
        int count = jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=CAST(:reference AS jsonb),updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND updated_at=:expectedUpdatedAt
                   AND lifecycle_state NOT IN ('SUCCEEDED','REJECTED','DEAD','CANCELLED')
                """, params(tenantId).addValue("receipt", receiptId)
                .addValue("expectedUpdatedAt", Timestamp.from(expectedUpdatedAt))
                .addValue("reference", json(value)));
        if (count != 1) throw Hrm012Problem.denied("Owner marker CAS failed.");
    }

    private LockedReceipt lock(long tenantId, UUID receiptId) {
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                 FOR UPDATE
                """, params(tenantId).addValue("receipt", receiptId),
                (result, ignored) -> new LockedReceipt(
                        receipt(result, false),
                        result.getTimestamp("updated_at").toInstant()))
                .stream().findFirst()
                .orElseThrow(() -> Hrm012Problem.conflict(
                        "HRM012_RECEIPT_MISSING", "The command receipt is unavailable."));
    }

    private MapSqlParameterSource parameters(Reservation value) {
        return params(value.tenantId())
                .addValue("command", Hrm012WorkforceIngestionContract.COMMAND_TYPE)
                .addValue("key", storedKey(value.personPublicId(), value.idempotencyKey()))
                .addValue("hash", value.requestHash())
                .addValue("actor", value.personPublicId())
                .addValue("action", Hrm012WorkforceIngestionContract.ORIGINATING_ACTION)
                .addValue("subject", value.personPublicId())
                .addValue("population", value.authority().populationScopeDigest())
                .addValue("fieldRevision", value.authority().fieldPolicyRevision())
                .addValue("purpose", Hrm012WorkforceIngestionContract.PURPOSE)
                .addValue("authorizationRevision", value.authority().authorizationRevision())
                .addValue("contextDigest", value.authority().originatingContextDigest())
                .addValue("expectedVersion", value.expectedVersion())
                .addValue("reference", json(Map.of(
                        "hrm012FrozenAuthority", value.authority())))
                .addValue("correlation", value.correlationId())
                .addValue("now", Timestamp.from(value.createdAt()));
    }

    private Hrm012WorkforceIngestionContract.Receipt receipt(
            ResultSet result, boolean newlyReserved) throws SQLException {
        Map<String, Object> reference = map(result.getString("result_reference"));
        return new Hrm012WorkforceIngestionContract.Receipt(
                result.getObject("command_receipt_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("idempotency_key"), result.getString("request_hash"),
                result.getString("lifecycle_state"), result.getString("result_code"),
                reference, result.getObject("correlation_id", UUID.class),
                result.getObject("actor_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"), result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                (Long) result.getObject("expected_version"),
                result.getTimestamp("created_at").toInstant(),
                frozenAuthority(reference), newlyReserved);
    }

    private Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot frozenAuthority(
            Map<String, Object> reference) {
        Object value = reference.get("hrm012FrozenAuthority");
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("HRM-012 frozen receipt authority is missing.");
        }
        try {
            return mapper.convertValue(
                    value, Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("HRM-012 frozen receipt authority is invalid.", exception);
        }
    }

    static String storedKey(UUID personPublicId, String rawKey) {
        if (personPublicId == null || rawKey == null) {
            throw Hrm012Problem.invalid("Receipt identity is incomplete.");
        }
        return "HRM012:" + Hrm012CanonicalJson.sha256(
                personPublicId + "\u0000" + rawKey);
    }

    static Map<String, Object> currentEvidence(
            String jti, Instant expiresAt, String markerDigest, Instant boundAt) {
        LinkedHashMap<String, Object> current = new LinkedHashMap<>();
        current.put("jti", jti);
        current.put("exp", expiresAt.toString());
        current.put("markerSha256", markerDigest);
        current.put("state", "UNVERIFIED");
        current.put("boundAt", boundAt.toString());
        current.put("authDecisionRevision", null);
        return current;
    }

    static Map<String, Object> previousEvidence(
            Map<String, Object> current, Instant replacedAt) {
        return Map.of(
                "jtiSha256", Hrm012CanonicalJson.sha256(
                        String.valueOf(current.get("jti"))),
                "exp", String.valueOf(current.get("exp")),
                "state", String.valueOf(current.get("state")),
                "replacedAt", replacedAt.toString());
    }

    static boolean exactSameMarkerRetry(
            Map<String, Object> current,
            String jti,
            String markerDigest,
            Instant expiresAt,
            Instant now) {
        if (current == null || jti == null || markerDigest == null
                || expiresAt == null || now == null) return false;
        Object currentExpiry = current.get("exp");
        Instant parsed;
        try {
            parsed = currentExpiry instanceof String value
                    ? Instant.parse(value) : null;
        } catch (RuntimeException exception) {
            return false;
        }
        return jti.equals(current.get("jti"))
                && markerDigest.equals(current.get("markerSha256"))
                && expiresAt.equals(parsed) && parsed.isAfter(now);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evidence(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw) || raw.isEmpty() || raw.size() > 2) {
            throw Hrm012Problem.denied("Owner marker evidence is not bounded.");
        }
        List<Map<String, Object>> result = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw Hrm012Problem.denied("Owner marker evidence entry is invalid.");
            }
            result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        validateCurrent(result.getFirst());
        if (result.size() == 2) validatePrevious(result.get(1));
        return List.copyOf(result);
    }

    private void validateCurrent(Map<String, Object> current) {
        Object revision = current.get("authDecisionRevision");
        if (!current.keySet().equals(Set.of(
                "jti", "exp", "markerSha256", "state", "boundAt",
                "authDecisionRevision"))
                || !Hrm012WorkforceIngestionContract.canonical(
                        string(current.get("jti")), 512)
                || instant(current.get("exp")) == null
                || !Hrm012WorkforceIngestionContract.digest(
                        string(current.get("markerSha256")))
                || !List.of("UNVERIFIED", "DENIED", "TRANSIENT_UNAVAILABLE",
                        "VERIFIED_ALLOWED").contains(string(current.get("state")))
                || instant(current.get("boundAt")) == null
                || revision != null && (!(revision instanceof String text)
                        || !Hrm012WorkforceIngestionContract.canonical(text, 512))
                || "VERIFIED_ALLOWED".equals(string(current.get("state")))
                        && revision == null) {
            throw Hrm012Problem.denied("Owner marker current evidence is invalid.");
        }
    }

    private void validatePrevious(Map<String, Object> previous) {
        if (!previous.keySet().equals(
                Set.of("jtiSha256", "exp", "state", "replacedAt"))
                || !Hrm012WorkforceIngestionContract.digest(
                        string(previous.get("jtiSha256")))
                || instant(previous.get("exp")) == null
                || !List.of("UNVERIFIED", "DENIED", "TRANSIENT_UNAVAILABLE")
                        .contains(string(previous.get("state")))
                || instant(previous.get("replacedAt")) == null) {
            throw Hrm012Problem.denied("Owner marker previous evidence is invalid.");
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null) return Map.of();
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Receipt evidence JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Receipt evidence JSON cannot be written.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private Instant instant(Object value) {
        try {
            return value instanceof String text ? Instant.parse(text) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void establishTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id',CAST(:tenant AS VARCHAR),TRUE)",
                params(tenantId), String.class);
        if (!Long.toString(tenantId).equals(value)) {
            throw new IllegalStateException("HRM-012 tenant scope was not established.");
        }
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private record LockedReceipt(
            Hrm012WorkforceIngestionContract.Receipt receipt,
            Instant updatedAt) {
    }

    /**
     * Signals that the receipt became terminal after the service's optimistic
     * read but before the repository acquired its row lock. The service must
     * return this durable original outcome without running authorization or
     * accessing the workforce object.
     */
    static final class TerminalReceiptObserved extends RuntimeException {
        private final transient Hrm012WorkforceIngestionContract.Receipt receipt;

        TerminalReceiptObserved(Hrm012WorkforceIngestionContract.Receipt receipt) {
            super("The command receipt is already terminal.", null, false, false);
            this.receipt = receipt;
        }

        Hrm012WorkforceIngestionContract.Receipt receipt() {
            return receipt;
        }
    }

    public record Reservation(
            long tenantId,
            UUID personPublicId,
            String idempotencyKey,
            String requestHash,
            Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot authority,
            long expectedVersion,
            UUID correlationId,
            Instant createdAt) {
    }
}
