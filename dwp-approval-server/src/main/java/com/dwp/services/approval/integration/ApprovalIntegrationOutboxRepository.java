package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.time.Instant;
import java.util.function.Consumer;

@Repository
public class ApprovalIntegrationOutboxRepository {

    private final JdbcTemplate jdbc;

    public ApprovalIntegrationOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<PendingEvent> claim(int batchSize, String workerId) {
        worker(workerId);
        if (batchSize < 1 || batchSize > 200) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        // Request rows are acquired before outbox leases, including initially absent retention heads.
        var requests = jdbc.query("""
                SELECT request.tenant_id,request.request_id FROM apr_requests request
                  JOIN apr_tenants tenant ON tenant.tenant_id=request.tenant_id AND tenant.lifecycle_state='ACTIVE'
                 WHERE request.deleted_at IS NULL AND EXISTS(
                    SELECT 1 FROM apr_integration_outbox event WHERE event.tenant_id=request.tenant_id
                     AND event.request_id=request.request_id AND event.available_at<=clock_timestamp()
                     AND (event.status IN ('PENDING','FAILED') OR (event.status='SENDING' AND event.locked_until<=clock_timestamp())))
                """ + " AND " + ApprovalRetentionLiveGuard.LIVE + " ORDER BY request.tenant_id,request.request_id FOR UPDATE OF request SKIP LOCKED LIMIT ?",
                (row, index) -> new Request(row.getLong("tenant_id"),row.getObject("request_id",UUID.class)), batchSize);
        var events = new ArrayList<PendingEvent>();
        for (var request : requests) {
            if (!live(request.tenant(), request.id())) continue;
            events.addAll(claimRequest(request, batchSize-events.size(), workerId));
            if (events.size() == batchSize) break;
        }
        return List.copyOf(events);
    }

    private record Request(long tenant,UUID id) { }
    private List<PendingEvent> claimRequest(Request request,int limit,String workerId) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT outbox_id
                      FROM apr_integration_outbox
                     WHERE tenant_id=? AND request_id=? AND available_at <= clock_timestamp()
                       AND (status IN ('PENDING', 'FAILED')
                            OR (status = 'SENDING' AND locked_until <= clock_timestamp()))
                     ORDER BY created_at, outbox_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE apr_integration_outbox event
                       SET status = 'SENDING', attempt_count = attempt_count + 1,
                           locked_by = ?, locked_until = clock_timestamp() + INTERVAL '30 seconds',
                           updated_at = clock_timestamp()
                      FROM candidates
                     WHERE event.outbox_id = candidates.outbox_id
                    RETURNING event.outbox_id, event.event_id, event.tenant_id,
                              event.request_id, event.event_type, event.payload::text,
                              event.attempt_count,event.payload_sha256,event.locked_until
                )
                SELECT * FROM claimed ORDER BY outbox_id
                """, (result, ignored) -> new PendingEvent(
                result.getObject("outbox_id", UUID.class),
                result.getObject("event_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("request_id", UUID.class),
                result.getString("event_type"),
                result.getString("payload"),
                result.getInt("attempt_count"),result.getString("payload_sha256").strip(),result.getTimestamp("locked_until").toInstant()), request.tenant(),request.id(),limit,workerId);
    }

    /** Legacy unbound acknowledgements cannot identify a reclaimed native lease. */
    public void markPublished(UUID outboxId, String workerId) {
        throw unavailable();
    }

    @Transactional
    public boolean publishCurrent(PendingEvent event,String workerId,Consumer<PendingEvent> network) {
        worker(workerId);
        if (!sealed(event) || network == null) throw unavailable();
        if (!live(event.tenantId(),event.requestId()) || !current(event,workerId)) return false;
        network.accept(event);
        if (!live(event.tenantId(),event.requestId()) || !current(event,workerId)) return false;
        return jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL, last_error = NULL,
                       recovery_auditor_assignment_state = CASE
                           WHEN recovery_auditor_assignment_state IN (
                               'PENDING', 'ASSIGNING', 'RETRY', 'EXHAUSTED')
                               THEN 'NOT_REQUIRED'
                           ELSE recovery_auditor_assignment_state
                       END,
                       recovery_auditor_assignment_locked_by = NULL,
                       recovery_auditor_assignment_locked_until = NULL,
                       recovery_auditor_assignment_exhausted_at = NULL,
                       recovery_auditor_assignment_next_probe_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ? AND tenant_id=? AND request_id=? AND status = 'SENDING' AND locked_by = ?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, event.outboxId(),event.tenantId(),event.requestId(),workerId,event.attemptCount(),java.sql.Timestamp.from(event.leaseUntil())) == 1;
    }

    public void markFailed(
            UUID outboxId,
            String workerId,
            int attemptCount,
            int maximumAttempts,
            String error) {
        throw unavailable();
    }

    @Transactional
    public boolean markFailed(PendingEvent event,String workerId,int maximumAttempts,String error) {
        worker(workerId);
        if (!sealed(event) || maximumAttempts < 1 || maximumAttempts > 100) throw unavailable();
        if (!live(event.tenantId(),event.requestId()) || !current(event,workerId)) return false;
        int attemptCount=event.attemptCount();
        long delaySeconds = Math.min(900L, 1L << Math.min(9, Math.max(1, attemptCount)));
        return jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = CASE WHEN ? >= ? THEN 'DEAD' ELSE 'FAILED' END,
                       available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       locked_by = NULL, locked_until = NULL, last_error = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ? AND tenant_id=? AND request_id=? AND status = 'SENDING' AND locked_by = ?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, attemptCount, maximumAttempts, delaySeconds,
                truncate(error, 1000),event.outboxId(),event.tenantId(),event.requestId(),workerId,attemptCount,java.sql.Timestamp.from(event.leaseUntil())) == 1;
    }

    private boolean live(long tenant,UUID request) {
        try {new ApprovalRetentionLiveGuard(new NamedParameterJdbcTemplate(jdbc)).writeRequest(tenant,request);}
        catch (BaseException denied) {if (denied.getErrorCode()==ErrorCode.NOT_FOUND) return false;throw denied;}
        return !jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=? AND lifecycle_state='ACTIVE' FOR SHARE",tenant).isEmpty();
    }
    private boolean current(PendingEvent event,String worker) {
        var rows=jdbc.query("""
                SELECT * FROM apr_integration_outbox WHERE outbox_id=? AND tenant_id=? AND request_id=?
                 AND status='SENDING' AND locked_by=? AND attempt_count=? AND locked_until>clock_timestamp() FOR UPDATE
                """,(row,index)->new PendingEvent(row.getObject("outbox_id",UUID.class),row.getObject("event_id",UUID.class),row.getLong("tenant_id"),row.getObject("request_id",UUID.class),row.getString("event_type"),row.getString("payload"),row.getInt("attempt_count"),row.getString("payload_sha256").strip(),row.getTimestamp("locked_until").toInstant()),
                event.outboxId(),event.tenantId(),event.requestId(),worker,event.attemptCount());
        return rows.size()==1 && event.equals(rows.getFirst());
    }
    private static boolean sealed(PendingEvent event) {return event!=null && event.outboxId()!=null && event.eventId()!=null && event.requestId()!=null && event.attemptCount()>0 && event.payloadSha256()!=null && event.payloadSha256().matches("[a-f0-9]{64}") && event.leaseUntil()!=null;}
    private static void worker(String worker) {if (worker==null || !worker.matches("[A-Za-z0-9._:-]{1,160}")) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);}
    private static BaseException unavailable() {return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"A current native event lease and retention-fenced transaction are required.");}

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) return "Unknown approval event delivery failure";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record PendingEvent(
            UUID outboxId,
            UUID eventId,
            long tenantId,
            UUID requestId,
            String eventType,
            String payload,
            int attemptCount,String payloadSha256,Instant leaseUntil) {
        public PendingEvent(UUID outboxId,UUID eventId,long tenantId,UUID requestId,String eventType,String payload,int attemptCount) {
            this(outboxId,eventId,tenantId,requestId,eventType,payload,attemptCount,null,null);
        }
    }
}
