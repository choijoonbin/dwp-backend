package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmploymentContractControllerTest {
    private static final UUID CORRELATION = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");

    @Test
    void expectedVersionRejectsNonCanonicalDuplicateAndOverflowForms() {
        EmploymentContractService service = mock(EmploymentContractService.class);
        EmploymentContractController controller = new EmploymentContractController(service);

        for (String invalid : List.of(
                "+1", "01", " 1", "1,2", "9223372036854775808")) {
            assertThatThrownBy(() -> issue(controller, invalid))
                    .isInstanceOf(EmploymentContractException.class)
                    .satisfies(error -> assertThat(
                            ((EmploymentContractException) error).status()).isEqualTo(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void canonicalExpectedVersionIsParsedOnceAndBoundToStepUp() {
        EmploymentContractService service = mock(EmploymentContractService.class);
        EmploymentContractController controller = new EmploymentContractController(service);
        CommandReceiptV1 receipt = new CommandReceiptV1(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "EMPLOYMENT_CONTRACT_ISSUE", "ISSUE_EMPLOYMENT_CONTRACT",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "a".repeat(64), 1, "HR_LIFECYCLE", 1, "RUNNING",
                CORRELATION, "b".repeat(64), Instant.parse("2026-10-19T03:00:00Z"));
        when(service.issue(any(), any(), any(), eq(1L), any(), any(), any()))
                .thenReturn(new EmploymentContractService.CommandResult(receipt, 1));

        issue(controller, "1");

        verify(service).issue(any(), any(), any(), eq(1L), eq(CORRELATION), any(),
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.expectedObjectVersion() == 1L));
    }

    private void issue(EmploymentContractController controller, String expectedVersion) {
        controller.issue(
                "Bearer signed-token", "employment-contract-key-001", "\"1\"",
                CORRELATION.toString(), null, "signed-challenge", "psr-v1",
                expectedVersion, new EmploymentContractDtos.IssueRequest(
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        1, "ko-KR", LocalDate.parse("2026-10-19"), null,
                        "c".repeat(64)));
    }
}
