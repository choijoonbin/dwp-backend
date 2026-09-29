package com.dwp.services.people.hr.performance;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;

import java.io.IOException;
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
}
