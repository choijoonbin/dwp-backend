package com.dwp.services.platform.catalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<CatalogDtos.Entity> inventory(Long tenantId) {
        Map<String, CatalogDtos.Entity> inventory = new LinkedHashMap<>();
        referenceSets(tenantId).forEach(entity -> inventory.put(entity.ref(), entity));
        registryEntries(tenantId).forEach(entity -> inventory.put(entity.ref(), entity));
        codeSets().forEach(entity -> inventory.put(entity.ref(), entity));
        services().forEach(entity -> inventory.put(entity.ref(), entity));
        navigation(tenantId).forEach(entity -> inventory.put(entity.ref(), entity));
        connectorInstances(tenantId).forEach(entity -> inventory.put(entity.ref(), entity));
        permissions(tenantId).forEach(entity -> inventory.put(entity.ref(), entity));
        return List.copyOf(inventory.values());
    }

    public List<CatalogDtos.Relation> relations(Long tenantId) {
        Map<String, CatalogDtos.Relation> relations = new LinkedHashMap<>();
        explicitRelations(tenantId).forEach(relation -> putRelation(relations, relation));
        codeRelations().forEach(relation -> putRelation(relations, relation));
        navigationRelations(tenantId).forEach(relation -> putRelation(relations, relation));
        connectorRelations(tenantId).forEach(relation -> putRelation(relations, relation));
        return List.copyOf(relations.values());
    }

    public List<Long> activeTenantIds() {
        return jdbc.queryForList("""
                SELECT tenant_id
                  FROM sys_service_tenants
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY tenant_id
                """, Long.class);
    }

    public CatalogDtos.Relation saveRelation(
            Long tenantId,
            Long actorId,
            CatalogDtos.DeclareRelationRequest request,
            String sourceRef,
            String targetRef) {
        CatalogDtos.Relation existing = findByNaturalKey(
                tenantId, sourceRef, targetRef, request.relationType());
        JsonNode metadata = request.metadata() == null || request.metadata().isNull()
                ? objectMapper.createObjectNode()
                : request.metadata();
        if (!metadata.isObject()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Catalog relation metadata must be an object.");
        }
        String evidenceRef = trimToNull(request.evidenceRef());
        try {
            if (existing == null) {
                if (request.version() != null && request.version() != 0L) throw conflict();
                UUID relationId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO adm_catalog_relations (
                            catalog_relation_id, tenant_id, source_ref, target_ref,
                            relation_type, relation_origin, criticality, evidence_ref,
                            metadata, lifecycle_state, version, created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, 'DECLARED', ?, ?, ?::jsonb, 'ACTIVE', 0, ?, ?)
                        """, relationId, tenantId, sourceRef, targetRef,
                        request.relationType(), request.criticality(), evidenceRef,
                        jsonText(metadata), actorId, actorId);
                return findById(tenantId, relationId);
            }
            if (request.version() == null || existing.version() != request.version()) throw conflict();
            int updated = jdbc.update("""
                    UPDATE adm_catalog_relations
                       SET relation_origin = 'DECLARED',
                           criticality = ?, evidence_ref = ?, metadata = ?::jsonb,
                           lifecycle_state = 'ACTIVE', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND catalog_relation_id = ? AND version = ?
                    """, request.criticality(), evidenceRef, jsonText(metadata), actorId,
                    tenantId, existing.relationId(), request.version());
            if (updated != 1) throw conflict();
            return findById(tenantId, existing.relationId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "Catalog relation conflicts with existing data.", exception);
        }
    }

    public CatalogDtos.Relation retireRelation(
            Long tenantId, Long actorId, UUID relationId, long version) {
        int updated = jdbc.update("""
                UPDATE adm_catalog_relations
                   SET lifecycle_state = 'RETIRED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND catalog_relation_id = ?
                   AND version = ? AND lifecycle_state = 'ACTIVE'
                """, actorId, tenantId, relationId, version);
        if (updated != 1) throw conflict();
        return findById(tenantId, relationId);
    }

    public CatalogDtos.CompatibilityRule activeCompatibilityRule() {
        List<CatalogDtos.CompatibilityRule> rows = jdbc.query("""
                SELECT rule_key, rule_version, rule_definition::text, content_sha256
                  FROM sys_catalog_compatibility_rules
                 WHERE rule_key = 'DWP_CATALOG_IMPACT' AND lifecycle_state = 'ACTIVE'
                 ORDER BY rule_version DESC
                 LIMIT 1
                """, (row, ignored) -> new CatalogDtos.CompatibilityRule(
                row.getString("rule_key"), row.getLong("rule_version"),
                json(row.getString("rule_definition")), row.getString("content_sha256")));
        if (rows.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "No active catalog compatibility rule exists.");
        }
        return rows.get(0);
    }

    public List<CatalogDtos.AssuranceFinding> synchronizeFindings(
            Long tenantId,
            CatalogDtos.CompatibilityRule rule,
            List<FindingCandidate> candidates) {
        Set<String> detected = new HashSet<>();
        Map<String, CatalogDtos.AssuranceFinding> existingByIdentity = jdbc.query("""
                SELECT catalog_finding_id, tenant_id, entity_ref, finding_code, severity,
                       lifecycle_state, rule_key, rule_version, evidence::text,
                       evidence_sha256, first_detected_at, last_detected_at,
                       disposition_reason, disposition_evidence_ref, disposed_by,
                       disposed_at, version
                  FROM adm_catalog_assurance_findings
                 WHERE tenant_id = ? AND rule_key = ? AND rule_version = ?
                """, this::mapFinding, tenantId, rule.ruleKey(), rule.ruleVersion()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        finding -> finding.entityRef() + "|" + finding.findingCode(),
                        finding -> finding));
        for (FindingCandidate candidate : candidates) {
            String identity = candidate.entityRef() + "|" + candidate.findingCode();
            if (!detected.add(identity)) continue;
            String evidence = jsonText(candidate.evidence());
            String evidenceHash = sha256(evidence);
            CatalogDtos.AssuranceFinding existing = existingByIdentity.get(identity);
            if (existing != null && shouldReopen(existing, evidenceHash)) {
                appendDisposition(
                            tenantId, existing, "OPEN", "SYSTEM", null,
                            "Automated catalog evidence changed and requires a new review.", null);
            }
            jdbc.update("""
                    INSERT INTO adm_catalog_assurance_findings (
                        tenant_id, entity_ref, finding_code, severity, lifecycle_state,
                        rule_key, rule_version, evidence, evidence_sha256)
                    VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?::jsonb, ?)
                    ON CONFLICT (tenant_id, entity_ref, finding_code, rule_key, rule_version)
                    DO UPDATE SET
                        severity = EXCLUDED.severity,
                        evidence = EXCLUDED.evidence,
                        evidence_sha256 = EXCLUDED.evidence_sha256,
                        last_detected_at = CURRENT_TIMESTAMP,
                        lifecycle_state = CASE
                            WHEN adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN 'OPEN'
                            ELSE adm_catalog_assurance_findings.lifecycle_state
                        END,
                        disposition_reason = CASE
                            WHEN adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN NULL
                            ELSE adm_catalog_assurance_findings.disposition_reason
                        END,
                        disposition_evidence_ref = CASE
                            WHEN adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN NULL
                            ELSE adm_catalog_assurance_findings.disposition_evidence_ref
                        END,
                        disposed_by = CASE
                            WHEN adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN NULL
                            ELSE adm_catalog_assurance_findings.disposed_by
                        END,
                        disposed_at = CASE
                            WHEN adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN NULL
                            ELSE adm_catalog_assurance_findings.disposed_at
                        END,
                        version = CASE
                            WHEN adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256
                              OR adm_catalog_assurance_findings.severity <> EXCLUDED.severity
                              OR adm_catalog_assurance_findings.lifecycle_state = 'RESOLVED'
                              OR (adm_catalog_assurance_findings.lifecycle_state IN ('FALSE_POSITIVE', 'ACCEPTED_RISK')
                                  AND adm_catalog_assurance_findings.evidence_sha256 <> EXCLUDED.evidence_sha256)
                                THEN adm_catalog_assurance_findings.version + 1
                            ELSE adm_catalog_assurance_findings.version
                        END
                    """, tenantId, candidate.entityRef(), candidate.findingCode(),
                    candidate.severity(), rule.ruleKey(), rule.ruleVersion(), evidence, evidenceHash);
        }

        List<CatalogDtos.AssuranceFinding> active = jdbc.query("""
                SELECT catalog_finding_id, tenant_id, entity_ref, finding_code, severity,
                       lifecycle_state, rule_key, rule_version, evidence::text,
                       evidence_sha256, first_detected_at, last_detected_at,
                       disposition_reason, disposition_evidence_ref, disposed_by,
                       disposed_at, version
                  FROM adm_catalog_assurance_findings
                 WHERE tenant_id = ? AND rule_key = ? AND rule_version = ?
                   AND lifecycle_state IN ('OPEN', 'ACKNOWLEDGED')
                """, this::mapFinding, tenantId, rule.ruleKey(), rule.ruleVersion());
        for (CatalogDtos.AssuranceFinding finding : active) {
            if (!detected.contains(finding.entityRef() + "|" + finding.findingCode())) {
                appendDisposition(
                        tenantId, finding, "RESOLVED", "SYSTEM", null,
                        "The automated catalog evaluation no longer detects this condition.", null);
                jdbc.update("""
                        UPDATE adm_catalog_assurance_findings
                           SET lifecycle_state = 'RESOLVED',
                               disposition_reason = ?, disposition_evidence_ref = NULL,
                               disposed_by = NULL, disposed_at = CURRENT_TIMESTAMP,
                               version = version + 1
                         WHERE tenant_id = ? AND catalog_finding_id = ? AND version = ?
                        """, "The automated catalog evaluation no longer detects this condition.",
                        tenantId, finding.findingId(), finding.version());
            }
        }
        return findings(tenantId);
    }

    public List<CatalogDtos.AssuranceFinding> findings(Long tenantId) {
        return jdbc.query("""
                SELECT catalog_finding_id, tenant_id, entity_ref, finding_code, severity,
                       lifecycle_state, rule_key, rule_version, evidence::text,
                       evidence_sha256, first_detected_at, last_detected_at,
                       disposition_reason, disposition_evidence_ref, disposed_by,
                       disposed_at, version
                  FROM adm_catalog_assurance_findings
                 WHERE tenant_id = ?
                 ORDER BY CASE lifecycle_state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END,
                          CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                               WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          last_detected_at DESC, entity_ref
                """, this::mapFinding, tenantId);
    }

    public CatalogDtos.AssuranceFinding dispositionFinding(
            Long tenantId,
            Long actorId,
            UUID findingId,
            CatalogDtos.DispositionFindingRequest request) {
        CatalogDtos.AssuranceFinding before = requireFinding(tenantId, findingId);
        if (before.version() != request.version()) throw conflict();
        appendDisposition(
                tenantId, before, request.decision(), "USER", actorId,
                request.reason().trim(), trimToNull(request.evidenceRef()));
        int updated = jdbc.update("""
                UPDATE adm_catalog_assurance_findings
                   SET lifecycle_state = ?, disposition_reason = ?,
                       disposition_evidence_ref = ?, disposed_by = ?,
                       disposed_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND catalog_finding_id = ? AND version = ?
                """, request.decision(), request.reason().trim(),
                trimToNull(request.evidenceRef()), actorId,
                tenantId, findingId, request.version());
        if (updated != 1) throw conflict();
        return requireFinding(tenantId, findingId);
    }

    private CatalogDtos.AssuranceFinding requireFinding(Long tenantId, UUID findingId) {
        List<CatalogDtos.AssuranceFinding> rows = jdbc.query("""
                SELECT catalog_finding_id, tenant_id, entity_ref, finding_code, severity,
                       lifecycle_state, rule_key, rule_version, evidence::text,
                       evidence_sha256, first_detected_at, last_detected_at,
                       disposition_reason, disposition_evidence_ref, disposed_by,
                       disposed_at, version
                  FROM adm_catalog_assurance_findings
                 WHERE tenant_id = ? AND catalog_finding_id = ?
                """, this::mapFinding, tenantId, findingId);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private boolean shouldReopen(
            CatalogDtos.AssuranceFinding finding, String nextEvidenceHash) {
        if ("RESOLVED".equals(finding.lifecycleState())) return true;
        return Set.of("FALSE_POSITIVE", "ACCEPTED_RISK").contains(finding.lifecycleState())
                && !finding.evidenceSha256().equals(nextEvidenceHash);
    }

    private void appendDisposition(
            Long tenantId,
            CatalogDtos.AssuranceFinding finding,
            String decision,
            String actorType,
            Long actorId,
            String reason,
            String evidenceRef) {
        String content = String.join("|",
                finding.findingId().toString(), finding.lifecycleState(), decision,
                reason, evidenceRef == null ? "" : evidenceRef,
                actorType, actorId == null ? "" : actorId.toString());
        jdbc.update("""
                INSERT INTO adm_catalog_finding_dispositions (
                    catalog_finding_id, tenant_id, previous_state, decision,
                    reason, evidence_ref, actor_type, decided_by, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, finding.findingId(), tenantId, finding.lifecycleState(), decision,
                reason, evidenceRef, actorType, actorId, sha256(content));
    }

    private CatalogDtos.AssuranceFinding mapFinding(ResultSet row, int ignored) throws SQLException {
        return new CatalogDtos.AssuranceFinding(
                row.getObject("catalog_finding_id", UUID.class), row.getString("entity_ref"),
                row.getString("finding_code"), row.getString("severity"),
                row.getString("lifecycle_state"), row.getString("rule_key"),
                row.getLong("rule_version"), json(row.getString("evidence")),
                row.getString("evidence_sha256"),
                row.getObject("first_detected_at", OffsetDateTime.class),
                row.getObject("last_detected_at", OffsetDateTime.class),
                row.getString("disposition_reason"), row.getString("disposition_evidence_ref"),
                row.getObject("disposed_by", Long.class),
                row.getObject("disposed_at", OffsetDateTime.class), row.getLong("version"));
    }

    private List<CatalogDtos.Entity> referenceSets(Long tenantId) {
        return jdbc.query("""
                SELECT reference_set.set_key, reference_set.name, reference_set.description,
                       reference_set.lifecycle_state, reference_set.content_revision,
                       COUNT(reference_item.reference_item_id) AS item_count,
                       COUNT(reference_item.reference_item_id)
                           FILTER (WHERE reference_item.lifecycle_state = 'ACTIVE') AS active_item_count
                  FROM adm_reference_sets reference_set
                  LEFT JOIN adm_reference_items reference_item
                    ON reference_item.tenant_id = reference_set.tenant_id
                   AND reference_item.reference_set_id = reference_set.reference_set_id
                 WHERE reference_set.tenant_id = ?
                 GROUP BY reference_set.reference_set_id
                 ORDER BY reference_set.set_key
                """, (result, ignored) -> entity(
                ref("REFERENCE_SET", result.getString("set_key")),
                "REFERENCE_SET", result.getString("set_key"), result.getString("name"),
                result.getString("description"), "tenant-reference-owner",
                result.getString("lifecycle_state"), "MEDIUM", "TENANT",
                result.getLong("content_revision"), metadata(
                        "itemCount", result.getLong("item_count"),
                        "activeItemCount", result.getLong("active_item_count"))), tenantId);
    }

    private List<CatalogDtos.Entity> registryEntries(Long tenantId) {
        return jdbc.query("""
                WITH ranked AS (
                    SELECT entry.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY registry_type, entry_key
                               ORDER BY CASE lifecycle_state
                                   WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                                   revision DESC) AS rank
                      FROM adm_registry_entries entry
                     WHERE tenant_id = ?
                )
                SELECT registry_type, entry_key, name, description, owner_ref,
                       risk_tier, artifact_version, lifecycle_state, revision
                  FROM ranked
                 WHERE rank = 1
                 ORDER BY registry_type, entry_key
                """, (result, ignored) -> {
            String type = result.getString("registry_type");
            String key = result.getString("entry_key");
            return entity(
                    ref("REGISTRY", type + ":" + key), type, key,
                    result.getString("name"), result.getString("description"),
                    result.getString("owner_ref"), result.getString("lifecycle_state"),
                    result.getString("risk_tier"), "TENANT", result.getLong("revision"),
                    metadata("artifactVersion", result.getString("artifact_version"),
                            "registryType", type));
        }, tenantId);
    }

    private List<CatalogDtos.Entity> codeSets() {
        return jdbc.query("""
                SELECT code_set.code_set_key, code_set.display_name, code_set.description,
                       code_set.owner_service, code_set.lifecycle_state,
                       code_set.schema_version, code_set.configuration_level,
                       code_set.validation_source, code_set.runtime_visibility,
                       COUNT(DISTINCT code_value.code) AS value_count,
                       COUNT(DISTINCT code_binding.code_binding_id) AS binding_count
                  FROM sys_code_sets code_set
                  LEFT JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                  LEFT JOIN sys_code_bindings code_binding
                    ON code_binding.code_set_key = code_set.code_set_key
                   AND code_binding.lifecycle_state = 'ACTIVE'
                 GROUP BY code_set.code_set_key
                 ORDER BY code_set.code_set_key
                """, (result, ignored) -> entity(
                ref("CODE_SET", result.getString("code_set_key")),
                "CODE_SET", result.getString("code_set_key"), result.getString("display_name"),
                result.getString("description"), result.getString("owner_service"),
                result.getString("lifecycle_state"), "MEDIUM", "GLOBAL_PRODUCT",
                result.getLong("schema_version"), metadata(
                        "configurationLevel", result.getString("configuration_level"),
                        "validationSource", result.getString("validation_source"),
                        "runtimeVisibility", result.getString("runtime_visibility"),
                        "valueCount", result.getLong("value_count"),
                        "bindingCount", result.getLong("binding_count"))));
    }

    private List<CatalogDtos.Entity> services() {
        return jdbc.query("""
                SELECT service_name, COUNT(DISTINCT owned_code_set) AS owned_code_sets,
                       COUNT(DISTINCT consumed_code_set) AS consumed_code_sets
                  FROM (
                    SELECT owner_service AS service_name, code_set_key AS owned_code_set,
                           NULL::varchar AS consumed_code_set
                      FROM sys_code_sets
                    UNION ALL
                    SELECT consumer_service, NULL::varchar, code_set_key
                      FROM sys_code_bindings
                     WHERE lifecycle_state = 'ACTIVE'
                  ) service_catalog
                 GROUP BY service_name
                 ORDER BY service_name
                """, (result, ignored) -> entity(
                ref("SERVICE", result.getString("service_name")),
                "SERVICE", result.getString("service_name"), result.getString("service_name"),
                "DWP runtime service", result.getString("service_name"), "ACTIVE",
                "MEDIUM", "GLOBAL_PRODUCT", 1,
                metadata("ownedCodeSets", result.getLong("owned_code_sets"),
                        "consumedCodeSets", result.getLong("consumed_code_sets"))));
    }

    private List<CatalogDtos.Entity> navigation(Long tenantId) {
        return jdbc.query("""
                SELECT item.navigation_key, item.item_type, item.route, item.icon_key,
                       item.required_resource_key, item.required_permission_code,
                       item.lifecycle_state, item.version,
                       COALESCE(
                           MAX(label.label) FILTER (WHERE LOWER(label.locale) = 'ko'),
                           MAX(label.label) FILTER (WHERE LOWER(label.locale) = 'en'),
                           item.navigation_key) AS display_name
                  FROM adm_navigation_items item
                  LEFT JOIN adm_navigation_labels label
                    ON label.tenant_id = item.tenant_id
                   AND label.navigation_item_id = item.navigation_item_id
                 WHERE item.tenant_id = ?
                 GROUP BY item.navigation_item_id
                 ORDER BY item.sort_order, item.navigation_key
                """, (result, ignored) -> entity(
                ref("NAVIGATION", result.getString("navigation_key")),
                "NAVIGATION", result.getString("navigation_key"), result.getString("display_name"),
                result.getString("route"), "tenant-experience-owner",
                result.getString("lifecycle_state"), "LOW", "TENANT",
                result.getLong("version"), metadata(
                        "itemType", result.getString("item_type"),
                        "route", result.getString("route"),
                        "iconKey", result.getString("icon_key"),
                        "resourceKey", result.getString("required_resource_key"),
                        "permissionCode", result.getString("required_permission_code"))), tenantId);
    }

    private List<CatalogDtos.Entity> connectorInstances(Long tenantId) {
        return jdbc.query("""
                SELECT connector_key, display_name, provider_type, auth_mode,
                       lifecycle_state, health_state, policy_state, version,
                       jsonb_array_length(requested_scopes) AS scope_count,
                       jsonb_array_length(capabilities) AS capability_count
                  FROM int_productivity_connectors
                 WHERE tenant_id = ?
                 ORDER BY connector_key
                """, (result, ignored) -> entity(
                ref("CONNECTOR_INSTANCE", result.getString("connector_key")),
                "CONNECTOR_INSTANCE", result.getString("connector_key"),
                result.getString("display_name"), result.getString("provider_type"),
                "tenant-integration-owner", result.getString("lifecycle_state"),
                "HIGH", "TENANT", result.getLong("version"), metadata(
                        "providerType", result.getString("provider_type"),
                        "authMode", result.getString("auth_mode"),
                        "healthState", result.getString("health_state"),
                        "policyState", result.getString("policy_state"),
                        "scopeCount", result.getLong("scope_count"),
                        "capabilityCount", result.getLong("capability_count"))), tenantId);
    }

    private List<CatalogDtos.Entity> permissions(Long tenantId) {
        return jdbc.query("""
                SELECT DISTINCT required_resource_key, required_permission_code
                  FROM adm_navigation_items
                 WHERE tenant_id = ? AND required_resource_key IS NOT NULL
                """, (result, ignored) -> {
            String resource = result.getString("required_resource_key");
            String permission = result.getString("required_permission_code");
            String key = resource + "/" + permission;
            return entity(ref("PERMISSION", key), "PERMISSION", key, permission,
                    "Permission required by tenant navigation", "identity-and-access",
                    "ACTIVE", "HIGH", "TENANT", 1,
                    metadata("resourceKey", resource, "permissionCode", permission));
        }, tenantId);
    }

    private List<CatalogDtos.Relation> explicitRelations(Long tenantId) {
        return jdbc.query("""
                SELECT catalog_relation_id, source_ref, target_ref, relation_type,
                       relation_origin, criticality, evidence_ref, metadata::text,
                       lifecycle_state, version
                  FROM adm_catalog_relations
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY criticality DESC, relation_type, source_ref, target_ref
                """, this::mapRelation, tenantId);
    }

    private List<CatalogDtos.Relation> codeRelations() {
        List<CatalogDtos.Relation> result = new ArrayList<>();
        result.addAll(jdbc.query("""
                SELECT owner_service, code_set_key
                  FROM sys_code_sets
                 WHERE lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> inferred(
                ref("SERVICE", row.getString("owner_service")),
                ref("CODE_SET", row.getString("code_set_key")),
                "GOVERNS", "OPERATIONAL", row.getString("code_set_key"))));
        result.addAll(jdbc.query("""
                SELECT consumer_service, code_set_key, usage_type, source_reference,
                       enforcement_type
                  FROM sys_code_bindings
                 WHERE lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> inferred(
                ref("SERVICE", row.getString("consumer_service")),
                ref("CODE_SET", row.getString("code_set_key")),
                "CONSUMES",
                "CHECK".equals(row.getString("enforcement_type")) ? "CRITICAL" : "OPERATIONAL",
                row.getString("source_reference"),
                metadata("usageType", row.getString("usage_type"),
                        "enforcementType", row.getString("enforcement_type")))));
        return result;
    }

    private List<CatalogDtos.Relation> navigationRelations(Long tenantId) {
        List<CatalogDtos.Relation> result = new ArrayList<>();
        result.addAll(jdbc.query("""
                SELECT item.navigation_key, item.registry_entry_key,
                       item.required_resource_key, item.required_permission_code,
                       parent.navigation_key AS parent_key
                  FROM adm_navigation_items item
                  LEFT JOIN adm_navigation_items parent
                    ON parent.tenant_id = item.tenant_id
                   AND parent.navigation_item_id = item.parent_navigation_item_id
                 WHERE item.tenant_id = ?
                """, (row, ignored) -> {
            List<CatalogDtos.Relation> edges = new ArrayList<>();
            String navigationRef = ref("NAVIGATION", row.getString("navigation_key"));
            String registryKey = row.getString("registry_entry_key");
            if (registryKey != null) {
                edges.add(inferred(ref("REGISTRY", "APP:" + registryKey), navigationRef,
                        "NAVIGATES_TO", "OPERATIONAL", "adm_navigation_items.registry_entry_key"));
            }
            String resourceKey = row.getString("required_resource_key");
            if (resourceKey != null) {
                edges.add(inferred(navigationRef,
                        ref("PERMISSION", resourceKey + "/" + row.getString("required_permission_code")),
                        "REQUIRES_PERMISSION", "CRITICAL",
                        "adm_navigation_items.required_resource_key"));
            }
            String parentKey = row.getString("parent_key");
            if (parentKey != null) {
                edges.add(inferred(ref("NAVIGATION", parentKey), navigationRef,
                        "EXPOSES", "INFORMATIONAL", "adm_navigation_items.parent_navigation_item_id"));
            }
            return edges;
        }, tenantId).stream().flatMap(List::stream).toList());
        return result;
    }

    private List<CatalogDtos.Relation> connectorRelations(Long tenantId) {
        return jdbc.query("""
                SELECT connector.connector_key, registry.entry_key
                  FROM int_productivity_connectors connector
                  JOIN LATERAL (
                    SELECT entry_key
                      FROM adm_registry_entries entry
                     WHERE entry.tenant_id = connector.tenant_id
                       AND entry.registry_type = 'CONNECTOR'
                       AND entry.lifecycle_state <> 'RETIRED'
                       AND (entry.entry_key = connector.connector_key
                            OR UPPER(entry.name) = UPPER(connector.display_name))
                     ORDER BY entry.revision DESC
                     LIMIT 1
                  ) registry ON TRUE
                 WHERE connector.tenant_id = ?
                """, (row, ignored) -> inferred(
                ref("REGISTRY", "CONNECTOR:" + row.getString("entry_key")),
                ref("CONNECTOR_INSTANCE", row.getString("connector_key")),
                "SYNCHRONIZES", "CRITICAL", "int_productivity_connectors.connector_key"), tenantId);
    }

    private CatalogDtos.Relation findByNaturalKey(
            Long tenantId, String sourceRef, String targetRef, String relationType) {
        List<CatalogDtos.Relation> rows = jdbc.query("""
                SELECT catalog_relation_id, source_ref, target_ref, relation_type,
                       relation_origin, criticality, evidence_ref, metadata::text,
                       lifecycle_state, version
                  FROM adm_catalog_relations
                 WHERE tenant_id = ? AND source_ref = ? AND target_ref = ? AND relation_type = ?
                """, this::mapRelation, tenantId, sourceRef, targetRef, relationType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CatalogDtos.Relation findById(Long tenantId, UUID relationId) {
        List<CatalogDtos.Relation> rows = jdbc.query("""
                SELECT catalog_relation_id, source_ref, target_ref, relation_type,
                       relation_origin, criticality, evidence_ref, metadata::text,
                       lifecycle_state, version
                  FROM adm_catalog_relations
                 WHERE tenant_id = ? AND catalog_relation_id = ?
                """, this::mapRelation, tenantId, relationId);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private CatalogDtos.Relation mapRelation(ResultSet row, int ignored) throws SQLException {
        return new CatalogDtos.Relation(
                row.getObject("catalog_relation_id", UUID.class),
                row.getString("source_ref"), row.getString("target_ref"),
                row.getString("relation_type"), row.getString("relation_origin"),
                row.getString("criticality"), row.getString("evidence_ref"),
                json(row.getString("metadata")), row.getString("lifecycle_state"),
                row.getLong("version"));
    }

    private CatalogDtos.Relation inferred(
            String sourceRef, String targetRef, String relationType,
            String criticality, String evidenceRef) {
        return inferred(sourceRef, targetRef, relationType, criticality, evidenceRef,
                objectMapper.createObjectNode());
    }

    private CatalogDtos.Relation inferred(
            String sourceRef, String targetRef, String relationType,
            String criticality, String evidenceRef, JsonNode metadata) {
        return new CatalogDtos.Relation(
                null, sourceRef, targetRef, relationType, "DISCOVERED", criticality,
                evidenceRef, metadata, "ACTIVE", 0);
    }

    private CatalogDtos.Entity entity(
            String ref, String kind, String key, String name, String description,
            String ownerRef, String lifecycleState, String riskTier, String scope,
            long revision, JsonNode metadata) {
        return new CatalogDtos.Entity(
                canonical(ref), kind, key, name, description, ownerRef, lifecycleState,
                riskTier, scope, revision, metadata);
    }

    private ObjectNode metadata(Object... values) {
        ObjectNode metadata = objectMapper.createObjectNode();
        for (int index = 0; index + 1 < values.length; index += 2) {
            String key = String.valueOf(values[index]);
            Object value = values[index + 1];
            if (value == null) metadata.putNull(key);
            else if (value instanceof Number number) metadata.put(key, number.longValue());
            else if (value instanceof Boolean bool) metadata.put(key, bool);
            else metadata.put(key, String.valueOf(value));
        }
        return metadata;
    }

    private JsonNode json(String value) {
        try {
            return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Catalog metadata is invalid.", exception);
        }
    }

    private String jsonText(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Catalog metadata is invalid.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String ref(String kind, String key) {
        return canonical(kind + ":" + key);
    }

    private String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void putRelation(Map<String, CatalogDtos.Relation> relations, CatalogDtos.Relation relation) {
        String key = relation.sourceRef() + "|" + relation.targetRef() + "|" + relation.relationType();
        relations.putIfAbsent(key, relation);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Catalog relation changed after it was loaded. Refresh and try again.");
    }

    public record FindingCandidate(
            String entityRef,
            String findingCode,
            String severity,
            JsonNode evidence) {
    }
}
