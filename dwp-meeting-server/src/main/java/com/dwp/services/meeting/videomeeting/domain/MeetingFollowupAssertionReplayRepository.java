package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class MeetingFollowupAssertionReplayRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public MeetingFollowupAssertionReplayRepository(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    MeetingFollowupAssertionReplayRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(MeetingFollowupAssertionVerifier.VerifiedAssertion assertion) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            jdbc.update("DELETE FROM vm_meeting_followup_assertion_replay WHERE expires_at <= ?", now);
            int inserted = jdbc.update("""
                    INSERT INTO vm_meeting_followup_assertion_replay (
                        jti, key_id, tenant_id, actor_user_id, meeting_id, report_id,
                        candidate_id, action, expires_at, consumed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, assertion.jti(), assertion.keyId(), assertion.tenantId(),
                    assertion.actorUserId(), assertion.meetingId(), assertion.reportId(),
                    assertion.candidateId(), assertion.action(),
                    OffsetDateTime.ofInstant(assertion.expiresAt(), ZoneOffset.UTC), now);
            if (inserted != 1) throw denied();
        } catch (DuplicateKeyException exception) {
            throw denied();
        }
    }

    private BaseException denied() {
        return new BaseException(
                ErrorCode.UNAUTHORIZED,
                "The Platform Work source assertion has already been consumed.");
    }
}
