package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetRegistryImpactService {
    private final JdbcTemplate jdbc;
    private final WidgetRegistryLedger ledger;
    private final WidgetRegistryCommandReceiptService fingerprints;
    private final ObjectMapper objectMapper;

    public WidgetRegistryImpactService(
            JdbcTemplate jdbc,
            WidgetRegistryLedger ledger,
            WidgetRegistryCommandReceiptService fingerprints,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse preview(
            UUID definitionId, UUID versionId, String operation) {
        return calculate(null, definitionId, versionId, operation, null);
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse preview(
            UUID definitionId, UUID versionId, String operation, String decisionContext) {
        return calculate(null, definitionId, versionId, operation, decisionContext);
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse previewTenant(
            Long tenantId, UUID definitionId, UUID versionId, String operation) {
        return calculate(tenantId, definitionId, versionId, operation, null);
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse previewTenant(
            Long tenantId, UUID definitionId, UUID versionId, String operation,
            String decisionContext) {
        return calculate(tenantId, definitionId, versionId, operation, decisionContext);
    }

    WidgetRegistryDtos.ImpactResponse calculate(
            Long tenantId, UUID definitionId, UUID versionId, String operation) {
        return calculate(tenantId, definitionId, versionId, operation, null);
    }

    WidgetRegistryDtos.ImpactResponse calculate(
            Long tenantId, UUID definitionId, UUID versionId, String operation,
            String decisionContext) {
        WidgetRegistryState state = ledger.state();
        long channels = count("""
                SELECT count(*) FROM plt_widget_release_channels
                 WHERE definition_id = ?
                   AND (?::uuid IS NULL OR current_version_id = ?::uuid)
                """, definitionId, versionId, versionId);
        long policies = count("""
                SELECT count(*)
                  FROM adm_tenant_widget_policy_heads h
                  JOIN adm_tenant_widget_policy_revisions p
                    ON p.policy_revision_id = h.current_revision_id
                 WHERE h.definition_id = ?
                   AND (?::bigint IS NULL OR h.tenant_id = ?::bigint)
                   AND p.policy_state = 'PUBLISHED'
                """, definitionId, tenantId, tenantId);
        long instances = count("""
                SELECT count(*) FROM plt_widget_instances
                 WHERE definition_id = ?
                   AND (?::uuid IS NULL OR definition_version_id = ?::uuid)
                   AND (?::bigint IS NULL OR tenant_id = ?::bigint)
                   AND instance_state = 'ACTIVE'
                """, definitionId, versionId, versionId, tenantId, tenantId);
        long tenants = count("""
                SELECT count(DISTINCT h.tenant_id)
                  FROM adm_tenant_widget_policy_heads h
                  JOIN adm_tenant_widget_policy_revisions p
                    ON p.policy_revision_id = h.current_revision_id
                 WHERE h.definition_id = ? AND p.policy_state = 'PUBLISHED'
                   AND (?::bigint IS NULL OR h.tenant_id = ?::bigint)
                """, definitionId, tenantId, tenantId);
        OffsetDateTime calculatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> preimage = new LinkedHashMap<>();
        preimage.put("schemaVersion", 1);
        preimage.put("tenantId", tenantId);
        preimage.put("definitionId", definitionId);
        preimage.put("versionId", versionId);
        preimage.put("operation", operation);
        preimage.put("decisionContext", decisionContext);
        preimage.put("registryRevision", state.getRegistryRevision());
        preimage.put("policyRevision", state.getPolicyRevision());
        preimage.put("safetyRevision", state.getSafetyRevision());
        preimage.put("activeChannelCount", channels);
        preimage.put("tenantPolicyReferenceCount", policies);
        preimage.put("instanceReferenceCount", instances);
        preimage.put("affectedTenantCount", tenants);
        String impactRevision = fingerprints.fingerprint(objectMapper.valueToTree(preimage));
        return new WidgetRegistryDtos.ImpactResponse(
                definitionId, versionId, operation, channels, policies, instances, tenants,
                true, impactRevision, calculatedAt);
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }
}
