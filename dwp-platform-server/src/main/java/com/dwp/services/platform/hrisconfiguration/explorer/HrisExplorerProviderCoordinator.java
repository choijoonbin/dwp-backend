package com.dwp.services.platform.hrisconfiguration.explorer;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

@Component
final class HrisExplorerProviderCoordinator {

    private static final Set<SourceKind> EXPECTED = EnumSet.of(
            SourceKind.COMMAND_RECEIPT, SourceKind.WORKFLOW_CASE,
            SourceKind.APPROVAL_CASE, SourceKind.SERVICE_REQUEST,
            SourceKind.OPERATION_EXCEPTION);

    private final Map<SourceKind, HrisExplorerOwnerEvidenceProvider> providers;
    private final HrisExplorerRuntimeControls controls;
    private final ExecutorService executor;

    HrisExplorerProviderCoordinator(
            List<HrisExplorerOwnerEvidenceProvider> providers,
            HrisExplorerRuntimeControls controls) {
        Map<SourceKind, HrisExplorerOwnerEvidenceProvider> indexed = new LinkedHashMap<>();
        for (HrisExplorerOwnerEvidenceProvider provider : providers) {
            if (provider == null || provider.sourceKind() == null
                    || provider.module() == null
                    || provider.sourceKind() == SourceKind.CATALOG
                    || indexed.putIfAbsent(provider.sourceKind(), provider) != null) {
                throw new IllegalStateException("Duplicate/invalid Explorer owner provider");
            }
        }
        this.providers = Map.copyOf(indexed);
        this.controls = controls;
        this.executor = Executors.newFixedThreadPool(controls.maxProviderConcurrency(), task -> {
            Thread thread = new Thread(task, "hris-explorer-owner-provider");
            thread.setDaemon(true);
            return thread;
        });
    }

    ProviderProjection project(HrisExplorerOwnerEvidenceProvider.ProviderQuery query) {
        List<ExplorerActivity> activities = new ArrayList<>();
        List<DegradedSource> degraded = new ArrayList<>();
        Map<SourceKind, CompletableFuture<TimedProviderResult>>
                futures = new LinkedHashMap<>();
        long deadline = System.nanoTime()
                + controls.providerTimeout().toNanos();
        for (SourceKind kind : EXPECTED) {
            HrisExplorerOwnerEvidenceProvider provider = providers.get(kind);
            if (provider != null) {
                futures.put(kind, CompletableFuture.supplyAsync(
                        () -> new TimedProviderResult(
                                provider.findInProgress(query), System.nanoTime()), executor));
            }
        }
        for (SourceKind kind : EXPECTED) {
            HrisExplorerOwnerEvidenceProvider provider = providers.get(kind);
            if (provider == null) {
                degraded.add(new DegradedSource(
                        defaultModule(kind), kind, DegradedReason.UNAVAILABLE, true));
                continue;
            }
            CompletableFuture<TimedProviderResult> future =
                    futures.get(kind);
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 && !future.isDone()) throw new TimeoutException();
                TimedProviderResult timed = future.get(
                        Math.max(0, remaining), TimeUnit.NANOSECONDS);
                if (timed.completedAtNanos() > deadline) throw new TimeoutException();
                HrisExplorerOwnerEvidenceProvider.ProviderResult result = timed.result();
                if (!valid(provider, result, query)) {
                    degraded.add(new DegradedSource(
                            provider.module(), kind, DegradedReason.STALE, true));
                    continue;
                }
                activities.addAll(result.activities());
            } catch (TimeoutException exception) {
                future.cancel(true);
                degraded.add(new DegradedSource(
                        provider.module(), kind, DegradedReason.TIMEOUT, true));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                degraded.add(new DegradedSource(
                        provider.module(), kind, DegradedReason.UNAVAILABLE, true));
            } catch (ExecutionException exception) {
                DegradedReason reason = exception.getCause()
                        instanceof HrisExplorerProviderEvidenceException evidence
                        ? evidence.reason() : DegradedReason.UNAVAILABLE;
                degraded.add(new DegradedSource(
                        provider.module(), kind, reason, true));
            } catch (RuntimeException exception) {
                degraded.add(new DegradedSource(
                        provider.module(), kind, DegradedReason.UNAVAILABLE, true));
            }
        }
        List<ExplorerActivity> bounded = activities.stream()
                .sorted(java.util.Comparator.comparing(ExplorerActivity::updatedAt).reversed()
                        .thenComparing(value -> value.activityId().toString()))
                .limit(20).toList();
        return new ProviderProjection(bounded, List.copyOf(degraded));
    }

    private boolean valid(
            HrisExplorerOwnerEvidenceProvider provider,
            HrisExplorerOwnerEvidenceProvider.ProviderResult result,
            HrisExplorerOwnerEvidenceProvider.ProviderQuery query) {
        if (result == null || result.sourceRevision() < 1
                || result.tenantId() != query.tenantId()
                || !java.util.Objects.equals(
                result.subjectPrincipalPublicId(), query.subjectPrincipalPublicId())
                || !java.util.Objects.equals(
                result.contextScopeKey(), query.contextScopeKey())
                || result.authorizationRevision() != query.authorizationRevision()
                || result.fieldPolicyRevision() != query.fieldPolicyRevision()
                || result.generatedAt() == null || result.freshUntil() == null
                || result.activities() == null
                || result.activities().size() > 20
                || query.evidenceEvaluatedAt() == null
                || result.generatedAt().isAfter(
                query.evidenceEvaluatedAt().plus(controls.providerTimeout()))
                || !result.freshUntil().isAfter(result.generatedAt())
                || !result.freshUntil().isAfter(query.evidenceEvaluatedAt())
                || result.freshUntil().isAfter(
                result.generatedAt().plus(controls.providerFreshness()))
                || !result.generatedAt().plus(controls.providerFreshness())
                .isAfter(query.evidenceEvaluatedAt())) {
            return false;
        }
        for (ExplorerActivity activity : result.activities()) {
            if (activity == null || activity.activityId() == null
                    || activity.sourceKind() != provider.sourceKind()
                    || activity.module() == null
                    || !validModule(provider, activity)
                    || activity.state() == null
                    || activity.sourceRevision() != result.sourceRevision()
                    || activity.updatedAt() == null
                    || activity.updatedAt().isAfter(query.asOf())
                    || activity.target() == null
                    || !validTarget(activity.target(), query.allowedTargets())) {
                return false;
            }
        }
        return true;
    }

    private boolean validModule(
            HrisExplorerOwnerEvidenceProvider provider,
            ExplorerActivity activity) {
        return activity.module() == provider.module();
    }

    private boolean validTarget(ExplorerTarget target, Set<ExplorerTarget> allowedTargets) {
        if (target.kind() == null || target.path() == null
                || target.path().isBlank() || !target.path().equals(target.path().strip())) {
            return false;
        }
        if (!allowedTargets.contains(target)) return false;
        if (target.kind() == TargetKind.HRIS_INTERNAL) {
            return target.path().matches("^/hr(?:/|$).*$");
        }
        HrisExplorerCatalog.CatalogNode governance = HrisExplorerCatalog.node(
                HrisExplorerV43RouteAdapter.OPERATIONAL_DWP_CONTROL_ROUTE_KEY);
        return governance != null && governance.target().equals(target);
    }

    private Module defaultModule(SourceKind kind) {
        return switch (kind) {
            case WORKFLOW_CASE -> Module.PER;
            case SERVICE_REQUEST -> Module.HRM;
            case APPROVAL_CASE -> Module.PAY;
            case OPERATION_EXCEPTION -> Module.TIM;
            case COMMAND_RECEIPT, CATALOG -> Module.SYS;
        };
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    record ProviderProjection(
            List<ExplorerActivity> activities,
            List<DegradedSource> degradedSources) {
    }

    private record TimedProviderResult(
            HrisExplorerOwnerEvidenceProvider.ProviderResult result,
            long completedAtNanos) {
    }
}
