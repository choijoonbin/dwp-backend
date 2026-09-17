package com.dwp.services.platform.workplace.workplaceplanning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;

@Repository
public class WorkplacePlanningRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<UUID>> UUIDS = new TypeReference<>() { };
    private static final TypeReference<List<SeriesPoint>> SERIES_POINTS = new TypeReference<>() { };
    private static final TypeReference<List<ForecastPoint>> FORECAST_POINTS = new TypeReference<>() { };
    private static final TypeReference<List<NeighborhoodAllocationInput>> ALLOCATIONS =
            new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplacePlanningRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean siteExists(long tenantId, UUID siteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_sites
                 WHERE tenant_id=? AND site_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId));
    }

    public boolean floorBelongsToSite(long tenantId, UUID siteId, UUID floorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_floors
                 WHERE tenant_id=? AND site_id=? AND floor_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId, floorId));
    }

    public CurrentSpaceMetrics currentMetrics(long tenantId, PlanningScope scope) {
        return jdbc.query("""
                SELECT COALESCE(SUM(resource.capacity),0)::int capacity,
                       COALESCE(SUM(resource.capacity) FILTER
                           (WHERE resource.resource_type='ROOM'),0)::int room_capacity,
                       COUNT(*) FILTER (WHERE resource.accessible)::int accessible_count,
                       COUNT(resource.resource_id)::int resource_count,
                       COALESCE(MAX(resource.updated_at),MAX(floor.updated_at),MAX(site.updated_at)) catalog_as_of
                  FROM wp_sites site
                  LEFT JOIN wp_floors floor
                    ON floor.tenant_id=site.tenant_id AND floor.site_id=site.site_id
                   AND (?::uuid IS NULL OR floor.floor_id=?::uuid)
                  LEFT JOIN wp_resources resource
                    ON resource.tenant_id=floor.tenant_id AND resource.floor_id=floor.floor_id
                   AND resource.lifecycle_state='AVAILABLE'
                   AND (?::text IS NULL OR resource.neighborhood=?::text)
                   AND (?::text IS NULL OR resource.resource_type=?::text)
                 WHERE site.tenant_id=? AND site.site_id=?
                 GROUP BY site.site_id
                """, (rs, row) -> new CurrentSpaceMetrics(
                rs.getInt("capacity"), rs.getInt("room_capacity"),
                rs.getInt("accessible_count"), rs.getInt("resource_count"),
                rs.getObject("catalog_as_of", OffsetDateTime.class)),
                scope.floorId(), scope.floorId(), normalized(scope.neighborhood()),
                normalized(scope.neighborhood()), normalized(scope.resourceType()),
                normalized(scope.resourceType()), tenantId, scope.siteId()).stream()
                .findFirst().orElse(new CurrentSpaceMetrics(0, 0, 0, 0, null));
    }

    public boolean resourcesBelongToScope(
            long tenantId, PlanningScope scope, List<UUID> resourceIds) {
        if (resourceIds.isEmpty()) return true;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT resource.resource_id)::int
                  FROM wp_resources resource
                  JOIN wp_floors floor
                    ON floor.tenant_id=resource.tenant_id AND floor.floor_id=resource.floor_id
                 WHERE resource.tenant_id=? AND floor.site_id=?
                   AND (?::uuid IS NULL OR floor.floor_id=?::uuid)
                   AND (?::text IS NULL OR resource.neighborhood=?::text)
                   AND (?::text IS NULL OR resource.resource_type=?::text)
                   AND resource.lifecycle_state<>'RETIRED'
                   AND resource.resource_id IN (
                       SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                """, Integer.class, tenantId, scope.siteId(), scope.floorId(), scope.floorId(),
                normalized(scope.neighborhood()), normalized(scope.neighborhood()),
                normalized(scope.resourceType()), normalized(scope.resourceType()), json(resourceIds));
        return count != null && count == resourceIds.stream().distinct().count();
    }

    public boolean appendSourceObservation(SourceObservation observation) {
        return jdbc.update("""
                INSERT INTO wp_space_planning_source_observations(
                    observation_id,tenant_id,series_kind,site_id,floor_id,neighborhood,
                    resource_type,window_start,window_end,availability_state,coverage_percent,
                    source_at,received_at,evidence_reference,exclusions,series_points,
                    observation_sequence,payload_fingerprint)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)
                ON CONFLICT (observation_id) DO NOTHING
                """, observation.observationId(), observation.tenantId(), observation.series().name(),
                observation.scope().siteId(), observation.scope().floorId(),
                normalized(observation.scope().neighborhood()),
                normalized(observation.scope().resourceType()), observation.scope().from(),
                observation.scope().to(), observation.availability().name(),
                observation.coveragePercent(), observation.sourceAt(), observation.receivedAt(),
                observation.evidenceReference().trim(), json(observation.exclusions()),
                json(observation.points()), observation.sequence(),
                observation.payloadFingerprint()) == 1;
    }

    public List<SourceRow> latestSources(long tenantId, PlanningScope scope) {
        return jdbc.query("""
                SELECT DISTINCT ON (series_kind) *
                  FROM wp_space_planning_source_observations
                 WHERE tenant_id=? AND site_id=?
                   AND floor_id IS NOT DISTINCT FROM ?::uuid
                   AND neighborhood IS NOT DISTINCT FROM ?::text
                   AND resource_type IS NOT DISTINCT FROM ?::text
                   AND window_start<=? AND window_end>=?
                 ORDER BY series_kind, received_at DESC, observation_sequence DESC
                """, this::sourceRow, tenantId, scope.siteId(), scope.floorId(),
                normalized(scope.neighborhood()), normalized(scope.resourceType()),
                scope.from(), scope.to());
    }

    public List<SourceRow> sourcesByIds(long tenantId, List<UUID> observationIds) {
        if (observationIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT * FROM wp_space_planning_source_observations
                 WHERE tenant_id=? AND observation_id IN (
                       SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                """, this::sourceRow, tenantId, json(observationIds));
    }

    public boolean appendForecast(ForecastObservation observation) {
        return jdbc.update("""
                INSERT INTO wp_space_planning_forecasts(
                    forecast_id,tenant_id,site_id,floor_id,neighborhood,resource_type,
                    window_start,window_end,forecast_state,calculation_version,evidence_reference,
                    source_observation_ids,forecast_points,recommendation_metrics,limitations,
                    source_at,received_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)
                ON CONFLICT (forecast_id) DO NOTHING
                """, observation.forecastId(), observation.tenantId(), observation.scope().siteId(),
                observation.scope().floorId(), normalized(observation.scope().neighborhood()),
                normalized(observation.scope().resourceType()), observation.scope().from(),
                observation.scope().to(), observation.state().name(),
                normalized(observation.calculationVersion()), normalized(observation.evidenceReference()),
                json(observation.sourceObservationIds()), json(observation.points()),
                observation.recommendationMetrics() == null
                        ? null : json(observation.recommendationMetrics()),
                json(observation.limitations()), observation.sourceAt(), observation.receivedAt()) == 1;
    }

    public Optional<ForecastRow> latestForecast(long tenantId, PlanningScope scope) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_forecasts
                 WHERE tenant_id=? AND site_id=?
                   AND floor_id IS NOT DISTINCT FROM ?::uuid
                   AND neighborhood IS NOT DISTINCT FROM ?::text
                   AND resource_type IS NOT DISTINCT FROM ?::text
                   AND window_start<=? AND window_end>=?
                 ORDER BY received_at DESC, forecast_id DESC LIMIT 1
                """, this::forecastRow, tenantId, scope.siteId(), scope.floorId(),
                normalized(scope.neighborhood()), normalized(scope.resourceType()),
                scope.from(), scope.to()).stream().findFirst();
    }

    public boolean appendEmissionEvidence(EmissionObservation observation) {
        return jdbc.update("""
                INSERT INTO wp_space_planning_emission_evidence(
                    emission_evidence_id,tenant_id,site_id,floor_id,evidence_kind,
                    energy_value,energy_unit,co2e_value,co2e_unit,factor_version,region_code,
                    evidence_reference,source_at,received_at,approved_by,
                    approval_authority_reference)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (emission_evidence_id) DO NOTHING
                """, observation.emissionEvidenceId(), observation.tenantId(), observation.siteId(),
                observation.floorId(), observation.evidenceKind().name(), observation.energyValue(),
                observation.energyUnit().trim(), observation.co2eValue(), observation.co2eUnit().trim(),
                observation.factorVersion().trim(), observation.regionCode().trim(),
                observation.evidenceReference().trim(), observation.sourceAt(), observation.receivedAt(),
                observation.approvedBy(), normalized(observation.approvalAuthorityReference())) == 1;
    }

    public Optional<EmissionProjection> emission(long tenantId, UUID evidenceId) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_emission_evidence
                 WHERE tenant_id=? AND emission_evidence_id=?
                """, this::emission, tenantId, evidenceId).stream().findFirst();
    }

    public Optional<EmissionProjection> latestEmission(long tenantId, PlanningScope scope) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_emission_evidence
                 WHERE tenant_id=? AND site_id=?
                   AND floor_id IS NOT DISTINCT FROM ?::uuid
                 ORDER BY received_at DESC, emission_evidence_id DESC LIMIT 1
                """, this::emission, tenantId, scope.siteId(), scope.floorId())
                .stream().findFirst();
    }

    public boolean emissionBelongsToScope(
            long tenantId, UUID evidenceId, PlanningScope scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_space_planning_emission_evidence
                 WHERE tenant_id=? AND emission_evidence_id=? AND site_id=?
                   AND floor_id IS NOT DISTINCT FROM ?::uuid)
                """, Boolean.class, tenantId, evidenceId, scope.siteId(), scope.floorId()));
    }

    public void insertScenario(ScenarioRow row) {
        jdbc.update("""
                INSERT INTO wp_space_planning_scenarios(
                    scenario_id,tenant_id,name,description,lifecycle_state,site_id,floor_id,
                    neighborhood,resource_type,window_start,window_end,proposed_capacity,
                    proposed_room_capacity,proposed_accessible_resource_count,operating_start,
                    operating_end,policy_reference,affected_resource_ids,neighborhood_allocations,
                    emission_evidence_id,version,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)
                """, row.scenarioId(), row.tenantId(), row.name(), row.description(), row.state().name(),
                row.scope().siteId(), row.scope().floorId(), normalized(row.scope().neighborhood()),
                normalized(row.scope().resourceType()), row.scope().from(), row.scope().to(),
                row.draft().proposedCapacity(), row.draft().proposedRoomCapacity(),
                row.draft().proposedAccessibleResourceCount(), row.draft().operatingStart(),
                row.draft().operatingEnd(), normalized(row.draft().policyReference()),
                json(row.draft().affectedResourceIds()), json(row.draft().neighborhoodAllocations()),
                row.draft().emissionEvidenceId(), row.version(), row.createdAt(), row.createdBy(),
                row.updatedAt(), row.updatedBy());
    }

    public Optional<ScenarioRow> scenario(long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_scenarios
                 WHERE tenant_id=? AND scenario_id=?
                """, this::scenarioRow, tenantId, scenarioId).stream().findFirst();
    }

    public List<ScenarioRow> scenarios(long tenantId, UUID siteId, ScenarioState state) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_scenarios
                 WHERE tenant_id=?
                   AND (?::uuid IS NULL OR site_id=?::uuid)
                   AND (?::text IS NULL OR lifecycle_state=?::text)
                 ORDER BY updated_at DESC, scenario_id
                """, this::scenarioRow, tenantId, siteId, siteId,
                state == null ? null : state.name(), state == null ? null : state.name());
    }

    public boolean updateDraft(
            long tenantId,
            long actorId,
            UUID scenarioId,
            long expectedVersion,
            UpdateScenarioRequest request,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_space_planning_scenarios
                   SET name=?,description=?,lifecycle_state='DRAFT',proposed_capacity=?,
                       proposed_room_capacity=?,proposed_accessible_resource_count=?,
                       operating_start=?,operating_end=?,policy_reference=?,
                       affected_resource_ids=?::jsonb,neighborhood_allocations=?::jsonb,
                       emission_evidence_id=?,active_preview_id=NULL,
                       version=version+1,updated_at=?,updated_by=?
                 WHERE tenant_id=? AND scenario_id=? AND version=?
                   AND lifecycle_state IN ('DRAFT','PREVIEWED')
                """, request.name().trim(), normalized(request.description()),
                request.draft().proposedCapacity(), request.draft().proposedRoomCapacity(),
                request.draft().proposedAccessibleResourceCount(), request.draft().operatingStart(),
                request.draft().operatingEnd(), normalized(request.draft().policyReference()),
                json(request.draft().affectedResourceIds()),
                json(request.draft().neighborhoodAllocations()), request.draft().emissionEvidenceId(),
                now, actorId, tenantId, scenarioId, expectedVersion) == 1;
    }

    public boolean saveScenarioPreview(
            long tenantId,
            long actorId,
            UUID scenarioId,
            long expectedVersion,
            ScenarioPreview preview,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_space_planning_scenario_previews(
                    preview_id,tenant_id,scenario_id,scenario_version,forecast_id,forecast_state,
                    forecast_projection,comparison,emission_projection,eligible,limitations,
                    expires_at,created_by,created_at)
                VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?,?)
                """, preview.previewId(), tenantId, scenarioId, preview.scenarioVersion(),
                preview.forecast() == null ? null : preview.forecast().forecastId(),
                preview.forecastState().name(), json(preview.forecast()), json(preview.comparison()),
                preview.emission() == null ? null : json(preview.emission()), preview.eligible(),
                json(preview.limitations()), preview.expiresAt(), actorId, preview.createdAt());
        return jdbc.update("""
                UPDATE wp_space_planning_scenarios
                   SET lifecycle_state='PREVIEWED',active_preview_id=?,version=version+1,
                       updated_at=?,updated_by=?
                 WHERE tenant_id=? AND scenario_id=? AND version=?
                   AND lifecycle_state IN ('DRAFT','PREVIEWED')
                """, preview.previewId(), now, actorId, tenantId, scenarioId, expectedVersion) == 1;
    }

    public Optional<ScenarioPreviewRow> preview(long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_scenario_previews
                 WHERE tenant_id=? AND preview_id=?
                """, this::previewRow, tenantId, previewId).stream().findFirst();
    }

    public boolean submit(
            long tenantId, long actorId, UUID scenarioId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_space_planning_scenarios
                   SET lifecycle_state='SUBMITTED',submitted_at=?,submitted_by=?,
                       version=version+1,updated_at=?,updated_by=?
                 WHERE tenant_id=? AND scenario_id=? AND version=?
                   AND lifecycle_state='PREVIEWED'
                """, now, actorId, now, actorId, tenantId, scenarioId, expectedVersion) == 1;
    }

    public boolean decide(
            long tenantId,
            long actorId,
            UUID scenarioId,
            long expectedVersion,
            ScenarioApprovalRequest request,
            OffsetDateTime now) {
        if (request.decision() == ApprovalDecision.REJECT) {
            return jdbc.update("""
                    UPDATE wp_space_planning_scenarios
                       SET lifecycle_state='DRAFT',active_preview_id=NULL,
                           submitted_at=NULL,submitted_by=NULL,approved_at=NULL,approved_by=NULL,
                           approval_authority_reference=NULL,last_rejected_at=?,last_rejected_by=?,
                           last_rejection_reason=?,version=version+1,updated_at=?,updated_by=?
                     WHERE tenant_id=? AND scenario_id=? AND version=?
                       AND lifecycle_state='SUBMITTED'
                    """, now, actorId, request.reason().trim(), now, actorId,
                    tenantId, scenarioId, expectedVersion) == 1;
        }
        return jdbc.update("""
                UPDATE wp_space_planning_scenarios
                   SET lifecycle_state='APPROVED',approved_at=?,approved_by=?,
                       approval_authority_reference=?,version=version+1,updated_at=?,updated_by=?
                 WHERE tenant_id=? AND scenario_id=? AND version=?
                   AND lifecycle_state='SUBMITTED'
                """, now, actorId, request.approvalAuthorityReference().trim(), now, actorId,
                tenantId, scenarioId, expectedVersion) == 1;
    }

    public boolean publish(
            long tenantId, long actorId, UUID scenarioId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_space_planning_scenarios
                   SET lifecycle_state='PUBLISHED',published_at=?,published_by=?,
                       version=version+1,updated_at=?,updated_by=?
                 WHERE tenant_id=? AND scenario_id=? AND version=?
                   AND lifecycle_state='APPROVED'
                   AND approved_at IS NOT NULL AND approved_by IS NOT NULL
                   AND approval_authority_reference IS NOT NULL
                """, now, actorId, now, actorId, tenantId, scenarioId, expectedVersion) == 1;
    }

    public List<BookingImpactItem> bookingImpact(ScenarioRow scenario) {
        if (scenario.draft().affectedResourceIds().isEmpty()) return List.of();
        return jdbc.query("""
                SELECT booking_id,resource_id,starts_at,ends_at,booking_status
                  FROM wp_bookings
                 WHERE tenant_id=?
                   AND resource_id IN (
                       SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                   AND booking_status IN ('RESERVED','CHECKED_IN')
                   AND starts_at<? AND ends_at>?
                 ORDER BY starts_at,booking_id
                """, (rs, row) -> new BookingImpactItem(
                rs.getObject("booking_id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("booking_status"),
                "REVIEW_REQUIRED_FOR_PROPOSED_SPACE_CHANGE", false),
                scenario.tenantId(), json(scenario.draft().affectedResourceIds()),
                scenario.scope().to(), scenario.scope().from());
    }

    public void saveBookingImpactPreview(
            long tenantId, long actorId, BookingImpactPreview preview) {
        jdbc.update("""
                INSERT INTO wp_space_planning_booking_impact_previews(
                    impact_preview_id,tenant_id,scenario_id,scenario_version,impact_state,
                    impacted_booking_count,booking_items,limitations,expires_at,created_by,created_at)
                VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
                """, preview.impactPreviewId(), tenantId, preview.scenarioId(),
                preview.scenarioVersion(), preview.state().name(), preview.impactedBookingCount(),
                json(preview.bookings()), json(preview.limitations()), preview.expiresAt(), actorId,
                preview.createdAt());
    }

    public Optional<CommandRow> commandByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT command.*,outbox.delivery_state
                  FROM wp_space_planning_commands command
                  LEFT JOIN wp_space_planning_outbox outbox
                    ON outbox.tenant_id=command.tenant_id AND outbox.outbox_id=command.outbox_id
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                   AND command.idempotency_key=?
                """, this::commandRow, tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public void lockCommand(
            long tenantId, long actorId, String commandType, String idempotencyKey) {
        String baseIdentity = tenantId + ":" + actorId + ":" + idempotencyKey;
        String typedIdentity = tenantId + ":" + actorId + ":" + commandType + ":" + idempotencyKey;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", ignored -> { },
                baseIdentity);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", ignored -> { },
                typedIdentity);
    }

    public CommandRow saveCommand(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String commandType,
            String idempotencyKey,
            String fingerprint,
            String reason,
            String correlationId,
            ScenarioView result,
            boolean emitOutbox,
            OffsetDateTime now) {
        UUID commandId = UUID.randomUUID();
        UUID outboxId = emitOutbox ? UUID.randomUUID() : null;
        jdbc.update("""
                INSERT INTO wp_space_planning_commands(
                    command_id,tenant_id,actor_user_id,scenario_id,command_type,idempotency_key,
                    request_fingerprint,reason,command_state,resulting_scenario_state,
                    resulting_scenario_version,result_snapshot,correlation_id,outbox_id,accepted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, commandId, tenantId, actorId, scenarioId, commandType, idempotencyKey,
                fingerprint, reason.trim(), CommandState.SUCCEEDED.name(), result.state().name(),
                result.version(), json(result), correlationId, outboxId, now);
        if (outboxId != null) {
            jdbc.update("""
                    INSERT INTO wp_space_planning_outbox(
                        outbox_id,tenant_id,command_id,scenario_id,event_type,payload,
                        delivery_state,attempt_count,next_attempt_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?::jsonb,'PENDING',0,?,?,?)
                    """, outboxId, tenantId, commandId, scenarioId,
                    "workplace.space-planning." + commandType.toLowerCase(java.util.Locale.ROOT),
                    json(Map.of("scenarioId", scenarioId.toString(), "state", result.state().name(),
                            "version", result.version())), now, now, now);
        }
        return new CommandRow(commandId, tenantId, actorId, scenarioId, commandType,
                idempotencyKey, fingerprint, reason.trim(), CommandState.SUCCEEDED,
                result.state(), result.version(), result, null, correlationId, outboxId,
                outboxId == null ? null : OutboxState.PENDING, now);
    }

    public CommandRow saveBookingImpactCommand(
            long tenantId,
            long actorId,
            ScenarioState scenarioState,
            String idempotencyKey,
            String fingerprint,
            String reason,
            String correlationId,
            BookingImpactPreview result,
            OffsetDateTime now) {
        UUID commandId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_space_planning_commands(
                    command_id,tenant_id,actor_user_id,scenario_id,command_type,idempotency_key,
                    request_fingerprint,reason,command_state,resulting_scenario_state,
                    resulting_scenario_version,result_snapshot,correlation_id,outbox_id,accepted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, commandId, tenantId, actorId, result.scenarioId(),
                "BOOKING_IMPACT_PREVIEW", idempotencyKey, fingerprint, reason.trim(),
                CommandState.SUCCEEDED.name(), scenarioState.name(), result.scenarioVersion(),
                json(result), correlationId, outboxId, now);
        jdbc.update("""
                INSERT INTO wp_space_planning_outbox(
                    outbox_id,tenant_id,command_id,scenario_id,event_type,payload,
                    delivery_state,attempt_count,next_attempt_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?::jsonb,'PENDING',0,?,?,?)
                """, outboxId, tenantId, commandId, result.scenarioId(),
                "workplace.space-planning.booking-impact-previewed",
                json(Map.of("scenarioId", result.scenarioId().toString(),
                        "scenarioVersion", result.scenarioVersion(),
                        "impactState", result.state().name(),
                        "impactPreviewId", result.impactPreviewId().toString())),
                now, now, now);
        return new CommandRow(commandId, tenantId, actorId, result.scenarioId(),
                "BOOKING_IMPACT_PREVIEW", idempotencyKey, fingerprint, reason.trim(),
                CommandState.SUCCEEDED, scenarioState, result.scenarioVersion(), null, result,
                correlationId, outboxId, OutboxState.PENDING, now);
    }

    public void audit(
            long tenantId,
            long actorId,
            String action,
            UUID scenarioId,
            String reason,
            String correlationId,
            ScenarioState state,
            long version,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_space_planning_audit_events(
                    audit_event_id,tenant_id,actor_user_id,action,scenario_id,reason,
                    correlation_id,snapshot,occurred_at)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, actorId, action, scenarioId, reason.trim(),
                correlationId, json(Map.of("state", state.name(), "version", version)), now);
    }

    private SourceRow sourceRow(ResultSet rs, int row) throws SQLException {
        PlanningScope scope = new PlanningScope(rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("neighborhood"),
                rs.getString("resource_type"), rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class));
        return new SourceRow(rs.getObject("observation_id", UUID.class), rs.getLong("tenant_id"),
                PlanningSeries.valueOf(rs.getString("series_kind")), scope,
                SourceAvailability.valueOf(rs.getString("availability_state")),
                rs.getBigDecimal("coverage_percent"), rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getString("evidence_reference"),
                read(rs.getString("exclusions"), STRINGS), read(rs.getString("series_points"), SERIES_POINTS),
                rs.getLong("observation_sequence"));
    }

    private ForecastRow forecastRow(ResultSet rs, int row) throws SQLException {
        PlanningScope scope = new PlanningScope(rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("neighborhood"),
                rs.getString("resource_type"), rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class));
        String recommendation = rs.getString("recommendation_metrics");
        return new ForecastRow(rs.getObject("forecast_id", UUID.class), rs.getLong("tenant_id"),
                scope, ForecastState.valueOf(rs.getString("forecast_state")),
                rs.getString("calculation_version"), rs.getString("evidence_reference"),
                read(rs.getString("source_observation_ids"), UUIDS),
                read(rs.getString("forecast_points"), FORECAST_POINTS),
                recommendation == null ? null : read(recommendation, RecommendationMetrics.class),
                read(rs.getString("limitations"), STRINGS),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    private EmissionProjection emission(ResultSet rs, int row) throws SQLException {
        return new EmissionProjection(rs.getObject("emission_evidence_id", UUID.class),
                EmissionEvidenceKind.valueOf(rs.getString("evidence_kind")),
                rs.getBigDecimal("energy_value"), rs.getString("energy_unit"),
                rs.getBigDecimal("co2e_value"), rs.getString("co2e_unit"),
                rs.getString("factor_version"), rs.getString("region_code"),
                rs.getString("evidence_reference"), rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    private ScenarioRow scenarioRow(ResultSet rs, int row) throws SQLException {
        PlanningScope scope = new PlanningScope(rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("neighborhood"),
                rs.getString("resource_type"), rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class));
        ScenarioDraftInput draft = new ScenarioDraftInput(rs.getInt("proposed_capacity"),
                rs.getInt("proposed_room_capacity"),
                rs.getInt("proposed_accessible_resource_count"),
                rs.getObject("operating_start", LocalTime.class),
                rs.getObject("operating_end", LocalTime.class), rs.getString("policy_reference"),
                read(rs.getString("affected_resource_ids"), UUIDS),
                read(rs.getString("neighborhood_allocations"), ALLOCATIONS),
                rs.getObject("emission_evidence_id", UUID.class));
        return new ScenarioRow(rs.getObject("scenario_id", UUID.class), rs.getLong("tenant_id"),
                rs.getString("name"), rs.getString("description"),
                ScenarioState.valueOf(rs.getString("lifecycle_state")), scope, draft,
                rs.getObject("active_preview_id", UUID.class), rs.getLong("version"),
                rs.getObject("submitted_at", OffsetDateTime.class), longOrNull(rs, "submitted_by"),
                rs.getObject("approved_at", OffsetDateTime.class), longOrNull(rs, "approved_by"),
                rs.getString("approval_authority_reference"),
                rs.getObject("published_at", OffsetDateTime.class), longOrNull(rs, "published_by"),
                rs.getObject("last_rejected_at", OffsetDateTime.class),
                longOrNull(rs, "last_rejected_by"), rs.getString("last_rejection_reason"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getLong("created_by"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("updated_by"));
    }

    private ScenarioPreviewRow previewRow(ResultSet rs, int row) throws SQLException {
        String emission = rs.getString("emission_projection");
        return new ScenarioPreviewRow(rs.getObject("preview_id", UUID.class),
                rs.getLong("tenant_id"), rs.getObject("scenario_id", UUID.class),
                rs.getLong("scenario_version"), rs.getObject("forecast_id", UUID.class),
                ForecastState.valueOf(rs.getString("forecast_state")),
                read(rs.getString("forecast_projection"), ForecastProjection.class),
                read(rs.getString("comparison"), SpaceComparison.class),
                emission == null ? null : read(emission, EmissionProjection.class),
                rs.getBoolean("eligible"), read(rs.getString("limitations"), STRINGS),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getLong("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private CommandRow commandRow(ResultSet rs, int row) throws SQLException {
        String delivery = rs.getString("delivery_state");
        String commandType = rs.getString("command_type");
        String snapshot = rs.getString("result_snapshot");
        boolean impact = "BOOKING_IMPACT_PREVIEW".equals(commandType);
        return new CommandRow(rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("scenario_id", UUID.class),
                commandType, rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("reason"),
                CommandState.valueOf(rs.getString("command_state")),
                ScenarioState.valueOf(rs.getString("resulting_scenario_state")),
                rs.getLong("resulting_scenario_version"),
                impact ? null : read(snapshot, ScenarioView.class),
                impact ? read(snapshot, BookingImpactPreview.class) : null,
                rs.getString("correlation_id"),
                rs.getObject("outbox_id", UUID.class),
                delivery == null ? null : OutboxState.valueOf(delivery),
                rs.getObject("accepted_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Space-planning value could not be serialized.", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted space-planning value is invalid.", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted space-planning value is invalid.", exception);
        }
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record SourceRow(
            UUID observationId, long tenantId, PlanningSeries series, PlanningScope scope,
            SourceAvailability availability, BigDecimal coveragePercent,
            OffsetDateTime sourceAt, OffsetDateTime receivedAt, String evidenceReference,
            List<String> exclusions, List<SeriesPoint> points, long sequence) { }

    record ForecastRow(
            UUID forecastId, long tenantId, PlanningScope scope, ForecastState state,
            String calculationVersion, String evidenceReference, List<UUID> sourceObservationIds,
            List<ForecastPoint> points, RecommendationMetrics recommendationMetrics,
            List<String> limitations, OffsetDateTime sourceAt, OffsetDateTime receivedAt) { }

    record ScenarioRow(
            UUID scenarioId, long tenantId, String name, String description, ScenarioState state,
            PlanningScope scope, ScenarioDraftInput draft, UUID activePreviewId, long version,
            OffsetDateTime submittedAt, Long submittedBy, OffsetDateTime approvedAt, Long approvedBy,
            String approvalAuthorityReference, OffsetDateTime publishedAt, Long publishedBy,
            OffsetDateTime lastRejectedAt, Long lastRejectedBy, String lastRejectionReason,
            OffsetDateTime createdAt, long createdBy, OffsetDateTime updatedAt, long updatedBy) { }

    record ScenarioPreviewRow(
            UUID previewId, long tenantId, UUID scenarioId, long scenarioVersion, UUID forecastId,
            ForecastState forecastState, ForecastProjection forecast, SpaceComparison comparison,
            EmissionProjection emission,
            boolean eligible, List<String> limitations, OffsetDateTime expiresAt,
            long createdBy, OffsetDateTime createdAt) { }

    record CommandRow(
            UUID commandId, long tenantId, long actorUserId, UUID scenarioId, String commandType,
            String idempotencyKey, String requestFingerprint, String reason, CommandState state,
            ScenarioState resultingState, long resultingVersion, ScenarioView result,
            BookingImpactPreview bookingImpactResult,
            String correlationId,
            UUID outboxId, OutboxState outboxState, OffsetDateTime acceptedAt) { }
}
