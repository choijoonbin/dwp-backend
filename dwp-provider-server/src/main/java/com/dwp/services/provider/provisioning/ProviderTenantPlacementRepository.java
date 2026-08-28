package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class ProviderTenantPlacementRepository {

    private final JdbcTemplate jdbc;

    public ProviderTenantPlacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TenantPlacement initializeOrValidate(
            UUID tenantId,
            String region,
            String isolationModel,
            Set<String> expectedServiceKeys,
            Long operatorId,
            boolean allowCreate) {
        requireTransaction();
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "dwp-provider-placement:" + region);

        List<String> activeServiceKeys = jdbc.queryForList("""
                SELECT service_key
                  FROM prv_service_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY provisioning_order, service_key
                """, String.class);
        if (!new HashSet<>(activeServiceKeys).equals(Set.copyOf(expectedServiceKeys))) {
            throw invalidState("The active provider service catalog does not match the onboarding contract.");
        }

        List<ServicePlacementRow> existing = jdbc.query("""
                SELECT service_key, deployment_cell_id, lifecycle_state,
                       external_resource_id, endpoint_reference, applied_schema_version,
                       configuration_schema_version,
                       configuration::text AS configuration_json,
                       health_snapshot::text AS health_snapshot_json
                  FROM prv_tenant_service_instances
                 WHERE provider_tenant_id = ?
                 ORDER BY service_key
                """, (result, ignored) -> new ServicePlacementRow(
                result.getString("service_key"),
                result.getObject("deployment_cell_id", UUID.class),
                result.getString("lifecycle_state"), result.getString("external_resource_id"),
                result.getString("endpoint_reference"),
                result.getObject("applied_schema_version", Integer.class),
                result.getInt("configuration_schema_version"),
                result.getString("configuration_json"),
                result.getString("health_snapshot_json")), tenantId);
        if (!existing.isEmpty()) {
            return requireReusablePlacement(region, isolationModel, activeServiceKeys, existing);
        }
        if (!allowCreate) {
            throw invalidState("The persisted onboarding foundation has no provider service placement.");
        }

        CellPlacement cell = selectAvailableCell(region, isolationModel);
        String capturedRows = String.join(", ",
                Collections.nCopies(activeServiceKeys.size(), "(?)"));
        List<Object> insertParameters = new ArrayList<>();
        insertParameters.add(tenantId);
        insertParameters.add(cell.cellId());
        insertParameters.add(operatorId);
        insertParameters.add(operatorId);
        insertParameters.addAll(activeServiceKeys);
        int inserted = jdbc.update("""
                INSERT INTO prv_tenant_service_instances (
                    provider_tenant_id, service_key, deployment_cell_id,
                    lifecycle_state, created_by, updated_by)
                SELECT ?, expected.service_key, ?, 'PROVISIONING', ?, ?
                  FROM (VALUES %s) AS expected(service_key)
                ON CONFLICT (provider_tenant_id, service_key) DO NOTHING
                """.formatted(capturedRows), insertParameters.toArray());
        List<String> placedServiceKeys = jdbc.queryForList("""
                SELECT service_key
                  FROM prv_tenant_service_instances
                 WHERE provider_tenant_id = ?
                 ORDER BY service_key
                """, String.class, tenantId);
        if (inserted != activeServiceKeys.size()
                || !placedServiceKeys.equals(activeServiceKeys.stream().sorted().toList())) {
            throw invalidState("Provider service placement did not create the exact active service set.");
        }
        return new TenantPlacement(cell.cellId(), inserted, false);
    }

    public void updateServiceInstance(
            UUID tenantId,
            String serviceKey,
            String state,
            String externalReference,
            Integer appliedSchemaVersion,
            String healthSnapshot,
            Long operatorId) {
        if ("READY".equals(state)
                && (appliedSchemaVersion == null || appliedSchemaVersion < 1)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A positive applied schema version is required for a ready service instance.");
        }
        int updated = jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET lifecycle_state = ?,
                       external_resource_id = COALESCE(?, external_resource_id),
                       applied_schema_version = CASE WHEN ? = 'READY' THEN ? ELSE applied_schema_version END,
                       health_snapshot = CAST(? AS jsonb),
                       last_reconciled_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE provider_tenant_id = ? AND service_key = ?
                """, state, externalReference, state, appliedSchemaVersion,
                healthSnapshot, operatorId, tenantId, serviceKey);
        if (updated != 1) {
            throw invalidState("The provider service instance is missing or no longer unique.");
        }
    }

    private CellPlacement selectAvailableCell(String region, String isolationModel) {
        return jdbc.query("""
                SELECT cell.deployment_cell_id,
                       cell.placement_capacity,
                       placement.tenant_count
                  FROM prv_deployment_cells cell
                  JOIN prv_regions region_catalog
                    ON region_catalog.region_key = cell.region_key
                  CROSS JOIN LATERAL (
                      SELECT COUNT(DISTINCT instance.provider_tenant_id)::INTEGER AS tenant_count
                        FROM prv_tenant_service_instances instance
                       WHERE instance.deployment_cell_id = cell.deployment_cell_id
                         AND instance.lifecycle_state <> 'RETIRED'
                  ) placement
                 WHERE region_catalog.region_key = ?
                   AND region_catalog.lifecycle_state = 'ACTIVE'
                   AND cell.lifecycle_state = 'ACTIVE'
                   AND cell.supported_isolation_models @> jsonb_build_array(CAST(? AS text))
                   AND placement.tenant_count < cell.placement_capacity
                 ORDER BY placement.tenant_count, cell.cell_key
                 LIMIT 1
                 FOR UPDATE OF region_catalog, cell
                """, (result, ignored) -> new CellPlacement(
                result.getObject("deployment_cell_id", UUID.class),
                result.getInt("placement_capacity"),
                result.getInt("tenant_count")), region, isolationModel)
                .stream().findFirst()
                .orElseThrow(() -> invalidState(
                        "No compatible provider deployment cell has placement capacity."));
    }

    private TenantPlacement requireReusablePlacement(
            String region,
            String isolationModel,
            List<String> activeServiceKeys,
            List<ServicePlacementRow> existing) {
        Set<String> existingKeys = new HashSet<>();
        Set<UUID> cellIds = new HashSet<>();
        for (ServicePlacementRow row : existing) {
            existingKeys.add(row.serviceKey());
            if (row.cellId() == null) {
                throw invalidState("Existing provider service placement has no deployment cell.");
            }
            cellIds.add(row.cellId());
            if (!"PROVISIONING".equals(row.lifecycleState())
                    || row.externalReference() != null
                    || row.endpointReference() != null
                    || row.appliedSchemaVersion() != null
                    || row.configurationSchemaVersion() != 1
                    || !"{}".equals(row.configuration())
                    || !"{}".equals(row.healthSnapshot())) {
                throw invalidState(
                        "Existing provider service placement is not an untouched control record.");
            }
        }
        if (existing.size() != activeServiceKeys.size()
                || !existingKeys.equals(new HashSet<>(activeServiceKeys))
                || cellIds.size() != 1) {
            throw invalidState("Existing provider service placement does not match the onboarding plan.");
        }

        UUID cellId = cellIds.iterator().next();
        CellPlacement cell = jdbc.query("""
                SELECT cell.deployment_cell_id,
                       cell.placement_capacity,
                       placement.tenant_count
                  FROM prv_deployment_cells cell
                  JOIN prv_regions region_catalog
                    ON region_catalog.region_key = cell.region_key
                  CROSS JOIN LATERAL (
                      SELECT COUNT(DISTINCT instance.provider_tenant_id)::INTEGER AS tenant_count
                        FROM prv_tenant_service_instances instance
                       WHERE instance.deployment_cell_id = cell.deployment_cell_id
                         AND instance.lifecycle_state <> 'RETIRED'
                  ) placement
                 WHERE cell.deployment_cell_id = ?
                   AND cell.region_key = ?
                   AND region_catalog.lifecycle_state IN ('ACTIVE', 'DRAINING')
                   AND cell.lifecycle_state IN ('ACTIVE', 'DRAINING')
                   AND cell.supported_isolation_models @> jsonb_build_array(CAST(? AS text))
                 FOR UPDATE OF region_catalog, cell
                """, (result, ignored) -> new CellPlacement(
                result.getObject("deployment_cell_id", UUID.class),
                result.getInt("placement_capacity"),
                result.getInt("tenant_count")), cellId, region, isolationModel)
                .stream().findFirst()
                .orElseThrow(() -> invalidState(
                        "Existing provider service placement is not compatible with the onboarding plan."));
        if (cell.tenantCount() > cell.capacity()) {
            throw invalidState("Existing provider service placement exceeds deployment cell capacity.");
        }
        return new TenantPlacement(cell.cellId(), existing.size(), true);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant placement requires an active transaction.");
        }
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    public record TenantPlacement(UUID cellId, int serviceCount, boolean reused) {
    }

    private record ServicePlacementRow(
            String serviceKey,
            UUID cellId,
            String lifecycleState,
            String externalReference,
            String endpointReference,
            Integer appliedSchemaVersion,
            int configurationSchemaVersion,
            String configuration,
            String healthSnapshot) {
    }

    private record CellPlacement(UUID cellId, int capacity, int tenantCount) {
    }
}
