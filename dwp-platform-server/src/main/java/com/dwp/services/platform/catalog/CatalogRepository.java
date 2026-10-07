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
public class CatalogRepository extends CatalogRepositorySupport {


    public CatalogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
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

    public List<CatalogDtos.Entity> overviewEntities(
            Long tenantId,
            String normalizedQuery,
            String normalizedKind,
            String normalizedLifecycle,
            int fetchLimit) {
        return jdbc.query(INVENTORY_PROJECTION + """
                SELECT inventory.ref, inventory.kind, inventory.entity_key,
                       inventory.name, inventory.description, inventory.owner_ref,
                       inventory.lifecycle_state, inventory.risk_tier, inventory.scope,
                       inventory.revision, inventory.metadata::text
                  FROM inventory
                  CROSS JOIN (SELECT ?::text AS query, ?::text AS kind,
                                     ?::text AS lifecycle) criteria
                 WHERE (criteria.query IS NULL
                        OR POSITION(criteria.query IN LOWER(BTRIM(ref))) > 0
                        OR POSITION(criteria.query IN LOWER(BTRIM(name))) > 0
                        OR POSITION(criteria.query IN LOWER(BTRIM(COALESCE(owner_ref, '')))) > 0)
                   AND (criteria.kind IS NULL OR inventory.kind = criteria.kind)
                   AND (criteria.lifecycle IS NULL
                        OR inventory.lifecycle_state = criteria.lifecycle)
                 ORDER BY inventory.kind, LOWER(inventory.name), inventory.ref
                 LIMIT ?
                """, this::mapEntity, tenantId, tenantId, tenantId, tenantId, tenantId,
                normalizedQuery, normalizedKind, normalizedLifecycle, fetchLimit);
    }

    public OverviewMetrics overviewMetrics(Long tenantId) {
        return jdbc.queryForObject(INVENTORY_PROJECTION + """
                , navigation_relation_sources AS (
                    SELECT item.navigation_item_id, item.navigation_key,
                           item.registry_entry_key, item.required_resource_key,
                           item.required_permission_code,
                           parent.navigation_key AS parent_key
                      FROM adm_navigation_items item
                      LEFT JOIN adm_navigation_items parent
                        ON parent.tenant_id = item.tenant_id
                       AND parent.navigation_item_id = item.parent_navigation_item_id
                     WHERE item.tenant_id = ?
                ),
                relation_candidates AS (
                    SELECT 0 AS source_rank, 0::bigint AS stable_order,
                           catalog_relation_id, source_ref, target_ref,
                           relation_type, criticality
                      FROM adm_catalog_relations
                     WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                    UNION ALL
                    SELECT 1, 0, NULL::uuid,
                           UPPER(BTRIM('SERVICE:' || owner_service)),
                           UPPER(BTRIM('CODE_SET:' || code_set_key)),
                           'GOVERNS', 'OPERATIONAL'
                      FROM sys_code_sets
                     WHERE lifecycle_state = 'ACTIVE'
                    UNION ALL
                    SELECT 2, code_binding_id, NULL::uuid,
                           UPPER(BTRIM('SERVICE:' || consumer_service)),
                           UPPER(BTRIM('CODE_SET:' || code_set_key)),
                           'CONSUMES',
                           CASE WHEN enforcement_type = 'CHECK'
                                THEN 'CRITICAL' ELSE 'OPERATIONAL' END
                      FROM sys_code_bindings
                     WHERE lifecycle_state = 'ACTIVE'
                    UNION ALL
                    SELECT 3, navigation_item_id, NULL::uuid,
                           UPPER(BTRIM('REGISTRY:APP:' || registry_entry_key)),
                           UPPER(BTRIM('NAVIGATION:' || navigation_key)),
                           'NAVIGATES_TO', 'OPERATIONAL'
                      FROM navigation_relation_sources
                     WHERE registry_entry_key IS NOT NULL
                    UNION ALL
                    SELECT 3, navigation_item_id, NULL::uuid,
                           UPPER(BTRIM('NAVIGATION:' || navigation_key)),
                           UPPER(BTRIM('PERMISSION:' || required_resource_key || '/'
                               || required_permission_code)),
                           'REQUIRES_PERMISSION', 'CRITICAL'
                      FROM navigation_relation_sources
                     WHERE required_resource_key IS NOT NULL
                    UNION ALL
                    SELECT 3, navigation_item_id, NULL::uuid,
                           UPPER(BTRIM('NAVIGATION:' || parent_key)),
                           UPPER(BTRIM('NAVIGATION:' || navigation_key)),
                           'EXPOSES', 'INFORMATIONAL'
                      FROM navigation_relation_sources
                     WHERE parent_key IS NOT NULL
                    UNION ALL
                    SELECT 4, 0, NULL::uuid,
                           UPPER(BTRIM('REGISTRY:CONNECTOR:' || registry.entry_key)),
                           UPPER(BTRIM('CONNECTOR_INSTANCE:' || connector.connector_key)),
                           'SYNCHRONIZES', 'CRITICAL'
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
                ),
                ranked_relations AS (
                    SELECT candidate.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY source_ref, target_ref, relation_type
                               ORDER BY source_rank, stable_order) AS relation_rank
                      FROM relation_candidates candidate
                ),
                live_relations AS (
                    SELECT relation.catalog_relation_id, relation.source_ref,
                           relation.target_ref, relation.criticality
                      FROM ranked_relations relation
                     WHERE relation.relation_rank = 1
                       AND EXISTS (SELECT 1 FROM inventory source
                                    WHERE source.ref = relation.source_ref)
                       AND EXISTS (SELECT 1 FROM inventory target
                                    WHERE target.ref = relation.target_ref)
                )
                SELECT (SELECT COUNT(*) FROM inventory) AS entity_count,
                       (SELECT COUNT(*) FROM live_relations) AS relation_count,
                       (SELECT COUNT(*) FROM live_relations
                         WHERE catalog_relation_id IS NOT NULL) AS declared_relation_count,
                       (SELECT COUNT(*) FROM inventory entity
                         WHERE entity.kind NOT IN ('SERVICE', 'PERMISSION', 'NAVIGATION')
                           AND NOT EXISTS (
                               SELECT 1 FROM live_relations relation
                                WHERE relation.source_ref = entity.ref
                                   OR relation.target_ref = entity.ref)) AS orphan_count,
                       (SELECT COUNT(*) FROM live_relations
                         WHERE criticality = 'CRITICAL') AS critical_relation_count,
                       COALESCE((
                           SELECT jsonb_object_agg(counts.kind, counts.total ORDER BY counts.kind)
                             FROM (SELECT kind, COUNT(*) AS total
                                     FROM inventory GROUP BY kind) counts
                       ), '{}'::jsonb)::text AS entities_by_kind,
                       COALESCE((
                           SELECT jsonb_object_agg(
                                      counts.lifecycle_state, counts.total
                                      ORDER BY counts.lifecycle_state)
                             FROM (SELECT lifecycle_state, COUNT(*) AS total
                                     FROM inventory GROUP BY lifecycle_state) counts
                       ), '{}'::jsonb)::text AS entities_by_lifecycle
                """, (result, ignored) -> new OverviewMetrics(
                result.getLong("entity_count"), result.getLong("relation_count"),
                result.getLong("declared_relation_count"), result.getLong("orphan_count"),
                result.getLong("critical_relation_count"),
                longMap(result.getString("entities_by_kind")),
                longMap(result.getString("entities_by_lifecycle"))),
                tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId);
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
            List<FindingCandidate> candidates,
            int fetchLimit) {
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
        return findings(tenantId, fetchLimit);
    }

    public AssuranceMetrics assuranceMetrics(Long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total_count,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('OPEN', 'ACKNOWLEDGED')) AS open_count,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('OPEN', 'ACKNOWLEDGED')
                             AND severity = 'CRITICAL') AS critical_count,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('OPEN', 'ACKNOWLEDGED')
                             AND finding_code = 'OWNER_MISSING') AS owner_missing_count,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('OPEN', 'ACKNOWLEDGED')
                             AND finding_code = 'DEPRECATION_IMPACT') AS deprecation_impact_count
                  FROM adm_catalog_assurance_findings
                 WHERE tenant_id = ?
                """, (result, ignored) -> new AssuranceMetrics(
                result.getLong("total_count"), result.getLong("open_count"),
                result.getLong("critical_count"), result.getLong("owner_missing_count"),
                result.getLong("deprecation_impact_count")), tenantId);
    }

    public List<CatalogDtos.AssuranceFinding> findings(Long tenantId, int fetchLimit) {
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
                          last_detected_at DESC, entity_ref, finding_code, catalog_finding_id
                 LIMIT ?
                """, this::mapFinding, tenantId, fetchLimit);
    }

    public CatalogDtos.AssuranceFinding finding(Long tenantId, UUID findingId) {
        return requireFinding(tenantId, findingId);
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

    private CatalogDtos.Entity mapEntity(ResultSet row, int ignored) throws SQLException {
        return new CatalogDtos.Entity(
                row.getString("ref"), row.getString("kind"), row.getString("entity_key"),
                row.getString("name"), row.getString("description"),
                row.getString("owner_ref"), row.getString("lifecycle_state"),
                row.getString("risk_tier"), row.getString("scope"), row.getLong("revision"),
                json(row.getString("metadata")));
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

    public record FindingCandidate(
            String entityRef,
            String findingCode,
            String severity,
            JsonNode evidence) {
    }

    public record OverviewMetrics(
            long entityCount,
            long relationCount,
            long declaredRelationCount,
            long orphanCount,
            long criticalRelationCount,
            Map<String, Long> entitiesByKind,
            Map<String, Long> entitiesByLifecycle) {
    }

    public record AssuranceMetrics(
            long totalCount,
            long openCount,
            long criticalCount,
            long ownerMissingCount,
            long deprecationImpactCount) {
    }
}
