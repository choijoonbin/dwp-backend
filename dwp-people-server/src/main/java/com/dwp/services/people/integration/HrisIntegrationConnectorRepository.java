package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class HrisIntegrationConnectorRepository extends HrisIntegrationJdbcRepository {
    HrisIntegrationConnectorRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    public long upsertSource(
            Long tenantId,
            Long actorId,
            String sourceKey,
            String sourceType,
            String name) {
        String sql = """
                INSERT INTO int_source_systems (
                    tenant_id, source_key, system_type, name,
                    authoritative_domains, lifecycle_state, created_by, updated_by)
                VALUES (
                    :tenantId, :sourceKey, :sourceType, :name,
                    CAST(:domains AS jsonb), 'ACTIVE', :actorId, :actorId)
                ON CONFLICT (tenant_id, source_key) DO UPDATE SET
                    system_type = EXCLUDED.system_type,
                    name = EXCLUDED.name,
                    authoritative_domains = EXCLUDED.authoritative_domains,
                    lifecycle_state = 'ACTIVE',
                    version = int_source_systems.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING source_system_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("sourceKey", sourceKey)
                .addValue("sourceType", sourceType)
                .addValue("name", name)
                .addValue("domains", "[\"PERSON\",\"WORKER\",\"ASSIGNMENT\"]"));
    }

    public void upsertReferenceConnector(Long tenantId, Long actorId, long sourceSystemId) {
        String sql = """
                INSERT INTO int_connector_instances (
                    tenant_id, source_system_id, connector_key, connector_type,
                    auth_mode, capabilities, lifecycle_state, health_state,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, 'workday-reference-file', 'FILE_IMPORT',
                    'NONE', CAST('["FULL","DELTA","EFFECTIVE_DATED"]' AS jsonb),
                    'ACTIVE', 'HEALTHY', :actorId, :actorId)
                ON CONFLICT (tenant_id, connector_key) DO UPDATE SET
                    source_system_id = EXCLUDED.source_system_id,
                    lifecycle_state = 'ACTIVE',
                    health_state = 'HEALTHY',
                    last_health_checked_at = CURRENT_TIMESTAMP,
                    version = int_connector_instances.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId).addValue("sourceSystemId", sourceSystemId));
    }

    public HrisDtos.ConnectorInstance createConnector(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisDtos.CreateConnectorRequest request) {
        UUID connectorId = jdbc.queryForObject("""
                INSERT INTO int_connector_instances (
                    tenant_id, source_system_id, connector_key, connector_type,
                    endpoint_uri, auth_mode, credential_reference, schedule_expression,
                    capabilities, lifecycle_state, health_state, created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, :connectorKey, :connectorType,
                    :endpointUri, :authMode, :credentialReference, :scheduleExpression,
                    CAST(:capabilities AS jsonb), 'DRAFT', 'UNKNOWN', :actorId, :actorId)
                RETURNING connector_instance_id
                """, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("connectorKey", request.connectorKey().trim().toLowerCase())
                .addValue("connectorType", request.connectorType())
                .addValue("endpointUri", trimToNull(request.endpointUri()))
                .addValue("authMode", request.authMode())
                .addValue("credentialReference", trimToNull(request.credentialReference()))
                .addValue("scheduleExpression", trimToNull(request.scheduleExpression()))
                .addValue("capabilities", "[\"FULL\",\"DELTA\",\"EFFECTIVE_DATED\"]"),
                UUID.class);
        if (connectorId == null) throw new IllegalStateException("Connector identifier was not returned.");
        return findConnector(tenantId, connectorId)
                .orElseThrow(() -> new IllegalStateException("Created connector is missing."));
    }

    public Optional<HrisDtos.ConnectorInstance> findConnector(Long tenantId, UUID connectorId) {
        return jdbc.query(connectorSelect() + """
                 WHERE connector.tenant_id = :tenantId
                   AND connector.connector_instance_id = :connectorId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId), this::mapConnector)
                .stream().findFirst();
    }

    public boolean updateConnector(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            HrisDtos.UpdateConnectorRequest request) {
        return jdbc.update("""
                UPDATE int_connector_instances
                   SET endpoint_uri = :endpointUri,
                       credential_reference = :credentialReference,
                       schedule_expression = :scheduleExpression,
                       lifecycle_state = :lifecycleState,
                       health_state = CASE
                           WHEN endpoint_uri IS DISTINCT FROM :endpointUri
                             OR credential_reference IS DISTINCT FROM :credentialReference
                           THEN 'UNKNOWN' ELSE health_state END,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND connector_instance_id = :connectorId
                   AND version = :version
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId)
                .addValue("endpointUri", trimToNull(request.endpointUri()))
                .addValue("credentialReference", trimToNull(request.credentialReference()))
                .addValue("scheduleExpression", trimToNull(request.scheduleExpression()))
                .addValue("lifecycleState", request.lifecycleState())
                .addValue("version", request.version())) == 1;
    }

    public void recordConfigurationCheck(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            String healthState) {
        jdbc.update("""
                UPDATE int_connector_instances
                   SET health_state = :healthState,
                       last_health_checked_at = CURRENT_TIMESTAMP,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND connector_instance_id = :connectorId
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId)
                .addValue("healthState", healthState));
    }

    public void auditConnector(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            String action,
            String correlationId,
            String snapshot) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action, target_type, target_id,
                    outcome, correlation_id, after_snapshot)
                VALUES (
                    :tenantId, 'USER', :actorId, :action, 'HRIS_CONNECTOR', :connectorId,
                    'SUCCESS', :correlationId, CAST(:snapshot AS jsonb))
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId.toString())
                .addValue("action", action)
                .addValue("correlationId", correlationId)
                .addValue("snapshot", snapshot));
    }

    public void upsertMappingProfile(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            String sourceSchemaVersion,
            JsonNode mappingDefinition) {
        String sql = """
                INSERT INTO int_mapping_profiles (
                    tenant_id, source_system_id, profile_key, adapter_type,
                    source_schema_version, target_schema_version, mapping_definition,
                    lifecycle_state, mapping_sha256, activated_at, activated_by,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, 'workday-reference-v1', 'WORKDAY_REFERENCE',
                    :sourceSchemaVersion, 'dwp.workforce-projection.v1',
                    CAST(:mappingDefinition AS jsonb), 'ACTIVE', :mappingSha256,
                    CURRENT_TIMESTAMP, :actorId, :actorId, :actorId)
                ON CONFLICT (tenant_id, source_system_id, profile_key) DO UPDATE SET
                    source_schema_version = EXCLUDED.source_schema_version,
                    target_schema_version = EXCLUDED.target_schema_version,
                    mapping_definition = EXCLUDED.mapping_definition,
                    mapping_sha256 = EXCLUDED.mapping_sha256,
                    lifecycle_state = 'ACTIVE',
                    activated_at = CURRENT_TIMESTAMP,
                    activated_by = EXCLUDED.activated_by,
                    version = int_mapping_profiles.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("sourceSchemaVersion", sourceSchemaVersion)
                .addValue("mappingDefinition", mappingDefinition.toString())
                .addValue("mappingSha256", sha256(mappingDefinition.toString())));
    }


    public List<HrisDtos.SourceSystem> listSources(Long tenantId) {
        return jdbc.query("""
                SELECT source_system_id, source_key, system_type, name, lifecycle_state, version
                  FROM int_source_systems
                 WHERE tenant_id = :tenantId
                 ORDER BY source_key
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, rowNum) ->
                new HrisDtos.SourceSystem(
                        rs.getLong("source_system_id"), rs.getString("source_key"),
                        rs.getString("system_type"), rs.getString("name"),
                        rs.getString("lifecycle_state"), rs.getLong("version")));
    }

    public List<HrisDtos.ConnectorInstance> listConnectors(Long tenantId) {
        return jdbc.query(connectorSelect() + """
                 WHERE connector.tenant_id = :tenantId
                 ORDER BY connector.connector_key
                """, new MapSqlParameterSource("tenantId", tenantId), this::mapConnector);
    }

    private String connectorSelect() {
        return """
                SELECT connector.connector_instance_id, connector.source_system_id,
                       source.source_key, connector.connector_key, connector.connector_type,
                       connector.endpoint_uri, connector.auth_mode, connector.credential_reference,
                       connector.schedule_expression, connector.lifecycle_state,
                       connector.health_state, connector.last_health_checked_at,
                       connector.last_successful_sync_at, connector.last_attempted_sync_at,
                       connector.last_error_code, connector.consecutive_failure_count,
                       connector.version
                  FROM int_connector_instances connector
                  JOIN int_source_systems source
                    ON source.tenant_id = connector.tenant_id
                   AND source.source_system_id = connector.source_system_id
                """;
    }

    private HrisDtos.ConnectorInstance mapConnector(
            java.sql.ResultSet rs,
            int rowNum) throws java.sql.SQLException {
        return new HrisDtos.ConnectorInstance(
                rs.getObject("connector_instance_id", UUID.class),
                rs.getLong("source_system_id"), rs.getString("source_key"),
                rs.getString("connector_key"), rs.getString("connector_type"),
                rs.getString("endpoint_uri"), rs.getString("auth_mode"),
                rs.getString("credential_reference"), rs.getString("schedule_expression"),
                rs.getString("lifecycle_state"), rs.getString("health_state"),
                instant(rs.getTimestamp("last_health_checked_at")),
                instant(rs.getTimestamp("last_successful_sync_at")),
                instant(rs.getTimestamp("last_attempted_sync_at")),
                rs.getString("last_error_code"), rs.getInt("consecutive_failure_count"),
                rs.getLong("version"));
    }

}
