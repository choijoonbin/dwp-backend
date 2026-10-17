package com.dwp.services.payroll.input.closedtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClosedTimeSingleWriterBoundaryTest {

    @Test
    void mainPackageContainsNoRuntimeBindingTransportOrPersistenceCapability()
            throws Exception {
        Path source = Path.of(
                "src/main/java/com/dwp/services/payroll/input/closedtime");
        List<Path> files;
        try (var paths = Files.list(source)) {
            files = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(files).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder(
                        "ClosedTimeResultConsumerPort.java",
                        "ClosedTimeResultContractVerifier.java",
                        "ClosedTimeResultConsumerGate.java");

        String sources = files.stream()
                .map(ClosedTimeSingleWriterBoundaryTest::read)
                .reduce("", (left, right) -> left + '\n' + right);
        assertThat(sources)
                .doesNotContain("org.springframework")
                .doesNotContain("java.sql")
                .doesNotContain("JdbcTemplate")
                .doesNotContain("DataSource")
                .doesNotContain("EntityManager")
                .doesNotContain("KafkaListener")
                .doesNotContain("com.dwp.services.time")
                .doesNotContain("com.dwp.services.payroll.calculation")
                .doesNotContain("com.dwp.services.payroll.preparation");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to inspect " + path, exception);
        }
    }
}
