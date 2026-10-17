package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseValidationCommandV1;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class TimeClosePeriodProvisioningGateTest {

    private static final long TENANT = 70_073L;
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("70073000-0000-4000-8000-000000000001");
    private static final UUID PERIOD =
            UUID.fromString("70073000-0000-4000-8000-000000000002");

    @Test
    void productionSurfaceHasNoPeriodCreatePortRouteJobOrMaterializer() {
        Set<String> repositoryMethods = Arrays.stream(
                        TimeCloseRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(repositoryMethods)
                .containsExactlyInAnyOrder(
                        "findPeriod", "findResult", "findHandoff", "findReceipt",
                        "queryProjectionRows", "execute", "applyOutcome");
        assertThat(repositoryMethods.stream().noneMatch(name ->
                name.contains("createPeriod")
                        || name.contains("provision")
                        || name.contains("materialize"))).isTrue();

        assertThat(Arrays.stream(TimeCloseOwnerController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .flatMap(method -> Arrays.stream(
                        method.getAnnotation(PostMapping.class).value()))
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "/v1/close-periods/{periodId}/validations",
                        "/v1/close-periods/{periodId}/close",
                        "/v1/close-periods/{periodId}/reopen",
                        "/v1/close-periods/{periodId}/payroll-handoffs")
                .doesNotContain("/v1/close-periods");

        assertClassAbsent("com.dwp.services.time.close.TimeClosePeriodMaterializer");
        assertClassAbsent("com.dwp.services.time.close.TimeClosePeriodProvisioner");
        assertClassAbsent("com.dwp.services.time.close.TimeClosePeriodScheduler");
        assertClassAbsent("com.dwp.services.time.close.TimeClosePeriodCreateController");
    }

    @Test
    void missingPeriodIsOpaqueAndCallsNoEvidenceApprovalOutboxOrHandoffBoundary() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseEvidenceSource evidenceSource = mock(TimeCloseEvidenceSource.class);
        TimeCloseApprovalVerifier approvalVerifier = mock(TimeCloseApprovalVerifier.class);
        TimeCloseStepUpVerifier stepUpVerifier = mock(TimeCloseStepUpVerifier.class);
        TimeCloseService service = new TimeCloseService(
                repository, evidenceSource, approvalVerifier, stepUpVerifier,
                Clock.fixed(NOW, ZoneOffset.UTC));
        VerifiedCloseRequest authority = authority();
        String idempotencyKey = "tim007-missing-period-0001";
        when(repository.findReceipt(
                TENANT, SUBJECT, authority.routeContractKey(), idempotencyKey))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TENANT, PERIOD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(
                authority, PERIOD, idempotencyKey, 1,
                new TimeCloseValidationCommandV1(NOW)))
                .isInstanceOfSatisfying(BaseException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(failure.getMessage())
                            .doesNotContain(PERIOD.toString(), Long.toString(TENANT));
                });

        verify(repository).findReceipt(
                TENANT, SUBJECT, authority.routeContractKey(), idempotencyKey);
        verify(repository).findPeriod(TENANT, PERIOD);
        verify(repository, never()).execute(any());
        verify(repository, never()).findResult(anyLong(), any(UUID.class));
        verify(repository, never()).findHandoff(anyLong(), any(UUID.class));
        verifyNoInteractions(evidenceSource, approvalVerifier, stepUpVerifier);
    }

    private static VerifiedCloseRequest authority() {
        return new VerifiedCloseRequest(
                TENANT,
                73,
                SUBJECT,
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_TIME_CLOSE:EXECUTE"),
                Set.of("HRIS_DUTY_TIME_CLOSE_OPERATOR"),
                CloseAction.VALIDATE,
                "route.hcm.operations.time-close-validate.action",
                "psc-" + "a".repeat(64),
                "hcm-scope-" + "b".repeat(40),
                "ELEVATED",
                UUID.fromString("70073000-0000-4000-8000-000000000003"),
                7,
                11,
                "c".repeat(64),
                UUID.fromString("70073000-0000-4000-8000-000000000004"),
                UUID.fromString("70073000-0000-4000-8000-000000000005"),
                13,
                17,
                "psr-" + "d".repeat(64),
                "WORK_TIME_PERIOD_CLOSURE",
                NOW.plusSeconds(300),
                UUID.fromString("70073000-0000-4000-8000-000000000006"),
                Set.of(),
                Set.of());
    }

    private static void assertClassAbsent(String className) {
        assertThatThrownBy(() -> Class.forName(className))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
