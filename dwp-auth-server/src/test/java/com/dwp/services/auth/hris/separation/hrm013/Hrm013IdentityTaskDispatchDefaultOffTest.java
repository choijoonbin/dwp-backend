package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class Hrm013IdentityTaskDispatchDefaultOffTest {
    @Test
    void applicationConfigurationKeepsIngressAndRelayDefaultOff() throws Exception {
        String yaml = Files.readString(Path.of(
                "src/main/resources/application.yml"));
        assertThat(yaml).contains(
                "enabled: ${DWP_AUTH_HRM013_IDENTITY_TASK_DISPATCH_ENABLED:false}",
                "receipt-relay-enabled: ${DWP_AUTH_HRM013_IDENTITY_RECEIPT_RELAY_ENABLED:false}");
        assertThat(yaml).doesNotContain(
                "DWP_AUTH_HRM013_IDENTITY_TASK_DISPATCH_ENABLED:true",
                "DWP_AUTH_HRM013_IDENTITY_RECEIPT_RELAY_ENABLED:true");
    }

    @Test
    void enabledRuntimeBeansAreConditionedOnTheDedicatedFlag() throws Exception {
        var method = Hrm013IdentityTaskDispatchRuntimeConfiguration.class
                .getDeclaredMethod("hrm013IdentityTaskDispatchStore",
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        org.springframework.transaction.PlatformTransactionManager.class,
                        com.fasterxml.jackson.databind.ObjectMapper.class,
                        java.time.Clock.class,
                        Hrm013IdentityWorkloadCredentialProvider.class,
                        com.dwp.services.auth.hrisservicegrant
                                .HrisServiceGrantQuiesceService.class);
        ConditionalOnProperty condition = method.getAnnotation(
                ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly(
                "dwp.auth.hrm013-identity-task-dispatch.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
