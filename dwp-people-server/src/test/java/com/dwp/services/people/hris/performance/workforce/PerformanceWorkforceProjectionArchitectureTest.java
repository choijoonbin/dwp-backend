package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class PerformanceWorkforceProjectionArchitectureTest {

    private static final List<String> CONSUMER_TYPES = List.of(
            "WorkforceSnapshotQueryPort",
            "WorkforceSnapshotQuery",
            "WorkforceSnapshotPage",
            "WorkforceSnapshotV1",
            "WorkforceSnapshotProjection",
            "WorkforceSnapshotContract");

    @Test
    void perUsesOnlyTheSealedConsumerSurfaceAndNoSelfHttpOrOwnerPersistence()
            throws IOException {
        Path main = repositoryRoot().resolve(
                "dwp-people-server/src/main/java/com/dwp/services/people/hris/performance");
        Path workforce = main.resolve("workforce");
        try (var sources = Files.walk(main)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                assertThat(text)
                        .as(source.toString())
                        .doesNotContain("com.dwp.services.people.hris.people.")
                        .doesNotContain("com.dwp.services.people.hris.employment.")
                        .doesNotContain("com.dwp.services.people.hris.organization.")
                        .doesNotContain("com.dwp.services.people.hris.employeeservice.")
                        .doesNotContain("WorkforceSnapshotProvider")
                        .doesNotContain("WorkforceSnapshotRequestVerifier")
                        .doesNotContain("VerifiedWorkforceSnapshot")
                        .doesNotContain("WorkforceSnapshotQueryPorts")
                        .doesNotContain("org.springframework.web.client")
                        .doesNotContain("org.springframework.web.reactive.function.client")
                        .doesNotContain("ppl_")
                        .doesNotContain("hrm_")
                        .doesNotContain("prf_population_freezes")
                        .doesNotContain("prf_population_freeze_members");
                // The workforce projection and every direct workforce-contract consumer must
                // use the in-process port. PER-004's signed Platform transport is a separate
                // owner boundary; the persistence restrictions above still cover all PER code.
                if (source.startsWith(workforce)
                        || text.contains("com.dwp.services.people.hris.contracts.workforce.v1.")) {
                    assertThat(text)
                            .as("workforce consumer transport in %s", source)
                            .doesNotContain("java.net.http");
                }
                text.lines()
                        .filter(line -> line.startsWith(
                                "import com.dwp.services.people.hris.contracts.workforce.v1."))
                        .forEach(line -> assertThat(CONSUMER_TYPES)
                                .as("consumer-visible import in %s", source)
                                .contains(typeName(line)));
            }
        }
    }

    @Test
    void projectionIsExactlyTheSevenFieldsSealedBySvcPepHrm002AndXcon001()
            throws IOException {
        assertThat(PerformanceWorkforceProjection.Member.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "workerPublicId",
                        "assignmentPublicId",
                        "status",
                        "organizationPublicId",
                        "managerAssignmentPublicId",
                        "jobProfilePublicId",
                        "gradePublicId");

        JsonNode schema = new ObjectMapper().readTree(Files.readString(repositoryRoot().resolve(
                "contracts/in-process/hris/workforce-snapshot-query-port.v1.schema.json")));
        JsonNode workforce = schema.path("$defs").path("WorkforceSnapshotV1");
        assertThat(workforce.path("additionalProperties").asBoolean()).isFalse();
        assertThat(workforce.path("required").size()).isEqualTo(7);
        assertThat(workforce.path("properties").size()).isEqualTo(7);
        assertThat(workforce.path("properties").path("status")
                .path("x-authoritativePointer").asText())
                .isEqualTo("contracts/hris/canonical/schemas/"
                        + "cross-module-canonical-schemas.v1.json#/$defs/"
                        + "XCON_001/properties/workerStatus");
    }

    private static String typeName(String importLine) {
        int dot = importLine.lastIndexOf('.');
        return importLine.substring(dot + 1, importLine.length() - 1);
    }

    private static Path repositoryRoot() {
        Path working = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(working.resolve("contracts")) ? working : working.getParent();
    }
}
