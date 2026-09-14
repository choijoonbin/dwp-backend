package com.dwp.services.notification.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence primitive only: the consumer must separately verify current signed SLA authority. */
@Component
public final class ApprovalSlaDeliveryJournal {
    private final JdbcTemplate jdbc;

    public ApprovalSlaDeliveryJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean finished(ApprovalSlaNotificationPlan plan) {
        requireWorker(plan.actor().tenantId());
        var rows = jdbc.query("""
                SELECT *,clock_timestamp() AS db_now FROM ntf_approval_sla_deliveries
                 WHERE tenant_id=? AND event_id=? FOR UPDATE
                """, (rs, index) -> row(rs), plan.actor().tenantId(), plan.eventId());
        if (rows.isEmpty()) return false;
        requireIdentity(rows.getFirst(), plan);
        return rows.getFirst().finishedAt() != null;
    }

    Lease claim(ApprovalSlaNotificationPlan plan, UUID owner, Duration duration) {
        requireWorker(plan.actor().tenantId());
        if (owner == null || duration == null || duration.isNegative() || duration.isZero()
                || duration.compareTo(Duration.ofSeconds(30)) > 0) throw invalid("Invalid SLA lease.");
        for (String hash : List.of(plan.originalEnvelopeSha256(), plan.envelopeSha256(),
                plan.recipientSnapshotSha256(), plan.sourcePinsSha256())) requireHash(hash);
        if (plan.recipients().isEmpty() || plan.recipients().size() > 1000)
            throw invalid("Incomplete SLA audience.");
        long tenant = plan.actor().tenantId();
        jdbc.update("""
                INSERT INTO ntf_approval_sla_deliveries
                    (tenant_id,event_id,request_id,event_type,original_envelope_sha256,
                     canonical_envelope_sha256,recipient_snapshot_sha256,source_pins_sha256,
                     recipient_count,chunk_count)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,event_id) DO NOTHING
                """, tenant, plan.eventId(), plan.requestId(), plan.eventType(),
                plan.originalEnvelopeSha256(), plan.envelopeSha256(), plan.recipientSnapshotSha256(),
                plan.sourcePinsSha256(), plan.recipients().size(), plan.chunkCount());
        Row row = locked(tenant, plan.eventId());
        requireIdentity(row, plan);
        if (row.finishedAt() != null) return new Lease(plan, owner, row.epoch(), row.now(), true);
        if (row.owner() != null && !owner.equals(row.owner()) && row.until().isAfter(row.now()))
            throw new LeaseUnavailableException();
        long epoch = Math.addExact(row.epoch(), 1);
        Instant until = row.now().plus(duration);
        int changed = jdbc.update("""
                UPDATE ntf_approval_sla_deliveries SET lease_owner=?,lease_epoch=?,lease_until=?
                 WHERE tenant_id=? AND event_id=? AND lease_epoch=? AND finished_at IS NULL
                """, owner, epoch, java.sql.Timestamp.from(until), tenant, plan.eventId(), row.epoch());
        if (changed != 1) throw new LeaseUnavailableException();
        return new Lease(plan, owner, epoch, until, false);
    }

    boolean hasChunk(Lease lease, int index) {
        requireLease(lease);
        lease.plan().chunk(index);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ntf_approval_sla_delivery_chunks
                 WHERE tenant_id=? AND event_id=? AND chunk_index=?)
                """, Boolean.class, lease.plan().actor().tenantId(), lease.plan().eventId(), index));
    }

    boolean completeChunk(Lease lease, int index, List<Outcome> outcomes, String authoritySha256) {
        requireLease(lease);
        requireHash(authoritySha256);
        List<ApprovalSlaNotificationPlan.Recipient> seats = lease.plan().chunk(index);
        if (outcomes == null || outcomes.size() != seats.size()) throw invalid("SLA chunk cannot truncate seats.");
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (int position = 0; position < seats.size(); position++) {
            var seat = seats.get(position);
            Outcome outcome = outcomes.get(position);
            if (outcome == null || !seat.equals(outcome.recipient())
                    || (outcome.authorityEligible() && outcome.intentId() == null)
                    || (!outcome.authorityEligible() && (outcome.intentId() != null || outcome.notificationId() != null)))
                throw invalid("SLA outcome must match the exact frozen seat.");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("userId", seat.userId());
            value.put("personPublicId", seat.personPublicId().toString());
            value.put("taskId", seat.taskId().toString());
            value.put("taskVersion", seat.taskVersion());
            value.put("childEventId", lease.plan().childEventId(seat).toString());
            value.put("authorityEligible", outcome.authorityEligible());
            value.put("intentId", string(outcome.intentId()));
            value.put("notificationId", string(outcome.notificationId()));
            canonical.add(value);
        }
        String outcomeHash = ApprovalSlaNotificationContract.sha256(ApprovalSlaNotificationContract.canonical(canonical));
        var existing = jdbc.queryForList("""
                SELECT outcome_sha256 FROM ntf_approval_sla_delivery_chunks
                 WHERE tenant_id=? AND event_id=? AND chunk_index=?
                """, String.class, lease.plan().actor().tenantId(), lease.plan().eventId(), index);
        if (!existing.isEmpty()) {
            if (!outcomeHash.equals(existing.getFirst())) throw invalid("Completed SLA chunk changed.");
            return false;
        }
        long tenant = lease.plan().actor().tenantId();
        jdbc.update("""
                INSERT INTO ntf_approval_sla_delivery_chunks
                    (tenant_id,event_id,chunk_index,recipient_count,outcome_sha256,authority_sha256,lease_epoch)
                VALUES (?,?,?,?,?,?,?)
                """, tenant, lease.plan().eventId(), index, seats.size(), outcomeHash, authoritySha256, lease.epoch());
        for (Outcome outcome : outcomes) {
            var seat = outcome.recipient();
            jdbc.update("""
                    INSERT INTO ntf_approval_sla_delivery_recipients
                        (tenant_id,event_id,chunk_index,user_id,person_public_id,task_id,task_version,
                         child_event_id,authority_eligible,intent_id,notification_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, tenant, lease.plan().eventId(), index, seat.userId(), seat.personPublicId(),
                    seat.taskId(), seat.taskVersion(), lease.plan().childEventId(seat),
                    outcome.authorityEligible(), outcome.intentId(), outcome.notificationId());
        }
        requireLease(lease);
        return true;
    }

    void finish(Lease lease) {
        requireLease(lease);
        long tenant = lease.plan().actor().tenantId();
        List<Integer> indices = jdbc.queryForList("""
                SELECT chunk_index FROM ntf_approval_sla_delivery_chunks
                 WHERE tenant_id=? AND event_id=? ORDER BY chunk_index
                """, Integer.class, tenant, lease.plan().eventId());
        if (!indices.equals(java.util.stream.IntStream.range(0, lease.plan().chunkCount()).boxed().toList()))
            throw invalid("SLA delivery has unfinished chunks.");
        Long recipients = jdbc.queryForObject("""
                SELECT count(*) FROM ntf_approval_sla_delivery_recipients WHERE tenant_id=? AND event_id=?
                """, Long.class, tenant, lease.plan().eventId());
        if (recipients == null || recipients != lease.plan().recipients().size())
            throw invalid("SLA delivery has missing seats.");
        if (jdbc.update("""
                UPDATE ntf_approval_sla_deliveries SET finished_at=clock_timestamp()
                 WHERE tenant_id=? AND event_id=? AND lease_owner=? AND lease_epoch=?
                   AND lease_until>clock_timestamp() AND finished_at IS NULL
                """, tenant, lease.plan().eventId(), lease.owner(), lease.epoch()) != 1)
            throw new LeaseUnavailableException();
    }

    private void requireLease(Lease lease) {
        if (lease == null || lease.finished()) throw new LeaseUnavailableException();
        requireWorker(lease.plan().actor().tenantId());
        Row row = locked(lease.plan().actor().tenantId(), lease.plan().eventId());
        requireIdentity(row, lease.plan());
        if (row.finishedAt() != null || row.epoch() != lease.epoch() || !lease.owner().equals(row.owner())
                || row.until() == null || !row.until().isAfter(row.now())) throw new LeaseUnavailableException();
    }

    private Row locked(long tenant, UUID event) {
        return jdbc.queryForObject("""
                SELECT *,clock_timestamp() AS db_now FROM ntf_approval_sla_deliveries
                 WHERE tenant_id=? AND event_id=? FOR UPDATE
                """, (rs, index) -> row(rs), tenant, event);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("request_id", UUID.class), rs.getString("event_type"),
                rs.getString("original_envelope_sha256"), rs.getString("canonical_envelope_sha256"),
                rs.getString("recipient_snapshot_sha256"), rs.getString("source_pins_sha256"),
                rs.getInt("recipient_count"), rs.getInt("chunk_count"), rs.getLong("lease_epoch"),
                rs.getObject("lease_owner", UUID.class), instant(rs, "lease_until"),
                instant(rs, "finished_at"), instant(rs, "db_now"));
    }

    private static void requireIdentity(Row row, ApprovalSlaNotificationPlan plan) {
        if (row == null || !row.request().equals(plan.requestId()) || !row.type().equals(plan.eventType())
                || !row.originalHash().equals(plan.originalEnvelopeSha256()) || !row.canonicalHash().equals(plan.envelopeSha256())
                || !row.audienceHash().equals(plan.recipientSnapshotSha256()) || !row.pinsHash().equals(plan.sourcePinsSha256())
                || row.recipients() != plan.recipients().size() || row.chunks() != plan.chunkCount())
            throw invalid("Original SLA delivery bindings changed.");
    }

    private void requireWorker(long tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || tenant <= 0)
            throw new IllegalStateException("SLA journal requires an existing worker transaction.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ntf_is_worker() AND ntf_current_tenant_id()=?", Boolean.class, tenant)))
            throw new IllegalStateException("SLA journal worker scope does not match its tenant.");
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw invalid("Invalid SLA journal digest.");
    }
    private static String string(UUID id) { return id == null ? null : id.toString(); }
    private static Instant instant(ResultSet rs, String key) throws SQLException {
        var value = rs.getTimestamp(key); return value == null ? null : value.toInstant();
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    record Lease(ApprovalSlaNotificationPlan plan, UUID owner, long epoch, Instant validUntil, boolean finished) { }
    record Outcome(ApprovalSlaNotificationPlan.Recipient recipient, boolean authorityEligible,
                   UUID intentId, UUID notificationId) { }
    private record Row(UUID request, String type, String originalHash, String canonicalHash,
                       String audienceHash, String pinsHash, int recipients, int chunks, long epoch,
                       UUID owner, Instant until, Instant finishedAt, Instant now) { }
    static final class LeaseUnavailableException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        LeaseUnavailableException() { super("Approval SLA delivery lease is unavailable or stale."); }
    }
}
