package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration owner page operations shared by the owner facade. */
final class JdbcIntegrationOwnerPage {
    private final JdbcTemplate jdbc;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationOwnerPage(
            JdbcTemplate jdbc,
            IntegrationCanonicalJson canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public OwnerPage ownerPage(
            TrustedOwnerQuery query,
            String afterItemPublicId,
            List<String> allowedActions) {
        OffsetDateTime asOf = OffsetDateTime.ofInstant(
                query.asOf(), java.time.ZoneOffset.UTC);
        String sortExpression = switch (query.sort().split(":", 2)[0]) {
            case "connectorMetadata" -> "LOWER(connector_key)";
            case "policyVersion" -> "definition_version";
            case "executionPolicy" ->
                    "COALESCE(direction, '') || ':' || "
                            + "COALESCE(compatibility_mode, '')";
            case "effectiveStatus" -> "definition_state";
            default -> "public_id::text";
        };
        boolean descending = query.sort().endsWith(":DESC");
        StringBuilder sql = new StringBuilder("""
                WITH bounds AS (
                    SELECT ?::timestamptz AS as_of
                ), owner_rows AS (
                    SELECT d.public_id, d.connector_key,
                           (1
                            + CASE WHEN v.validated_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.tested_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.submitted_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.approved_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.activated_at <= b.as_of THEN 1 ELSE 0 END
                            + COALESCE((
                                SELECT SUM(1
                                    + CASE WHEN mv.validated_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.dry_run_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.submitted_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.approved_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.activated_at <= b.as_of
                                        THEN 1 ELSE 0 END)
                                  FROM sys_connector_mapping_versions mv
                                 WHERE mv.tenant_id = d.tenant_id
                                   AND mv.definition_id = d.definition_id
                                   AND mv.created_at <= b.as_of), 0))::bigint
                               AS aggregate_revision,
                           GREATEST(d.created_at, v.state_updated_at,
                               COALESCE(m.state_updated_at, d.created_at))
                               AS definition_updated_at,
                           v.connector_type,
                           v.version_number AS definition_version,
                           v.state_as_of AS definition_state,
                           m.version_number AS mapping_version,
                           m.state_as_of AS mapping_state,
                           m.direction, m.compatibility_mode, m.effective_from
                      FROM sys_connector_definitions d
                      CROSS JOIN bounds b
                      JOIN LATERAL (
                           SELECT version.*,
                                  CASE
                                    WHEN version.activated_at <= b.as_of
                                        THEN 'ACTIVE'
                                    WHEN version.approved_at <= b.as_of
                                        THEN 'APPROVED'
                                    WHEN version.submitted_at <= b.as_of
                                        THEN 'PENDING_APPROVAL'
                                    WHEN version.tested_at <= b.as_of
                                        THEN 'TESTED'
                                    WHEN version.validated_at <= b.as_of
                                        THEN 'VALIDATED'
                                    ELSE 'DRAFT'
                                  END AS state_as_of,
                                  GREATEST(version.created_at,
                                    CASE WHEN version.validated_at <= b.as_of
                                        THEN version.validated_at END,
                                    CASE WHEN version.tested_at <= b.as_of
                                        THEN version.tested_at END,
                                    CASE WHEN version.submitted_at <= b.as_of
                                        THEN version.submitted_at END,
                                    CASE WHEN version.approved_at <= b.as_of
                                        THEN version.approved_at END,
                                    CASE WHEN version.activated_at <= b.as_of
                                        THEN version.activated_at END)
                                      AS state_updated_at
                             FROM sys_connector_definition_versions version
                            WHERE version.tenant_id = d.tenant_id
                              AND version.definition_id = d.definition_id
                              AND version.created_at <= b.as_of
                            ORDER BY version.version_number DESC LIMIT 1
                      ) v ON TRUE
                      LEFT JOIN LATERAL (
                           SELECT mapped.*
                             FROM (
                                  SELECT mapping.*,
                                         CASE
                                           WHEN mapping.activated_at <= b.as_of
                                               THEN 'ACTIVE'
                                           WHEN mapping.approved_at <= b.as_of
                                               THEN 'APPROVED'
                                           WHEN mapping.submitted_at <= b.as_of
                                               THEN 'PENDING_APPROVAL'
                                           WHEN mapping.validated_at <= b.as_of
                                               THEN 'VALIDATED'
                                           ELSE 'DRAFT'
                                         END AS state_as_of,
                                         GREATEST(mapping.created_at,
                                           CASE WHEN mapping.validated_at <= b.as_of
                                               THEN mapping.validated_at END,
                                           CASE WHEN mapping.dry_run_at <= b.as_of
                                               THEN mapping.dry_run_at END,
                                           CASE WHEN mapping.submitted_at <= b.as_of
                                               THEN mapping.submitted_at END,
                                           CASE WHEN mapping.approved_at <= b.as_of
                                               THEN mapping.approved_at END,
                                           CASE WHEN mapping.activated_at <= b.as_of
                                               THEN mapping.activated_at END)
                                             AS state_updated_at
                                    FROM sys_connector_mapping_versions mapping
                                   WHERE mapping.tenant_id = d.tenant_id
                                     AND mapping.definition_id = d.definition_id
                                     AND mapping.created_at <= b.as_of
                             ) mapped
                            ORDER BY (mapped.state_as_of = 'ACTIVE') DESC,
                                     mapped.version_number DESC LIMIT 1
                      ) m ON TRUE
                     WHERE d.tenant_id = ? AND d.created_at <= b.as_of
                )
                SELECT * FROM owner_rows WHERE TRUE
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(asOf);
        arguments.add(query.tenantId());
        appendOwnerFilters(sql, arguments, query.filters());
        if (afterItemPublicId != null) {
            String comparator = descending ? " < " : " > ";
            if ("public_id::text".equals(sortExpression)) {
                sql.append(" AND public_id::text").append(comparator)
                        .append("?");
                arguments.add(afterItemPublicId);
            } else {
                sql.append(" AND ((").append(sortExpression).append(')')
                        .append(comparator).append("(SELECT ")
                        .append(sortExpression)
                        .append(" FROM owner_rows WHERE public_id::text = ?)")
                        .append(" OR ((").append(sortExpression)
                        .append(") = (SELECT ").append(sortExpression)
                        .append(" FROM owner_rows WHERE public_id::text = ?)")
                        .append(" AND public_id::text > ?))");
                arguments.add(afterItemPublicId);
                arguments.add(afterItemPublicId);
                arguments.add(afterItemPublicId);
            }
        }
        sql.append(" ORDER BY ").append(sortExpression)
                .append(descending ? " DESC" : " ASC")
                .append(", public_id::text ASC LIMIT ?");
        arguments.add(query.limit() + 1);
        List<OwnerRow> rows = jdbc.query(sql.toString(), (rs, row) -> new OwnerRow(
                uuid(rs, "public_id"), rs.getString("connector_key"),
                rs.getLong("aggregate_revision"),
                rs.getObject("definition_updated_at", OffsetDateTime.class),
                rs.getString("connector_type"),
                rs.getLong("definition_version"),
                rs.getString("definition_state"),
                nullableLong(rs, "mapping_version"),
                rs.getString("mapping_state"), rs.getString("direction"),
                rs.getString("compatibility_mode"),
                rs.getObject("effective_from", OffsetDateTime.class)),
                arguments.toArray());
        boolean hasMore = rows.size() > query.limit();
        List<OwnerRow> selected = rows.stream().limit(query.limit()).toList();
        List<OwnerItem> items = selected.stream().map(row -> {
            EnumMap<FieldGroup, Object> fields = new EnumMap<>(FieldGroup.class);
            fields.put(FieldGroup.CONNECTOR_METADATA, Map.of(
                    "connectorKey", row.connectorKey(),
                    "connectorType", row.connectorType()));
            fields.put(FieldGroup.POLICY_VERSION, Map.of(
                    "definitionVersion", row.definitionVersion(),
                    "mappingVersion", row.mappingVersion() == null
                            ? 0L : row.mappingVersion()));
            fields.put(FieldGroup.EXECUTION_POLICY, Map.of(
                    "direction", row.direction() == null ? "UNAVAILABLE" : row.direction(),
                    "compatibilityMode", row.compatibilityMode() == null
                            ? "UNAVAILABLE" : row.compatibilityMode()));
            fields.put(FieldGroup.EFFECTIVE_STATUS, Map.of(
                    "definitionState", row.definitionState(),
                    "mappingState", row.mappingState() == null
                            ? "UNAVAILABLE" : row.mappingState(),
                    "effectiveOn", row.effectiveFrom() == null
                            ? row.updatedAt().toString()
                            : row.effectiveFrom().toString()));
            return new OwnerItem(row.publicId(), row.revision(), fields,
                    itemActions(row, allowedActions));
        }).toList();
        String receipt = canonical.digest(Map.of(
                "tenantId", query.tenantId(),
                "projection", query.projection().name(),
                "asOf", query.asOf().toString(),
                "items", selected.stream().map(row ->
                        row.publicId() + ":" + row.revision()).toList()));
        return new OwnerPage(items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE,
                true, items, receipt,
                hasMore ? selected.getLast().publicId().toString() : null);
    }
}
