package com.dwp.services.approval.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRecoveryAuditorOutboxCreationContractTest {

    @Test
    void commandCreationRecordsOnlyTheServerActorAndQueuesAsyncAssignment() {
        assertThat(ApprovalCommandSql02.APPEND_INTEGRATION_INSERT_APR_INTEGRATION_OUTBOX)
                .contains(
                        "event_originator_user_id, recovery_auditor_assignment_state",
                        ":userId, 'PENDING'")
                .doesNotContain(
                        ":assignedAuditor",
                        ":auditorUserId",
                        "recovery_auditor_assignment_revision)");
    }
}
