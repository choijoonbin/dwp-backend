package com.dwp.services.people.hris.home;

import com.dwp.contracts.hris.xcon.v1.Xcon013PeopleHomeContributionChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed HRM Home materializer and XCON-013 transactional-outbox producer. */
@Service
@ConditionalOnProperty(prefix = ConfiguredPeopleHomeAuthority.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class PeopleHomeContributionService {

    static final String EVENT_TYPE = "dwp.hris.home.contribution-changed.v1";
    static final String EVENT_SOURCE = "/dwp-people-server/hris/home";
    private static final Duration FRESH_FOR = Duration.ofMinutes(5);
    private static final Duration STALE_FOR = Duration.ofMinutes(10);
    private static final List<String> FIELD_ORDER = List.of(
            "employmentStatus", "organizationLabel", "pendingActionCount");

    private final PeopleHomeSourcePort source;
    private final PeopleHomeAuthorityPort authorities;
    private final PeopleHomePayloadCodec codec;
    private final DomainEventRecorder events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean publicationEnabled;

    PeopleHomeContributionService(
            PeopleHomeSourcePort source,
            PeopleHomeAuthorityPort authorities,
            PeopleHomePayloadCodec codec,
            DomainEventRecorder events,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            @Value("${dwp.hris.home.people.publication-enabled:false}")
            boolean publicationEnabled) {
        this(source, authorities, codec, events, contracts, mapper,
                Clock.systemUTC(), publicationEnabled);
    }

    PeopleHomeContributionService(
            PeopleHomeSourcePort source,
            PeopleHomeAuthorityPort authorities,
            PeopleHomePayloadCodec codec,
            DomainEventRecorder events,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            Clock clock,
            boolean publicationEnabled) {
        this.source = source;
        this.authorities = authorities;
        this.codec = codec;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.publicationEnabled = publicationEnabled;
        contracts.register(EVENT_TYPE, 1, 1);
    }

    @Transactional(readOnly = true)
    HrisHomeContributionMaterializationV1 materialize(
            String payloadRef,
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            UUID correlationId) {
        PeopleHomePayloadCodec.Opened opened = codec.open(authority.tenantId(), payloadRef);
        Instant now = clock.instant();
        Instant staleAfter = opened.generatedAt().plus(FRESH_FOR).plus(STALE_FOR);
        if (opened.generatedAt().isAfter(now.plusSeconds(1))
                || !staleAfter.isAfter(now)) throw new PeopleHomePayloadCodec.UnknownReference();
        PeopleHomeSourcePort.Snapshot snapshot = source.snapshot(authority);
        String currentDigest = codec.digest(authority, snapshot);
        if (!constantTime(currentDigest, opened.payloadDigest())
                || snapshot.asOf().isAfter(opened.generatedAt())) {
            throw new PeopleHomePayloadCodec.UnknownReference();
        }
        return assemble(authority, snapshot, correlationId, opened.generatedAt(),
                payloadRef, currentDigest, now);
    }

    @Transactional
    Published publish(PeopleHomeAuthorityPort.CurrentAuthority authority) {
        PeopleHomeSourcePort.Snapshot snapshot = source.snapshot(authority);
        String digest = codec.digest(authority, snapshot);
        Instant generatedAt = generationTime(clock.instant(), snapshot.asOf());
        String reference = codec.seal(authority.tenantId(), generatedAt, digest);
        UUID eventId = deterministicUuid(
                "people-home-event\n" + authority.tenantId() + "\n" + reference);
        HrisHomeContributionMaterializationV1 materialization = assemble(
                authority, snapshot, eventId, generatedAt, reference, digest, generatedAt);
        Xcon013PeopleHomeContributionChangedV1 data =
                new Xcon013PeopleHomeContributionChangedV1(
                        authority.ownerPopulationScope().name(),
                        materialization.freshUntil(), materialization.generatedAt(), "HRM",
                        digest, reference, authority.scopeRevision(),
                        materialization.staleAfter(), materialization.state().name(),
                        authority.widgetKey());
        DomainEventEnvelope event = new DomainEventEnvelope(
                "1.0", eventId, EVENT_SOURCE, EVENT_TYPE, 1, generatedAt,
                "HRIS_HOME_CONTRIBUTION/" + aggregateId(authority),
                authority.tenantId(), "HRIS_HOME_CONTRIBUTION", aggregateId(authority),
                aggregateSequence(generatedAt, snapshot.sourceVersion()),
                eventId.toString(), null, null,
                mapper.valueToTree(data), Map.of());
        events.record(event);
        return new Published(materialization, event);
    }

    @Scheduled(fixedDelayString =
            "${dwp.hris.home.people.publication-delay-ms:60000}")
    @Transactional
    void publishCurrentGrants() {
        if (!publicationEnabled) return;
        for (PeopleHomeAuthorityPort.CurrentAuthority authority
                : authorities.currentGrants()) {
            publish(authority);
        }
    }

    private HrisHomeContributionMaterializationV1 assemble(
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            PeopleHomeSourcePort.Snapshot snapshot,
            UUID correlationId,
            Instant generatedAt,
            String reference,
            String digest,
            Instant now) {
        ProjectionResult projections = project(authority, snapshot);
        HrisHomeContributionMaterializationV1.State state;
        List<HrisHomeContributionMaterializationV1.PartialFailure> failures;
        if (!projections.missingRequiredFields().isEmpty()) {
            boolean anySafe = projections.decisions().stream()
                    .anyMatch(value -> value.decision()
                            != HrisHomeContributionMaterializationV1.Decision.OMIT);
            state = anySafe ? HrisHomeContributionMaterializationV1.State.DEGRADED
                    : HrisHomeContributionMaterializationV1.State.UNAVAILABLE;
            failures = List.of(new HrisHomeContributionMaterializationV1.PartialFailure(
                    HrisHomeContributionMaterializationV1.Module.HRM,
                    authority.widgetKey(), authority.browserAudience(),
                    authority.ownerPopulationScope(), authority.scopeRevision(),
                    authority.fieldPolicyRevision(),
                    HrisHomeContributionMaterializationV1.PURPOSE,
                    HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE,
                    true, correlationId, generatedAt));
        } else {
            state = now.isBefore(generatedAt.plus(FRESH_FOR))
                    ? HrisHomeContributionMaterializationV1.State.FRESH
                    : HrisHomeContributionMaterializationV1.State.STALE;
            failures = List.of();
        }
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE,
                HrisHomeContributionMaterializationV1.Module.HRM,
                authority.widgetKey(),
                HrisHomeContributionMaterializationV1.WidgetType.PEOPLE_SUMMARY,
                authority.browserAudience(), authority.ownerPopulationScope(),
                authority.scopeRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                HrisHomeContributionMaterializationV1.PURPOSE, state,
                generatedAt, generatedAt.plus(FRESH_FOR),
                generatedAt.plus(FRESH_FOR).plus(STALE_FOR),
                "/hr/people", snapshot.sourceVersion(), reference, digest,
                snapshot.asOf(),
                new HrisHomeContributionMaterializationV1.PeopleHomeData(
                        HrisHomeContributionMaterializationV1.WidgetType.PEOPLE_SUMMARY,
                        projections.employmentStatus(), projections.organizationLabel(),
                        projections.pendingActionCount()),
                projections.decisions(), failures);
    }

    private static ProjectionResult project(
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            PeopleHomeSourcePort.Snapshot snapshot) {
        List<String> missing = new ArrayList<>();
        HrisHomeContributionMaterializationV1.TextProjection employment = text(
                "employmentStatus", authority, snapshot.employmentStatus(), missing);
        HrisHomeContributionMaterializationV1.TextProjection organization = text(
                "organizationLabel", authority, snapshot.organizationLabel(), missing);
        HrisHomeContributionMaterializationV1.NumberProjection pending = number(
                "pendingActionCount", authority, snapshot.pendingActionCount(), missing);
        List<HrisHomeContributionMaterializationV1.FieldDecision> decisions = FIELD_ORDER.stream()
                .map(field -> new HrisHomeContributionMaterializationV1.FieldDecision(
                        field, switch (field) {
                            case "employmentStatus" -> employment.decision();
                            case "organizationLabel" -> organization.decision();
                            default -> pending.decision();
                        })).toList();
        return new ProjectionResult(employment, organization, pending, decisions,
                List.copyOf(missing));
    }

    private static HrisHomeContributionMaterializationV1.TextProjection text(
            String field,
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            java.util.Optional<String> value,
            List<String> missing) {
        return switch (authority.fieldDecisions().get(field)) {
            case MASK -> new HrisHomeContributionMaterializationV1.MaskedField(
                    HrisHomeContributionMaterializationV1.Decision.MASK,
                    HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new HrisHomeContributionMaterializationV1.OmittedField(
                    HrisHomeContributionMaterializationV1.Decision.OMIT);
            case VIEW -> value.<HrisHomeContributionMaterializationV1.TextProjection>map(
                    item -> new HrisHomeContributionMaterializationV1.ViewedText(
                            HrisHomeContributionMaterializationV1.Decision.VIEW, item))
                    .orElseGet(() -> {
                        missing.add(field);
                        return new HrisHomeContributionMaterializationV1.OmittedField(
                                HrisHomeContributionMaterializationV1.Decision.OMIT);
                    });
        };
    }

    private static HrisHomeContributionMaterializationV1.NumberProjection number(
            String field,
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            java.util.Optional<Long> value,
            List<String> missing) {
        return switch (authority.fieldDecisions().get(field)) {
            case MASK -> new HrisHomeContributionMaterializationV1.MaskedField(
                    HrisHomeContributionMaterializationV1.Decision.MASK,
                    HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new HrisHomeContributionMaterializationV1.OmittedField(
                    HrisHomeContributionMaterializationV1.Decision.OMIT);
            case VIEW -> value.<HrisHomeContributionMaterializationV1.NumberProjection>map(
                    item -> new HrisHomeContributionMaterializationV1.ViewedNumber(
                            HrisHomeContributionMaterializationV1.Decision.VIEW, item))
                    .orElseGet(() -> {
                        missing.add(field);
                        return new HrisHomeContributionMaterializationV1.OmittedField(
                                HrisHomeContributionMaterializationV1.Decision.OMIT);
                    });
        };
    }

    private static Instant generationTime(Instant now, Instant asOf) {
        Instant bucket = now.truncatedTo(ChronoUnit.MINUTES)
                .minusSeconds(Math.floorMod(now.getEpochSecond() / 60, 5) * 60);
        Instant candidate = asOf.isAfter(bucket) ? asOf : bucket;
        long millis = candidate.toEpochMilli();
        if (candidate.isAfter(Instant.ofEpochMilli(millis))) millis++;
        return Instant.ofEpochMilli(millis);
    }

    private static boolean constantTime(String left, String right) {
        return MessageDigestSupport.equals(left, right);
    }

    private static String aggregateId(PeopleHomeAuthorityPort.CurrentAuthority authority) {
        return authority.tenantId() + ":" + authority.widgetKey() + ":"
                + authority.subjectPrincipalPublicId() + ":"
                + authority.ownerPopulationScope() + ":" + authority.scopeRevision()
                + ":" + authority.effectivePermissionRevision() + ":"
                + authority.fieldPolicyRevision();
    }

    private static long aggregateSequence(Instant generatedAt, long sourceVersion) {
        return Math.addExact(
                Math.multiplyExact(generatedAt.toEpochMilli(), 1_000_000L),
                Math.floorMod(sourceVersion, 1_000_000L));
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    record Published(
            HrisHomeContributionMaterializationV1 materialization,
            DomainEventEnvelope event) {
    }

    private record ProjectionResult(
            HrisHomeContributionMaterializationV1.TextProjection employmentStatus,
            HrisHomeContributionMaterializationV1.TextProjection organizationLabel,
            HrisHomeContributionMaterializationV1.NumberProjection pendingActionCount,
            List<HrisHomeContributionMaterializationV1.FieldDecision> decisions,
            List<String> missingRequiredFields) {
    }

    private static final class MessageDigestSupport {
        private static boolean equals(String left, String right) {
            return left != null && right != null && java.security.MessageDigest.isEqual(
                    left.getBytes(StandardCharsets.US_ASCII),
                    right.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
