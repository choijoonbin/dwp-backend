package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.FieldDecision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.FailureCode;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.MaskedField;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.NumberProjection;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OmittedField;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.PartialFailure;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.State;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.TextProjection;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.TimeLeaveHomeData;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.ViewedNumber;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.ViewedText;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.WidgetType;
import com.dwp.services.time.home.TimeHomeCurrentAuthorityPort.CurrentAuthority;
import com.dwp.services.time.home.TimeHomePayloadReferenceCodec.DecodedReference;
import com.dwp.services.time.home.TimeHomePayloadReferenceCodec.InvalidPayloadReferenceException;
import com.dwp.services.time.home.TimeHomeProjectionRepository.Projection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds and re-materializes the closed TIM Home contribution without retaining snapshots. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomeContributionService {

    private static final String PLACEHOLDER_REF = "href_AAAAAAAAAAAAAAAA";
    private static final String PLACEHOLDER_DIGEST = "0".repeat(64);

    private final Clock clock;
    private final TimeHomeProjectionRepository projections;
    private final TimeHomePayloadReferenceCodec references;
    private final TimeHomeCanonicalDigest digests;
    private final TimeHomeEventPublisher events;
    private final long freshSeconds;
    private final long staleSeconds;

    TimeHomeContributionService(
            @Qualifier("timeHomeClock") Clock clock,
            TimeHomeProjectionRepository projections,
            TimeHomePayloadReferenceCodec references,
            TimeHomeCanonicalDigest digests,
            TimeHomeEventPublisher events,
            @Value("${dwp.hris.time.home.fresh-seconds:60}") long freshSeconds,
            @Value("${dwp.hris.time.home.stale-seconds:300}") long staleSeconds) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.references = Objects.requireNonNull(references, "references");
        this.digests = Objects.requireNonNull(digests, "digests");
        this.events = Objects.requireNonNull(events, "events");
        if (freshSeconds < 1 || staleSeconds <= freshSeconds || staleSeconds > 86_400) {
            throw new IllegalArgumentException("Time Home freshness settings are invalid");
        }
        this.freshSeconds = freshSeconds;
        this.staleSeconds = staleSeconds;
    }

    /**
     * Prepares the contribution and records XCON-014 in the same transaction when publication is
     * enabled. Callers must pass authority that was re-evaluated for this owner operation.
     */
    @Transactional
    HrisHomeContributionMaterializationV1 prepare(CurrentAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        HrisHomeContributionMaterializationV1 materialization = build(authority, issuedAt);
        events.publish(authority.tenantId(), materialization);
        return materialization;
    }

    @Transactional(readOnly = true)
    HrisHomeContributionMaterializationV1 materialize(
            String payloadRef, CurrentAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        DecodedReference decoded = references.decode(payloadRef);
        Instant now = clock.instant();
        if (decoded.tenantId() != authority.tenantId()
                || decoded.issuedAt().isAfter(now)
                || !decoded.issuedAt().plusSeconds(staleSeconds).isAfter(now)) {
            throw new InvalidPayloadReferenceException();
        }
        HrisHomeContributionMaterializationV1 materialization =
                build(authority, decoded.issuedAt());
        if (!MessageDigest.isEqual(
                        decoded.payloadDigest().getBytes(StandardCharsets.US_ASCII),
                        materialization.payloadDigest().getBytes(StandardCharsets.US_ASCII))
                || !MessageDigest.isEqual(
                        payloadRef.getBytes(StandardCharsets.US_ASCII),
                        materialization.payloadRef().getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidPayloadReferenceException();
        }
        return materialization;
    }

    private HrisHomeContributionMaterializationV1 build(
            CurrentAuthority authority, Instant issuedAt) {
        Projection projection;
        boolean ownerUnavailable = false;
        try {
            projection = projections.project(authority, issuedAt);
            if (projection == null || projection.asOf().isAfter(issuedAt)) {
                throw new IllegalStateException("Time Home projection is not current-safe");
            }
        } catch (RuntimeException unavailable) {
            ownerUnavailable = true;
            projection = new Projection(
                    "NOT_APPLICABLE", 0, 0,
                    Math.max(1, authority.scopeRevision()), issuedAt);
        }

        Decision clockDecision = authority.ownerPopulationScope() == OwnerPopulationScope.SELF
                ? authority.decision("clockState") : Decision.OMIT;
        Decision leaveDecision = Decision.OMIT;
        Decision openDecision = ownerUnavailable
                ? Decision.OMIT : authority.decision("openTimecardCount");
        Decision pendingDecision = ownerUnavailable
                ? Decision.OMIT : authority.decision("pendingApprovalCount");
        if (ownerUnavailable) {
            clockDecision = Decision.OMIT;
        }

        TimeLeaveHomeData data = new TimeLeaveHomeData(
                WidgetType.TIME_LEAVE_SUMMARY,
                text(clockDecision, projection.clockState()),
                number(leaveDecision, 0),
                number(openDecision, projection.boundedOpenCount()),
                number(pendingDecision, projection.boundedPendingCount()));
        List<FieldDecision> decisions = List.of(
                new FieldDecision("clockState", clockDecision),
                new FieldDecision("leaveBalanceMinutes", leaveDecision),
                new FieldDecision("openTimecardCount", openDecision),
                new FieldDecision("pendingApprovalCount", pendingDecision));

        UUID failureId = stableFailureId(authority, issuedAt,
                ownerUnavailable ? "TIME_OWNER_UNAVAILABLE" : "LEAVE_BALANCE_SOURCE_MISSING");
        List<PartialFailure> failures = List.of(new PartialFailure(
                HrisHomeContributionMaterializationV1.Module.TIM,
                authority.widgetKey(), authority.browserAudience(),
                authority.ownerPopulationScope(), authority.scopeRevision(),
                authority.fieldPolicyRevision(),
                HrisHomeContributionMaterializationV1.PURPOSE,
                FailureCode.OWNER_UNAVAILABLE, ownerUnavailable,
                failureId, issuedAt));
        boolean anySafeField = decisions.stream()
                .anyMatch(decision -> decision.decision() != Decision.OMIT);
        State state = anySafeField ? State.DEGRADED : State.UNAVAILABLE;

        HrisHomeContributionMaterializationV1 draft = response(
                authority, projection, issuedAt, data, decisions, failures, state,
                PLACEHOLDER_REF, PLACEHOLDER_DIGEST);
        String digest = digests.digest(draft);
        String payloadRef = references.issue(authority.tenantId(), issuedAt, digest);
        HrisHomeContributionMaterializationV1 result = response(
                authority, projection, issuedAt, data, decisions, failures, state,
                payloadRef, digest);
        if (!digest.equals(digests.digest(result))) {
            throw new IllegalStateException("Time Home canonical digest was not stable");
        }
        return result;
    }

    private HrisHomeContributionMaterializationV1 response(
            CurrentAuthority authority,
            Projection projection,
            Instant issuedAt,
            TimeLeaveHomeData data,
            List<FieldDecision> decisions,
            List<PartialFailure> failures,
            State state,
            String payloadRef,
            String payloadDigest) {
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE,
                HrisHomeContributionMaterializationV1.Module.TIM,
                authority.widgetKey(),
                WidgetType.TIME_LEAVE_SUMMARY,
                authority.browserAudience(),
                authority.ownerPopulationScope(),
                authority.scopeRevision(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                HrisHomeContributionMaterializationV1.PURPOSE,
                state,
                issuedAt,
                issuedAt.plusSeconds(freshSeconds),
                issuedAt.plusSeconds(staleSeconds),
                deepLink(authority.browserAudience()),
                projection.sourceVersion(),
                payloadRef,
                payloadDigest,
                projection.asOf(),
                data,
                decisions,
                failures);
    }

    private static TextProjection text(Decision decision, String value) {
        return switch (decision) {
            case VIEW -> new ViewedText(Decision.VIEW, value);
            case MASK -> new MaskedField(Decision.MASK,
                    HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new OmittedField(Decision.OMIT);
        };
    }

    private static NumberProjection number(Decision decision, long value) {
        return switch (decision) {
            case VIEW -> new ViewedNumber(Decision.VIEW, value);
            case MASK -> new MaskedField(Decision.MASK,
                    HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new OmittedField(Decision.OMIT);
        };
    }

    private static String deepLink(BrowserAudience audience) {
        return switch (audience) {
            case SELF -> "/hr/time";
            case TEAM -> "/hr/team/time";
            case OPERATIONS -> "/hr/operations/time";
            case EXECUTIVE -> "/hr/executive/time";
        };
    }

    private static UUID stableFailureId(
            CurrentAuthority authority, Instant issuedAt, String reason) {
        return UUID.nameUUIDFromBytes(("TIM_HOME|" + authority.tenantId() + "|"
                + authority.widgetKey() + "|" + authority.scopeRevision() + "|"
                + issuedAt + "|" + reason).getBytes(StandardCharsets.UTF_8));
    }
}
