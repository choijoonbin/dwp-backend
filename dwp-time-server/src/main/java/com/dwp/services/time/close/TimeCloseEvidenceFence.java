package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Locks and revalidates every mutable source fence before close persistence starts. */
final class TimeCloseEvidenceFence {

    private final JdbcTemplate jdbc;

    TimeCloseEvidenceFence(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    void lockAndVerify(
            CloseCommand command,
            ClosePeriod period,
            CloseEvidence evidence,
            Instant processingTime) {
        VerifiedCloseRequest authority = command.authority();
        List<PopulationState> populations = jdbc.query("""
                SELECT population_public_id, projection_revision, lifecycle_state,
                       effective_from, effective_to, source_digest
                  FROM tim_target_population_projections
                 WHERE tenant_id=? AND population_public_id=?
                 FOR UPDATE
                """, (row, ignored) -> new PopulationState(
                        row.getObject("population_public_id", UUID.class),
                        row.getLong("projection_revision"),
                        row.getString("lifecycle_state"),
                        row.getDate("effective_from").toLocalDate(),
                        row.getDate("effective_to") == null
                                ? null : row.getDate("effective_to").toLocalDate(),
                        row.getString("source_digest").trim()),
                authority.tenantId(), authority.populationPublicId());

        List<GrantState> grants = jdbc.query("""
                SELECT actor_id, gateway_scope_key, population_public_id,
                       population_revision, grant_revision, lifecycle_state,
                       valid_from, valid_to, source_digest
                  FROM tim_target_population_actor_grants
                 WHERE tenant_id=? AND actor_id=? AND gateway_scope_key=?
                 FOR UPDATE
                """, (row, ignored) -> new GrantState(
                        row.getLong("actor_id"), row.getString("gateway_scope_key"),
                        row.getObject("population_public_id", UUID.class),
                        row.getLong("population_revision"),
                        row.getLong("grant_revision"), row.getString("lifecycle_state"),
                        row.getTimestamp("valid_from").toInstant(),
                        row.getTimestamp("valid_to") == null
                                ? null : row.getTimestamp("valid_to").toInstant(),
                        row.getString("source_digest").trim()),
                authority.tenantId(), authority.actorId(), authority.gatewayScopeKey());

        List<MemberState> allMembers = jdbc.query("""
                SELECT worker_public_id, people_assignment_public_id,
                       population_revision, membership_revision, lifecycle_state,
                       effective_from, effective_to, source_digest, updated_at
                  FROM tim_target_population_members
                 WHERE tenant_id=? AND population_public_id=?
                 ORDER BY worker_public_id, people_assignment_public_id
                 FOR UPDATE
                """, (row, ignored) -> new MemberState(
                        row.getObject("worker_public_id", UUID.class),
                        row.getObject("people_assignment_public_id", UUID.class),
                        row.getLong("population_revision"),
                        row.getLong("membership_revision"),
                        row.getString("lifecycle_state"),
                        row.getDate("effective_from").toLocalDate(),
                        row.getDate("effective_to") == null
                                ? null : row.getDate("effective_to").toLocalDate(),
                        row.getString("source_digest").trim(),
                        row.getTimestamp("updated_at").toInstant()),
                authority.tenantId(), authority.populationPublicId());

        List<JdbcTimeCloseEvidenceSource.Member> currentMembers = allMembers.stream()
                .filter(member -> member.currentAt(
                        authority.populationRevision(), period, command.asOf()))
                .map(MemberState::digestFact)
                .toList();

        List<JdbcTimeCloseEvidenceSource.Card> currentCards = jdbc.query("""
                SELECT c.worker_public_id, c.public_id, c.state, c.ledger_revision
                  FROM tme_timecards c
                 WHERE c.tenant_id=? AND c.period_start=? AND c.period_end=?
                   AND c.state <> 'SUPERSEDED'
                   AND EXISTS (
                       SELECT 1
                         FROM tim_target_population_members m
                        WHERE m.tenant_id=c.tenant_id
                          AND m.worker_public_id=c.worker_public_id
                          AND m.population_public_id=?
                          AND m.population_revision=?
                          AND m.lifecycle_state='ACTIVE'
                          AND m.effective_from <= ?
                          AND (m.effective_to IS NULL OR m.effective_to > ?)
                          AND m.updated_at <= ?)
                 ORDER BY c.worker_public_id, c.public_id
                 FOR UPDATE OF c
                """, (row, ignored) -> new JdbcTimeCloseEvidenceSource.Card(
                        row.getObject("worker_public_id", UUID.class),
                        row.getObject("public_id", UUID.class),
                        row.getString("state"), row.getLong("ledger_revision")),
                authority.tenantId(), period.periodStart(), period.periodEnd(),
                authority.populationPublicId(), authority.populationRevision(),
                period.periodEnd(), period.periodStart(), Timestamp.from(command.asOf()));

        List<CounterState> counters = jdbc.query("""
                SELECT c.worker_public_id, c.current_revision
                  FROM tme_time_ledger_revision_counters c
                  JOIN tim_target_population_members m
                    ON m.tenant_id=c.tenant_id
                   AND m.worker_public_id=c.worker_public_id
                 WHERE m.tenant_id=? AND m.population_public_id=?
                   AND m.population_revision=?
                   AND m.lifecycle_state='ACTIVE'
                   AND m.effective_from <= ?
                   AND (m.effective_to IS NULL OR m.effective_to > ?)
                   AND m.updated_at <= ?
                 ORDER BY c.worker_public_id, m.people_assignment_public_id
                 FOR UPDATE OF c
                """, (row, ignored) -> new CounterState(
                        row.getObject("worker_public_id", UUID.class),
                        row.getLong("current_revision")),
                authority.tenantId(), authority.populationPublicId(),
                authority.populationRevision(), period.periodEnd(), period.periodStart(),
                Timestamp.from(command.asOf()));
        LinkedHashMap<UUID, Long> highWater = new LinkedHashMap<>();
        for (CounterState counter : counters) {
            Long existing = highWater.putIfAbsent(
                    counter.workerId(), counter.currentRevision());
            if (existing != null && existing != counter.currentRevision()) {
                throw stale();
            }
        }

        PopulationState population = populations.size() == 1
                ? populations.getFirst() : null;
        GrantState grant = grants.size() == 1 ? grants.getFirst() : null;
        if (!isCurrent(authority, evidence, population, grant,
                currentMembers, currentCards, highWater, processingTime)
                || command.kind() == TimeCloseModels.CommandKind.CLOSE
                && !JdbcTimeCloseEvidenceSource.hasOneEligibleCardPerWorker(
                        workerIds(currentMembers), currentCards, highWater)) {
            throw stale();
        }
    }

    static boolean isCurrent(
            VerifiedCloseRequest authority,
            CloseEvidence evidence,
            PopulationState population,
            GrantState grant,
            List<JdbcTimeCloseEvidenceSource.Member> currentMembers,
            List<JdbcTimeCloseEvidenceSource.Card> currentCards,
            Map<UUID, Long> currentHighWater,
            Instant processingTime) {
        if (authority == null || evidence == null || population == null || grant == null
                || currentMembers == null || currentCards == null || currentHighWater == null
                || processingTime == null) {
            return false;
        }
        LocalDate processingDate = processingTime.atZone(ZoneOffset.UTC).toLocalDate();
        String scopeDigest = TimeCloseDigests.sha256(
                "TimeClosePopulationScope.v1", authority.tenantId(),
                population.publicId(), population.revision(), grant.grantRevision(),
                population.sourceDigest(), grant.sourceDigest(),
                authority.legalEntityPublicId(), authority.timeGroupPublicId());
        return population.publicId().equals(authority.populationPublicId())
                && population.revision() == authority.populationRevision()
                && "ACTIVE".equals(population.lifecycleState())
                && !population.effectiveFrom().isAfter(processingDate)
                && (population.effectiveTo() == null
                    || population.effectiveTo().isAfter(processingDate))
                && grant.actorId() == authority.actorId()
                && grant.gatewayScopeKey().equals(authority.gatewayScopeKey())
                && grant.populationPublicId().equals(authority.populationPublicId())
                && grant.populationRevision() == authority.populationRevision()
                && grant.grantRevision() == authority.grantRevision()
                && "ACTIVE".equals(grant.lifecycleState())
                && !grant.validFrom().isAfter(processingTime)
                && (grant.validTo() == null || grant.validTo().isAfter(processingTime))
                && authority.revalidateAt().isAfter(processingTime)
                && scopeDigest.equals(authority.populationScopeDigest())
                && evidence.populationPublicId().equals(authority.populationPublicId())
                && evidence.populationRevision() == authority.populationRevision()
                && evidence.membershipDigest().equals(
                        JdbcTimeCloseEvidenceSource.membershipDigest(currentMembers))
                && evidence.timecardDigest().equals(
                        JdbcTimeCloseEvidenceSource.timecardDigest(
                                workerIds(currentMembers), currentCards))
                && evidence.workerLedgerHighWater().equals(currentHighWater);
    }

    private static Set<UUID> workerIds(
            List<JdbcTimeCloseEvidenceSource.Member> members) {
        LinkedHashSet<UUID> workers = new LinkedHashSet<>();
        for (JdbcTimeCloseEvidenceSource.Member member : members) {
            workers.add(member.workerId());
        }
        return workers;
    }

    private static BaseException stale() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The frozen close evidence is no longer current.");
    }

    record PopulationState(
            UUID publicId,
            long revision,
            String lifecycleState,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceDigest) {}

    record GrantState(
            long actorId,
            String gatewayScopeKey,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String lifecycleState,
            Instant validFrom,
            Instant validTo,
            String sourceDigest) {}

    private record CounterState(UUID workerId, long currentRevision) {}

    private record MemberState(
            UUID workerId,
            UUID assignmentId,
            long populationRevision,
            long membershipRevision,
            String lifecycleState,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceDigest,
            Instant updatedAt) {

        boolean currentAt(long expectedRevision, ClosePeriod period, Instant asOf) {
            return populationRevision == expectedRevision
                    && "ACTIVE".equals(lifecycleState)
                    && !effectiveFrom.isAfter(period.periodEnd())
                    && (effectiveTo == null || effectiveTo.isAfter(period.periodStart()))
                    && !updatedAt.isAfter(asOf);
        }

        JdbcTimeCloseEvidenceSource.Member digestFact() {
            return new JdbcTimeCloseEvidenceSource.Member(
                    workerId, assignmentId, membershipRevision, sourceDigest);
        }
    }
}
