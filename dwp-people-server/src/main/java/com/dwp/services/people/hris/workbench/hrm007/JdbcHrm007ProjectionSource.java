package com.dwp.services.people.hris.workbench.hrm007;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-predicated SELECT-only adapter. It deliberately owns no command,
 * event, receipt, approval, or outbox behavior.
 */
@Repository
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
class JdbcHrm007ProjectionSource implements Hrm007ProjectionSource {

    private static final UUID EMPTY_UUID = new UUID(0L, 0L);

    private final NamedParameterJdbcTemplate jdbc;

    JdbcHrm007ProjectionSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProjectionRow> read(
            Hrm007QueryDefinition definition,
            SourceRequest request) {
        requireRequest(definition, request);
        establishTenant(request.tenantId());
        return switch (definition) {
            case PEOPLE_CHANGES -> peopleChanges(request);
            case APPOINTMENTS -> appointments(request);
            case PEOPLE_EMPLOYMENT -> peopleEmployment(request);
        };
    }

    private List<ProjectionRow> peopleChanges(SourceRequest request) {
        MapSqlParameterSource parameters = common(request)
                .addValue("actorWorker", request.population().actorWorkerId())
                .addValue("managerAssignment", request.population().managerAssignmentKey())
                .addValue("tenantWide", request.population().tenantWide())
                .addValue("organizationIds", organizations(request.population()))
                .addValue("effectiveOn", request.effectiveOn());
        return List.copyOf(jdbc.query("""
                SELECT request.public_id::text AS item_public_id,
                       target.display_name || ' · ' || request.change_type AS title,
                       request.lifecycle_state AS status_code,
                       jsonb_array_length(request.field_groups) AS summary_item_count,
                       worker.worker_status AS limited_employment_code,
                       request.effective_date,
                       request.version,
                       requester.public_id::text AS requester_public_id
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT candidate.worker_id,candidate.worker_status,
                              assignment.manager_assignment_key,
                              organization.public_id AS organization_public_id
                         FROM ppl_workers candidate
                         JOIN ppl_work_relationships relationship
                           ON relationship.tenant_id=candidate.tenant_id
                          AND relationship.worker_id=candidate.worker_id
                          AND relationship.start_date<=:scopeDate
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date>=:scopeDate)
                         JOIN ppl_assignments assignment
                           ON assignment.tenant_id=relationship.tenant_id
                          AND assignment.work_relationship_id=relationship.work_relationship_id
                          AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                          AND assignment.effective_start_date<=:scopeDate
                          AND (assignment.effective_end_date IS NULL
                               OR assignment.effective_end_date>=:scopeDate)
                         LEFT JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE candidate.tenant_id=target.tenant_id
                          AND candidate.person_id=target.person_id
                          AND candidate.worker_status IN ('ACTIVE','LEAVE')
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE request.tenant_id=:tenant
                   AND request.created_at<=:asOf
                   AND worker.worker_id<>:actorWorker
                   AND (:tenantWide
                        OR worker.manager_assignment_key=:managerAssignment
                        OR worker.organization_public_id IN (:organizationIds))
                   AND (:effectiveOn IS NULL OR request.effective_date=:effectiveOn)
                 ORDER BY request.public_id
                """, parameters, (result, ignored) ->
                (ProjectionRow) new PeopleChangeRow(
                        result.getString("item_public_id"),
                        result.getString("title"),
                        result.getString("status_code"),
                        result.getInt("summary_item_count"),
                        result.getString("limited_employment_code"),
                        result.getObject("effective_date", LocalDate.class),
                        result.getLong("version"),
                        result.getString("requester_public_id"))));
    }

    private List<ProjectionRow> appointments(SourceRequest request) {
        MapSqlParameterSource parameters = common(request)
                .addValue("tenantWide", request.population().tenantWide())
                .addValue("managerAssignment", request.population().managerAssignmentKey())
                .addValue("organizationIds", organizations(request.population()))
                .addValue("effectiveOn", request.effectiveOn());
        return List.copyOf(jdbc.query("""
                SELECT event.public_id::text AS item_public_id,
                       subject.display_name || ' · ' || event.event_type AS title,
                       event.lifecycle_state AS status_code,
                       event.effective_date,
                       NULLIF(event.event_payload #>> '{assignment,validToExclusive}', '')::date
                           AS effective_to,
                       COALESCE(published.public_id::text,
                                event.event_payload #>> '{assignment,positionRef,publicId}',
                                event.public_id::text) AS assignment_public_id,
                       COALESCE(published.assignment_status,event.event_type)
                           AS assignment_code,
                       COALESCE(published.business_title,subject.display_name || ' appointment')
                           AS assignment_label,
                       event.version
                  FROM ppl_assignment_events event
                  JOIN ppl_persons subject
                    ON subject.tenant_id=event.tenant_id
                   AND subject.person_id=event.subject_person_id
                  LEFT JOIN ppl_assignments published
                    ON published.tenant_id=event.tenant_id
                   AND published.assignment_id=event.published_assignment_id
                  LEFT JOIN LATERAL (
                       SELECT assignment.manager_assignment_key,
                              organization.public_id AS organization_public_id
                         FROM ppl_workers worker
                         JOIN ppl_work_relationships relationship
                           ON relationship.tenant_id=worker.tenant_id
                          AND relationship.worker_id=worker.worker_id
                          AND relationship.start_date<=:scopeDate
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date>=:scopeDate)
                         JOIN ppl_assignments assignment
                           ON assignment.tenant_id=relationship.tenant_id
                          AND assignment.work_relationship_id=relationship.work_relationship_id
                          AND assignment.effective_start_date<=:scopeDate
                          AND (assignment.effective_end_date IS NULL
                               OR assignment.effective_end_date>=:scopeDate)
                         LEFT JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE worker.tenant_id=subject.tenant_id
                          AND worker.person_id=subject.person_id
                          AND worker.worker_status IN ('ACTIVE','LEAVE')
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,
                                 assignment.assignment_id
                        LIMIT 1
                  ) workforce ON TRUE
                 WHERE event.tenant_id=:tenant
                   AND event.created_at<=:asOf
                   AND (:tenantWide
                        OR workforce.manager_assignment_key=:managerAssignment
                        OR workforce.organization_public_id IN (:organizationIds)
                        OR NULLIF(event.event_payload #>>
                            '{assignment,organizationRef,publicId}', '')::uuid
                            IN (:organizationIds))
                   AND (:effectiveOn IS NULL OR event.effective_date=:effectiveOn)
                 ORDER BY event.public_id
                """, parameters, (result, ignored) -> appointmentRow(result)));
    }

    private ProjectionRow appointmentRow(ResultSet result) throws SQLException {
        LocalDate start = result.getObject("effective_date", LocalDate.class);
        LocalDate end = result.getObject("effective_to", LocalDate.class);
        Instant startAt = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endAt = end == null ? null : end.atStartOfDay(ZoneOffset.UTC).toInstant();
        long minutes = endAt == null ? 0L : Math.max(0L,
                ChronoUnit.MINUTES.between(startAt, endAt));
        return new AppointmentRow(
                result.getString("item_public_id"), result.getString("title"),
                result.getString("status_code"), startAt, endAt,
                result.getString("assignment_public_id"),
                result.getString("assignment_code"),
                result.getString("assignment_label"), start, end,
                result.getLong("version"));
    }

    private List<ProjectionRow> peopleEmployment(SourceRequest request) {
        MapSqlParameterSource parameters = common(request);
        return List.copyOf(jdbc.query("""
                SELECT catalog.public_id::text AS item_public_id,
                       catalog.display_name AS title,
                       catalog.lifecycle_state AS status_code,
                       'people-employment.policy.' || lower(catalog.reason_code)
                           AS policy_metadata_key,
                       'people-employment.rule.' || lower(catalog.reason_code)
                           AS employment_rule_key,
                       catalog.effective_start_date,
                       catalog.effective_end_date,
                       COUNT(assignment.assignment_id)::integer AS impact_item_count,
                       catalog.version
                  FROM ppl_assignment_change_reason_catalog catalog
                  LEFT JOIN ppl_assignments assignment
                    ON assignment.tenant_id=catalog.tenant_id
                   AND assignment.change_reason_code=catalog.reason_code
                   AND assignment.effective_start_date<=:scopeDate
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date>=:scopeDate)
                 WHERE catalog.tenant_id=:tenant
                   AND catalog.effective_start_date<=:scopeDate
                   AND (catalog.effective_end_date IS NULL
                        OR catalog.effective_end_date>=:scopeDate)
                 GROUP BY catalog.public_id,catalog.display_name,catalog.lifecycle_state,
                          catalog.reason_code,catalog.effective_start_date,
                          catalog.effective_end_date,catalog.version,catalog.sort_order
                 ORDER BY catalog.sort_order,catalog.public_id
                """, parameters, (result, ignored) ->
                (ProjectionRow) new PeopleEmploymentRow(
                        result.getString("item_public_id"), result.getString("title"),
                        result.getString("status_code"),
                        result.getString("policy_metadata_key"),
                        result.getString("employment_rule_key"),
                        result.getObject("effective_start_date", LocalDate.class),
                        result.getObject("effective_end_date", LocalDate.class),
                        result.getInt("impact_item_count"),
                        result.getLong("version"))));
    }

    private MapSqlParameterSource common(SourceRequest request) {
        return new MapSqlParameterSource()
                .addValue("tenant", request.tenantId())
                .addValue("asOf", Timestamp.from(request.asOf()))
                .addValue("scopeDate", request.scopeDate());
    }

    private Set<UUID> organizations(Hrm007Authority.TargetPopulation population) {
        return population.organizationIds().isEmpty()
                ? Set.of(EMPTY_UUID) : population.organizationIds();
    }

    private void establishTenant(long tenantId) {
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("HRM-007 tenant scope was not established.");
        }
    }

    private void requireRequest(
            Hrm007QueryDefinition definition,
            SourceRequest request) {
        if (definition == null || request == null || request.tenantId() < 1
                || request.asOf() == null || request.scopeDate() == null
                || request.population() == null) {
            throw new IllegalArgumentException("A complete HRM-007 source request is required.");
        }
    }
}
