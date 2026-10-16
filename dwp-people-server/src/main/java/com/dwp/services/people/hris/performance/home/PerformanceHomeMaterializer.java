package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.MASK;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;

/** Builds and verifies the single closed Performance materialization profile. */
@Component
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeMaterializer {

    static final String DEEP_LINK = "/hr/performance";

    private final PerformanceHomeReferenceCodec references;

    PerformanceHomeMaterializer(PerformanceHomeReferenceCodec references) {
        this.references = references;
    }

    Prepared prepare(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            PerformanceHomeProjectionPort.Snapshot snapshot) {
        Projection projection = projection(authority, snapshot);
        HrisHomeContributionMaterializationV1 draft = materialization(
                authority, snapshot, projection,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_REFERENCE,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_DIGEST);
        String digest = HrisHomeMaterializationDigestV1.digest(draft);
        String reference = references.issue(authority, digest, snapshot.staleAfter());
        HrisHomeContributionMaterializationV1 result = materialization(
                authority, snapshot, projection, reference, digest);
        if (!HrisHomeMaterializationDigestV1.matches(result, digest)) {
            throw new IllegalStateException(
                    "Performance Home canonical digest was not stable.");
        }
        return new Prepared(result, digest, reference);
    }

    HrisHomeContributionMaterializationV1 materialize(
            String payloadRef,
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            PerformanceHomeProjectionPort.Snapshot snapshot) {
        Projection projection = projection(authority, snapshot);
        HrisHomeContributionMaterializationV1 draft = materialization(
                authority, snapshot, projection,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_REFERENCE,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_DIGEST);
        String digest = HrisHomeMaterializationDigestV1.digest(draft);
        references.verify(payloadRef, authority, digest);
        return materialization(authority, snapshot, projection, payloadRef, digest);
    }

    private HrisHomeContributionMaterializationV1 materialization(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            PerformanceHomeProjectionPort.Snapshot snapshot,
            Projection projection,
            String payloadRef,
            String payloadDigest) {
        List<HrisHomeContributionMaterializationV1.PartialFailure> failures =
                snapshot.partialFailures().stream().map(failure ->
                        new HrisHomeContributionMaterializationV1.PartialFailure(
                                HrisHomeContributionMaterializationV1.Module.PER,
                                authority.widgetKey(), authority.browserAudience(),
                                authority.ownerPopulationScope(), authority.scopeRevision(),
                                authority.fieldPolicyRevision(),
                                HrisHomeContributionMaterializationV1.PURPOSE,
                                failure.code(), failure.retryable(), failure.correlationId(),
                                failure.occurredAt())).toList();
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE,
                HrisHomeContributionMaterializationV1.Module.PER,
                authority.widgetKey(),
                HrisHomeContributionMaterializationV1.WidgetType.PERFORMANCE_SUMMARY,
                authority.browserAudience(), authority.ownerPopulationScope(),
                authority.scopeRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                HrisHomeContributionMaterializationV1.PURPOSE,
                snapshot.state(), snapshot.generatedAt(), snapshot.freshUntil(),
                snapshot.staleAfter(), DEEP_LINK, snapshot.sourceVersion(),
                payloadRef, payloadDigest, snapshot.asOf(), projection.data(),
                projection.fieldDecisions(), failures);
    }

    private Projection projection(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            PerformanceHomeProjectionPort.Snapshot snapshot) {
        var goalDecision = decision(authority, snapshot, "goalProgress");
        var reviewDecision = decision(authority, snapshot, "reviewStatus");
        var pendingDecision = decision(authority, snapshot, "pendingActionCount");
        var data = new HrisHomeContributionMaterializationV1.PerformanceHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.PERFORMANCE_SUMMARY,
                text(goalDecision, snapshot.goalProgress().name()),
                text(reviewDecision, snapshot.reviewStatus().name()),
                number(pendingDecision, snapshot.pendingActionCount()));
        return new Projection(data, List.of(
                new HrisHomeContributionMaterializationV1.FieldDecision(
                        "goalProgress", goalDecision),
                new HrisHomeContributionMaterializationV1.FieldDecision(
                        "reviewStatus", reviewDecision),
                new HrisHomeContributionMaterializationV1.FieldDecision(
                        "pendingActionCount", pendingDecision)));
    }

    private HrisHomeContributionMaterializationV1.Decision decision(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            PerformanceHomeProjectionPort.Snapshot snapshot,
            String key) {
        if (snapshot.state() == HrisHomeContributionMaterializationV1.State.UNAVAILABLE) {
            return OMIT;
        }
        return authority.fieldDecisions().get(key);
    }

    private HrisHomeContributionMaterializationV1.TextProjection text(
            HrisHomeContributionMaterializationV1.Decision decision,
            String value) {
        return switch (decision) {
            case VIEW -> new HrisHomeContributionMaterializationV1.ViewedText(VIEW, value);
            case MASK -> new HrisHomeContributionMaterializationV1.MaskedField(
                    MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new HrisHomeContributionMaterializationV1.OmittedField(OMIT);
        };
    }

    private HrisHomeContributionMaterializationV1.NumberProjection number(
            HrisHomeContributionMaterializationV1.Decision decision,
            long value) {
        return switch (decision) {
            case VIEW -> new HrisHomeContributionMaterializationV1.ViewedNumber(VIEW, value);
            case MASK -> new HrisHomeContributionMaterializationV1.MaskedField(
                    MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
            case OMIT -> new HrisHomeContributionMaterializationV1.OmittedField(OMIT);
        };
    }

    record Prepared(
            HrisHomeContributionMaterializationV1 materialization,
            String payloadDigest,
            String payloadRef) {
    }

    private record Projection(
            HrisHomeContributionMaterializationV1.PerformanceHomeData data,
            List<HrisHomeContributionMaterializationV1.FieldDecision> fieldDecisions) {
    }

}
