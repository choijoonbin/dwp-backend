package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisExplorerCommandReceiptOwnerEvidenceProviderTest {

    private static final UUID RECEIPT =
            UUID.fromString("00000000-0000-4000-8000-000000000901");

    @Test
    void emitsActualReceiptStoreRevisionAndServerFreshnessEvidence() {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        when(repository.findActiveReceiptActivities(
                42, SUBJECT, Set.of("hris.explorer.team-time"), NOW))
                .thenReturn(projection(73, 11, 7));
        HrisExplorerCommandReceiptOwnerEvidenceProvider provider = provider(repository);

        HrisExplorerOwnerEvidenceProvider.ProviderResult result =
                provider.findInProgress(query(target));

        assertThat(result.sourceRevision()).isEqualTo(73);
        assertThat(result.generatedAt()).isEqualTo(NOW.minusSeconds(2));
        assertThat(result.freshUntil()).isEqualTo(NOW.plusMinutes(5).minusSeconds(2));
        assertThat(result.activities()).singleElement().satisfies(activity -> {
            assertThat(activity.activityId()).isEqualTo(RECEIPT);
            assertThat(activity.sourceRevision()).isEqualTo(73);
            assertThat(activity.state()).isEqualTo(ActivityState.RESULT_UNKNOWN);
            assertThat(activity.module()).isEqualTo(Module.SYS);
            assertThat(activity.target()).isEqualTo(target);
        });
    }

    @Test
    void mismatchedReceiptAuthorityIsStaleRatherThanFalseEmpty() {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        when(repository.findActiveReceiptActivities(
                42, SUBJECT, Set.of("hris.explorer.team-time"), NOW))
                .thenReturn(projection(73, 12, 7));

        assertThatThrownBy(() -> provider(repository).findInProgress(query(target)))
                .isInstanceOfSatisfying(
                        HrisExplorerProviderEvidenceException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DegradedReason.STALE));
    }

    @Test
    void mismatchedReceiptPopulationOrPurposeIsStaleRatherThanFalseEmpty() {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        ExplorerTarget target = HrisExplorerCatalog.node(
                "hris.explorer.team-time").target();
        when(repository.findActiveReceiptActivities(
                42, SUBJECT, Set.of("hris.explorer.team-time"), NOW))
                .thenReturn(projection(
                                73, 11, 7, "c".repeat(64),
                                "HRIS_APP_DISCOVERY"),
                        projection(73, 11, 7, POPULATION, "OTHER_PURPOSE"));

        assertThatThrownBy(() -> provider(repository).findInProgress(query(target)))
                .isInstanceOfSatisfying(
                        HrisExplorerProviderEvidenceException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DegradedReason.STALE));
        assertThatThrownBy(() -> provider(repository).findInProgress(query(target)))
                .isInstanceOfSatisfying(
                        HrisExplorerProviderEvidenceException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DegradedReason.STALE));
    }

    private HrisExplorerCommandReceiptOwnerEvidenceProvider provider(
            HrisExplorerRepository repository) {
        return new HrisExplorerCommandReceiptOwnerEvidenceProvider(
                repository,
                new HrisExplorerRuntimeControls(
                        Duration.ofMillis(500), Duration.ofMinutes(5), 5));
    }

    private HrisExplorerRepository.ReceiptActivityProjection projection(
            long sourceRevision, long authorizationRevision,
            long fieldPolicyRevision) {
        return projection(sourceRevision, authorizationRevision,
                fieldPolicyRevision, AUTHORITY_POPULATION,
                "HRIS_APP_DISCOVERY");
    }

    private HrisExplorerRepository.ReceiptActivityProjection projection(
            long sourceRevision, long authorizationRevision,
            long fieldPolicyRevision, String populationScopeDigest,
            String purposeCode) {
        return new HrisExplorerRepository.ReceiptActivityProjection(
                sourceRevision, NOW.minusSeconds(2), List.of(
                new HrisExplorerRepository.ReceiptActivityRow(
                        RECEIPT, "RESULT_UNKNOWN", "route:hris.explorer.team-time",
                        populationScopeDigest, purposeCode,
                        authorizationRevision, fieldPolicyRevision,
                        NOW.minusMinutes(2), NOW.minusMinutes(1))));
    }

    private HrisExplorerOwnerEvidenceProvider.ProviderQuery query(
            ExplorerTarget target) {
        return new HrisExplorerOwnerEvidenceProvider.ProviderQuery(
                42, SUBJECT, SCOPE, AUTHORITY_POPULATION,
                "HRIS_APP_DISCOVERY",
                11, 7, NOW, NOW,
                Set.of("hris.explorer.team-time"), Set.of(target));
    }
}
