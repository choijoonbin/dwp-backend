package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Per008ReleasedAggregateReadContractTest {
    private static final UUID REQUEST = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID WORKER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void allAnonymityModesExposeOnlyMetadataAndNeverDecryptTheSnapshot() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        for (AnonymityMode mode : AnonymityMode.values()) {
            RequestRecord request = released(mode);
            Per008Repository repository = new Per008TestSupport.RepositoryStub() {
                @Override public RequestRecord request(AuthorityEvidence authority, UUID id,
                        boolean forUpdate) { return request; }
                @Override public Optional<ReleaseSnapshot> releaseSnapshot(
                        AuthorityEvidence authority, UUID id) {
                    return Optional.of(new ReleaseSnapshot(UUID.randomUUID(), REQUEST, null,
                            "d".repeat(64), "PerformanceFeedbackAggregate.v1/mean-v1",
                            request.aggregatedAt()));
                }
            };
            Per008AuthorityPort authority = ignored -> Per008TestSupport.evidence(
                    "self-feedback-release-reader", "per.feedback.aggregate.view",
                    "PERFORMANCE_FEEDBACK", Map.of(
                            "PERFORMANCE_FEEDBACK_AGGREGATE", FieldDecision.VIEW),
                    Set.of(new SubjectBinding(WORKER, ASSIGNMENT)));
            // Protection is deliberately null: a public release read must not decrypt owner evidence.
            Per008Service service = new Per008Service(authority, repository, null, null, mapper,
                    (Per008Service.OwnerLifecycleAuthorityPort) null,
                    Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));

            JsonNode wire = mapper.valueToTree(service.release(REQUEST));

            assertThat(wire.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "schemaKey", "aggregateSchemaKey", "feedbackRequestId", "requestState",
                    "anonymityMode", "policyRevision", "aggregatedAt", "releasedAt", "title",
                    "correlationId");
            assertThat(wire.toString()).doesNotContain("section", "response", "answer",
                    "narrative", "score", "count", "digest", "encryption", "contributor");
        }
    }

    private RequestRecord released(AnonymityMode mode) {
        OffsetDateTime aggregated = OffsetDateTime.parse("2026-11-02T00:00:00Z");
        return new RequestRecord(7, REQUEST, 5, UUID.randomUUID(), WORKER, ASSIGNMENT,
                UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), "Feedback",
                RequestState.RELEASED, mode, 2, UUID.randomUUID(), "privacy-7",
                "c".repeat(64), OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-01T00:00:00Z"),
                OffsetDateTime.parse("2026-09-01T00:00:00Z"), aggregated,
                OffsetDateTime.parse("2026-11-03T00:00:00Z"));
    }
}
