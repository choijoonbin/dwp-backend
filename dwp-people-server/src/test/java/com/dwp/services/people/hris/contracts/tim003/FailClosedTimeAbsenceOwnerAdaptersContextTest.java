package com.dwp.services.people.hris.contracts.tim003;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FailClosedTimeAbsenceOwnerAdaptersContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    FailClosedTimeAbsenceOwnerAdapters.class,
                    TimeAbsenceOwnerIngressFilter.class);

    @Test
    void registersEveryFailClosedPortAndConstructsIngressFilter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TimeAbsenceOwnerPorts.WorkloadProofVerifier.class);
            assertThat(context).hasSingleBean(TimeAbsenceOwnerPorts.IngressDenialAudit.class);
            assertThat(context).hasSingleBean(TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority.class);
            assertThat(context).hasSingleBean(TimeAbsenceOwnerPorts.ResponseSigner.class);
            assertThat(context).hasSingleBean(TimeAbsenceOwnerPorts.Audit.class);
            assertThat(context).hasSingleBean(TimeAbsenceOwnerIngressFilter.class);
        });
    }

    @Test
    void keepsAnAdmittedVerifierInsteadOfAddingTheFailClosedFallback() {
        TimeAbsenceOwnerPorts.WorkloadProofVerifier admitted =
                (request, ownerRequest, exactPath, queryFingerprint) -> null;

        contextRunner
                .withBean(TimeAbsenceOwnerPorts.WorkloadProofVerifier.class, () -> admitted)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(TimeAbsenceOwnerPorts.WorkloadProofVerifier.class);
                    assertThat(context.getBean(TimeAbsenceOwnerPorts.WorkloadProofVerifier.class))
                            .isSameAs(admitted);
                    assertThat(context).hasSingleBean(TimeAbsenceOwnerIngressFilter.class);
                });
    }

    @Test
    void selectsRuntimeConstructorsForTheFactProviderAndOwnerService() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        FailClosedTimeAbsenceOwnerAdapters.class,
                        JdbcTimeAbsenceOwnerFactProvider.class,
                        TimeAbsenceOwnerCanonicalJson.class,
                        TimeAbsenceOwnerService.class)
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TimeAbsenceOwnerPorts.ReplayReservation.class,
                        () -> request -> false)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            TimeAbsenceOwnerPorts.OwnerFactProvider.class);
                    assertThat(context).hasSingleBean(TimeAbsenceOwnerService.class);
                });
    }
}
