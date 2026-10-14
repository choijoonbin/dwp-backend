package com.dwp.services.people.hris.performance.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceReviewSealedContractTest {

    @Test
    void consumesTheSealedReviewTransportAndMinimizedEventWithoutShadowFields()
            throws Exception {
        JsonNode people = new ObjectMapper().readTree(Files.readString(
                repositoryRoot().resolve("contracts/openapi/people.json")));
        JsonNode assigned = people.path("components").path("schemas")
                .path("AssignedReviewV1");
        assertThat(assigned.path("additionalProperties").asBoolean()).isFalse();
        assertThat(assigned.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "reviewId", "participantId", "reviewerAssignmentId",
                        "reviewerType", "stageKey", "state", "templateVersionId",
                        "templateContentHash", "aggregateVersion", "writable",
                        "draftContentHash", "form", "answers");

        JsonNode event = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(
                repositoryRoot().resolve("contracts/asyncapi/domain-events.yaml")))
                .path("components").path("schemas")
                .path("PerformanceReviewSubmittedData");
        assertThat(event.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(event.path("required")).extracting(JsonNode::asText)
                .containsExactly(
                        "reviewId", "participantId", "reviewerAssignmentId",
                        "submissionId", "templateVersionId", "contentHash");
        assertThat(event.path("properties").propertyStream()
                .map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "reviewId", "participantId", "reviewerAssignmentId",
                        "submissionId", "templateVersionId", "contentHash");
    }

    @Test
    void iaq022RemainsASeparateCycleOnlyDiscoverySurface() throws Exception {
        JsonNode schemas = new ObjectMapper().readTree(Files.readString(
                repositoryRoot().resolve("contracts/openapi/people.json")))
                .path("components").path("schemas");
        JsonNode actions = schemas.path("Iaq022PerformanceMultiraterSurveyPageV1")
                .path("properties").path("availableActions").path("items").path("enum");
        assertThat(actions).extracting(JsonNode::asText).containsExactlyInAnyOrder(
                "hris.action.per.create-cycle",
                "hris.action.per.update-cycle-draft",
                "hris.action.per.validate-cycle");
        assertThat(actions).extracting(JsonNode::asText).doesNotContain(
                PerformanceReviewService.SAVE_ACTION,
                PerformanceReviewService.SUBMIT_ACTION);

        Path source = repositoryRoot().resolve(
                "dwp-people-server/src/main/java/com/dwp/services/people/hris/performance/review");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).as(file.toString())
                        .doesNotContain("/workbench/multirater-surveys")
                        .doesNotContain("hris.action.per.create-cycle")
                        .doesNotContain("hris.action.per.update-cycle-draft")
                        .doesNotContain("hris.action.per.validate-cycle");
            }
        }
    }

    private static Path repositoryRoot() {
        Path working = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(working.resolve("contracts")) ? working : working.getParent();
    }
}
