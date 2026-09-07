package com.dwp.services.platform.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRepositoryContractTest {

    @Test
    void genericStatusMutationOnlyAllowsWorkspaceOwnedTasks() {
        assertThat(WorkspaceRepository.UPDATE_WORK_STATUS_SQL)
                .contains("AND work_type = 'TASK'")
                .contains("AND source_system IN ('WORKSPACE', 'DWP_WORKSPACE')");
    }
}
