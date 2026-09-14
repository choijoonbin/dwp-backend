package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;
import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads only the original installed scope's source HEAD; never creates or accepts an authority Window. */
public final class PolicyImpactSourceHeadReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApprovalPolicyImpactEvaluator evaluator;
    public PolicyImpactSourceHeadReader(NamedParameterJdbcTemplate jdbc, ObjectMapper json, ApprovalPolicyImpactEvaluator evaluator) {
        this.jdbc = jdbc; this.evaluator = evaluator;
        this.json = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public static final class Seal {
        private final PolicyImpactInstalledContext.Seal installed;
        private final Head head;
        private final String digest;
        private Seal(PolicyImpactInstalledContext.Seal installed, Head head, String digest) {
            this.installed = installed; this.head = head; this.digest = digest;
        }
        public PolicyImpactInstalledContext.Seal installed() { return installed; }
        public String digest() { return digest; }
    }
    public Seal capture(PolicyImpactInstalledContext.Seal installed) {
        if (installed == null) throw unavailable();
        Head head = read(installed);
        if (head.rowVersion() != installed.expectedVersion()) throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        try {
            evaluator.validate(head.policyKey(), head.current());
            if (head.pending() != null) evaluator.validate(head.policyKey(), head.pending());
        } catch (BaseException invalid) { throw unavailable(); }
        return new Seal(installed, head, evaluator.digest(head));
    }
    public void unchanged(Seal original) {
        var current = capture(original.installed());
        if (!original.digest().equals(current.digest()) || !original.head.equals(current.head)) throw changed();
    }
    Head read(PolicyImpactInstalledContext.Seal installed) {
        return jdbc.query("""
                SELECT p.*,h.policy_version_id,h.version_number AS published_number,
                  h.enforcement_mode AS published_mode,h.severity AS published_severity,
                  h.lifecycle_state AS published_state,h.rule_payload::text AS published_rule,
                  COALESCE(source.source_kind,'UNRECORDED_HISTORICAL_METADATA') AS metadata_provenance,
                  source.captured_at AS source_captured_at
                FROM apr_policy_rules p JOIN apr_tenants t ON t.tenant_id=p.tenant_id AND t.lifecycle_state='ACTIVE'
                LEFT JOIN LATERAL (SELECT * FROM apr_policy_rule_versions
                  WHERE tenant_id=p.tenant_id AND policy_id=p.policy_id ORDER BY version_number DESC LIMIT 1) h ON true
                LEFT JOIN apr_policy_version_source_records source ON source.policy_version_id=h.policy_version_id
                  AND source.tenant_id=h.tenant_id AND source.policy_id=h.policy_id
                WHERE p.tenant_id=:tenant AND p.management_resource_set_key=:scope AND p.policy_id=:id
                """, new MapSqlParameterSource("tenant", installed.tenantId()).addValue("scope", installed.resourceSetKey())
                    .addValue("id", installed.policyId()), rs -> {
            if (!rs.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            Rules current = new Rules(rs.getString("enforcement_mode"), rs.getString("severity"), rs.getString("lifecycle_state"), object(rs.getString("rule_payload")));
            String pending = rs.getString("pending_rule_payload");
            Rules proposed = pending == null ? null : new Rules(rs.getString("pending_enforcement_mode"), rs.getString("pending_severity"),
                    rs.getString("pending_lifecycle_state"), object(pending));
            Long maker = rs.getObject("pending_by", Long.class); var at = rs.getTimestamp("pending_at");
            if ((proposed == null) != (maker == null) || proposed != null && at == null || rs.getObject("policy_version_id") == null) throw unavailable();
            var published = new Rules(rs.getString("published_mode"), rs.getString("published_severity"), rs.getString("published_state"), object(rs.getString("published_rule")));
            if (!current.equals(published)) throw unavailable();
            String provenance = rs.getString("metadata_provenance"); var capturedAt = rs.getTimestamp("source_captured_at");
            if (!("UNRECORDED_HISTORICAL_METADATA".equals(provenance) && capturedAt == null)
                    && !("LEGACY_CAPTURE_TIME".equals(provenance) && capturedAt != null)) throw unavailable();
            return new Head(installed.policyId(), rs.getString("policy_key"), rs.getLong("version"), rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("published_number", Integer.class), current, proposed, maker, at == null ? null : at.toInstant(),
                    provenance, capturedAt == null ? null : capturedAt.toInstant());
        });
    }
    private Map<String, Object> object(String raw) {
        if (raw == null || raw.length() > 262144) throw unavailable();
        try {
            Map<String, Object> value = json.readValue(raw, new TypeReference<>() { });
            if (value == null || value.values().stream().anyMatch(java.util.Objects::isNull)) throw unavailable(); return value;
        } catch (java.io.IOException | IllegalArgumentException error) { throw unavailable(); }
    }
}
