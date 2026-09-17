package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.*;

@Repository
class WorkplaceFacilityClosureImpactRepository {
    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    WorkplaceFacilityClosureImpactRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    OffsetDateTime now() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
    }

    void lockKey(long tenant, long actor, String scope, String key) {
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                statement -> statement.setString(1, scope + ':' + tenant + ':' + actor + ':' + key), ignored -> null);
    }

    Optional<SavedPreview> previewReplay(long tenant, long actor, String key) {
        return jdbc.query("""
                SELECT preview_id, request_fingerprint
                  FROM wp_facility_closure_impact_previews
                 WHERE tenant_id=:tenant AND actor_user_id=:actor AND idempotency_key=:key
                """, p(tenant).addValue("actor", actor).addValue("key", key),
                (rs, ignored) -> new SavedPreview(rs.getObject(1, UUID.class), rs.getString(2)))
                .stream().findFirst();
    }

    Optional<SavedCommand> commandReplay(long tenant, long actor, String key) {
        return jdbc.query("""
                SELECT command_id, request_fingerprint
                  FROM wp_facility_closure_commands
                 WHERE tenant_id=:tenant AND actor_user_id=:actor AND idempotency_key=:key
                """, p(tenant).addValue("actor", actor).addValue("key", key),
                (rs, ignored) -> new SavedCommand(rs.getObject(1, UUID.class), rs.getString(2)))
                .stream().findFirst();
    }

    List<SourceBooking> affectedBookings(long tenant,
                                          WorkplaceExperienceFacilitiesRepository.Target target,
                                          OffsetDateTime from, OffsetDateTime to) {
        if ("ROOM".equals(target.type())) return affectedCalendarBookings(tenant, target, from, to);
        return jdbc.query("""
                SELECT booking_id, NULL::uuid event_id, resource_id owner_resource_id,
                       starts_at, ends_at, booking_status, version,
                       jsonb_build_array(user_id) recipient_user_ids,
                       NULL::varchar replacement_block_reason
                  FROM wp_bookings
                 WHERE tenant_id=:tenant AND resource_id=:resource
                   AND booking_status IN ('RESERVED','CHECKED_IN')
                   AND starts_at < :to AND ends_at > :from
                 ORDER BY starts_at, booking_id
                 LIMIT 1001
                """, p(tenant).addValue("resource", target.resourceId())
                        .addValue("from", from).addValue("to", to),
                (rs, ignored) -> source(rs, ReservationOwner.WORKPLACE, target.resourceId()));
    }

    private List<SourceBooking> affectedCalendarBookings(long tenant,
                                                          WorkplaceExperienceFacilitiesRepository.Target target,
                                                          OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                SELECT b.booking_id, b.event_id, b.resource_id owner_resource_id,
                       MIN(o.occurrence_starts_at) starts_at,
                       MAX(o.occurrence_ends_at) ends_at,
                       b.booking_status, b.version,
                       (SELECT COALESCE(jsonb_agg(u.user_id ORDER BY u.user_id),'[]'::jsonb)
                          FROM (SELECT e.organizer_user_id user_id
                                UNION
                                SELECT a.attendee_user_id FROM cal_event_attendees a
                                 WHERE a.tenant_id=e.tenant_id AND a.event_id=e.event_id
                                   AND a.attendee_user_id IS NOT NULL
                                   AND a.response_status <> 'DECLINED') u) recipient_user_ids,
                       CASE WHEN e.recurrence_pattern <> 'NONE'
                            THEN 'RECURRING_ROOM_REPLACEMENT_REQUIRES_CALENDAR'
                            ELSE NULL END replacement_block_reason
                  FROM cal_resource_bookings b
                  JOIN cal_events e ON e.tenant_id=b.tenant_id AND e.event_id=b.event_id
                  CROSS JOIN LATERAL wp_facility_calendar_occurrences(
                       b.starts_at,b.ends_at,e.time_zone,e.recurrence_pattern,
                       e.recurrence_interval,e.recurrence_until) o
                 WHERE b.tenant_id=:tenant AND b.resource_id=:ownerResource
                   AND b.booking_status IN ('PENDING','CONFIRMED')
                   AND e.status <> 'CANCELLED'
                   AND o.occurrence_starts_at < :to AND o.occurrence_ends_at > :from
                 GROUP BY b.booking_id,b.event_id,b.resource_id,b.booking_status,b.version,
                          e.tenant_id,e.event_id,e.organizer_user_id,e.recurrence_pattern
                 ORDER BY MIN(o.occurrence_starts_at),b.booking_id
                 LIMIT 1001
                """, p(tenant).addValue("ownerResource", target.calendarResourceId())
                        .addValue("from", from).addValue("to", to),
                (rs, ignored) -> source(rs, ReservationOwner.CALENDAR, target.resourceId()));
    }

    List<CandidateRow> candidates(long tenant, WorkplaceExperienceFacilitiesRepository.Target target,
                                  SourceBooking booking) {
        if (booking.replacementBlockReason() != null) return List.of();
        String ownerExpression = "ROOM".equals(target.type()) ? "r.calendar_resource_id" : "r.resource_id";
        String ownerAvailability = "ROOM".equals(target.type()) ? """
                AND r.calendar_resource_id IS NOT NULL
                AND EXISTS (SELECT 1 FROM cal_resources cr
                      WHERE cr.tenant_id=r.tenant_id AND cr.resource_id=r.calendar_resource_id
                        AND cr.lifecycle_state='AVAILABLE')
                AND NOT EXISTS (SELECT 1 FROM cal_resource_bookings cb
                      JOIN cal_events ce ON ce.tenant_id=cb.tenant_id AND ce.event_id=cb.event_id
                     WHERE cb.tenant_id=r.tenant_id AND cb.resource_id=r.calendar_resource_id
                       AND cb.booking_status IN ('PENDING','CONFIRMED') AND ce.status <> 'CANCELLED'
                       AND cb.booking_id <> :booking
                       AND cb.starts_at < :to AND cb.ends_at > :from)
                """ : """
                AND NOT EXISTS (SELECT 1 FROM wp_bookings wb
                     WHERE wb.tenant_id=r.tenant_id AND wb.resource_id=r.resource_id
                       AND wb.booking_status IN ('RESERVED','CHECKED_IN')
                       AND wb.booking_id <> :booking
                       AND wb.starts_at < :to AND wb.ends_at > :from)
                """;
        return jdbc.query("""
                SELECT r.resource_id workplace_resource_id, %s owner_resource_id,
                       r.name_ko resource_name, r.floor_id, r.version,
                       row_number() OVER (ORDER BY (r.floor_id=:floor) DESC,r.name_ko,r.resource_id) rank
                  FROM wp_resources r JOIN wp_floors f
                    ON f.tenant_id=r.tenant_id AND f.floor_id=r.floor_id
                 WHERE r.tenant_id=:tenant AND f.site_id=:site
                   AND r.resource_type=:type AND r.resource_id<>:source
                   AND r.lifecycle_state='AVAILABLE' AND r.booking_mode='RESERVABLE'
                   %s
                   AND NOT EXISTS (SELECT 1 FROM wp_experience_facility_closures c
                       WHERE c.tenant_id=r.tenant_id AND c.resource_id=r.resource_id
                         AND c.closure_status='ACTIVE'
                         AND c.starts_at < :to AND c.ends_at > :from)
                 ORDER BY rank LIMIT 5
                """.formatted(ownerExpression, ownerAvailability),
                p(tenant).addValue("site", target.siteId()).addValue("floor", target.floorId())
                        .addValue("type", target.type()).addValue("source", target.resourceId())
                        .addValue("booking", booking.bookingId()).addValue("from", booking.startsAt())
                        .addValue("to", booking.endsAt()),
                (rs, ignored) -> new CandidateRow(rs.getObject("workplace_resource_id", UUID.class),
                        rs.getObject("owner_resource_id", UUID.class), rs.getString("resource_name"),
                        rs.getObject("floor_id", UUID.class), rs.getLong("version"), rs.getInt("rank")));
    }

    UUID insertPreview(long tenant, long actor, UUID site, UUID resource, ReservationOwner owner,
                       OffsetDateTime from, OffsetDateTime to, long resourceVersion,
                       String token, int bookingCount, int recipientCount, String key,
                       String fingerprint, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_facility_closure_impact_previews(
                    preview_id,tenant_id,actor_user_id,site_id,resource_id,reservation_owner,
                    starts_at,ends_at,resource_version,confirmation_token,affected_booking_count,
                    affected_recipient_count,idempotency_key,request_fingerprint,expires_at)
                VALUES(:id,:tenant,:actor,:site,:resource,:owner,:from,:to,:resourceVersion,
                       :token,:bookingCount,:recipientCount,:key,:fingerprint,:expiresAt)
                """, p(tenant).addValue("id", id).addValue("actor", actor).addValue("site", site)
                .addValue("resource", resource).addValue("owner", owner.name())
                .addValue("from", from).addValue("to", to).addValue("resourceVersion", resourceVersion)
                .addValue("token", token).addValue("bookingCount", bookingCount)
                .addValue("recipientCount", recipientCount).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("expiresAt", expiresAt));
        return id;
    }

    void insertItem(long tenant, UUID preview, SourceBooking source, List<CandidateRow> candidates) {
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_facility_closure_impact_items(
                    preview_item_id,preview_id,tenant_id,reservation_owner,booking_id,event_id,
                    source_workplace_resource_id,source_owner_resource_id,starts_at,ends_at,
                    booking_status,booking_version,recipient_user_ids,replacement_block_reason)
                VALUES(:id,:preview,:tenant,:owner,:booking,:event,:sourceResource,:ownerResource,
                       :from,:to,:status,:version,CAST(:recipients AS jsonb),:block)
                """, p(tenant).addValue("id", item).addValue("preview", preview)
                .addValue("owner", source.owner().name()).addValue("booking", source.bookingId())
                .addValue("event", source.eventId()).addValue("sourceResource", source.sourceWorkplaceResourceId())
                .addValue("ownerResource", source.ownerResourceId()).addValue("from", source.startsAt())
                .addValue("to", source.endsAt()).addValue("status", source.status())
                .addValue("version", source.version()).addValue("recipients", write(source.recipientUserIds()))
                .addValue("block", source.replacementBlockReason()));
        for (CandidateRow candidate : candidates) jdbc.update("""
                INSERT INTO wp_facility_closure_replacement_candidates(
                    preview_item_id,tenant_id,workplace_resource_id,owner_resource_id,
                    resource_name,floor_id,resource_version,rank)
                VALUES(:item,:tenant,:workplace,:owner,:name,:floor,:version,:rank)
                """, p(tenant).addValue("item", item).addValue("workplace", candidate.workplaceResourceId())
                .addValue("owner", candidate.ownerResourceId()).addValue("name", candidate.name())
                .addValue("floor", candidate.floorId()).addValue("version", candidate.version())
                .addValue("rank", candidate.rank()));
    }

    Optional<PreviewRow> previewRow(long tenant, UUID site, UUID preview, boolean lock) {
        return jdbc.query("""
                SELECT * FROM wp_facility_closure_impact_previews
                 WHERE tenant_id=:tenant AND site_id=:site AND preview_id=:preview
                """ + (lock ? " FOR UPDATE" : ""),
                p(tenant).addValue("site", site).addValue("preview", preview), this::previewRow)
                .stream().findFirst();
    }

    List<ImpactItem> items(long tenant, UUID preview) {
        List<ImpactItem> result = new ArrayList<>();
        jdbc.query("""
                SELECT * FROM wp_facility_closure_impact_items
                 WHERE tenant_id=:tenant AND preview_id=:preview
                 ORDER BY starts_at,preview_item_id
                """, p(tenant).addValue("preview", preview), rs -> {
            UUID itemId = rs.getObject("preview_item_id", UUID.class);
            result.add(new ImpactItem(itemId, ReservationOwner.valueOf(rs.getString("reservation_owner")),
                    rs.getObject("booking_id", UUID.class), rs.getObject("event_id", UUID.class),
                    rs.getObject("source_workplace_resource_id", UUID.class),
                    rs.getObject("source_owner_resource_id", UUID.class),
                    rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                    rs.getString("booking_status"), rs.getLong("booking_version"),
                    readLongs(rs.getString("recipient_user_ids")), rs.getString("replacement_block_reason"),
                    candidateDtos(tenant, itemId)));
        });
        return result;
    }

    private List<ReplacementCandidate> candidateDtos(long tenant, UUID item) {
        return jdbc.query("""
                SELECT * FROM wp_facility_closure_replacement_candidates
                 WHERE tenant_id=:tenant AND preview_item_id=:item ORDER BY rank
                """, p(tenant).addValue("item", item), (rs, ignored) -> new ReplacementCandidate(
                rs.getObject("workplace_resource_id", UUID.class), rs.getObject("owner_resource_id", UUID.class),
                rs.getString("resource_name"), rs.getObject("floor_id", UUID.class),
                rs.getLong("resource_version"), rs.getInt("rank")));
    }

    UUID insertCommand(long tenant, long actor, UUID site, UUID resource, UUID preview,
                       long expectedVersion, String reason, String key, String fingerprint,
                       String correlation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_facility_closure_commands(
                    command_id,tenant_id,actor_user_id,site_id,resource_id,preview_id,
                    command_state,expected_preview_version,reason,explicit_confirmation,
                    idempotency_key,request_fingerprint,correlation_id)
                VALUES(:id,:tenant,:actor,:site,:resource,:preview,'EXECUTING',:version,
                       :reason,TRUE,:key,:fingerprint,:correlation)
                """, p(tenant).addValue("id", id).addValue("actor", actor).addValue("site", site)
                .addValue("resource", resource).addValue("preview", preview).addValue("version", expectedVersion)
                .addValue("reason", reason).addValue("key", key).addValue("fingerprint", fingerprint)
                .addValue("correlation", correlation));
        return id;
    }

    void insertCommandItem(long tenant, UUID command, ImpactItem item, ImpactSelection selection,
                           UUID replacementOwner) {
        jdbc.update("""
                INSERT INTO wp_facility_closure_command_items(
                    command_item_id,command_id,preview_item_id,tenant_id,reservation_owner,booking_id,
                    selected_action,expected_booking_version,replacement_workplace_resource_id,
                    replacement_owner_resource_id,replacement_resource_version,result_state,result_code)
                VALUES(:id,:command,:previewItem,:tenant,:owner,:booking,:action,:version,
                       :replacement,:replacementOwner,:replacementVersion,'PENDING','PENDING')
                """, p(tenant).addValue("id", UUID.randomUUID()).addValue("command", command)
                .addValue("previewItem", item.previewItemId()).addValue("owner", item.reservationOwner().name())
                .addValue("booking", item.bookingId()).addValue("action", selection.action().name())
                .addValue("version", selection.expectedBookingVersion())
                .addValue("replacement", selection.replacementResourceId())
                .addValue("replacementOwner", replacementOwner)
                .addValue("replacementVersion", selection.expectedReplacementResourceVersion()));
    }

    void lockResources(long tenant, Set<UUID> calendarResources, Set<UUID> workplaceResources) {
        calendarResources.stream().sorted().forEach(id -> advisory(tenant + ":" + id));
        workplaceResources.stream().sorted().forEach(id -> advisory("workplace-resource:" + tenant + ":" + id));
    }

    private void advisory(String value) {
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                statement -> statement.setString(1, value), ignored -> null);
    }

    long apply(long tenant, long actor, UUID command, ImpactItem item, ImpactSelection selection,
               UUID replacementOwner, String reason, String correlation) {
        if (selection.action() == ImpactAction.KEEP) {
            assertCurrent(tenant, item, selection.expectedBookingVersion());
            completeItem(tenant, command, item.previewItemId(), "KEPT", selection.expectedBookingVersion());
            return selection.expectedBookingVersion();
        }
        int changed;
        if (item.reservationOwner() == ReservationOwner.WORKPLACE) {
            changed = jdbc.update(selection.action() == ImpactAction.CANCEL ? """
                    UPDATE wp_bookings SET booking_status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,
                           version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=:actor
                     WHERE tenant_id=:tenant AND booking_id=:booking AND version=:version
                       AND booking_status IN ('RESERVED','CHECKED_IN')
                    """ : """
                    UPDATE wp_bookings SET resource_id=:replacement,version=version+1,
                           updated_at=CURRENT_TIMESTAMP,updated_by=:actor
                     WHERE tenant_id=:tenant AND booking_id=:booking AND version=:version
                       AND booking_status='RESERVED'
                    """, p(tenant).addValue("actor", actor).addValue("booking", item.bookingId())
                    .addValue("version", selection.expectedBookingVersion())
                    .addValue("replacement", selection.replacementResourceId()));
        } else {
            changed = jdbc.update(selection.action() == ImpactAction.CANCEL ? """
                    UPDATE cal_resource_bookings SET booking_status='CANCELLED',version=version+1,
                           updated_at=CURRENT_TIMESTAMP,updated_by=:actor
                     WHERE tenant_id=:tenant AND booking_id=:booking AND version=:version
                       AND booking_status IN ('PENDING','CONFIRMED')
                    """ : """
                    UPDATE cal_resource_bookings SET resource_id=:replacementOwner,version=version+1,
                           updated_at=CURRENT_TIMESTAMP,updated_by=:actor
                     WHERE tenant_id=:tenant AND booking_id=:booking AND version=:version
                       AND booking_status IN ('PENDING','CONFIRMED')
                    """, p(tenant).addValue("actor", actor).addValue("booking", item.bookingId())
                    .addValue("version", selection.expectedBookingVersion())
                    .addValue("replacementOwner", replacementOwner));
            if (changed == 1 && selection.action() == ImpactAction.REPLACE) {
                jdbc.update("""
                        UPDATE cal_events SET location=(SELECT name_ko FROM cal_resources
                              WHERE tenant_id=:tenant AND resource_id=:replacementOwner),
                              version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=:actor
                         WHERE tenant_id=:tenant AND event_id=:event
                        """, p(tenant).addValue("replacementOwner", replacementOwner)
                        .addValue("actor", actor).addValue("event", item.eventId()));
            }
            if (changed == 1) auditCalendar(tenant, actor, item.eventId(), item, selection, replacementOwner,
                    reason, correlation);
        }
        if (changed != 1) throw new StaleBookingException(item.bookingId());
        long resulting = selection.expectedBookingVersion() + 1;
        completeItem(tenant, command, item.previewItemId(),
                selection.action() == ImpactAction.CANCEL ? "CANCELLED" : "REPLACED", resulting);
        return resulting;
    }

    private void assertCurrent(long tenant, ImpactItem item, long version) {
        String table = item.reservationOwner() == ReservationOwner.WORKPLACE ? "wp_bookings" : "cal_resource_bookings";
        String active = item.reservationOwner() == ReservationOwner.WORKPLACE
                ? "('RESERVED','CHECKED_IN')" : "('PENDING','CONFIRMED')";
        Boolean current = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                        + " WHERE tenant_id=:tenant AND booking_id=:booking AND version=:version"
                        + " AND booking_status IN " + active + ")",
                p(tenant).addValue("booking", item.bookingId()).addValue("version", version), Boolean.class);
        if (!Boolean.TRUE.equals(current)) throw new StaleBookingException(item.bookingId());
    }

    private void completeItem(long tenant, UUID command, UUID previewItem, String code, long version) {
        jdbc.update("""
                UPDATE wp_facility_closure_command_items
                   SET result_state='SUCCEEDED',result_code=:code,resulting_booking_version=:version,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=:tenant AND command_id=:command AND preview_item_id=:previewItem
                """, p(tenant).addValue("command", command).addValue("previewItem", previewItem)
                .addValue("code", code).addValue("version", version));
    }

    private void auditCalendar(long tenant, long actor, UUID event, ImpactItem item,
                               ImpactSelection selection, UUID replacementOwner,
                               String reason, String correlation) {
        String after = write(java.util.Map.of(
                "bookingId", item.bookingId().toString(), "action", selection.action().name(),
                "reason", reason, "replacementResourceId",
                replacementOwner == null ? "" : replacementOwner.toString()));
        jdbc.update("""
                INSERT INTO cal_audit_events(audit_event_id,tenant_id,event_id,action,actor_user_id,
                    correlation_id,before_snapshot,after_snapshot)
                VALUES(:id,:tenant,:event,'calendar.resource.booking.facility.closure.impact',:actor,
                       :correlation,'{}'::jsonb,CAST(:after AS jsonb))
                """, p(tenant).addValue("id", UUID.randomUUID()).addValue("event", event)
                .addValue("actor", actor).addValue("correlation", correlation).addValue("after", after));
    }

    void completeCommand(long tenant, UUID command, UUID closure, int kept, int cancelled,
                         int replaced, int recipients) {
        jdbc.update("""
                UPDATE wp_facility_closure_commands
                   SET closure_id=:closure,command_state='SUCCEEDED',kept_count=:kept,
                       cancelled_count=:cancelled,replaced_count=:replaced,
                       notification_recipient_count=:recipients,version=version+1,
                       completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=:tenant AND command_id=:command AND command_state='EXECUTING'
                """, p(tenant).addValue("command", command).addValue("closure", closure)
                .addValue("kept", kept).addValue("cancelled", cancelled)
                .addValue("replaced", replaced).addValue("recipients", recipients));
    }

    void notificationEvent(long tenant, UUID command, long recipient, UUID booking,
                           ImpactAction action, UUID event) {
        jdbc.update("""
                INSERT INTO wp_facility_closure_notification_events(
                    command_id,tenant_id,recipient_user_id,booking_id,impact_action,event_id)
                VALUES(:command,:tenant,:recipient,:booking,:action,:event)
                """, p(tenant).addValue("command", command).addValue("recipient", recipient)
                .addValue("booking", booking).addValue("action", action.name()).addValue("event", event));
    }

    void commandEvent(long tenant, UUID command, long actor, String type, String evidence,
                      String correlation) {
        jdbc.update("""
                INSERT INTO wp_facility_closure_command_events(
                    command_event_id,tenant_id,command_id,event_type,actor_user_id,evidence,correlation_id)
                VALUES(:id,:tenant,:command,:type,:actor,CAST(:evidence AS jsonb),:correlation)
                """, p(tenant).addValue("id", UUID.randomUUID()).addValue("command", command)
                .addValue("type", type).addValue("actor", actor).addValue("evidence", evidence)
                .addValue("correlation", correlation));
    }

    Optional<CommandRow> commandRow(long tenant, UUID site, UUID command) {
        return jdbc.query("""
                SELECT * FROM wp_facility_closure_commands
                 WHERE tenant_id=:tenant AND site_id=:site AND command_id=:command
                """, p(tenant).addValue("site", site).addValue("command", command), this::commandRow)
                .stream().findFirst();
    }

    List<CommandItem> commandItems(long tenant, UUID command) {
        return jdbc.query("""
                SELECT * FROM wp_facility_closure_command_items
                 WHERE tenant_id=:tenant AND command_id=:command
                 ORDER BY created_at,command_item_id
                """, p(tenant).addValue("command", command), (rs, ignored) -> new CommandItem(
                rs.getObject("command_item_id", UUID.class), rs.getObject("preview_item_id", UUID.class),
                ReservationOwner.valueOf(rs.getString("reservation_owner")),
                rs.getObject("booking_id", UUID.class), ImpactAction.valueOf(rs.getString("selected_action")),
                rs.getLong("expected_booking_version"),
                rs.getObject("replacement_workplace_resource_id", UUID.class),
                rs.getObject("replacement_owner_resource_id", UUID.class),
                nullableLong(rs, "replacement_resource_version"),
                ItemResultState.valueOf(rs.getString("result_state")), rs.getString("result_code"),
                nullableLong(rs, "resulting_booking_version")));
    }

    NotificationCounts notificationCounts(long tenant, UUID command) {
        return jdbc.query("""
                SELECT COUNT(DISTINCT n.recipient_user_id) recipients, COUNT(*) total,
                       COUNT(*) FILTER (WHERE o.status='PENDING') pending,
                       COUNT(*) FILTER (WHERE o.status='FAILED') retry,
                       COUNT(*) FILTER (WHERE o.status='SENDING' AND o.locked_until>=CURRENT_TIMESTAMP) sending,
                       COUNT(*) FILTER (WHERE o.status='PUBLISHED') published,
                       COUNT(*) FILTER (WHERE o.status='SENDING' AND o.locked_until<CURRENT_TIMESTAMP) unknown_count,
                       COUNT(*) FILTER (WHERE o.status='DEAD') dead
                  FROM wp_facility_closure_notification_events n
                  LEFT JOIN sys_domain_event_outbox o ON o.event_id=n.event_id
                 WHERE n.tenant_id=:tenant AND n.command_id=:command
                """, p(tenant).addValue("command", command), (rs, ignored) -> new NotificationCounts(
                rs.getInt("recipients"), rs.getInt("total"), rs.getInt("pending"), rs.getInt("retry"), rs.getInt("sending"),
                rs.getInt("published"), rs.getInt("unknown_count"), rs.getInt("dead")))
                .stream().findFirst().orElse(new NotificationCounts(0,0,0,0,0,0,0,0));
    }

    int reconcileExpired(long tenant, UUID command) {
        return jdbc.update("""
                UPDATE sys_domain_event_outbox o
                   SET status='FAILED',locked_by=NULL,lock_token=NULL,locked_until=NULL,
                       available_at=CURRENT_TIMESTAMP,last_error='Publisher outcome unknown; reconciled for idempotent retry',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE o.event_id IN (SELECT event_id FROM wp_facility_closure_notification_events
                       WHERE tenant_id=:tenant AND command_id=:command)
                   AND o.status='SENDING' AND o.locked_until<CURRENT_TIMESTAMP
                """, p(tenant).addValue("command", command));
    }

    int expediteFailed(long tenant, UUID command) {
        return jdbc.update("""
                UPDATE sys_domain_event_outbox o SET available_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                 WHERE o.event_id IN (SELECT event_id FROM wp_facility_closure_notification_events
                       WHERE tenant_id=:tenant AND command_id=:command)
                   AND o.status='FAILED'
                """, p(tenant).addValue("command", command));
    }

    List<UUID> deadEvents(long tenant, UUID command) {
        return jdbc.queryForList("""
                SELECT o.event_id FROM sys_domain_event_outbox o
                 JOIN wp_facility_closure_notification_events n ON n.event_id=o.event_id
                 WHERE n.tenant_id=:tenant AND n.command_id=:command AND o.status='DEAD'
                """, p(tenant).addValue("command", command), UUID.class);
    }

    Optional<SavedOperation> operationReplay(long tenant, long actor, String key) {
        return jdbc.query("""
                SELECT command_id,request_fingerprint,affected_event_count
                  FROM wp_facility_closure_notification_operations
                 WHERE tenant_id=:tenant AND actor_user_id=:actor AND idempotency_key=:key
                """, p(tenant).addValue("actor", actor).addValue("key", key),
                (rs, ignored) -> new SavedOperation(rs.getObject("command_id", UUID.class),
                        rs.getString("request_fingerprint"), rs.getInt("affected_event_count")))
                .stream().findFirst();
    }

    void completeNotificationOperation(long tenant, long actor, UUID command, String operation,
                                       long expectedVersion, String key, String fingerprint, int affected) {
        int changed = jdbc.update("""
                UPDATE wp_facility_closure_commands
                   SET version=version+1,updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=:tenant AND command_id=:command AND version=:version
                """, p(tenant).addValue("command", command).addValue("version", expectedVersion));
        if (changed != 1) throw new StaleCommandException(command);
        jdbc.update("""
                INSERT INTO wp_facility_closure_notification_operations(
                    operation_id,tenant_id,actor_user_id,command_id,operation_type,
                    expected_command_version,idempotency_key,request_fingerprint,affected_event_count)
                VALUES(:id,:tenant,:actor,:command,:operation,:version,:key,:fingerprint,:affected)
                """, p(tenant).addValue("id", UUID.randomUUID()).addValue("actor", actor)
                .addValue("command", command).addValue("operation", operation)
                .addValue("version", expectedVersion).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("affected", affected));
    }

    List<AuditEntry> audit(long tenant, UUID command) {
        return jdbc.query("""
                SELECT * FROM wp_facility_closure_command_events
                 WHERE tenant_id=:tenant AND command_id=:command
                 ORDER BY occurred_at,command_event_id
                """, p(tenant).addValue("command", command), (rs, ignored) -> new AuditEntry(
                rs.getObject("command_event_id", UUID.class), rs.getString("event_type"),
                rs.getLong("actor_user_id"), rs.getString("evidence"),
                rs.getString("correlation_id"), rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    Set<Long> recipients(List<ImpactItem> items) {
        Set<Long> result = new LinkedHashSet<>();
        items.forEach(item -> result.addAll(item.recipientUserIds()));
        return result;
    }

    private SourceBooking source(ResultSet rs, ReservationOwner owner, UUID workplaceResource) throws SQLException {
        return new SourceBooking(owner, rs.getObject("booking_id", UUID.class),
                rs.getObject("event_id", UUID.class), workplaceResource,
                rs.getObject("owner_resource_id", UUID.class), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("booking_status"),
                rs.getLong("version"), readLongs(rs.getString("recipient_user_ids")),
                rs.getString("replacement_block_reason"));
    }

    private PreviewRow previewRow(ResultSet rs, int ignored) throws SQLException {
        return new PreviewRow(rs.getObject("preview_id", UUID.class), rs.getLong("actor_user_id"),
                rs.getObject("site_id", UUID.class), rs.getObject("resource_id", UUID.class),
                ReservationOwner.valueOf(rs.getString("reservation_owner")),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                rs.getLong("resource_version"), rs.getLong("preview_version"),
                rs.getString("confirmation_token"), rs.getInt("affected_booking_count"),
                rs.getInt("affected_recipient_count"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private CommandRow commandRow(ResultSet rs, int ignored) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class), rs.getObject("preview_id", UUID.class),
                rs.getObject("closure_id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("site_id", UUID.class), CommandState.valueOf(rs.getString("command_state")),
                rs.getLong("expected_preview_version"), rs.getString("reason"), rs.getInt("kept_count"),
                rs.getInt("cancelled_count"), rs.getInt("replaced_count"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("correlation_id"));
    }

    private List<Long> readLongs(String value) {
        try { return json.readValue(value, LONGS); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid recipient snapshot.", invalid); }
    }

    String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Could not serialize closure evidence.", invalid); }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static MapSqlParameterSource p(long tenant) {
        return new MapSqlParameterSource("tenant", tenant);
    }

    record SavedPreview(UUID previewId, String fingerprint) { }
    record SavedCommand(UUID commandId, String fingerprint) { }
    record SavedOperation(UUID commandId, String fingerprint, int affectedEventCount) { }
    record SourceBooking(ReservationOwner owner, UUID bookingId, UUID eventId,
                         UUID sourceWorkplaceResourceId, UUID ownerResourceId,
                         OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                         long version, List<Long> recipientUserIds, String replacementBlockReason) { }
    record CandidateRow(UUID workplaceResourceId, UUID ownerResourceId, String name,
                        UUID floorId, long version, int rank) { }
    record PreviewRow(UUID previewId, long actorUserId, UUID siteId, UUID resourceId,
                      ReservationOwner owner, OffsetDateTime startsAt, OffsetDateTime endsAt,
                      long resourceVersion, long version, String confirmationToken,
                      int affectedBookingCount, int affectedRecipientCount,
                      OffsetDateTime expiresAt, OffsetDateTime createdAt) { }
    record CommandRow(UUID commandId, UUID previewId, UUID closureId, UUID resourceId,
                      UUID siteId, CommandState state, long expectedPreviewVersion, String reason,
                      int keptCount, int cancelledCount, int replacedCount, long version,
                      OffsetDateTime createdAt, OffsetDateTime completedAt, String correlationId) { }
    record NotificationCounts(int recipients, int total, int pending, int retry, int sending,
                              int published, int unknown, int dead) { }

    static final class StaleBookingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        StaleBookingException(UUID booking) { super("Booking changed: " + booking); }
    }
    static final class StaleCommandException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        StaleCommandException(UUID command) { super("Command changed: " + command); }
    }
}
