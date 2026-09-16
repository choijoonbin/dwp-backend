package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Semantic legacy/v2 comparison. Projection material is never emitted or persisted. */
@Component
public final class HomeReadModelShadowComparator {

    private final HomeCanonicalJson canonicalJson;

    public HomeReadModelShadowComparator(HomeCanonicalJson canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public Projection project(HomeReadModelDtos.HomeReadModel model) {
        List<String> apps = new ArrayList<>();
        for (HomeReadModelDtos.AppGroup group : model.appDock()) {
            apps.add("group:" + group.groupKey());
            for (HomeReadModelDtos.AppEntry app : group.apps()) {
                apps.add("app:" + app.appKey() + ":" + app.badgeState()
                        + ":" + (app.badge() == null ? "none" : badgeClass(app.badge())));
            }
        }
        List<String> widgets = model.widgets().stream().map(widget ->
                widget.definitionKey() + ":" + widget.definitionVersion() + ":"
                        + widget.rendererBindingRevision() + ":" + widget.state())
                .toList();
        List<String> routesAndActions = model.widgets().stream().flatMap(widget -> {
            List<String> values = new ArrayList<>();
            values.add("source:" + widget.governance().sourceRoute());
            widget.actions().forEach(action -> values.add(
                    "action:" + action.kind() + ":"
                            + (action.kind() == HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE
                            ? action.sourceRoute() : action.actionId())));
            return values.stream();
        }).toList();
        List<String> freshness = model.widgets().stream().map(widget ->
                widget.definitionKey() + ":" + freshnessClass(widget)).toList();
        return new Projection(
                model.mode(),
                model.view().source(),
                !"DEFAULT".equals(model.view().source()),
                canonicalJson.fingerprint(List.of(
                        model.view().composition(), model.view().deviceOverlay())),
                List.copyOf(apps),
                List.copyOf(widgets),
                List.copyOf(routesAndActions),
                model.shell().announcements().stream()
                        .map(HomeReadModelDtos.Announcement::kind).toList(),
                model.unavailableSources().stream().sorted().toList(),
                List.copyOf(freshness));
    }

    public Comparison compare(Projection legacy, Projection v2) {
        if (legacy == null || v2 == null) {
            return new Comparison(
                    ShadowOutcome.UNAVAILABLE, Set.of(ShadowReason.UNAVAILABLE), 1);
        }
        Set<ShadowReason> reasons = new LinkedHashSet<>();
        int mismatches = 0;
        if (!legacy.mode().equals(v2.mode())) {
            reasons.add(ShadowReason.MODE);
            mismatches++;
        }
        if (!legacy.viewSource().equals(v2.viewSource())
                || legacy.customized() != v2.customized()
                || !legacy.layoutFingerprint().equals(v2.layoutFingerprint())) {
            reasons.add(ShadowReason.LAYOUT);
            mismatches++;
        }
        if (!legacy.appDock().equals(v2.appDock())) {
            reasons.add(ShadowReason.APP_DOCK);
            mismatches++;
        }
        if (!legacy.widgets().equals(v2.widgets())) {
            reasons.add(authorityDifference(legacy.widgets(), v2.widgets())
                    ? ShadowReason.AUTHORITY : ShadowReason.WIDGET_STATE);
            mismatches++;
        }
        if (!legacy.routesAndActions().equals(v2.routesAndActions())) {
            reasons.add(ShadowReason.ROUTE_ACTION);
            mismatches++;
        }
        if (!legacy.announcementClasses().equals(v2.announcementClasses())
                || !legacy.unavailableReasons().equals(v2.unavailableReasons())) {
            reasons.add(ShadowReason.STRUCTURE);
            mismatches++;
        }
        if (!legacy.freshnessClasses().equals(v2.freshnessClasses())) {
            reasons.add(ShadowReason.FRESHNESS);
            mismatches++;
        }
        if (reasons.isEmpty()) {
            return new Comparison(ShadowOutcome.MATCH, Set.of(ShadowReason.MATCH), 0);
        }
        if (reasons.equals(Set.of(ShadowReason.FRESHNESS))) {
            return new Comparison(
                    ShadowOutcome.EXPECTED_TRANSIENT,
                    Set.of(ShadowReason.EXPECTED_TRANSIENT, ShadowReason.FRESHNESS),
                    mismatches);
        }
        return new Comparison(ShadowOutcome.MISMATCH, Set.copyOf(reasons), mismatches);
    }

    private String badgeClass(HomeReadModelDtos.Badge badge) {
        return (badge.total() > 0 ? "NON_ZERO" : "ZERO") + ":"
                + (badge.urgent() > 0 ? "URGENT" : "NORMAL") + ":"
                + (badge.version() == null ? "NONE" : "VERSIONED");
    }

    private String freshnessClass(HomeReadModelDtos.Widget widget) {
        return switch (widget.state()) {
            case STALE -> "STALE";
            case UNAVAILABLE -> "UNAVAILABLE";
            default -> "FRESH";
        };
    }

    private boolean authorityDifference(List<String> first, List<String> second) {
        return first.stream().anyMatch(value -> value.endsWith(":FORBIDDEN"))
                || second.stream().anyMatch(value -> value.endsWith(":FORBIDDEN"));
    }

    public record Projection(
            String mode,
            String viewSource,
            boolean customized,
            String layoutFingerprint,
            List<String> appDock,
            List<String> widgets,
            List<String> routesAndActions,
            List<String> announcementClasses,
            List<String> unavailableReasons,
            List<String> freshnessClasses) {
        public Projection {
            appDock = List.copyOf(appDock);
            widgets = List.copyOf(widgets);
            routesAndActions = List.copyOf(routesAndActions);
            announcementClasses = List.copyOf(announcementClasses);
            unavailableReasons = List.copyOf(unavailableReasons);
            freshnessClasses = List.copyOf(freshnessClasses);
        }
    }

    public record Comparison(
            ShadowOutcome outcome,
            Set<ShadowReason> reasons,
            int mismatchCount) {
        public Comparison {
            reasons = Set.copyOf(reasons);
        }
    }

    public enum ShadowOutcome {
        MATCH,
        EXPECTED_TRANSIENT,
        MISMATCH,
        UNAVAILABLE
    }

    public enum ShadowReason {
        MATCH,
        EXPECTED_TRANSIENT,
        STRUCTURE,
        AUTHORITY,
        MODE,
        LAYOUT,
        APP_DOCK,
        WIDGET_STATE,
        ROUTE_ACTION,
        FRESHNESS,
        UNAVAILABLE
    }
}
