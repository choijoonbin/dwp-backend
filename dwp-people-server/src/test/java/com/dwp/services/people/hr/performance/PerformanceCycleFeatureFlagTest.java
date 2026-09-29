package com.dwp.services.people.hr.performance;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PerformanceCycleFeatureFlagTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void controllerIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(
                context.getBeansOfType(PerformanceCycleController.class)).isEmpty());
    }

    @Test
    void enabledControllerFailsClosedWithoutCanonicalAuthorityPort() {
        contextRunner.withPropertyValues("dwp.hris.performance.wave1.enabled=true")
                .run(context -> {
                    PerformanceCycleController controller = context.getBean(
                            PerformanceCycleController.class);
                    PerformanceCycleQueryRepository queries = context.getBean(
                            PerformanceCycleQueryRepository.class);
                    PeopleRequestContext.set(
                            19L, 77L, UUID.randomUUID(), Set.of("ADMIN"), Set.of(
                                    "APP.HRIS:VIEW",
                                    "DATA.HR_TALENT:VIEW"));
                    try {
                        assertThatThrownBy(controller::cycles)
                                .isInstanceOf(BaseException.class)
                                .extracting(error -> ((BaseException) error).getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                        verifyNoInteractions(queries);
                    } finally {
                        PeopleRequestContext.clear();
                    }
                    PerformanceParticipantPreviewService preview = context.getBean(
                            PerformanceParticipantPreviewService.class);
                    assertThatThrownBy(() -> preview.prepare(
                            77L,
                            Instant.parse("2027-04-01T00:00:00Z"),
                            UUID.randomUUID()))
                            .isInstanceOf(BaseException.class)
                            .extracting(error -> ((BaseException) error).getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            PerformanceCycleController.class,
            PerformanceCycleService.class,
            PerformanceParticipantPreviewService.class})
    static class TestConfiguration {

        @Bean
        PerformanceCycleQueryRepository performanceCycleQueryRepository() {
            return mock(PerformanceCycleQueryRepository.class);
        }

        @Bean
        PerformanceCycleCommandRepository performanceCycleCommandRepository() {
            return mock(PerformanceCycleCommandRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        AuditOutboxRecorder auditOutboxRecorder() {
            return mock(AuditOutboxRecorder.class);
        }
    }
}
