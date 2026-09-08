package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class MeetingRecordRetentionHealthRepository {
    private final JdbcTemplate jdbc;
    public MeetingRecordRetentionHealthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claim(UUID fence, String worker, Duration lease) {
        return jdbc.update("""
                UPDATE vm_meeting_record_retention_health
                   SET last_attempt_at=clock_timestamp(), active_fence=?, active_worker_id=?,
                       active_lease_expires_at=clock_timestamp()+?*INTERVAL '1 millisecond', version=version+1
                 WHERE health_key='RECORD_RETENTION'
                   AND (active_fence IS NULL OR active_lease_expires_at<=clock_timestamp())
                """, fence, worker, lease.toMillis()) == 1;
    }

    public void assertLease(UUID fence, String worker) {
        var active = jdbc.query("""
                SELECT health_key FROM vm_meeting_record_retention_health
                 WHERE health_key='RECORD_RETENTION' AND active_fence=? AND active_worker_id=?
                   AND active_lease_expires_at>clock_timestamp() FOR UPDATE
                """, (rs, n) -> rs.getString(1), fence, worker);
        if (active.isEmpty()) throw new IllegalStateException("Record retention lease is not active.");
    }

    public void finish(UUID fence, String worker, boolean blocked) {
        int changed = jdbc.update("""
                UPDATE vm_meeting_record_retention_health SET last_success_at=clock_timestamp(),
                    last_failure_at=NULL, last_failure_code=NULL, overdue_remaining=?, active_fence=NULL,
                    active_worker_id=NULL, active_lease_expires_at=NULL, version=version+1
                 WHERE health_key='RECORD_RETENTION' AND active_fence=? AND active_worker_id=?
                   AND active_lease_expires_at>clock_timestamp()
                """, blocked, fence, worker);
        if (changed != 1) throw new IllegalStateException("Record retention lease was lost.");
    }

    public void fail(UUID fence, String worker) {
        int changed = jdbc.update("""
                UPDATE vm_meeting_record_retention_health SET last_failure_at=clock_timestamp(),
                    last_failure_code='RECORD_RETENTION_PURGE_FAILED', overdue_remaining=TRUE, active_fence=NULL,
                    active_worker_id=NULL, active_lease_expires_at=NULL, version=version+1
                 WHERE health_key='RECORD_RETENTION' AND active_fence=? AND active_worker_id=?
                   AND active_lease_expires_at>clock_timestamp()
                """, fence, worker);
        if (changed != 1) throw new IllegalStateException("Record retention lease was lost.");
    }

    public Health read() {
        return jdbc.query("SELECT * FROM vm_meeting_record_retention_health WHERE health_key='RECORD_RETENTION'",
                (rs, n) -> new Health(rs.getObject("last_success_at", OffsetDateTime.class),
                        rs.getObject("last_failure_at", OffsetDateTime.class), rs.getBoolean("overdue_remaining"),
                        rs.getObject("active_fence", UUID.class),
                        rs.getObject("active_lease_expires_at", OffsetDateTime.class)))
                .stream().findFirst().orElse(null);
    }
    public record Health(OffsetDateTime lastSuccess, OffsetDateTime lastFailure, boolean blocked,
            UUID activeFence, OffsetDateTime leaseUntil) { }
}
