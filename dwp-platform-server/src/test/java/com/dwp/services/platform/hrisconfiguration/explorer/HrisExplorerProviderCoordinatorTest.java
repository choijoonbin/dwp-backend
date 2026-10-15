package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class HrisExplorerProviderCoordinatorTest {

    private HrisExplorerProviderCoordinator coordinator;

    @AfterEach
    void closeCoordinator() {
        if (coordinator != null) coordinator.close();
    }

    @Test
    void exactTypedEvidenceProducesMinimizedActivities() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        coordinator = coordinator(allProviders(target, false));
        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.degradedSources()).isEmpty();
        assertThat(projection.activities()).hasSize(5)
                .allSatisfy(activity -> {
                    assertThat(activity.target()).isEqualTo(target);
                    assertThat(activity.sourceRevision()).isEqualTo(31);
                });
    }

    @Test
    void xcon012Through015CompositionHasARealInjectedHappyPath() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("xcon012", query(
                "XCON-012", SourceKind.WORKFLOW_CASE, Module.PER, target));
        beans.addBean("xcon013", query(
                "XCON-013", SourceKind.SERVICE_REQUEST, Module.HRM, target));
        beans.addBean("xcon014", query(
                "XCON-014", SourceKind.OPERATION_EXCEPTION, Module.TIM, target));
        beans.addBean("xcon015", query(
                "XCON-015", SourceKind.APPROVAL_CASE, Module.PAY, target));
        var queries = beans.getBeanProvider(HrisExplorerXconOwnerEvidenceQuery.class);
        HrisExplorerOwnerProviderConfiguration configuration =
                new HrisExplorerOwnerProviderConfiguration();
        List<HrisExplorerOwnerEvidenceProvider> providers = new ArrayList<>(List.of(
                configuration.xcon012PerformanceExplorerProvider(queries),
                configuration.xcon013PeopleExplorerProvider(queries),
                configuration.xcon014TimeExplorerProvider(queries),
                configuration.xcon015PayrollExplorerProvider(queries)));
        providers.add(provider(SourceKind.COMMAND_RECEIPT, Module.SYS, target, false));
        coordinator = coordinator(providers);

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.degradedSources()).isEmpty();
        assertThat(projection.activities()).hasSize(5)
                .extracting(ExplorerActivity::sourceKind)
                .containsExactlyInAnyOrder(
                        SourceKind.COMMAND_RECEIPT, SourceKind.WORKFLOW_CASE,
                        SourceKind.SERVICE_REQUEST, SourceKind.OPERATION_EXCEPTION,
                        SourceKind.APPROVAL_CASE);
    }

    @Test
    void mismatchedOwnerScopeAndMissingProviderArePartialNotFalseEmpty() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(target, true);
        providers.removeLast();
        coordinator = coordinator(providers);
        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.activities()).hasSize(3);
        assertThat(projection.degradedSources()).hasSize(2)
                .extracting(DegradedSource::reason)
                .contains(DegradedReason.STALE, DegradedReason.UNAVAILABLE);
        assertThat(projection.degradedSources())
                .filteredOn(value -> value.sourceKind()
                        == SourceKind.OPERATION_EXCEPTION)
                .singleElement()
                .extracting(DegradedSource::module)
                .isEqualTo(Module.TIM);
    }

    @Test
    void unauthorizedInternalAndInactiveControlTargetsAreRejected() {
        ExplorerTarget allowed = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        ExplorerTarget arbitrary = new ExplorerTarget(
                TargetKind.HRIS_INTERNAL, "/hr/not-an-authorized-page");
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(allowed, false);
        providers.set(0, provider(SourceKind.COMMAND_RECEIPT, Module.SYS,
                arbitrary, false));
        coordinator = coordinator(providers);

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(allowed)));
        assertThat(projection.degradedSources())
                .extracting(DegradedSource::sourceKind)
                .contains(SourceKind.COMMAND_RECEIPT);
        assertThat(projection.activities())
                .noneMatch(activity -> activity.target().equals(arbitrary));
    }

    @Test
    void schemaInvalidNullActivityStateIsRejectedAsStale() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(target, false);
        providers.set(0, providerWithNullState(target));
        coordinator = coordinator(providers);

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.activities())
                .noneMatch(activity -> activity.sourceKind() == SourceKind.COMMAND_RECEIPT);
        assertThat(projection.degradedSources()).anySatisfy(value -> {
            assertThat(value.sourceKind()).isEqualTo(SourceKind.COMMAND_RECEIPT);
            assertThat(value.reason()).isEqualTo(DegradedReason.STALE);
        });
    }

    @Test
    void staleAndUnavailableEvidenceAreOmittedAndDegraded() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(target, false);
        providers.set(1, staleProvider(SourceKind.WORKFLOW_CASE, Module.PER, target));
        providers.set(2, failingProvider(SourceKind.APPROVAL_CASE, Module.PAY));
        coordinator = coordinator(providers);

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.activities())
                .noneMatch(value -> value.sourceKind() == SourceKind.WORKFLOW_CASE
                        || value.sourceKind() == SourceKind.APPROVAL_CASE);
        assertThat(projection.degradedSources())
                .anySatisfy(value -> {
                    assertThat(value.sourceKind()).isEqualTo(SourceKind.WORKFLOW_CASE);
                    assertThat(value.reason()).isEqualTo(DegradedReason.STALE);
                })
                .anySatisfy(value -> {
                    assertThat(value.sourceKind()).isEqualTo(SourceKind.APPROVAL_CASE);
                    assertThat(value.reason()).isEqualTo(DegradedReason.UNAVAILABLE);
                });
    }

    @Test
    void freshnessBoundaryEqualToEvaluationTimeIsStale() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(target, false);
        HrisExplorerOwnerEvidenceProvider delegate = providers.get(1);
        providers.set(1, new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return delegate.sourceKind();
            }

            @Override
            public Module module() {
                return delegate.module();
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                ProviderResult result = delegate.findInProgress(query);
                return new ProviderResult(
                        result.tenantId(), result.subjectPrincipalPublicId(),
                        result.contextScopeKey(), result.authorizationRevision(),
                        result.fieldPolicyRevision(), result.sourceRevision(),
                        result.generatedAt(), NOW, result.activities());
            }
        });
        coordinator = coordinator(providers);

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.degradedSources()).anySatisfy(value -> {
            assertThat(value.sourceKind()).isEqualTo(SourceKind.WORKFLOW_CASE);
            assertThat(value.reason()).isEqualTo(DegradedReason.STALE);
        });
    }

    @Test
    void boundedProviderTimeoutIsReportedWithoutFalseEmptySuccess() {
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        List<HrisExplorerOwnerEvidenceProvider> providers = allProviders(target, false);
        providers.set(3, slowProvider(SourceKind.SERVICE_REQUEST, Module.HRM));
        coordinator = new HrisExplorerProviderCoordinator(providers,
                new HrisExplorerRuntimeControls(
                        Duration.ofMillis(20), Duration.ofMinutes(5), 5));

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of(target)));

        assertThat(projection.activities()).hasSize(4);
        assertThat(projection.degradedSources())
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.sourceKind()).isEqualTo(SourceKind.SERVICE_REQUEST);
                    assertThat(value.reason()).isEqualTo(DegradedReason.TIMEOUT);
                });
    }

    @Test
    void providerFanOutUsesOneSharedBoundedDeadline() {
        List<HrisExplorerOwnerEvidenceProvider> providers = List.of(
                slowProvider(SourceKind.COMMAND_RECEIPT, Module.SYS),
                slowProvider(SourceKind.WORKFLOW_CASE, Module.PER),
                slowProvider(SourceKind.APPROVAL_CASE, Module.PAY),
                slowProvider(SourceKind.SERVICE_REQUEST, Module.HRM),
                slowProvider(SourceKind.OPERATION_EXCEPTION, Module.TIM));
        coordinator = new HrisExplorerProviderCoordinator(providers,
                new HrisExplorerRuntimeControls(
                        Duration.ofMillis(60), Duration.ofMinutes(5), 5));
        long started = System.nanoTime();

        HrisExplorerProviderCoordinator.ProviderProjection projection =
                coordinator.project(query(Set.of()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(projection.activities()).isEmpty();
        assertThat(projection.degradedSources()).hasSize(5)
                .allSatisfy(value -> assertThat(value.reason())
                        .isEqualTo(DegradedReason.TIMEOUT));
        assertThat(elapsed).isLessThan(Duration.ofMillis(250));
    }

    private HrisExplorerProviderCoordinator coordinator(
            List<HrisExplorerOwnerEvidenceProvider> providers) {
        return new HrisExplorerProviderCoordinator(providers,
                new HrisExplorerRuntimeControls(
                        Duration.ofMillis(500), Duration.ofMinutes(5), 5));
    }

    private List<HrisExplorerOwnerEvidenceProvider> allProviders(
            ExplorerTarget target, boolean oneMismatched) {
        List<HrisExplorerOwnerEvidenceProvider> values = new ArrayList<>();
        values.add(provider(SourceKind.COMMAND_RECEIPT, Module.SYS,
                target, false));
        values.add(provider(SourceKind.WORKFLOW_CASE, Module.PER,
                target, oneMismatched));
        values.add(provider(SourceKind.APPROVAL_CASE, Module.PAY,
                target, false));
        values.add(provider(SourceKind.SERVICE_REQUEST, Module.HRM,
                target, false));
        values.add(provider(SourceKind.OPERATION_EXCEPTION, Module.TIM,
                target, false));
        return values;
    }

    private HrisExplorerOwnerEvidenceProvider provider(
            SourceKind kind, Module module, ExplorerTarget target,
            boolean mismatchedScope) {
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return kind;
            }

            @Override
            public Module module() {
                return module;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                ExplorerActivity activity = new ExplorerActivity(
                        UUID.nameUUIDFromBytes(kind.name().getBytes()), kind, module,
                        ActivityState.IN_PROGRESS, 31, NOW.minusMinutes(1), target);
                return new ProviderResult(
                        query.tenantId(), query.subjectPrincipalPublicId(),
                        mismatchedScope ? "wrong-scope" : query.contextScopeKey(),
                        query.authorizationRevision(), query.fieldPolicyRevision(),
                        31, NOW.minusMinutes(1), NOW.plusMinutes(1), List.of(activity));
            }
        };
    }

    private HrisExplorerXconOwnerEvidenceQuery query(
            String contractKey, SourceKind kind, Module module,
            ExplorerTarget target) {
        return new HrisExplorerXconOwnerEvidenceQuery() {
            @Override
            public String contractKey() {
                return contractKey;
            }

            @Override
            public HrisExplorerOwnerEvidenceProvider.ProviderResult findInProgress(
                    HrisExplorerOwnerEvidenceProvider.ProviderQuery query) {
                ExplorerActivity activity = new ExplorerActivity(
                        UUID.nameUUIDFromBytes(contractKey.getBytes()), kind, module,
                        ActivityState.IN_PROGRESS, 31, NOW.minusMinutes(1), target);
                return new HrisExplorerOwnerEvidenceProvider.ProviderResult(
                        query.tenantId(), query.subjectPrincipalPublicId(),
                        query.contextScopeKey(), query.authorizationRevision(),
                        query.fieldPolicyRevision(), 31, NOW.minusMinutes(1),
                        NOW.plusMinutes(1), List.of(activity));
            }
        };
    }

    private HrisExplorerOwnerEvidenceProvider staleProvider(
            SourceKind kind, Module module, ExplorerTarget target) {
        HrisExplorerOwnerEvidenceProvider delegate = provider(kind, module, target, false);
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return kind;
            }

            @Override
            public Module module() {
                return module;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                ProviderResult value = delegate.findInProgress(query);
                return new ProviderResult(
                        value.tenantId(), value.subjectPrincipalPublicId(),
                        value.contextScopeKey(), value.authorizationRevision(),
                        value.fieldPolicyRevision(), value.sourceRevision(),
                        value.generatedAt(), NOW.minusSeconds(1), value.activities());
            }
        };
    }

    private HrisExplorerOwnerEvidenceProvider failingProvider(
            SourceKind kind, Module module) {
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return kind;
            }

            @Override
            public Module module() {
                return module;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                throw new IllegalStateException("owner unavailable");
            }
        };
    }

    private HrisExplorerOwnerEvidenceProvider providerWithNullState(
            ExplorerTarget target) {
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return SourceKind.COMMAND_RECEIPT;
            }

            @Override
            public Module module() {
                return Module.SYS;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                ExplorerActivity activity = new ExplorerActivity(
                        UUID.randomUUID(), SourceKind.COMMAND_RECEIPT,
                        Module.SYS,
                        null, 31, NOW.minusMinutes(1), target);
                return new ProviderResult(
                        query.tenantId(), query.subjectPrincipalPublicId(),
                        query.contextScopeKey(), query.authorizationRevision(),
                        query.fieldPolicyRevision(), 31, NOW.minusMinutes(1),
                        NOW.plusMinutes(1), List.of(activity));
            }
        };
    }

    private HrisExplorerOwnerEvidenceProvider slowProvider(
            SourceKind kind, Module module) {
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return kind;
            }

            @Override
            public Module module() {
                return module;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
    }

    private HrisExplorerOwnerEvidenceProvider.ProviderQuery query(
            Set<ExplorerTarget> allowedTargets) {
        return new HrisExplorerOwnerEvidenceProvider.ProviderQuery(
                42, SUBJECT, SCOPE, POPULATION, "HRIS_APP_DISCOVERY",
                11, 7, NOW, NOW,
                HrisExplorerV43RouteAdapter.deployedRouteKeys(), allowedTargets);
    }
}
