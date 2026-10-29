package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Module;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.*;
import static com.dwp.services.platform.hrisconfiguration.home.HrisHomeAuthorityContract.*;
import static com.dwp.services.platform.hrisconfiguration.home.HrisHomeCompositionDtos.*;
import static com.dwp.services.platform.hrisconfiguration.home.HrisHomeCompositionPorts.*;

/**
 * Controller-free SYS Home composition core.
 *
 * <p>The service consumes only tenant-bound candidates and strict R3 materializations. It exposes
 * no Spring stereotype so R4 cannot accidentally activate a browser or owner transport surface.
 */
public final class HrisHomeCompositionService {

    private static final List<Module> MODULE_ORDER = List.of(
            Module.HRM, Module.PER, Module.TIM, Module.PAY);

    private final CandidateSource candidates;
    private final HrisHomeCompositionCoordinator coordinator;
    private final DeepLinkPolicy deepLinks;
    private final Clock clock;

    public HrisHomeCompositionService(
            CandidateSource candidates,
            HrisHomeCompositionCoordinator coordinator,
            DeepLinkPolicy deepLinks,
            Clock clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.deepLinks = Objects.requireNonNull(deepLinks, "deepLinks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HomeResponse compose(ViewRequest request, CurrentViewAuthority authority) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authority, "authority");
        CurrentAuthoritySnapshot snapshot = authority.snapshot();
        if (request.browserAudience() != authority.browserAudience()) {
            throw new SecurityException("Current Home authority does not admit requested audience");
        }
        authority.guard().requireCurrent(snapshot);
        Instant now = clock.instant();
        if (request.asOf().isAfter(now)) {
            throw new IllegalArgumentException("Home asOf cannot be in the future");
        }

        List<HomeWidget> widgets = new ArrayList<>();
        List<HomeWidgetFailure> failures = new ArrayList<>();
        List<HrisHomeCompositionCoordinator.OwnerCall> calls = new ArrayList<>();
        for (Module module : MODULE_ORDER) {
            OwnerAuthority owner = authority.owners().get(module);
            if (owner == null) continue;
            prepareOwner(request, authority, owner, now, calls, failures);
        }
        authority.guard().requireCurrent(snapshot);
        List<HrisHomeCompositionCoordinator.OwnerResult> ownerResults =
                coordinator.materialize(calls);
        authority.guard().requireCurrent(snapshot);
        Instant projectionTime = clock.instant();
        for (HrisHomeCompositionCoordinator.OwnerResult result : ownerResults) {
            projectOwner(request, authority, result, projectionTime, widgets, failures);
        }
        authority.guard().requireCurrent(snapshot);
        Instant generatedAt = clock.instant();
        List<HomeWidget> finalWidgets = finalizeFreshness(
                request, widgets, failures, generatedAt);
        return new HomeResponse(
                request.asOf(), generatedAt, request.browserAudience(),
                authority.browserScopeRevision(), authority.browserPopulationScopeDigest(),
                authority.browserFieldPolicyRevision(), authority.authorizationRevision(),
                authority.compositionRevision(), BROWSER_PURPOSE,
                finalWidgets, failures.stream().limit(8).toList());
    }

    private void prepareOwner(
            ViewRequest request,
            CurrentViewAuthority authority,
            OwnerAuthority owner,
            Instant now,
            List<HrisHomeCompositionCoordinator.OwnerCall> calls,
            List<HomeWidgetFailure> failures) {
        UUID correlationId = ownerCorrelation(request.correlationId(), owner.module());
        authority.guard().requireCurrent(authority.snapshot());
        List<HrisHomeContributionCandidate> resolved;
        try {
            resolved = List.copyOf(candidates.latest(new CandidateQuery(
                    authority.tenantId(), authority.subjectPrincipalPublicId(),
                    request.asOf(), owner)));
        } catch (RuntimeException failure) {
            failures.add(failure(owner, FailureCode.OWNER_UNAVAILABLE, true,
                    correlationId, now));
            return;
        }
        if (resolved.isEmpty()) {
            failures.add(failure(owner, FailureCode.OWNER_UNAVAILABLE, true,
                    correlationId, now));
            return;
        }
        if (resolved.size() != 1) {
            failures.add(failure(owner, FailureCode.CONTRACT_INVALID, false,
                    correlationId, now));
            return;
        }
        HrisHomeContributionCandidate candidate = resolved.getFirst();
        FailureCode candidateFailure = validateCandidate(authority, owner, candidate, request);
        if (candidateFailure != null) {
            failures.add(failure(owner, candidateFailure, false, correlationId, now));
            return;
        }

        HomeMaterializationWorkloadProofV1.Endpoint endpoint = endpoint(owner.module());
        OwnerRequest ownerRequest = new OwnerRequest(
                authority.tenantId(), authority.subjectPrincipalPublicId(), owner.capability(),
                owner.effectivePermissionRevision(), owner.fieldPolicyRevision(),
                owner.populationScopeDigest(), candidate, owner.browserAudience(), correlationId,
                endpoint, endpoint.path(candidate.payloadRef()));
        calls.add(new HrisHomeCompositionCoordinator.OwnerCall(owner, ownerRequest));
    }

    private void projectOwner(
            ViewRequest request,
            CurrentViewAuthority authority,
            HrisHomeCompositionCoordinator.OwnerResult result,
            Instant now,
            List<HomeWidget> widgets,
            List<HomeWidgetFailure> failures) {
        OwnerAuthority owner = result.call().owner();
        HrisHomeContributionCandidate candidate = result.call().request().candidate();
        UUID correlationId = result.call().request().correlationId();
        if (result.failureCode() != null) {
            failures.add(failure(owner, result.failureCode(), result.retryable(),
                    correlationId, now));
            return;
        }
        HrisHomeContributionMaterializationV1 materialization = result.materialization();

        // Verify the sealed R3 digest before freshness, field or purpose projection.
        FailureCode materializationFailure = validateMaterialization(
                owner, candidate, materialization, request);
        if (materializationFailure != null) {
            failures.add(failure(owner, materializationFailure, false, correlationId, now));
            return;
        }

        if (now.isAfter(materialization.staleAfter())
                || (request.freshness() == Freshness.FRESH_ONLY
                && (materialization.state() == State.STALE
                || now.isAfter(materialization.freshUntil())))) {
            failures.add(failure(owner, FailureCode.STALE_BEYOND_LIMIT, true,
                    correlationId, now));
            return;
        }
        String deepLink;
        try {
            deepLink = deepLinks.authorize(authority, owner, materialization.deepLink());
        } catch (RuntimeException failure) {
            failures.add(failure(owner, FailureCode.AUTHORIZATION_CHANGED, false,
                    correlationId, now));
            return;
        }
        if (!canonicalDeepLink(deepLink)) {
            failures.add(failure(owner, FailureCode.AUTHORIZATION_CHANGED, false,
                    correlationId, now));
            return;
        }

        HomeData data = restrict(materialization.data(), owner.fieldCeilings());
        List<FieldDecision> decisions = data.decisions().entrySet().stream()
                .map(entry -> new FieldDecision(entry.getKey(), entry.getValue()))
                .toList();
        List<HomeWidgetFailure> ownerFailures = materialization.partialFailures().stream()
                .map(value -> failure(owner, value.code(), value.retryable(),
                        value.correlationId(), value.occurredAt()))
                .toList();
        failures.addAll(ownerFailures);
        BrowserState state = browserState(materialization, now);
        HomeWidgetError error = ownerFailures.isEmpty() ? null : new HomeWidgetError(
                ownerFailures.getFirst().code(), ownerFailures.getFirst().retryable(),
                ownerFailures.getFirst().correlationId());
        widgets.add(new HomeWidget(
                materialization.module(), materialization.widgetKey(),
                materialization.widgetType(), materialization.browserAudience(),
                materialization.ownerPopulationScope(), materialization.scopeRevision(),
                materialization.populationScopeDigest(), materialization.fieldPolicyRevision(),
                BROWSER_PURPOSE, state, materialization.generatedAt(),
                materialization.freshUntil(), materialization.staleAfter(), deepLink,
                materialization.sourceVersion(), materialization.payloadDigest(), data,
                decisions, error));
    }

    private FailureCode validateCandidate(
            CurrentViewAuthority authority,
            OwnerAuthority owner,
            HrisHomeContributionCandidate candidate,
            ViewRequest request) {
        if (candidate.tenantId() != authority.tenantId()) return FailureCode.AUTHORIZATION_CHANGED;
        if (candidate.module() != owner.module()
                || !candidate.widgetKey().equals(owner.widgetKey())
                || candidate.ownerPopulationScope() != owner.ownerPopulationScope()) {
            return FailureCode.CONTRACT_INVALID;
        }
        if (candidate.scopeRevision() != owner.scopeRevision()) {
            return FailureCode.SCOPE_CHANGED;
        }
        if (candidate.generatedAt().isAfter(request.asOf())) {
            return FailureCode.CONTRACT_INVALID;
        }
        return null;
    }

    private FailureCode validateMaterialization(
            OwnerAuthority owner,
            HrisHomeContributionCandidate candidate,
            HrisHomeContributionMaterializationV1 value,
            ViewRequest request) {
        if (value == null) return FailureCode.OWNER_UNAVAILABLE;
        boolean digestMatches;
        try {
            digestMatches = HrisHomeMaterializationDigestV1.matches(
                    value, candidate.payloadDigest());
        } catch (RuntimeException failure) {
            return FailureCode.CONTRACT_INVALID;
        }
        if (!digestMatches || !candidate.payloadDigest().equals(value.payloadDigest())) {
            return FailureCode.CONTRACT_INVALID;
        }
        if (value.module() != candidate.module()
                || !value.widgetKey().equals(candidate.widgetKey())
                || value.ownerPopulationScope() != candidate.ownerPopulationScope()
                || value.state() != candidate.state()
                || !value.generatedAt().equals(candidate.generatedAt())
                || !value.freshUntil().equals(candidate.freshUntil())
                || !value.staleAfter().equals(candidate.staleAfter())
                || !value.payloadRef().equals(candidate.payloadRef())) {
            return FailureCode.CONTRACT_INVALID;
        }
        if (value.browserAudience() != request.browserAudience()) {
            return FailureCode.AUTHORIZATION_CHANGED;
        }
        if (value.scopeRevision() != owner.scopeRevision()
                || !value.populationScopeDigest().equals(owner.populationScopeDigest())) {
            return FailureCode.SCOPE_CHANGED;
        }
        if (value.fieldPolicyRevision() != owner.fieldPolicyRevision()) {
            return FailureCode.FIELD_POLICY_CHANGED;
        }
        if (value.asOf().isAfter(request.asOf())) {
            return FailureCode.CONTRACT_INVALID;
        }
        return null;
    }

    private BrowserState browserState(
            HrisHomeContributionMaterializationV1 value, Instant now) {
        return switch (value.state()) {
            case FRESH -> now.isAfter(value.freshUntil())
                    ? BrowserState.STALE : BrowserState.FRESH;
            case STALE -> BrowserState.STALE;
            case DEGRADED -> BrowserState.DEGRADED;
            case UNAVAILABLE -> BrowserState.UNAVAILABLE;
        };
    }

    private HomeData restrict(HomeData data, Map<String, Decision> ceilings) {
        return switch (data) {
            case PeopleHomeData value -> new PeopleHomeData(
                    value.kind(),
                    restrict(value.employmentStatus(), ceilings.get("employmentStatus")),
                    restrict(value.organizationLabel(), ceilings.get("organizationLabel")),
                    restrict(value.pendingActionCount(), ceilings.get("pendingActionCount")));
            case PerformanceHomeData value -> new PerformanceHomeData(
                    value.kind(),
                    restrict(value.goalProgress(), ceilings.get("goalProgress")),
                    restrict(value.reviewStatus(), ceilings.get("reviewStatus")),
                    restrict(value.pendingActionCount(), ceilings.get("pendingActionCount")));
            case TimeLeaveHomeData value -> new TimeLeaveHomeData(
                    value.kind(),
                    restrict(value.clockState(), ceilings.get("clockState")),
                    restrict(value.leaveBalanceMinutes(), ceilings.get("leaveBalanceMinutes")),
                    restrict(value.openTimecardCount(), ceilings.get("openTimecardCount")),
                    restrict(value.pendingApprovalCount(), ceilings.get("pendingApprovalCount")));
            case PayrollHomeData value -> new PayrollHomeData(
                    value.kind(),
                    restrict(value.payPeriodLabel(), ceilings.get("payPeriodLabel")),
                    restrict(value.grossPay(), ceilings.get("grossPay")),
                    restrict(value.netPay(), ceilings.get("netPay")),
                    restrict(value.payslipAvailable(), ceilings.get("payslipAvailable")));
        };
    }

    @SuppressWarnings("unchecked")
    private <T extends Projection> T restrict(T value, Decision ceiling) {
        Decision effective = restrictive(value.decision(), ceiling == null ? Decision.OMIT : ceiling);
        if (effective == value.decision()) return value;
        Projection replacement = effective == Decision.MASK
                ? new MaskedField(Decision.MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY)
                : new OmittedField(Decision.OMIT);
        return (T) replacement;
    }

    private Decision restrictive(Decision owner, Decision outer) {
        return restrictionRank(owner) >= restrictionRank(outer) ? owner : outer;
    }

    private int restrictionRank(Decision decision) {
        return switch (decision) {
            case VIEW -> 0;
            case MASK -> 1;
            case OMIT -> 2;
        };
    }

    private HomeWidgetFailure failure(
            OwnerAuthority owner,
            FailureCode code,
            boolean retryable,
            UUID correlationId,
            Instant occurredAt) {
        return new HomeWidgetFailure(
                owner.module(), owner.widgetKey(), owner.browserAudience(),
                owner.ownerPopulationScope(), owner.scopeRevision(),
                owner.fieldPolicyRevision(), BROWSER_PURPOSE, code, retryable,
                correlationId, occurredAt);
    }

    private List<HomeWidget> finalizeFreshness(
            ViewRequest request,
            List<HomeWidget> widgets,
            List<HomeWidgetFailure> failures,
            Instant generatedAt) {
        List<HomeWidget> result = new ArrayList<>();
        for (HomeWidget widget : widgets) {
            if (generatedAt.isAfter(widget.staleAfter())
                    || (request.freshness() == Freshness.FRESH_ONLY
                    && (widget.state() == BrowserState.STALE
                    || generatedAt.isAfter(widget.freshUntil())))) {
                failures.add(new HomeWidgetFailure(
                        widget.module(), widget.widgetKey(), widget.browserAudience(),
                        widget.ownerPopulationScope(), widget.scopeRevision(),
                        widget.fieldPolicyRevision(), BROWSER_PURPOSE,
                        FailureCode.STALE_BEYOND_LIMIT, true,
                        ownerCorrelation(request.correlationId(), widget.module()), generatedAt));
                continue;
            }
            if (widget.state() == BrowserState.FRESH
                    && generatedAt.isAfter(widget.freshUntil())) {
                result.add(new HomeWidget(
                        widget.module(), widget.widgetKey(), widget.widgetType(),
                        widget.browserAudience(), widget.ownerPopulationScope(),
                        widget.scopeRevision(), widget.populationScopeDigest(),
                        widget.fieldPolicyRevision(), widget.purposeCode(), BrowserState.STALE,
                        widget.generatedAt(), widget.freshUntil(), widget.staleAfter(),
                        widget.deepLink(), widget.sourceVersion(), widget.payloadDigest(),
                        widget.data(), widget.fieldDecisions(), widget.error()));
                continue;
            }
            result.add(widget);
        }
        return List.copyOf(result);
    }

    private HomeMaterializationWorkloadProofV1.Endpoint endpoint(Module module) {
        return switch (module) {
            case HRM -> HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE;
            case PER -> HomeMaterializationWorkloadProofV1.Endpoint.PERFORMANCE;
            case TIM -> HomeMaterializationWorkloadProofV1.Endpoint.TIME_LEAVE;
            case PAY -> HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL;
        };
    }

    private UUID ownerCorrelation(UUID correlationId, Module module) {
        return UUID.nameUUIDFromBytes(
                (correlationId + ":" + module.name()).getBytes(StandardCharsets.UTF_8));
    }

    private boolean canonicalDeepLink(String value) {
        return value != null && value.length() <= 300
                && value.matches("^/hr(?:/|$).{0,296}$")
                && value.indexOf('%') < 0 && value.indexOf('?') < 0 && value.indexOf('#') < 0
                && !value.contains("//") && !value.contains("..");
    }
}
