package com.dwp.services.time.close;

import com.dwp.services.time.close.TimeCloseModels.CloseBlocker;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.ResultLine;
import com.dwp.services.time.close.TimeCloseModels.SourceEvidence;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Freezes the Time-owned ledger/timecard evidence used by a close attempt.
 * The repository rechecks the membership and per-worker ledger high-water under
 * locks before it is allowed to persist the immutable result.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcTimeCloseEvidenceSource implements TimeCloseEvidenceSource {

    private static final String SET_TENANT =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcTimeCloseEvidenceSource(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions.setReadOnly(true);
        transactions.setTimeout(30);
    }

    @Override
    public CloseEvidence freeze(
            ClosePeriod period, VerifiedCloseRequest authority, Instant asOf) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(asOf, "asOf");
        if (period.tenantId() != authority.tenantId()
                || !period.legalEntityPublicId().equals(authority.legalEntityPublicId())
                || !period.timeGroupPublicId().equals(authority.timeGroupPublicId())) {
            throw new IllegalArgumentException("close evidence authority is mismatched");
        }
        if (asOf.isBefore(period.periodStart().atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant())) {
            throw new IllegalArgumentException("asOf precedes the close period");
        }
        CloseEvidence evidence = transactions.execute(status -> {
            String tenant = Long.toString(period.tenantId());
            String established = jdbc.queryForObject(SET_TENANT, String.class, tenant);
            if (!tenant.equals(established)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return freezeInTenant(period, authority, asOf);
        });
        return Objects.requireNonNull(evidence, "close evidence transaction returned null");
    }

    private CloseEvidence freezeInTenant(
            ClosePeriod period, VerifiedCloseRequest authority, Instant asOf) {
        List<Member> members = jdbc.query("""
                SELECT worker_public_id, people_assignment_public_id,
                       membership_revision, source_digest
                  FROM tim_target_population_members
                 WHERE tenant_id=? AND population_public_id=?
                   AND population_revision=?
                   AND lifecycle_state='ACTIVE'
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                   AND updated_at <= ?
                 ORDER BY worker_public_id, people_assignment_public_id
                """, (row, ignored) -> new Member(
                        row.getObject("worker_public_id", UUID.class),
                        row.getObject("people_assignment_public_id", UUID.class),
                        row.getLong("membership_revision"),
                        row.getString("source_digest").trim()),
                period.tenantId(), authority.populationPublicId(),
                authority.populationRevision(), period.periodEnd(),
                period.periodStart(), Timestamp.from(asOf));

        List<CloseBlocker> blockers = new ArrayList<>();
        if (members.isEmpty()) {
            blockers.add(blocker("TIME_GROUP_EMPTY", period.timeGroupPublicId(),
                    period.publicId().toString()));
        }

        Map<UUID, Member> byWorker = new LinkedHashMap<>();
        for (Member member : members) {
            if (byWorker.putIfAbsent(member.workerId(), member) != null) {
                blockers.add(blocker("AMBIGUOUS_WORKER_ASSIGNMENT", member.workerId(),
                        member.assignmentId().toString()));
            }
        }

        List<Card> cardFacts = List.of();
        if (!byWorker.isEmpty()) {
            cardFacts = jdbc.query("""
                    SELECT worker_public_id, public_id, state, ledger_revision
                      FROM tme_timecards
                     WHERE tenant_id=? AND period_start=? AND period_end=?
                       AND state <> 'SUPERSEDED'
                     ORDER BY worker_public_id, public_id
                    """, (row, ignored) -> new Card(
                            row.getObject("worker_public_id", UUID.class),
                            row.getObject("public_id", UUID.class),
                            row.getString("state"), row.getLong("ledger_revision")),
                    period.tenantId(), period.periodStart(), period.periodEnd());
            cardFacts = cardFacts.stream()
                    .filter(card -> byWorker.containsKey(card.workerId()))
                    .toList();
        }
        Map<UUID, Card> cards = new LinkedHashMap<>();
        for (Card card : cardFacts) {
            cards.put(card.workerId(), card);
        }
        for (Member member : byWorker.values()) {
            Card card = cards.get(member.workerId());
            if (card == null) {
                blockers.add(blocker("TIMECARD_MISSING", member.workerId(),
                        period.publicId().toString()));
            } else if (!Set.of("APPROVED", "LOCKED", "CLOSED").contains(card.state())) {
                blockers.add(blocker("TIMECARD_NOT_APPROVED", card.timecardId(), card.state()));
            }
        }

        Map<UUID, Long> highWater = new LinkedHashMap<>();
        if (!byWorker.isEmpty()) {
            jdbc.query("""
                    SELECT worker_public_id, current_revision
                      FROM tme_time_ledger_revision_counters
                     WHERE tenant_id=?
                    """, row -> {
                        UUID worker = row.getObject("worker_public_id", UUID.class);
                        if (byWorker.containsKey(worker)) {
                            highWater.put(worker, row.getLong("current_revision"));
                        }
                    }, period.tenantId());
        }
        for (UUID worker : byWorker.keySet()) {
            if (!highWater.containsKey(worker)) {
                blockers.add(blocker("LEDGER_NOT_MATERIALIZED", worker,
                        period.publicId().toString()));
            }
        }
        for (Map.Entry<UUID, Card> card : cards.entrySet()) {
            Long currentRevision = highWater.get(card.getKey());
            if (currentRevision != null
                    && card.getValue().ledgerRevision() != currentRevision) {
                blockers.add(blocker("TIMECARD_LEDGER_STALE",
                        card.getValue().timecardId(),
                        card.getValue().ledgerRevision() + ":" + currentRevision));
            }
        }

        List<Entry> allEntries = byWorker.isEmpty() ? List.of() : jdbc.query("""
                SELECT public_id, worker_public_id, local_work_date, pay_code,
                       unit, quantity, source_type, source_public_id,
                       reverses_entry_public_id, ledger_revision, input_digest,
                       rule_digest, posted_at
                  FROM tme_time_ledger_entries
                 WHERE tenant_id=?
                   AND local_work_date BETWEEN ? AND ?
                 ORDER BY worker_public_id, pay_code, unit, ledger_revision, public_id
                """, (row, ignored) -> new Entry(
                        row.getObject("public_id", UUID.class),
                        row.getObject("worker_public_id", UUID.class),
                        row.getDate("local_work_date").toLocalDate(),
                        row.getString("pay_code"), row.getString("unit"),
                        row.getBigDecimal("quantity"), row.getString("source_type"),
                        row.getObject("source_public_id", UUID.class),
                        row.getObject("reverses_entry_public_id", UUID.class),
                        row.getLong("ledger_revision"), row.getString("input_digest").trim(),
                        row.getString("rule_digest").trim(),
                        row.getTimestamp("posted_at").toInstant()),
                period.tenantId(), period.periodStart(), period.periodEnd()).stream()
                .filter(entry -> byWorker.containsKey(entry.workerId()))
                .toList();
        LinkedHashMap<UUID, Entry> afterCutoff = new LinkedHashMap<>();
        for (Entry entry : allEntries) {
            if (entry.postedAt().isAfter(asOf)) {
                afterCutoff.putIfAbsent(entry.workerId(), entry);
            }
        }
        for (Map.Entry<UUID, Entry> stale : afterCutoff.entrySet()) {
            blockers.add(blocker("LEDGER_AFTER_AS_OF", stale.getKey(),
                    stale.getValue().publicId() + ":" + stale.getValue().postedAt()));
        }
        List<Entry> entries = allEntries.stream()
                .filter(entry -> !entry.postedAt().isAfter(asOf))
                .toList();

        Map<LineKey, List<SourceEvidence>> grouped = new LinkedHashMap<>();
        Set<String> rules = new LinkedHashSet<>();
        long minimumRevision = Long.MAX_VALUE;
        long maximumRevision = 0;
        for (Entry entry : entries) {
            Member member = byWorker.get(entry.workerId());
            String entryDigest = entryDigest(entry);
            SourceEvidence source = new SourceEvidence(
                    entry.publicId(), entry.postedAt(), entry.ledgerRevision(),
                    entry.quantity(), entry.unit(), entryDigest);
            grouped.computeIfAbsent(new LineKey(
                    entry.workerId(), member.assignmentId(), entry.payCode(), entry.unit()),
                    ignored -> new ArrayList<>()).add(source);
            rules.add(entry.ruleDigest());
            minimumRevision = Math.min(minimumRevision, entry.ledgerRevision());
            maximumRevision = Math.max(maximumRevision, entry.ledgerRevision());
        }
        if (entries.isEmpty()) {
            blockers.add(blocker("LEDGER_EMPTY", period.publicId(),
                    period.periodStart() + ":" + period.periodEnd()));
        }

        List<ResultLine> lines = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<SourceEvidence> sources = List.copyOf(entry.getValue());
                    BigDecimal quantity = sources.stream().map(SourceEvidence::quantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String digest = TimeCloseResultIntegrity.sourceEntryDigest(sources);
                    LineKey key = entry.getKey();
                    return new ResultLine(key.workerId(), key.assignmentId(), key.payCode(),
                            quantity, key.unit(), null, digest, sources);
                }).toList();
        String sourceDigest = TimeCloseResultIntegrity.sourceDigest(lines);
        String ruleDigest = TimeCloseDigests.sha256Utf8(
                TimeCloseDigests.canonicalJson(rules.stream().sorted().toList()));
        String membershipDigest = membershipDigest(members);
        String timecardDigest = timecardDigest(byWorker.keySet(), cardFacts);
        long from = minimumRevision == Long.MAX_VALUE ? 1 : minimumRevision;
        long to = maximumRevision == 0 ? 1 : maximumRevision;
        return new CloseEvidence(
                blockers, lines, authority.populationPublicId(),
                authority.populationRevision(), from, to, sourceDigest, ruleDigest,
                membershipDigest, timecardDigest, highWater);
    }

    static String membershipDigest(List<Member> members) {
        List<Map<String, Object>> facts = members.stream()
                .sorted(Comparator.comparing(Member::workerId)
                        .thenComparing(Member::assignmentId))
                .map(member -> Map.<String, Object>of(
                        "workerId", member.workerId().toString(),
                        "assignmentId", member.assignmentId().toString(),
                        "membershipRevision", member.membershipRevision(),
                        "sourceDigest", member.sourceDigest()))
                .toList();
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(facts));
    }

    static String timecardDigest(Set<UUID> workers, List<Card> cards) {
        List<Map<String, Object>> facts = workers.stream().sorted().map(worker -> {
            List<Map<String, Object>> workerCards = cards.stream()
                    .filter(card -> card.workerId().equals(worker))
                    .sorted(Comparator.comparing(Card::timecardId))
                    .map(card -> Map.<String, Object>of(
                            "timecardId", card.timecardId().toString(),
                            "state", card.state(),
                            "ledgerRevision", card.ledgerRevision()))
                    .toList();
            return Map.<String, Object>of(
                    "workerId", worker.toString(),
                    "cards", workerCards);
        }).toList();
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(facts));
    }

    static boolean hasOneEligibleCardPerWorker(
            Set<UUID> workers, List<Card> cards, Map<UUID, Long> highWater) {
        if (workers.isEmpty()) return false;
        return workers.stream().allMatch(worker -> {
            List<Card> matches = cards.stream()
                    .filter(card -> card.workerId().equals(worker))
                    .toList();
            return matches.size() == 1
                    && Set.of("APPROVED", "LOCKED", "CLOSED")
                            .contains(matches.getFirst().state())
                    && highWater.get(worker) != null
                    && matches.getFirst().ledgerRevision() == highWater.get(worker);
        });
    }

    private static CloseBlocker blocker(String code, UUID resourceId, String detail) {
        return new CloseBlocker(code, resourceId,
                TimeCloseDigests.sha256Utf8(detail));
    }

    private static String entryDigest(Entry entry) {
        LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
        fact.put("ledgerEntryId", entry.publicId().toString());
        fact.put("workerId", entry.workerId().toString());
        fact.put("workDate", entry.workDate().toString());
        fact.put("payCode", entry.payCode());
        fact.put("unit", entry.unit());
        fact.put("quantity", entry.quantity().stripTrailingZeros().toPlainString());
        fact.put("sourceType", entry.sourceType());
        fact.put("sourceId", entry.sourceId().toString());
        fact.put("reversesEntryId", entry.reversesId() == null
                ? null : entry.reversesId().toString());
        fact.put("ledgerRevision", entry.ledgerRevision());
        fact.put("inputDigest", entry.inputDigest());
        fact.put("ruleDigest", entry.ruleDigest());
        fact.put("postedAt", entry.postedAt().toString());
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(fact));
    }

    record Member(
            UUID workerId,
            UUID assignmentId,
            long membershipRevision,
            String sourceDigest) {}

    record Card(UUID workerId, UUID timecardId, String state, long ledgerRevision) {}

    private record Entry(
            UUID publicId,
            UUID workerId,
            java.time.LocalDate workDate,
            String payCode,
            String unit,
            BigDecimal quantity,
            String sourceType,
            UUID sourceId,
            UUID reversesId,
            long ledgerRevision,
            String inputDigest,
            String ruleDigest,
            Instant postedAt) {}

    private record LineKey(
            UUID workerId, UUID assignmentId, String payCode, String unit)
            implements Comparable<LineKey> {
        @Override
        public int compareTo(LineKey other) {
            int compared = workerId.compareTo(other.workerId);
            if (compared != 0) return compared;
            compared = assignmentId.compareTo(other.assignmentId);
            if (compared != 0) return compared;
            compared = payCode.compareTo(other.payCode);
            return compared != 0 ? compared : unit.compareTo(other.unit);
        }
    }
}
