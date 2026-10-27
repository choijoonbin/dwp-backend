package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class Hrm013TimeTaskDispatchDefaultOffTest {
    @Test
    void everyRuntimeAdapterUsesTheSameExplicitDefaultOffGate() throws IOException {
        assertThat(application())
                .contains("enabled: ${DWP_TIME_HRM013_TASK_DISPATCH_ENABLED:false}")
                .contains("auth-authority-uri: ${DWP_TIME_HRM013_AUTH_AUTHORITY_URI:}")
                .contains("people-origin: ${DWP_TIME_HRM013_PEOPLE_ORIGIN:}");

        for (Class<?> type : List.of(
                Hrm013TimeTaskDispatchController.class,
                Hrm013TimeTaskDispatchService.class,
                Hrm013TimeTaskDispatchStore.class,
                Hrm013TimeTaskDispatchIngressFilter.class,
                AuthHrm013ServiceAuthorityClient.class,
                Hrm013TimeTaskReceiptRelay.class,
                Hrm013TimeWorkloadCredentialProvider.class)) {
            ConditionalOnProperty gate = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).as(type.getSimpleName()).isNotNull();
            assertThat(gate.prefix()).isEqualTo("dwp.time.hrm013-task-dispatch");
            assertThat(gate.name()).containsExactly("enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
            assertThat(gate.matchIfMissing()).isFalse();
        }
    }

    private static String application() throws IOException {
        Path path = Path.of("dwp-time-server/src/main/resources/application.yml");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return Files.readString(path);
    }
}
