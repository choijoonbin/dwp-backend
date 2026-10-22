package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RLS-bound IAQ-012 SQL page owner; restricted payload columns are never selected. */
@Repository
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class JdbcHrm012QuerySource implements Hrm012QuerySource {
    private static final UUID EMPTY_UUID = new UUID(0, 0);
    private final NamedParameterJdbcTemplate jdbc;

    JdbcHrm012QuerySource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page read(
            long tenantId,
            QuerySpec query,
            HcmPopulationRepository.PopulationScope population) {
        establishTenant(tenantId);
        if (query == null || query.asOf() == null || query.effectiveOn() == null
                || query.filter() == null || query.limit() < 1 || query.limit() > 100
                || query.sort().isEmpty() || population == null) {
            throw new IllegalArgumentException("IAQ-012 SQL page specification is invalid.");
        }
        Hrm012QueryModels.IngestionStatusFilter status =
                query.filter().ingestionStatus();
        Hrm012QueryModels.QualityFindingFilter quality =
                query.filter().qualityFinding();
        Hrm012QueryModels.CommandReceiptFilter receiptFilter =
                query.filter().commandReceipt();
        List<String> statusCodes = status == null || status.codes().isEmpty()
                ? List.of("__NONE__") : status.codes();
        List<String> findingCodes = quality == null || quality.decisionCodes().isEmpty()
                ? List.of("__NONE__") : quality.decisionCodes();
        List<UUID> receiptIds = receiptFilter == null || receiptFilter.receiptIds().isEmpty()
                ? List.of(EMPTY_UUID) : receiptFilter.receiptIds();
        List<String> receiptStates = receiptFilter == null || receiptFilter.states().isEmpty()
                ? List.of("__NONE__") : receiptFilter.states();

        List<DatabaseRow> databaseRows = jdbc.query("""
                WITH active_population_rows AS (
                    SELECT DISTINCT employer.public_id AS legal_entity_public_id,
                           organization.public_id AS organization_public_id,
                           assignment.manager_assignment_key
                      FROM ppl_assignments assignment
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id=assignment.tenant_id
                       AND relationship.work_relationship_id=
                           assignment.work_relationship_id
                      JOIN ppl_legal_employers employer
                        ON employer.tenant_id=relationship.tenant_id
                       AND employer.legal_employer_id=
                           relationship.legal_employer_id
                      JOIN ppl_organizations organization
                        ON organization.tenant_id=assignment.tenant_id
                       AND organization.organization_id=assignment.organization_id
                     WHERE assignment.tenant_id=:tenant
                       AND assignment.assignment_status='ACTIVE'
                       AND assignment.effective_start_date<=:effectiveOn
                       AND (assignment.effective_end_date IS NULL
                            OR assignment.effective_end_date>=:effectiveOn)
                       AND relationship.start_date<=:effectiveOn
                       AND (relationship.end_date IS NULL
                            OR relationship.end_date>=:effectiveOn)
                       AND employer.lifecycle_state='ACTIVE'
                       AND organization.lifecycle_state='ACTIVE'
                ), trusted_population_legal_entity_closure AS (
                    SELECT legal_entity_public_id
                      FROM active_population_rows
                     GROUP BY legal_entity_public_id
                    HAVING bool_or(
                               (:managerKey IS NOT NULL
                                AND manager_assignment_key=:managerKey)
                               OR (NOT :organizationIdsEmpty
                                   AND organization_public_id IN (:organizationIds)))
                       AND bool_and(
                               (:managerKey IS NOT NULL
                                AND manager_assignment_key=:managerKey)
                               OR (NOT :organizationIdsEmpty
                                   AND organization_public_id IN (:organizationIds)))
                ), touched_legal_entities AS (
                    SELECT count(DISTINCT legal_entity_public_id) AS touched_count
                      FROM active_population_rows
                     WHERE (:managerKey IS NOT NULL
                            AND manager_assignment_key=:managerKey)
                        OR (NOT :organizationIdsEmpty
                            AND organization_public_id IN (:organizationIds))
                ), population_guard AS (
                    SELECT :tenantWide OR (
                           (:managerKey IS NOT NULL OR NOT :organizationIdsEmpty)
                       AND (:organizationIdsEmpty OR
                            (SELECT count(*)
                               FROM ppl_organizations requested
                              WHERE requested.tenant_id=:tenant
                                AND requested.lifecycle_state='ACTIVE'
                                AND requested.public_id IN (:organizationIds))
                            = :organizationCount)
                       AND (:managerKey IS NULL OR EXISTS (
                            SELECT 1 FROM active_population_rows manager_boundary
                             WHERE manager_boundary.manager_assignment_key=:managerKey))
                       AND (SELECT touched_count FROM touched_legal_entities)>0
                       AND (SELECT touched_count FROM touched_legal_entities)=
                           (SELECT count(*)
                              FROM trusted_population_legal_entity_closure)
                    ) AS resolved
                ), snapshot_drift AS (
                    SELECT EXISTS (
                        SELECT 1
                          FROM ppl_workforce_ingestion_runs drift_run
                          JOIN ppl_command_receipts drift_receipt
                            ON drift_receipt.tenant_id=drift_run.tenant_id
                           AND drift_receipt.command_receipt_id=
                               drift_run.command_receipt_id
                           AND drift_receipt.command_type=:commandType
                           AND drift_receipt.originating_action=:originatingAction
                           AND drift_receipt.purpose_code=:purpose
                           AND drift_receipt.target_type=
                               'WORKFORCE_INGESTION_OBJECT'
                         WHERE drift_run.tenant_id=:tenant
                           AND drift_run.created_at<=:asOf
                           AND cardinality(drift_run.legal_entity_public_ids)>0
                           AND (SELECT resolved FROM population_guard)
                           AND (:tenantWide OR
                                drift_run.legal_entity_public_ids <@ ARRAY(
                                    SELECT legal_entity_public_id
                                      FROM trusted_population_legal_entity_closure))
                           AND (drift_run.updated_at>:asOf
                                OR drift_receipt.updated_at>:asOf
                                OR EXISTS (
                                    SELECT 1
                                      FROM ppl_workforce_ingestion_items drift_item
                                     WHERE drift_item.tenant_id=drift_run.tenant_id
                                       AND drift_item.workforce_ingestion_run_id=
                                           drift_run.workforce_ingestion_run_id
                                       AND drift_item.created_at>:asOf))
                    ) AS detected
                ), candidates AS (
                    SELECT run.run_public_id,run.version + 1 AS run_revision,
                           run.lifecycle_state AS ingestion_state,
                           run.effective_date,run.updated_at AS occurred_at,
                           receipt.command_receipt_id AS receipt_public_id,
                           receipt.lifecycle_state AS receipt_state,
                           receipt.created_at AS receipt_occurred_at,
                           receipt.authorization_revision AS receipt_revision,
                           finding.item_public_id AS finding_public_id,
                           finding.finding_code AS finding_decision_code,
                           finding.outcome_digest AS finding_evidence_digest,
                           finding.created_at AS finding_occurred_at,
                           finding.item_sequence AS finding_revision
                      FROM ppl_workforce_ingestion_runs run
                      JOIN ppl_command_receipts receipt
                        ON receipt.tenant_id=run.tenant_id
                       AND receipt.command_receipt_id=run.command_receipt_id
                       AND receipt.command_type=:commandType
                       AND receipt.originating_action=:originatingAction
                       AND receipt.purpose_code=:purpose
                       AND receipt.target_type='WORKFORCE_INGESTION_OBJECT'
                      LEFT JOIN LATERAL (
                          SELECT item.item_public_id,item.finding_code,
                                 item.outcome_digest,item.created_at,item.item_sequence
                            FROM ppl_workforce_ingestion_items item
                           WHERE item.tenant_id=run.tenant_id
                             AND item.workforce_ingestion_run_id=
                                 run.workforce_ingestion_run_id
                             AND item.outcome IN (
                                 'REJECTED','QUARANTINED_AFTER_ACCEPTANCE')
                             AND (:findingCodesEmpty
                                  OR item.finding_code IN (:findingCodes))
                             AND (:findingDigest IS NULL
                                  OR item.outcome_digest=
                                     CAST(:findingDigest AS CHAR(64)))
                             AND item.created_at<=:asOf
                           ORDER BY item.item_sequence,item.item_public_id
                           LIMIT 1
                      ) finding ON TRUE
                     WHERE run.tenant_id=:tenant
                       AND run.created_at<=:asOf
                       AND run.updated_at<=:asOf
                       AND receipt.updated_at<=:asOf
                       AND (:effectiveAny OR run.effective_date=:effectiveOn)
                       AND cardinality(run.legal_entity_public_ids)>0
                       AND (SELECT resolved FROM population_guard)
                       AND (:tenantWide OR
                            run.legal_entity_public_ids <@ ARRAY(
                                SELECT legal_entity_public_id
                                  FROM trusted_population_legal_entity_closure))
                       AND (:statusCodesEmpty
                            OR run.lifecycle_state IN (:statusCodes))
                       AND (:minimumRevision IS NULL
                            OR (run.version + 1)>=
                               CAST(:minimumRevision AS BIGINT))
                       AND (:findingFilterEmpty OR finding.item_public_id IS NOT NULL)
                       AND (:receiptIdsEmpty
                            OR receipt.command_receipt_id IN (:receiptIds))
                       AND (:receiptStatesEmpty
                            OR receipt.lifecycle_state IN (:receiptStates))
                ), ordered AS (
                    SELECT candidates.*,
                           row_number() OVER (ORDER BY %s) AS page_number
                      FROM candidates
                ), boundary AS (
                    SELECT page_number FROM ordered
                     WHERE run_public_id=CAST(:after AS UUID)
                )
                SELECT (CAST(:after AS UUID) IS NULL
                        OR EXISTS(SELECT 1 FROM boundary)) AS cursor_found,
                       (SELECT resolved FROM population_guard) AS population_resolved,
                       (SELECT detected FROM snapshot_drift) AS snapshot_drift,
                       page.*
                  FROM (SELECT 1) marker
                  LEFT JOIN LATERAL (
                      SELECT * FROM ordered
                       WHERE CAST(:after AS UUID) IS NULL
                          OR page_number>(SELECT page_number FROM boundary)
                       ORDER BY page_number
                       LIMIT :fetchLimit
                  ) page ON TRUE
                 ORDER BY page.page_number NULLS LAST
                """.formatted(orderBy(query.sort())), new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("asOf", Timestamp.from(query.asOf()))
                .addValue("effectiveAny", query.filter().effectiveOn() == null)
                .addValue("effectiveOn", query.effectiveOn())
                .addValue("tenantWide", population.tenantWide())
                .addValue("managerKey", population.managerAssignmentKey())
                .addValue("organizationIdsEmpty", population.organizationIds().isEmpty())
                .addValue("organizationCount", population.organizationIds().size())
                .addValue("organizationIds", population.organizationIds().isEmpty()
                        ? List.of(EMPTY_UUID) : population.organizationIds())
                .addValue("commandType", Hrm012WorkforceIngestionContract.COMMAND_TYPE)
                .addValue("originatingAction",
                        Hrm012WorkforceIngestionContract.ORIGINATING_ACTION)
                .addValue("purpose", Hrm012WorkforceIngestionContract.PURPOSE)
                .addValue("statusCodesEmpty", status == null || status.codes().isEmpty())
                .addValue("statusCodes", statusCodes)
                .addValue("minimumRevision",
                        status == null ? null : status.minimumRevision())
                .addValue("findingCodesEmpty",
                        quality == null || quality.decisionCodes().isEmpty())
                .addValue("findingCodes", findingCodes)
                .addValue("findingDigest",
                        quality == null ? null : quality.evidenceDigest())
                .addValue("findingFilterEmpty", quality == null
                        || quality.decisionCodes().isEmpty()
                        && quality.evidenceDigest() == null)
                .addValue("receiptIdsEmpty",
                        receiptFilter == null || receiptFilter.receiptIds().isEmpty())
                .addValue("receiptIds", receiptIds)
                .addValue("receiptStatesEmpty",
                        receiptFilter == null || receiptFilter.states().isEmpty())
                .addValue("receiptStates", receiptStates)
                .addValue("after", query.afterRunPublicId() == null
                        ? null : query.afterRunPublicId().toString())
                .addValue("fetchLimit", query.limit() + 1),
                (result, ignored) -> new DatabaseRow(
                        result.getBoolean("cursor_found"),
                        result.getBoolean("population_resolved"),
                        result.getBoolean("snapshot_drift"),
                        result.getObject("run_public_id", UUID.class) == null ? null
                                : new Hrm012QueryModels.SourceRow(
                                result.getObject("run_public_id", UUID.class),
                                result.getLong("run_revision"),
                                result.getString("ingestion_state"),
                                result.getObject("effective_date", LocalDate.class),
                                instant(result, "occurred_at"),
                                result.getString("finding_decision_code"),
                                result.getString("finding_evidence_digest"),
                                instant(result, "finding_occurred_at"),
                                result.getObject("finding_revision") == null ? 0
                                        : result.getLong("finding_revision"),
                                result.getObject("receipt_public_id", UUID.class),
                                result.getString("receipt_state"),
                                instant(result, "receipt_occurred_at"),
                                result.getLong("receipt_revision"))));
        if (databaseRows.isEmpty()) {
            throw new IllegalStateException("IAQ-012 SQL page returned no marker row.");
        }
        if (!databaseRows.getFirst().populationResolved()) {
            throw Hrm012Problem.denied(
                    "The IAQ-012 population cannot be closed at legal-entity grain.");
        }
        List<Hrm012QueryModels.SourceRow> rows = new ArrayList<>();
        for (DatabaseRow row : databaseRows) {
            if (row.source() != null) rows.add(row.source());
        }
        boolean hasMore = rows.size() > query.limit();
        List<Hrm012QueryModels.SourceRow> bounded = hasMore
                ? List.copyOf(rows.subList(0, query.limit())) : List.copyOf(rows);
        UUID last = bounded.isEmpty() ? null : bounded.getLast().runPublicId();
        return new Page(bounded, databaseRows.getFirst().cursorFound(),
                databaseRows.getFirst().snapshotDrift(), hasMore, last, List.of());
    }

    private String orderBy(List<String> sorts) {
        List<String> clauses = new ArrayList<>();
        for (String sort : sorts) {
            switch (sort) {
                case "ingestionStatus:ASC" -> clauses.add("ingestion_state ASC");
                case "ingestionStatus:DESC" -> clauses.add("ingestion_state DESC");
                case "qualityFinding:ASC" ->
                        clauses.add("finding_decision_code ASC NULLS LAST");
                case "qualityFinding:DESC" ->
                        clauses.add("finding_decision_code DESC NULLS LAST");
                case "commandReceipt:ASC" -> clauses.add("receipt_state ASC");
                case "commandReceipt:DESC" -> clauses.add("receipt_state DESC");
                case "itemPublicId:ASC" -> clauses.add("run_public_id ASC");
                case "itemPublicId:DESC" -> clauses.add("run_public_id DESC");
                default -> throw new IllegalArgumentException("Unknown IAQ-012 SQL sort.");
            }
        }
        if (sorts.stream().noneMatch(value -> value.startsWith("itemPublicId:"))) {
            clauses.add("run_public_id ASC");
        }
        return String.join(",", clauses);
    }

    private Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void establishTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id',CAST(:tenant AS VARCHAR),TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(value)) {
            throw new IllegalStateException("HRM-012 tenant scope was not established.");
        }
    }

    private record DatabaseRow(
            boolean cursorFound,
            boolean populationResolved,
            boolean snapshotDrift,
            Hrm012QueryModels.SourceRow source) {
    }
}
