package com.dwp.services.platform.catalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

abstract class CatalogRepositorySupport {

    protected static final String INVENTORY_PROJECTION = """
            WITH registry_ranked AS (
                SELECT entry.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY registry_type, entry_key
                           ORDER BY CASE lifecycle_state
                               WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                               revision DESC) AS rank
                  FROM adm_registry_entries entry
                 WHERE tenant_id = ?
            ),
            inventory AS (
                SELECT UPPER(BTRIM('REFERENCE_SET:' || reference_set.set_key)) AS ref,
                       'REFERENCE_SET'::text AS kind,
                       reference_set.set_key::text AS entity_key,
                       reference_set.name::text AS name,
                       reference_set.description::text AS description,
                       'tenant-reference-owner'::text AS owner_ref,
                       reference_set.lifecycle_state::text AS lifecycle_state,
                       'MEDIUM'::text AS risk_tier,
                       'TENANT'::text AS scope,
                       reference_set.content_revision::bigint AS revision,
                       jsonb_build_object(
                           'itemCount', COUNT(reference_item.reference_item_id),
                           'activeItemCount', COUNT(reference_item.reference_item_id)
                               FILTER (WHERE reference_item.lifecycle_state = 'ACTIVE')) AS metadata
                  FROM adm_reference_sets reference_set
                  LEFT JOIN adm_reference_items reference_item
                    ON reference_item.tenant_id = reference_set.tenant_id
                   AND reference_item.reference_set_id = reference_set.reference_set_id
                 WHERE reference_set.tenant_id = ?
                 GROUP BY reference_set.reference_set_id
                UNION ALL
                SELECT UPPER(BTRIM('REGISTRY:' || registry_type || ':' || entry_key)),
                       registry_type::text, entry_key::text, name::text,
                       description::text, owner_ref::text, lifecycle_state::text,
                       risk_tier::text, 'TENANT'::text, revision::bigint,
                       jsonb_build_object(
                           'artifactVersion', artifact_version,
                           'registryType', registry_type)
                  FROM registry_ranked
                 WHERE rank = 1
                UNION ALL
                SELECT UPPER(BTRIM('CODE_SET:' || code_set.code_set_key)),
                       'CODE_SET'::text, code_set.code_set_key::text,
                       code_set.display_name::text, code_set.description::text,
                       code_set.owner_service::text, code_set.lifecycle_state::text,
                       'MEDIUM'::text, 'GLOBAL_PRODUCT'::text,
                       code_set.schema_version::bigint,
                       jsonb_build_object(
                           'configurationLevel', code_set.configuration_level,
                           'validationSource', code_set.validation_source,
                           'runtimeVisibility', code_set.runtime_visibility,
                           'valueCount', COUNT(DISTINCT code_value.code),
                           'bindingCount', COUNT(DISTINCT code_binding.code_binding_id))
                  FROM sys_code_sets code_set
                  LEFT JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                  LEFT JOIN sys_code_bindings code_binding
                    ON code_binding.code_set_key = code_set.code_set_key
                   AND code_binding.lifecycle_state = 'ACTIVE'
                 GROUP BY code_set.code_set_key
                UNION ALL
                SELECT UPPER(BTRIM('SERVICE:' || service_name)),
                       'SERVICE'::text, service_name::text, service_name::text,
                       'DWP runtime service'::text, service_name::text,
                       'ACTIVE'::text, 'MEDIUM'::text, 'GLOBAL_PRODUCT'::text, 1::bigint,
                       jsonb_build_object(
                           'ownedCodeSets', COUNT(DISTINCT owned_code_set),
                           'consumedCodeSets', COUNT(DISTINCT consumed_code_set))
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
                UNION ALL
                SELECT UPPER(BTRIM('NAVIGATION:' || item.navigation_key)),
                       'NAVIGATION'::text, item.navigation_key::text,
                       COALESCE(
                           MAX(label.label) FILTER (WHERE LOWER(label.locale) = 'ko'),
                           MAX(label.label) FILTER (WHERE LOWER(label.locale) = 'en'),
                           item.navigation_key)::text,
                       item.route::text, 'tenant-experience-owner'::text,
                       item.lifecycle_state::text, 'LOW'::text, 'TENANT'::text,
                       item.version::bigint,
                       jsonb_build_object(
                           'itemType', item.item_type,
                           'route', item.route,
                           'iconKey', item.icon_key,
                           'resourceKey', item.required_resource_key,
                           'permissionCode', item.required_permission_code)
                  FROM adm_navigation_items item
                  LEFT JOIN adm_navigation_labels label
                    ON label.tenant_id = item.tenant_id
                   AND label.navigation_item_id = item.navigation_item_id
                 WHERE item.tenant_id = ?
                 GROUP BY item.navigation_item_id
                UNION ALL
                SELECT UPPER(BTRIM('CONNECTOR_INSTANCE:' || connector_key)),
                       'CONNECTOR_INSTANCE'::text, connector_key::text,
                       display_name::text, provider_type::text,
                       'tenant-integration-owner'::text, lifecycle_state::text,
                       'HIGH'::text, 'TENANT'::text, version::bigint,
                       jsonb_build_object(
                           'providerType', provider_type,
                           'authMode', auth_mode,
                           'healthState', health_state,
                           'policyState', policy_state,
                           'scopeCount', jsonb_array_length(requested_scopes),
                           'capabilityCount', jsonb_array_length(capabilities))
                  FROM int_productivity_connectors
                 WHERE tenant_id = ?
                UNION ALL
                SELECT DISTINCT
                       UPPER(BTRIM('PERMISSION:' || required_resource_key || '/'
                           || required_permission_code)),
                       'PERMISSION'::text,
                       (required_resource_key || '/' || required_permission_code)::text,
                       required_permission_code::text,
                       'Permission required by tenant navigation'::text,
                       'identity-and-access'::text, 'ACTIVE'::text, 'HIGH'::text,
                       'TENANT'::text, 1::bigint,
                       jsonb_build_object(
                           'resourceKey', required_resource_key,
                           'permissionCode', required_permission_code)
                  FROM adm_navigation_items
                 WHERE tenant_id = ? AND required_resource_key IS NOT NULL
            )
            """;

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;

    protected CatalogRepositorySupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    protected List<CatalogDtos.Entity> referenceSets(Long tenantId) {
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

    protected List<CatalogDtos.Entity> registryEntries(Long tenantId) {
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

    protected List<CatalogDtos.Entity> codeSets() {
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

    protected List<CatalogDtos.Entity> services() {
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

    protected List<CatalogDtos.Entity> navigation(Long tenantId) {
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

    protected List<CatalogDtos.Entity> connectorInstances(Long tenantId) {
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

    protected List<CatalogDtos.Entity> permissions(Long tenantId) {
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

    protected List<CatalogDtos.Relation> explicitRelations(Long tenantId) {
        return jdbc.query("""
                SELECT catalog_relation_id, source_ref, target_ref, relation_type,
                       relation_origin, criticality, evidence_ref, metadata::text,
                       lifecycle_state, version
                  FROM adm_catalog_relations
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY criticality DESC, relation_type, source_ref, target_ref
                """, this::mapRelation, tenantId);
    }

    protected List<CatalogDtos.Relation> codeRelations() {
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
                 ORDER BY code_binding_id
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

    protected List<CatalogDtos.Relation> navigationRelations(Long tenantId) {
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

    protected List<CatalogDtos.Relation> connectorRelations(Long tenantId) {
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

    protected CatalogDtos.Relation mapRelation(ResultSet row, int ignored) throws SQLException {
        return new CatalogDtos.Relation(
                row.getObject("catalog_relation_id", UUID.class),
                row.getString("source_ref"), row.getString("target_ref"),
                row.getString("relation_type"), row.getString("relation_origin"),
                row.getString("criticality"), row.getString("evidence_ref"),
                json(row.getString("metadata")), row.getString("lifecycle_state"),
                row.getLong("version"));
    }

    protected CatalogDtos.Relation inferred(
            String sourceRef, String targetRef, String relationType,
            String criticality, String evidenceRef) {
        return inferred(sourceRef, targetRef, relationType, criticality, evidenceRef,
                objectMapper.createObjectNode());
    }

    protected CatalogDtos.Relation inferred(
            String sourceRef, String targetRef, String relationType,
            String criticality, String evidenceRef, JsonNode metadata) {
        return new CatalogDtos.Relation(
                null, sourceRef, targetRef, relationType, "DISCOVERED", criticality,
                evidenceRef, metadata, "ACTIVE", 0);
    }

    protected CatalogDtos.Entity entity(
            String ref, String kind, String key, String name, String description,
            String ownerRef, String lifecycleState, String riskTier, String scope,
            long revision, JsonNode metadata) {
        return new CatalogDtos.Entity(
                canonical(ref), kind, key, name, description, ownerRef, lifecycleState,
                riskTier, scope, revision, metadata);
    }

    protected ObjectNode metadata(Object... values) {
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

    protected JsonNode json(String value) {
        try {
            return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Catalog metadata is invalid.", exception);
        }
    }

    protected Map<String, Long> longMap(String value) {
        JsonNode source = json(value);
        Map<String, Long> result = new LinkedHashMap<>();
        source.properties().forEach(entry ->
                result.put(entry.getKey(), entry.getValue().asLong()));
        return Map.copyOf(result);
    }

    protected String jsonText(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Catalog metadata is invalid.", exception);
        }
    }

    protected String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    protected String ref(String kind, String key) {
        return canonical(kind + ":" + key);
    }

    protected String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    protected void putRelation(Map<String, CatalogDtos.Relation> relations, CatalogDtos.Relation relation) {
        String key = relation.sourceRef() + "|" + relation.targetRef() + "|" + relation.relationType();
        relations.putIfAbsent(key, relation);
    }

    protected String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Catalog relation changed after it was loaded. Refresh and try again.");
    }

}
