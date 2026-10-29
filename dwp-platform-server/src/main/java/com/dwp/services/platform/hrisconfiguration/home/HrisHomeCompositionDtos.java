package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.generated.CanonicalHrisJson;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Module;
import com.dwp.services.platform.hrisconfiguration.home.HrisHomeAuthorityContract.CurrentAuthorityGuard;
import com.dwp.services.platform.hrisconfiguration.home.HrisHomeAuthorityContract.CurrentAuthoritySnapshot;
import com.dwp.services.platform.hrisconfiguration.home.HrisHomeAuthorityContract.OwnerAuthoritySnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.*;

/** Closed input and browser-output types for the inactive R4 composition core. */
public final class HrisHomeCompositionDtos {

    public static final String BROWSER_PURPOSE = "HRIS_HOME_VIEW";

    private HrisHomeCompositionDtos() {
    }

    public enum Freshness {
        FRESH_ONLY,
        ALLOW_STALE
    }

    public record ViewRequest(
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant asOf,
            BrowserAudience browserAudience,
            Freshness freshness,
            UUID correlationId) {

        public ViewRequest {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(freshness, "freshness");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * Trusted current browser-composition authority. Top-level revisions describe the browser
     * view; owner revisions remain per-widget and must not be collapsed into these values.
     */
    public record CurrentViewAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            BrowserAudience browserAudience,
            long browserScopeRevision,
            String browserPopulationScopeDigest,
            long browserFieldPolicyRevision,
            long authorizationRevision,
            long compositionRevision,
            Map<Module, OwnerAuthority> owners,
            CurrentAuthorityGuard guard) {

        public CurrentViewAuthority {
            if (tenantId < 1 || browserScopeRevision < 1 || browserFieldPolicyRevision < 1
                    || authorizationRevision < 1 || compositionRevision < 1) {
                throw new IllegalArgumentException("view authority revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(browserAudience, "browserAudience");
            digest(browserPopulationScopeDigest, "browserPopulationScopeDigest");
            Objects.requireNonNull(owners, "owners");
            LinkedHashMap<Module, OwnerAuthority> copy = new LinkedHashMap<>();
            owners.forEach((module, owner) -> {
                Objects.requireNonNull(module, "owners contains null module");
                Objects.requireNonNull(owner, "owners contains null authority");
                if (module != owner.module()) {
                    throw new IllegalArgumentException("owner authority module key mismatch");
                }
                if (owner.browserAudience() != browserAudience) {
                    throw new IllegalArgumentException("owner authority audience mismatch");
                }
                copy.put(module, owner);
            });
            owners = Map.copyOf(copy);
            Objects.requireNonNull(guard, "guard");
        }

        CurrentAuthoritySnapshot snapshot() {
            LinkedHashMap<Module, OwnerAuthoritySnapshot> ownerSnapshots =
                    new LinkedHashMap<>();
            owners.forEach((module, owner) -> ownerSnapshots.put(module,
                    new OwnerAuthoritySnapshot(
                            owner.module(), owner.widgetKey(), owner.capability(),
                            owner.browserAudience(), owner.ownerPopulationScope(),
                            owner.scopeRevision(), owner.populationScopeDigest(),
                            owner.effectivePermissionRevision(), owner.fieldPolicyRevision(),
                            owner.fieldCeilings())));
            return new CurrentAuthoritySnapshot(
                    tenantId, subjectPrincipalPublicId, browserAudience,
                    browserScopeRevision, browserPopulationScopeDigest,
                    browserFieldPolicyRevision, authorizationRevision,
                    compositionRevision, ownerSnapshots);
        }
    }

    /** Exact outbound authority for one owner materialization. */
    public record OwnerAuthority(
            Module module,
            String widgetKey,
            String capability,
            BrowserAudience browserAudience,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            Map<String, Decision> fieldCeilings) {

        public OwnerAuthority {
            Objects.requireNonNull(module, "module");
            if (widgetKey == null || widgetKey.isBlank()
                    || capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("owner widget and capability are required");
            }
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
            if (!ownerPopulationScope.matches(browserAudience, module)) {
                throw new IllegalArgumentException("owner scope does not match its audience");
            }
            if (scopeRevision < 1 || effectivePermissionRevision < 1
                    || fieldPolicyRevision < 1) {
                throw new IllegalArgumentException("owner authority revisions must be positive");
            }
            digest(populationScopeDigest, "populationScopeDigest");
            fieldCeilings = Map.copyOf(Objects.requireNonNull(fieldCeilings, "fieldCeilings"));
            fieldCeilings.forEach((field, decision) -> {
                if (field == null || field.isBlank() || decision == null) {
                    throw new IllegalArgumentException("field ceiling is invalid");
                }
            });
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HomeResponse(
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant asOf,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant generatedAt,
            BrowserAudience browserAudience,
            long scopeRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            long compositionRevision,
            String purposeCode,
            List<HomeWidget> widgets,
            List<HomeWidgetFailure> partialFailures) {

        public HomeResponse {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(generatedAt, "generatedAt");
            Objects.requireNonNull(browserAudience, "browserAudience");
            if (scopeRevision < 1 || fieldPolicyRevision < 1 || authorizationRevision < 1
                    || compositionRevision < 1) {
                throw new IllegalArgumentException("response revisions must be positive");
            }
            digest(populationScopeDigest, "populationScopeDigest");
            if (!BROWSER_PURPOSE.equals(purposeCode)) {
                throw new IllegalArgumentException("browser purpose must be HRIS_HOME_VIEW");
            }
            widgets = List.copyOf(Objects.requireNonNull(widgets, "widgets"));
            partialFailures = List.copyOf(
                    Objects.requireNonNull(partialFailures, "partialFailures"));
            if (widgets.size() > 20 || partialFailures.size() > 8) {
                throw new IllegalArgumentException("Home response exceeds its closed bounds");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HomeWidget(
            Module module,
            String widgetKey,
            WidgetType widgetType,
            BrowserAudience browserAudience,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            BrowserState state,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant generatedAt,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant freshUntil,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant staleAfter,
            String deepLink,
            long sourceVersion,
            String payloadDigest,
            HomeData data,
            List<FieldDecision> fieldDecisions,
            HomeWidgetError error) {

        public HomeWidget {
            Objects.requireNonNull(module, "module");
            if (widgetKey == null || widgetKey.isBlank()) {
                throw new IllegalArgumentException("widgetKey is required");
            }
            Objects.requireNonNull(widgetType, "widgetType");
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
            if (scopeRevision < 1 || fieldPolicyRevision < 1 || sourceVersion < 1) {
                throw new IllegalArgumentException("widget revisions must be positive");
            }
            digest(populationScopeDigest, "populationScopeDigest");
            digest(payloadDigest, "payloadDigest");
            if (!BROWSER_PURPOSE.equals(purposeCode)) {
                throw new IllegalArgumentException("widget purpose must be HRIS_HOME_VIEW");
            }
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(generatedAt, "generatedAt");
            Objects.requireNonNull(freshUntil, "freshUntil");
            Objects.requireNonNull(staleAfter, "staleAfter");
            if (deepLink == null || !deepLink.matches("^/hr(?:/|$).{0,296}$")
                    || deepLink.contains("//") || deepLink.contains("..")
                    || deepLink.indexOf('?') >= 0 || deepLink.indexOf('#') >= 0
                    || deepLink.indexOf('%') >= 0) {
                throw new IllegalArgumentException("deepLink is not an exact canonical HR route");
            }
            Objects.requireNonNull(data, "data");
            fieldDecisions = List.copyOf(
                    Objects.requireNonNull(fieldDecisions, "fieldDecisions"));
            if ((state == BrowserState.FRESH || state == BrowserState.STALE)
                    && error != null) {
                throw new IllegalArgumentException("healthy widget cannot contain an error");
            }
            if ((state == BrowserState.DEGRADED || state == BrowserState.UNAVAILABLE)
                    && error == null) {
                throw new IllegalArgumentException("failed widget requires a typed error");
            }
        }
    }

    public enum BrowserState {
        FRESH,
        STALE,
        DEGRADED,
        UNAVAILABLE,
        EMPTY
    }

    public record HomeWidgetError(
            FailureCode code,
            boolean retryable,
            UUID correlationId) {

        public HomeWidgetError {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public record HomeWidgetFailure(
            Module module,
            String widgetKey,
            BrowserAudience browserAudience,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            long fieldPolicyRevision,
            String purposeCode,
            FailureCode code,
            boolean retryable,
            UUID correlationId,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant occurredAt) {

        public HomeWidgetFailure {
            Objects.requireNonNull(module, "module");
            if (widgetKey == null || widgetKey.isBlank()) {
                throw new IllegalArgumentException("failure widgetKey is required");
            }
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
            if (scopeRevision < 1 || fieldPolicyRevision < 1) {
                throw new IllegalArgumentException("failure revisions must be positive");
            }
            if (!BROWSER_PURPOSE.equals(purposeCode)) {
                throw new IllegalArgumentException("failure purpose must be HRIS_HOME_VIEW");
            }
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    static Set<String> fieldKeys(HomeData data) {
        return data.decisions().keySet();
    }

    private static void digest(String value, String name) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
