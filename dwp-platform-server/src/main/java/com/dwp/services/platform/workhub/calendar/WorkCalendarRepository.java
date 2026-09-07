package com.dwp.services.platform.workhub.calendar;

import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.SourceReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workhub.calendar.WorkCalendarDtos.*;

@Repository
public class WorkCalendarRepository {
    private final JdbcTemplate jdbc;
    public WorkCalendarRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(AccessContext context) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> { },
                "work-calendar:" + context.tenantId() + ":" + context.userId());
    }

    public Optional<Link> find(AccessContext context, UUID linkId) {
        return jdbc.query("SELECT * FROM personal_work_calendar_links WHERE tenant_id = ? AND owner_user_id = ? AND link_id = ?",
                this::map, context.tenantId(), context.userId(), linkId).stream().findFirst();
    }

    public List<Link> list(AccessContext context, int page, int size) {
        return jdbc.query("""
                SELECT * FROM personal_work_calendar_links
                WHERE tenant_id = ? AND owner_user_id = ? AND state = 'LINKED'
                ORDER BY created_at DESC, link_id LIMIT ? OFFSET ?
                """, this::map, context.tenantId(), context.userId(), size, (long) page * size);
    }

    public long count(AccessContext context) {
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM personal_work_calendar_links
                WHERE tenant_id = ? AND owner_user_id = ? AND state = 'LINKED'
                """, Long.class, context.tenantId(), context.userId());
        return value == null ? 0 : value;
    }

    public void insert(AccessContext context, UUID linkId, LinkRequest request) {
        jdbc.update("""
                INSERT INTO personal_work_calendar_links
                (link_id,tenant_id,owner_user_id,source_system,source_reference,obligation_key,event_id)
                VALUES (?,?,?,?,?,?,?)
                """, linkId, context.tenantId(), context.userId(), request.work().sourceSystem(),
                request.work().sourceReference(), normalizedKey(request.work()), request.eventId());
    }

    public boolean remove(AccessContext context, UUID linkId, long version) {
        return jdbc.update("""
                UPDATE personal_work_calendar_links SET state = 'REMOVED', version = version + 1,
                  updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND owner_user_id = ? AND link_id = ? AND version = ? AND state = 'LINKED'
                """, context.tenantId(), context.userId(), linkId, version) == 1;
    }

    static String normalizedKey(SourceReference reference) {
        return reference.obligationKey() == null ? "" : reference.obligationKey();
    }

    private Link map(ResultSet result, int index) throws SQLException {
        String obligationKey = result.getString("obligation_key");
        return new Link(result.getObject("link_id", UUID.class), new SourceReference(
                result.getString("source_system"), result.getString("source_reference"),
                obligationKey == null || obligationKey.isEmpty() ? null : obligationKey), result.getObject("event_id", UUID.class),
                result.getString("state"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class), "REFERENCE_ONLY");
    }
}
