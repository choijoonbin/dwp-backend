package com.dwp.services.people.hr;

import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import com.dwp.services.people.workforce.WorkforceExportDtos;
import com.dwp.services.people.workforce.WorkforceExportService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HcmMutationTransactionContractTest {

    @Test
    void populationBoundMutationsOwnTheTransactionThatKeepsAllProofLocks() throws Exception {
        assertRequiredMutation(HrService.class.getMethod(
                "decideTimeCard", UUID.class, HrDtos.DecisionRequest.class, String.class));
        assertRequiredMutation(HrService.class.getMethod(
                "decideTeamTimeCard", UUID.class, HrDtos.DecisionRequest.class, String.class));
        assertRequiredMutation(HrService.class.getMethod(
                "decideLeaveRequest", UUID.class, HrDtos.DecisionRequest.class, String.class));
        assertRequiredMutation(HrService.class.getMethod(
                "decideTeamLeaveRequest", UUID.class, HrDtos.DecisionRequest.class, String.class));
        assertRequiredMutation(WorkforceExportService.class.getMethod(
                "create", WorkforceExportDtos.CreateRequest.class, String.class,
                HcmStepUpHeaders.class));
        assertRequiredMutation(WorkforceExportService.class.getMethod(
                "cancel", UUID.class, WorkforceExportDtos.DecisionRequest.class, String.class));
        assertRequiredMutation(WorkforceExportService.class.getMethod(
                "retry", UUID.class, WorkforceExportDtos.DecisionRequest.class, String.class,
                HcmStepUpHeaders.class));
        assertRequiredMutation(WorkforceAccessPolicyService.class.getMethod(
                "findForMutation", String.class));
    }

    private void assertRequiredMutation(Method method) {
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertThat(transaction)
                .as("%s must own or join the mutation transaction", method)
                .isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(transaction.readOnly()).isFalse();
    }
}
