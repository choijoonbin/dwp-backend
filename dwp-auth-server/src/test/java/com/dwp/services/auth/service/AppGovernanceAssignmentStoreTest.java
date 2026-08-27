package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppGovernanceAssignmentStoreTest {

    @Test
    void managerCannotOverlapApprovalOrReviewForAnyEffectiveUser() {
        assertThat(AppGovernanceAssignmentStore.conflictingResponsibilities(
                "APP_ACCESS_MANAGER"))
                .containsExactlyInAnyOrder(
                        "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER");
    }

    @Test
    void approvalAndReviewCannotOverlapFulfilment() {
        assertThat(AppGovernanceAssignmentStore.conflictingResponsibilities(
                "APP_ACCESS_APPROVER"))
                .containsExactly("APP_ACCESS_MANAGER");
        assertThat(AppGovernanceAssignmentStore.conflictingResponsibilities(
                "APP_ACCESS_REVIEWER"))
                .containsExactly("APP_ACCESS_MANAGER");
    }

    @Test
    void ownershipIsIndependentFromTheFulfilmentApprovalConflict() {
        assertThat(AppGovernanceAssignmentStore.conflictingResponsibilities(
                "APP_OWNER")).isEmpty();
    }
}
