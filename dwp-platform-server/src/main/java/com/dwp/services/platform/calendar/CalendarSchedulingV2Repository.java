package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingV2Dtos.IntervalState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;

@Repository
class CalendarSchedulingV2Repository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CalendarSchedulingV2Repository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    boolean identityMatches(long tenantId, long userId, UUID personPublicId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                CalendarSchedulingV2Sql.IDENTITY_MATCH, Boolean.class,
                tenantId, userId, personPublicId));
    }

    Set<UUID> authorizedPeople(
            long tenantId,
            UUID viewerPersonPublicId,
            String verifiedGroupRefs,
            List<UUID> people) {
        if (people.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.query(
                CalendarSchedulingV2Sql.AUTHORIZED_PEOPLE,
                (row, ignored) -> row.getObject("person_public_id", UUID.class),
                tenantId,
                people.toArray(UUID[]::new),
                viewerPersonPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs)));
    }

    Optional<WorkingSettingsRow> workingSettings(
            long tenantId, UUID personPublicId, String fallbackTimeZone) {
        return jdbc.query(
                CalendarSchedulingV2Sql.WORKING_SETTINGS,
                (row, ignored) -> new WorkingSettingsRow(
                        row.getLong("user_id"),
                        row.getInt("working_days_mask"),
                        row.getObject("working_day_start", LocalTime.class),
                        row.getObject("working_day_end", LocalTime.class),
                        row.getString("time_zone"),
                        row.getString("source_type"),
                        row.getLong("version")),
                fallbackTimeZone, tenantId, personPublicId).stream().findFirst();
    }

    PolicyRow policy(long tenantId) {
        return jdbc.queryForObject(
                CalendarSchedulingV2Sql.POLICY,
                (row, ignored) -> new PolicyRow(
                        row.getObject("working_day_start", LocalTime.class),
                        row.getObject("working_day_end", LocalTime.class),
                        row.getInt("minimum_event_minutes"),
                        row.getInt("maximum_event_minutes"),
                        row.getInt("default_buffer_minutes"),
                        row.getLong("version")),
                tenantId);
    }

    List<IntervalRow> intervals(
            long tenantId,
            List<UUID> people,
            OffsetDateTime from,
            OffsetDateTime to) {
        if (people.isEmpty()) return List.of();
        UUID[] ids = people.toArray(UUID[]::new);
        return jdbc.query(
                CalendarSchedulingV2Sql.INTERVALS,
                (row, ignored) -> new IntervalRow(
                        row.getObject("person_public_id", UUID.class),
                        row.getObject("starts_at", OffsetDateTime.class),
                        row.getObject("ends_at", OffsetDateTime.class),
                        IntervalState.valueOf(row.getString("interval_state")),
                        row.getString("source_type")),
                from, to, ids, ids, tenantId);
    }

    String sourceVersion(long tenantId) {
        String value = jdbc.queryForObject(
                CalendarSchedulingV2Sql.SOURCE_VERSION,
                String.class, tenantId, tenantId, tenantId, tenantId);
        return value == null ? "-|-|-|-" : value;
    }

    List<RoomRow> rooms(
            long tenantId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return jdbc.query(
                CalendarSchedulingV2Sql.ROOMS,
                (row, ignored) -> room(row), endsAt, startsAt, tenantId);
    }

    Optional<RoomRow> room(
            long tenantId,
            UUID roomId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        return jdbc.query(
                CalendarSchedulingV2Sql.ROOM,
                (row, ignored) -> room(row), endsAt, startsAt, tenantId, roomId)
                .stream().findFirst();
    }

    void insertEvaluation(EvaluationEvidence value) {
        jdbc.update(
                CalendarSchedulingV2Sql.INSERT_EVALUATION,
                value.evaluationId(), value.tenantId(), value.actorUserId(),
                value.actorPersonPublicId(), value.criteriaHash(), value.participantHash(),
                value.criteriaPayload(), value.sourceEvidence(), value.roomEvidence(),
                value.sourceVersion(), value.challengeTokenHash(), value.generatedAt(),
                value.expiresAt());
    }

    Optional<EvaluationEvidence> evaluation(
            long tenantId, UUID evaluationId, long actorUserId, UUID actorPersonPublicId) {
        return jdbc.query(
                CalendarSchedulingV2Sql.EVALUATION,
                (row, ignored) -> evidence(row, tenantId, actorUserId, actorPersonPublicId),
                tenantId, evaluationId, actorUserId, actorPersonPublicId)
                .stream().findFirst();
    }

    Optional<EvaluationEvidence> consumeChallenge(
            long tenantId,
            UUID evaluationId,
            long actorUserId,
            UUID actorPersonPublicId,
            String criteriaHash,
            String challengeTokenHash,
            OffsetDateTime now) {
        return jdbc.query(
                CalendarSchedulingV2Sql.CONSUME_CHALLENGE,
                (row, ignored) -> evidence(row, tenantId, actorUserId, actorPersonPublicId),
                now, tenantId, evaluationId, actorUserId, actorPersonPublicId,
                criteriaHash, challengeTokenHash, now).stream().findFirst();
    }

    void insertRecheck(RecheckEvidence value) {
        jdbc.update(
                CalendarSchedulingV2Sql.INSERT_RECHECK,
                value.recheckId(), value.tenantId(), value.evaluationId(),
                value.actorUserId(), value.actorPersonPublicId(), value.criteriaHash(),
                value.participantHash(), value.selectionHash(), value.selectedRoomId(),
                value.sourceVersion(), value.resourceVersion(), value.policyVersion(),
                value.resultStatus(), value.hardRecheckTokenHash(), value.evidencePayload(),
                value.checkedAt(), value.expiresAt());
    }

    boolean consumeToken(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String tokenHash,
            String participantHash,
            String selectionHash,
            String sourceVersion,
            UUID selectedRoomId,
            Long resourceVersion,
            long policyVersion,
            OffsetDateTime now,
            UUID eventId) {
        return jdbc.update(
                CalendarSchedulingV2Sql.CONSUME_RECHECK_TOKEN,
                now, eventId, tenantId, actorUserId, actorPersonPublicId,
                tokenHash, participantHash, selectionHash, sourceVersion,
                selectedRoomId, resourceVersion, policyVersion, now) == 1;
    }

    int purgeExpired(long tenantId, OffsetDateTime cutoff, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.update(
                CalendarSchedulingV2Sql.PURGE_EXPIRED,
                tenantId, cutoff, bounded, tenantId);
    }

    int purgeExpiredGlobal(OffsetDateTime cutoff, int limit) {
        int bounded = Math.max(1, Math.min(limit, 5000));
        return jdbc.update(
                CalendarSchedulingV2Sql.PURGE_EXPIRED_GLOBAL,
                cutoff, bounded);
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Scheduling evidence could not be serialized.", exception);
        }
    }

    <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Scheduling evidence could not be read.", exception);
        }
    }

    private RoomRow room(java.sql.ResultSet row) throws java.sql.SQLException {
        return new RoomRow(
                row.getObject("resource_id", UUID.class),
                row.getString("resource_code"),
                row.getString("name_ko"),
                row.getString("name_en"),
                ResourceType.valueOf(row.getString("resource_type")),
                row.getString("site_name"),
                row.getString("floor_name"),
                row.getInt("capacity"),
                stringList(row.getString("features")),
                row.getString("time_zone"),
                row.getBoolean("approval_required"),
                ResourceState.valueOf(row.getString("lifecycle_state")),
                row.getLong("version"),
                row.getBoolean("conflicted"));
    }

    private EvaluationEvidence evidence(
            java.sql.ResultSet row,
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId) throws java.sql.SQLException {
        return new EvaluationEvidence(
                row.getObject("evaluation_id", UUID.class), tenantId,
                actorUserId, actorPersonPublicId,
                row.getString("criteria_hash"),
                row.getString("participant_hash"),
                row.getString("criteria_payload"),
                row.getString("source_evidence"),
                row.getString("room_evidence"),
                row.getString("source_version"),
                row.getString("challenge_token_hash"),
                row.getObject("generated_at", OffsetDateTime.class),
                row.getObject("expires_at", OffsetDateTime.class));
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    record WorkingSettingsRow(
            long userId,
            int workingDaysMask,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            String timeZone,
            String sourceType,
            long version) {
    }

    record PolicyRow(
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            int minimumEventMinutes,
            int maximumEventMinutes,
            int defaultBufferMinutes,
            long version) {
    }

    record IntervalRow(
            UUID personPublicId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            IntervalState state,
            String sourceType) {
    }

    record RoomRow(
            UUID resourceId,
            String code,
            String nameKo,
            String nameEn,
            ResourceType type,
            String site,
            String floor,
            int capacity,
            List<String> features,
            String timeZone,
            boolean approvalRequired,
            ResourceState state,
            long version,
            boolean conflicted) {
    }

    record EvaluationEvidence(
            UUID evaluationId,
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String criteriaHash,
            String participantHash,
            String criteriaPayload,
            String sourceEvidence,
            String roomEvidence,
            String sourceVersion,
            String challengeTokenHash,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt) {
    }

    record RecheckEvidence(
            UUID recheckId,
            long tenantId,
            UUID evaluationId,
            long actorUserId,
            UUID actorPersonPublicId,
            String criteriaHash,
            String participantHash,
            String selectionHash,
            UUID selectedRoomId,
            String sourceVersion,
            Long resourceVersion,
            long policyVersion,
            String resultStatus,
            String hardRecheckTokenHash,
            String evidencePayload,
            OffsetDateTime checkedAt,
            OffsetDateTime expiresAt) {
    }

}
