package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompatibilityPeople360ProjectionPolicyProviderTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 17);
    private final CompatibilityPeople360ProjectionPolicyProvider provider =
            new CompatibilityPeople360ProjectionPolicyProvider();

    @Test
    void onlyExplicitCapabilitiesSelectAnOperationsArchetype() {
        assertThat(provider.operationsArchetype(
                new People360ProjectionPolicyProvider.Subject(
                        Set.of("HR_OPERATOR"), Set.of("DATA.WORKFORCE:AUDIT"))))
                .isEqualTo(People360Dtos.Archetype.AUDITOR);
        assertThat(provider.operationsArchetype(
                new People360ProjectionPolicyProvider.Subject(
                        Set.of("HR_AUDITOR"), Set.of("DATA.WORKFORCE:VIEW"))))
                .isEqualTo(People360Dtos.Archetype.HR_OPERATOR);
        assertThat(provider.operationsArchetype(
                new People360ProjectionPolicyProvider.Subject(
                        Set.of("HR_OPERATOR"), Set.of("DATA.WORKFORCE:VIEW"))))
                .isEqualTo(People360Dtos.Archetype.HR_OPERATOR);
    }

    @Test
    void roleLabelsWithoutCapabilityEvidenceFailClosed() {
        assertThatThrownBy(() -> provider.operationsArchetype(
                new People360ProjectionPolicyProvider.Subject(
                        Set.of("AUDITOR", "HR_AUDITOR", "WORKFORCE_AUDITOR"), Set.of())))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void listPurposeMinimizesEveryFieldOutsideTheSelectionContract() {
        People360ProjectionPolicyProvider.ProjectionPolicy policy = provider.resolve(
                input(People360ProjectionPolicyProvider.ProjectionPurpose.LIST));

        assertThat(policy.providerRevision())
                .isEqualTo(CompatibilityPeople360ProjectionPolicyProvider.PROVIDER_REVISION);
        assertThat(policy.fieldDecisions()).hasSize(22);
        assertThat(policy.fieldDecisions())
                .containsEntry("person.displayName", People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry("employment.workerStatus", People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry(
                        "primaryAssignment.businessTitle",
                        People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry("person.timeZone", People360Dtos.FieldDecisionValue.OMIT)
                .containsEntry(
                        "employment.workerNumber",
                        People360Dtos.FieldDecisionValue.OMIT)
                .containsEntry(
                        "primaryAssignment.jobGradeName",
                        People360Dtos.FieldDecisionValue.OMIT);
    }

    @Test
    void detailPolicyIsResolvedFromEffectiveContextAndFieldGroups() {
        People360ProjectionPolicyProvider.ProjectionPolicy policy = provider.resolve(
                input(People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL));

        assertThat(policy.fieldDecisions())
                .containsEntry(
                        "employment.workerNumber",
                        People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry(
                        "primaryAssignment.jobGradeName",
                        People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry(
                        "person.preferredLocale",
                        People360Dtos.FieldDecisionValue.OMIT);
    }

    @Test
    void directoryOnlyListPolicyCannotExposeEmploymentOrAssignmentFields() {
        People360ProjectionPolicyProvider.ProjectionPolicy policy = provider.resolve(
                new People360ProjectionPolicyProvider.PolicyInput(
                        7L, AS_OF,
                        People360ProjectionPolicyProvider.ProjectionPurpose.LIST,
                        People360Dtos.Archetype.HR_OPERATOR,
                        "directory-only-v2", Set.of("DIRECTORY")));

        assertThat(policy.fieldDecisions())
                .containsEntry(
                        "employment.workerStatus",
                        People360Dtos.FieldDecisionValue.OMIT)
                .containsEntry(
                        "primaryAssignment.businessTitle",
                        People360Dtos.FieldDecisionValue.OMIT);
    }

    @Test
    void directoryOnlyManagerDetailCannotExposeEmploymentOrAssignmentFields() {
        People360ProjectionPolicyProvider.ProjectionPolicy policy = provider.resolve(
                new People360ProjectionPolicyProvider.PolicyInput(
                        7L, AS_OF,
                        People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL,
                        People360Dtos.Archetype.MANAGER,
                        "directory-only-team-v3", Set.of("DIRECTORY")));

        assertThat(policy.fieldDecisions().entrySet())
                .filteredOn(entry -> entry.getKey().startsWith("employment.")
                        || entry.getKey().startsWith("primaryAssignment."))
                .allSatisfy(entry -> assertThat(entry.getValue())
                        .isEqualTo(People360Dtos.FieldDecisionValue.OMIT));
        assertThat(policy.fieldDecisions())
                .containsEntry(
                        "person.displayName",
                        People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry(
                        "person.lifecycleState",
                        People360Dtos.FieldDecisionValue.VIEW);
    }

    private People360ProjectionPolicyProvider.PolicyInput input(
            People360ProjectionPolicyProvider.ProjectionPurpose purpose) {
        return new People360ProjectionPolicyProvider.PolicyInput(
                7L, AS_OF, purpose, People360Dtos.Archetype.HR_OPERATOR,
                "policy-source-v3",
                Set.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS", "JOB_GRADE"));
    }
}
