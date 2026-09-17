package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Repository
public class SafetyAudienceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SafetyAudienceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    boolean scopeExists(long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds) {
        Boolean site = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_sites
                 WHERE tenant_id=? AND site_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId);
        if (!Boolean.TRUE.equals(site)) return false;
        boolean floors = floorIds.stream().distinct().allMatch(id -> Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM wp_floors
                         WHERE tenant_id=? AND site_id=? AND floor_id=?
                           AND lifecycle_state<>'CLOSED')
                        """, Boolean.class, tenantId, siteId, id)));
        if (!floors) return false;
        return zoneIds.stream().distinct().allMatch(id -> Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM wp_zones z JOIN wp_floors f
                          ON f.tenant_id=z.tenant_id AND f.floor_id=z.floor_id
                         WHERE z.tenant_id=? AND f.site_id=? AND z.zone_id=?
                           AND z.lifecycle_state<>'CLOSED')
                        """, Boolean.class, tenantId, siteId, id)));
    }

    List<CandidateRow> workplaceReservations(
            long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds,
            OffsetDateTime at) {
        return jdbc.query("""
                SELECT b.user_id, b.booked_for_display_name, f.floor_id, r.zone_id
                  FROM wp_bookings b
                  JOIN wp_resources r ON r.tenant_id=b.tenant_id AND r.resource_id=b.resource_id
                  JOIN wp_floors f ON f.tenant_id=r.tenant_id AND f.floor_id=r.floor_id
                 WHERE b.tenant_id=? AND f.site_id=?
                   AND b.booking_status IN ('RESERVED','CHECKED_IN')
                   AND b.starts_at<=? AND b.ends_at>?
                   AND (?::uuid[]='{}'::uuid[] OR f.floor_id=ANY(?::uuid[]))
                   AND (?::uuid[]='{}'::uuid[] OR r.zone_id=ANY(?::uuid[]))
                """, (rs, n) -> candidate(rs.getLong(1), rs.getString(2),
                        AudienceSourceKind.RESERVATION, false, at, at),
                tenantId, siteId, at, at, uuidArray(floorIds), uuidArray(floorIds),
                uuidArray(zoneIds), uuidArray(zoneIds));
    }

    List<CandidateRow> calendarReservations(
            long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds,
            OffsetDateTime at) {
        return jdbc.query("""
                SELECT person.user_id, person.label
                  FROM (
                    SELECT e.organizer_user_id user_id, 'U-'||e.organizer_user_id label,
                           f.floor_id, wr.zone_id
                      FROM cal_events e
                      JOIN cal_resource_bookings rb
                        ON rb.tenant_id=e.tenant_id AND rb.event_id=e.event_id
                       AND rb.booking_status IN ('PENDING','CONFIRMED')
                      JOIN wp_resources wr
                        ON wr.tenant_id=rb.tenant_id AND wr.calendar_resource_id=rb.resource_id
                      JOIN wp_floors f
                        ON f.tenant_id=wr.tenant_id AND f.floor_id=wr.floor_id
                     WHERE e.tenant_id=? AND f.site_id=?
                       AND e.status IN ('CONFIRMED','TENTATIVE')
                       AND e.starts_at<=? AND e.ends_at>?
                    UNION ALL
                    SELECT a.attendee_user_id user_id, a.attendee_name label,
                           f.floor_id, wr.zone_id
                      FROM cal_events e
                      JOIN cal_event_attendees a
                        ON a.tenant_id=e.tenant_id AND a.event_id=e.event_id
                       AND a.response_status<>'DECLINED' AND a.attendee_user_id IS NOT NULL
                      JOIN cal_resource_bookings rb
                        ON rb.tenant_id=e.tenant_id AND rb.event_id=e.event_id
                       AND rb.booking_status IN ('PENDING','CONFIRMED')
                      JOIN wp_resources wr
                        ON wr.tenant_id=rb.tenant_id AND wr.calendar_resource_id=rb.resource_id
                      JOIN wp_floors f
                        ON f.tenant_id=wr.tenant_id AND f.floor_id=wr.floor_id
                     WHERE e.tenant_id=? AND f.site_id=?
                       AND e.status IN ('CONFIRMED','TENTATIVE')
                       AND e.starts_at<=? AND e.ends_at>?
                  ) person
                 WHERE (?::uuid[]='{}'::uuid[] OR person.floor_id=ANY(?::uuid[]))
                   AND (?::uuid[]='{}'::uuid[] OR person.zone_id=ANY(?::uuid[]))
                """, (rs, n) -> candidate(rs.getLong(1), rs.getString(2),
                        AudienceSourceKind.RESERVATION, false, at, at),
                tenantId, siteId, at, at, tenantId, siteId, at, at,
                uuidArray(floorIds), uuidArray(floorIds), uuidArray(zoneIds), uuidArray(zoneIds));
    }

    List<CandidateRow> actualPresence(
            long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds) {
        return jdbc.query("""
                SELECT subject_key_sha256,subject_user_id,masked_label,presence_state,
                       source_at,received_at
                  FROM (SELECT DISTINCT ON (subject_key_sha256) *
                          FROM wp_safety_presence_observations
                         WHERE tenant_id=? AND site_id=?
                           AND (?::uuid[]='{}'::uuid[] OR floor_id=ANY(?::uuid[]))
                           AND (?::uuid[]='{}'::uuid[] OR zone_id=ANY(?::uuid[]))
                         ORDER BY subject_key_sha256,source_at DESC,sequence DESC) latest
                 WHERE presence_state<>'DEPARTED'
                """, (rs, n) -> new CandidateRow(rs.getString(1), nullableLong(rs, 2),
                        rs.getString(3), AudienceSourceKind.ACTUAL_PRESENCE,
                        !"PRESENT".equals(rs.getString(4)), rs.getObject(5, OffsetDateTime.class),
                        rs.getObject(6, OffsetDateTime.class)),
                tenantId, siteId, uuidArray(floorIds), uuidArray(floorIds),
                uuidArray(zoneIds), uuidArray(zoneIds));
    }

    boolean insertPresence(PresenceObservation observation) {
        return jdbc.update("""
                INSERT INTO wp_safety_presence_observations(
                    observation_id,tenant_id,subject_key_sha256,subject_user_id,masked_label,
                    site_id,floor_id,zone_id,presence_state,source_reference,evidence_reference,
                    source_at,received_at,sequence)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, observation.observationId(), observation.tenantId(),
                observation.subjectKeySha256(), observation.subjectUserId(),
                observation.maskedLabel(), observation.siteId(), observation.floorId(),
                observation.zoneId(), observation.presenceState(), observation.sourceReference(),
                observation.evidenceReference(), observation.sourceAt(),
                observation.receivedAt(), observation.sequence()) == 1;
    }

    List<CandidateRow> visitors(
            long tenantId, UUID siteId, List<UUID> zoneIds, OffsetDateTime at,
            boolean scheduled) {
        String condition = scheduled
                ? "v.starts_at>? AND v.visit_state IN ('APPROVED','ACCESS_PENDING','READY','INVITED')"
                : "v.starts_at<=? AND v.ends_at>? AND v.visit_state='ARRIVED'";
        Object[] args = scheduled
                ? new Object[]{tenantId, siteId, uuidArray(zoneIds), uuidArray(zoneIds), at}
                : new Object[]{tenantId, siteId, uuidArray(zoneIds), uuidArray(zoneIds), at, at};
        return jdbc.query("""
                SELECT g.search_token_sha256,g.guest_id,g.masked_label,v.updated_at
                  FROM wp_visits v
                  JOIN wp_visit_guests g
                    ON g.tenant_id=v.tenant_id AND g.visit_id=v.visit_id
                 WHERE v.tenant_id=? AND v.site_id=?
                   AND (?::uuid[]='{}'::uuid[] OR EXISTS(
                       SELECT 1 FROM wp_visit_zone_selections s
                        WHERE s.tenant_id=v.tenant_id AND s.visit_id=v.visit_id
                          AND s.zone_id=ANY(?::uuid[]))) AND """ + " " + condition,
                (rs, n) -> new CandidateRow(
                        rs.getString(1) == null ? sha256("guest:" + rs.getObject(2, UUID.class)) : rs.getString(1),
                        null, rs.getString(3), scheduled
                        ? AudienceSourceKind.SCHEDULED_VISITOR : AudienceSourceKind.VISITOR,
                        rs.getString(1) == null, rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(4, OffsetDateTime.class)), args);
    }

    void insertSnapshot(
            UUID snapshotId, long tenantId, String ownerType, UUID ownerId,
            int total, int deduplicated, int excluded, int unknown, int target,
            OffsetDateTime asOf) {
        jdbc.update("""
                INSERT INTO wp_safety_audience_snapshots(
                    audience_snapshot_id,tenant_id,owner_type,owner_id,total_candidates,
                    deduplicated_count,excluded_count,unknown_count,final_target_count,as_of)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, snapshotId, tenantId, ownerType, ownerId, total, deduplicated,
                excluded, unknown, target, asOf);
    }

    void insertSource(long tenantId, UUID snapshotId, SourceSummary source) {
        jdbc.update("""
                INSERT INTO wp_safety_audience_sources(
                    source_summary_id,tenant_id,audience_snapshot_id,source_kind,
                    candidate_count,included_count,excluded_count,unknown_count,
                    coverage_percent,freshness_state,availability_state,source_at,received_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, snapshotId, source.source().name(),
                source.candidateCount(), source.includedCount(), source.excludedCount(),
                source.unknownCount(), source.coveragePercent(), source.freshness().name(),
                source.availability().name(), source.sourceAt(), source.receivedAt());
    }

    void insertMember(long tenantId, UUID snapshotId, AudienceMember member) {
        jdbc.update("""
                INSERT INTO wp_safety_audience_members(
                    audience_member_id,tenant_id,audience_snapshot_id,subject_key_sha256,
                    subject_user_id,masked_label,source_kinds,included,exclusion_code,unknown_identity)
                VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)
                """, member.audienceMemberId(), tenantId, snapshotId,
                member.subjectKeySha256(), member.subjectUserId(), member.maskedLabel(),
                json(member.sources()), member.included(), member.exclusionCode(),
                member.unknownIdentity());
    }

    Optional<SnapshotRow> snapshotRow(long tenantId, UUID snapshotId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_audience_snapshots
                 WHERE tenant_id=? AND audience_snapshot_id=?
                """, (rs, n) -> new SnapshotRow(rs.getObject("audience_snapshot_id", UUID.class),
                rs.getInt("total_candidates"), rs.getInt("deduplicated_count"),
                rs.getInt("excluded_count"), rs.getInt("unknown_count"),
                rs.getInt("final_target_count"), rs.getObject("as_of", OffsetDateTime.class)),
                tenantId, snapshotId).stream().findFirst();
    }

    List<SourceSummary> sources(long tenantId, UUID snapshotId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_audience_sources
                 WHERE tenant_id=? AND audience_snapshot_id=? ORDER BY source_kind
                """, (rs, n) -> new SourceSummary(AudienceSourceKind.valueOf(rs.getString("source_kind")),
                rs.getInt("candidate_count"), rs.getInt("included_count"),
                rs.getInt("excluded_count"), rs.getInt("unknown_count"),
                nullableDouble(rs, "coverage_percent"),
                FreshnessState.valueOf(rs.getString("freshness_state")),
                AvailabilityState.valueOf(rs.getString("availability_state")),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class)), tenantId, snapshotId);
    }

    List<AudienceMember> members(long tenantId, UUID snapshotId, boolean includeExcluded) {
        return jdbc.query("""
                SELECT * FROM wp_safety_audience_members
                 WHERE tenant_id=? AND audience_snapshot_id=? AND (? OR included=TRUE)
                 ORDER BY masked_label,audience_member_id
                """, this::member, tenantId, snapshotId, includeExcluded);
    }

    private AudienceMember member(ResultSet rs, int row) throws SQLException {
        return new AudienceMember(rs.getObject("audience_member_id", UUID.class),
                rs.getString("subject_key_sha256"), nullableLong(rs, "subject_user_id"),
                rs.getString("masked_label"), enums(rs.getString("source_kinds")),
                rs.getBoolean("included"), rs.getString("exclusion_code"),
                rs.getBoolean("unknown_identity"));
    }

    private List<AudienceSourceKind> enums(String json) {
        try {
            return Arrays.stream(mapper.readValue(json, String[].class))
                    .map(AudienceSourceKind::valueOf).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted safety audience sources are invalid.", exception);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Safety audience value cannot be serialized.", exception);
        }
    }

    private static CandidateRow candidate(long userId, String label, AudienceSourceKind source,
                                          boolean unknown, OffsetDateTime sourceAt,
                                          OffsetDateTime receivedAt) {
        return new CandidateRow(sha256("user:" + userId), userId, mask(label), source,
                unknown, sourceAt, receivedAt);
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return "U**";
        String trimmed = value.trim();
        return trimmed.substring(0, 1) + "**";
    }

    static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object uuidArray(List<UUID> values) {
        return values.toArray(UUID[]::new);
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    record CandidateRow(String subjectKey, Long userId, String maskedLabel,
                        AudienceSourceKind source, boolean unknown,
                        OffsetDateTime sourceAt, OffsetDateTime receivedAt) { }
    record SnapshotRow(UUID id, int total, int deduplicated, int excluded,
                       int unknown, int target, OffsetDateTime asOf) { }
}
