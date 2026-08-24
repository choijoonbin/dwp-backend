package com.dwp.services.platform.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRepositoryContractTest {

    @Test
    void genericStatusMutationRetainsDatabaseDefenseForOwnerManagedReviews() {
        assertThat(WorkspaceRepository.UPDATE_WORK_STATUS_SQL)
                .contains("AND work_type <> 'REVIEW'")
                .contains("AND source_system <> 'IDENTITY_GOVERNANCE'");
    }
}
