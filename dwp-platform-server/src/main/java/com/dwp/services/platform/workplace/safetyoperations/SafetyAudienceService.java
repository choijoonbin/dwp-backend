package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyAudienceRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyAudienceService {
    private final SafetyAudienceRepository repository;
    private final Duration freshness;
    private final Clock clock;

    @Autowired
    public SafetyAudienceService(
            SafetyAudienceRepository repository,
            @Value("${dwp.workplace.safety.audience-freshness:PT5M}") Duration freshness) {
        this(repository, freshness, Clock.systemUTC());
    }

    SafetyAudienceService(SafetyAudienceRepository repository, Duration freshness, Clock clock) {
        this.repository = repository;
        if (freshness.isNegative() || freshness.isZero()) {
            throw new IllegalArgumentException("audience freshness must be positive");
        }
        this.freshness = freshness;
        this.clock = clock;
    }

    @Transactional
    public AudienceSnapshot snapshot(
            long tenantId, String ownerType, UUID ownerId, UUID siteId,
            List<UUID> floorIds, List<UUID> zoneIds, List<String> excludedSubjectKeys) {
        requireScope(tenantId, siteId, floorIds, zoneIds);
        OffsetDateTime now = now();
        Map<AudienceSourceKind, List<CandidateRow>> bySource = candidates(
                tenantId, siteId, floorIds, zoneIds, now);
        List<CandidateRow> all = bySource.values().stream().flatMap(List::stream).toList();
        Set<String> excluded = Set.copyOf(excludedSubjectKeys);
        Map<String, MutableMember> merged = new LinkedHashMap<>();
        for (CandidateRow candidate : all) {
            MutableMember member = merged.computeIfAbsent(candidate.subjectKey(), key ->
                    new MutableMember(key, candidate.userId(), candidate.maskedLabel()));
            member.sources.add(candidate.source());
            member.unknown |= candidate.unknown();
            if (member.userId == null && candidate.userId() != null) member.userId = candidate.userId();
        }

        List<AudienceMember> members = merged.values().stream().map(member -> {
            boolean manuallyExcluded = excluded.contains(member.key);
            boolean included = !manuallyExcluded && !member.unknown;
            String code = manuallyExcluded ? "ADMIN_EXCLUSION"
                    : member.unknown ? "IDENTITY_UNKNOWN" : null;
            return new AudienceMember(UUID.randomUUID(), member.key, member.userId,
                    member.label, member.sources.stream().sorted().toList(), included, code,
                    member.unknown);
        }).sorted(Comparator.comparing(AudienceMember::maskedLabel)
                .thenComparing(AudienceMember::subjectKeySha256)).toList();

        List<SourceSummary> summaries = new ArrayList<>();
        for (AudienceSourceKind kind : AudienceSourceKind.values()) {
            List<CandidateRow> rows = bySource.getOrDefault(kind, List.of());
            Set<String> keys = new LinkedHashSet<>();
            rows.forEach(row -> keys.add(row.subjectKey()));
            long unknown = rows.stream().filter(CandidateRow::unknown)
                    .map(CandidateRow::subjectKey).distinct().count();
            long excludedCount = keys.stream().filter(excluded::contains).count();
            long included = keys.stream().filter(key -> members.stream().anyMatch(
                    member -> member.subjectKeySha256().equals(key) && member.included())).count();
            OffsetDateTime sourceAt = rows.stream().map(CandidateRow::sourceAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            OffsetDateTime receivedAt = rows.stream().map(CandidateRow::receivedAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            AvailabilityState availability = rows.isEmpty()
                    && kind == AudienceSourceKind.ACTUAL_PRESENCE
                    ? AvailabilityState.UNAVAILABLE : unknown > 0
                    ? AvailabilityState.PARTIAL : AvailabilityState.AVAILABLE;
            FreshnessState state = sourceAt == null
                    ? (kind == AudienceSourceKind.ACTUAL_PRESENCE
                    ? FreshnessState.UNKNOWN : FreshnessState.FRESH)
                    : sourceAt.isBefore(now.minus(freshness))
                    ? FreshnessState.STALE : FreshnessState.FRESH;
            double coverage = keys.isEmpty() ? (availability == AvailabilityState.UNAVAILABLE ? 0 : 100)
                    : Math.max(0, (keys.size() - unknown) * 100.0 / keys.size());
            summaries.add(new SourceSummary(kind, keys.size(), Math.toIntExact(included),
                    Math.toIntExact(excludedCount), Math.toIntExact(unknown), coverage,
                    state, availability, sourceAt, receivedAt));
        }

        int excludedCount = Math.toIntExact(members.stream().filter(
                member -> "ADMIN_EXCLUSION".equals(member.exclusionCode())).count());
        int unknownCount = Math.toIntExact(members.stream().filter(AudienceMember::unknownIdentity).count());
        int target = Math.toIntExact(members.stream().filter(AudienceMember::included).count());
        UUID snapshotId = UUID.randomUUID();
        repository.insertSnapshot(snapshotId, tenantId, ownerType, ownerId, all.size(),
                members.size(), excludedCount, unknownCount, target, now);
        summaries.forEach(summary -> repository.insertSource(tenantId, snapshotId, summary));
        members.forEach(member -> repository.insertMember(tenantId, snapshotId, member));
        return new AudienceSnapshot(snapshotId, all.size(), members.size(), excludedCount,
                unknownCount, target, List.copyOf(summaries), members, now);
    }

    @Transactional(readOnly = true)
    public AudienceSnapshot get(long tenantId, UUID snapshotId, boolean includeExcluded) {
        SnapshotRow row = repository.snapshotRow(tenantId, snapshotId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND,
                        "The safety audience snapshot was not found."));
        return new AudienceSnapshot(row.id(), row.total(), row.deduplicated(), row.excluded(),
                row.unknown(), row.target(), repository.sources(tenantId, snapshotId),
                repository.members(tenantId, snapshotId, includeExcluded), row.asOf());
    }

    @Transactional
    public boolean observePresence(PresenceObservation observation) {
        if (observation.tenantId() <= 0 || observation.sourceAt().isAfter(observation.receivedAt())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The presence observation has an invalid tenant or clock ordering.");
        }
        return repository.insertPresence(observation);
    }

    private Map<AudienceSourceKind, List<CandidateRow>> candidates(
            long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds,
            OffsetDateTime now) {
        Map<AudienceSourceKind, List<CandidateRow>> result =
                new EnumMap<>(AudienceSourceKind.class);
        List<CandidateRow> reservations = new ArrayList<>(repository.workplaceReservations(
                tenantId, siteId, floorIds, zoneIds, now));
        reservations.addAll(repository.calendarReservations(
                tenantId, siteId, floorIds, zoneIds, now));
        result.put(AudienceSourceKind.RESERVATION, reservations);
        result.put(AudienceSourceKind.ACTUAL_PRESENCE,
                repository.actualPresence(tenantId, siteId, floorIds, zoneIds));
        result.put(AudienceSourceKind.VISITOR,
                repository.visitors(tenantId, siteId, zoneIds, now, false));
        result.put(AudienceSourceKind.SCHEDULED_VISITOR,
                repository.visitors(tenantId, siteId, zoneIds, now, true));
        return result;
    }

    private void requireScope(
            long tenantId, UUID siteId, List<UUID> floorIds, List<UUID> zoneIds) {
        if (tenantId <= 0 || siteId == null || floorIds == null || zoneIds == null
                || !repository.scopeExists(tenantId, siteId,
                floorIds.stream().distinct().toList(), zoneIds.stream().distinct().toList())) {
            throw new BaseException(ErrorCode.NOT_FOUND,
                    "The safety scope is not contained in this tenant and site.");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static final class MutableMember {
        private final String key;
        private Long userId;
        private final String label;
        private final Set<AudienceSourceKind> sources = new LinkedHashSet<>();
        private boolean unknown;

        private MutableMember(String key, Long userId, String label) {
            this.key = key;
            this.userId = userId;
            this.label = label;
        }
    }
}
