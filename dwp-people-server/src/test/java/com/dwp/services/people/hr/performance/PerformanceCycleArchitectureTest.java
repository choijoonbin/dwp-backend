package com.dwp.services.people.hr.performance;

import com.dwp.services.people.security.HcmStepUpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCycleArchitectureTest {

    @Test
    void updateUsesCanonicalPatchTransport() throws NoSuchMethodException {
        PatchMapping mapping = PerformanceCycleController.class.getMethod(
                "update", UUID.class, String.class, String.class,
                PerformanceCycleDtos.UpdateCycleRequest.class)
                .getAnnotation(PatchMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/cycles/{cycleId}");
    }

    @Test
    void publishRequiresCanonicalStepUpHeaders() throws NoSuchMethodException {
        Method publish = PerformanceCycleController.class.getMethod(
                "publish", UUID.class, String.class, String.class,
                String.class, String.class, Long.class,
                PerformanceCycleDtos.PublishCycleRequest.class);

        assertRequiredHeader(publish, 3, HcmStepUpHeaders.CHALLENGE);
        assertRequiredHeader(publish, 4, HcmStepUpHeaders.DECISION_REVISION);
        assertRequiredHeader(publish, 5, HcmStepUpHeaders.EXPECTED_OBJECT_VERSION);
    }

    @Test
    void performancePackageDoesNotReadCrossOwnedHrmTables() throws IOException {
        Path source = Path.of("src/main/java/com/dwp/services/people/hr/performance");
        String code;
        try (var files = Files.walk(source)) {
            code = files.filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase(Locale.ROOT);
        }

        assertThat(code)
                .doesNotContain(" from ppl_")
                .doesNotContain(" join ppl_")
                .doesNotContain(" from hrm_")
                .doesNotContain(" join hrm_")
                .doesNotContain(" from tal_")
                .doesNotContain(" join tal_")
                .doesNotContain("com.dwp.services.people.hr.hrrepository")
                .contains("workforcesnapshotqueryport");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertRequiredHeader(Method method, int parameter, String name) {
        RequestHeader header = method.getParameters()[parameter]
                .getAnnotation(RequestHeader.class);
        assertThat(header).isNotNull();
        assertThat(header.value()).isEqualTo(name);
        assertThat(header.required()).isTrue();
    }
}
