package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class EmployeeChangeApprovalFeatureContextTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NamedParameterJdbcTemplate.class,
                    () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(EmployeeChangeApprovalTrustProfile.class);

    @Test
    void featureIsOffByDefaultAndDoesNotCreateTrustBoundary() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmployeeChangeApprovalTrustProfile.class);
        });
    }

    @Test
    void enabledFeatureFailsStartupWithoutRegisteredTrustMaterial() {
        runner.withPropertyValues("dwp.hris.employee-change.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
