package com.dwp.services.people.hris.contracts.workforce.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CompensationPopulationContractTest {

    private static final Instant AS_OF = Instant.parse("2026-10-13T00:00:00Z");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CORRELATION_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void additiveGuardRevalidatesAuthorityBeforeProviderAndAuthenticatesIssuedCursor() {
        TestVerifier verifier = new TestVerifier();
        List<CompensationPopulationLine> complete = List.of(line("11", "21"));
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, complete);
        String nextCursor = verifier.issue(
                SNAPSHOT_ID, 7, AS_OF, fullDigest,
                CompensationPopulationSnapshotPage.canonicalPosition(complete.getFirst()));
        AtomicInteger providerCalls = new AtomicInteger();
        WorkforceSnapshotProvider provider = compensationProvider(request -> {
            assertThat(verifier.requestChecks()).isEqualTo(1);
            providerCalls.incrementAndGet();
            return CompensationPopulationSnapshotPage.verified(
                    request.snapshotPublicId(), request.ownerRevision(), request.asOf(),
                    request.projection(), request.purposeCode(), fullDigest,
                    complete, nextCursor);
        });
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(verifier, provider);

        CompensationPopulationSnapshotPage page =
                port.queryCompensationPopulation(query(null));

        assertThat(page.lines()).containsExactlyElementsOf(complete);
        assertThat(page.fullPopulationDigest()).isEqualTo(fullDigest);
        assertThat(page.pageDigest()).matches("[0-9a-f]{64}");
        assertThat(providerCalls).hasValue(1);
        assertThat(verifier.issuedCursorChecks()).isEqualTo(1);
    }

    @Test
    void unavailableCompensationAuthorityFailsClosedBeforeProviderAccess() {
        AtomicInteger providerCalls = new AtomicInteger();
        WorkforceSnapshotRequestVerifier legacyVerifier = legacyVerifier();
        WorkforceSnapshotProvider provider = compensationProvider(request -> {
            providerCalls.incrementAndGet();
            throw new AssertionError("provider must not run without explicit authority support");
        });
        WorkforceSnapshotQueryPort port =
                WorkforceSnapshotQueryPorts.guarded(legacyVerifier, provider);

        assertThatThrownBy(() -> port.queryCompensationPopulation(query(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority verification is unavailable");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void verifiedRequestMustDeriveFromEveryConsumerSuppliedField() {
        CompensationPopulationSnapshotQuery query = query("opaque-cursor");
        AtomicInteger providerCalls = new AtomicInteger();
        WorkforceSnapshotRequestVerifier forged = new WorkforceSnapshotRequestVerifier() {
            @Override
            public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery ignored) {
                throw new AssertionError("legacy verifier must not run");
            }

            @Override
            public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
                    VerifiedWorkforceSnapshotRequest request,
                    WorkforceSnapshotPage page) {
                throw new AssertionError("legacy cursor verifier must not run");
            }

            @Override
            public VerifiedCompensationPopulationRequest verifyCompensationPopulation(
                    CompensationPopulationSnapshotQuery ignored) {
                return new VerifiedCompensationPopulationRequest(
                        1, 11, 13, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        CORRELATION_ID, UUID.randomUUID(), query.ownerRevision(), query.asOf(),
                        query.projection(), query.purposeCode(), query.limit(),
                        "a".repeat(64),
                        "22222222-2222-2222-2222-222222222222:"
                                + "11111111-1111-1111-1111-111111111111",
                        query.cursorTokenDigest());
            }
        };
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                forged, compensationProvider(request -> {
                    providerCalls.incrementAndGet();
                    throw new AssertionError("provider must not run for forged authority output");
                }));

        assertThatThrownBy(() -> port.queryCompensationPopulation(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not derive");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void cursorBindsSnapshotRevisionPurposeDigestAndLastCanonicalKey() {
        TestVerifier verifier = new TestVerifier();
        CompensationPopulationLine value = line("11", "21");
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, List.of(value));
        String cursor = verifier.issue(
                SNAPSHOT_ID, 7, AS_OF, "f".repeat(64),
                CompensationPopulationSnapshotPage.canonicalPosition(value));
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                verifier,
                compensationProvider(request -> CompensationPopulationSnapshotPage.verified(
                        request.snapshotPublicId(), request.ownerRevision(), request.asOf(),
                        request.projection(), request.purposeCode(), fullDigest,
                        List.of(value), cursor)));

        assertThatThrownBy(() -> port.queryCompensationPopulation(query(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("population digest");
    }

    @Test
    void everyContinuationPageMustKeepTheAuthenticatedFullPopulationDigest() {
        TestVerifier verifier = new TestVerifier();
        CompensationPopulationLine first = line("11", "21");
        CompensationPopulationLine second = line("12", "22");
        String originalDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, List.of(first, second));
        String cursor = verifier.issue(
                SNAPSHOT_ID, 7, AS_OF, originalDigest,
                CompensationPopulationSnapshotPage.canonicalPosition(first));
        String driftedDigest = "e".repeat(64);
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                verifier,
                compensationProvider(request -> CompensationPopulationSnapshotPage.verified(
                        request.snapshotPublicId(), request.ownerRevision(), request.asOf(),
                        request.projection(), request.purposeCode(), driftedDigest,
                        List.of(second), null)));

        assertThatThrownBy(() -> port.queryCompensationPopulation(query(cursor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed during compensation pagination");
    }

    @Test
    void assignmentIdentityCannotRepeatAcrossAContinuationBoundary() {
        TestVerifier verifier = new TestVerifier();
        CompensationPopulationLine first = line("11", "21");
        CompensationPopulationLine repeatedAssignment = line("12", "21");
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, List.of(first));
        String cursor = verifier.issue(
                SNAPSHOT_ID, 7, AS_OF, fullDigest,
                CompensationPopulationSnapshotPage.canonicalPosition(first));
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                verifier,
                compensationProvider(request -> CompensationPopulationSnapshotPage.verified(
                        request.snapshotPublicId(), request.ownerRevision(), request.asOf(),
                        request.projection(), request.purposeCode(), fullDigest,
                        List.of(repeatedAssignment), null)));

        assertThatThrownBy(() -> port.queryCompensationPopulation(query(cursor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique across compensation pages");
    }

    @Test
    void pageAndLineAreClosedContentAddressedCanonicalAndDefensivelyCopied() {
        CompensationPopulationLine first = line("11", "21");
        CompensationPopulationLine second = line("12", "22");
        List<CompensationPopulationLine> mutable =
                new ArrayList<>(List.of(first, second));
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, mutable);
        CompensationPopulationSnapshotPage page = CompensationPopulationSnapshotPage.verified(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, mutable, null);
        mutable.clear();

        assertThat(page.lines()).containsExactly(first, second);
        assertThatThrownBy(() -> page.lines().add(first))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.eligibleComponentCodes().add("BONUS"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CompensationPopulationSnapshotPage.verified(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, List.of(second, first), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CompensationPopulationSnapshotPage.ORDERING_VERSION);
        CompensationPopulationLine duplicateAssignment = line("13", "21");
        assertThatThrownBy(() -> CompensationPopulationSnapshotPage.verified(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, List.of(first, duplicateAssignment), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignmentPublicId");
        assertThatThrownBy(() -> new CompensationPopulationSnapshotPage(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, "a".repeat(64), List.of(first), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageDigest");
    }

    @Test
    void digestsUseOnlyTheSealedRfc8785CanonicalContentAndPageFields() {
        CompensationPopulationLine value = line("11", "21");
        List<CompensationPopulationLine> lines = List.of(value);
        String expectedContent = "{\"asOf\":\"2026-10-13T00:00:00Z\","
                + "\"hrmOwnerRevision\":7,\"lines\":[{"
                + "\"assignmentOwnerRevision\":9,"
                + "\"assignmentPublicId\":\"00000000-0000-0000-0000-000000000021\","
                + "\"eligibleComponentCodes\":[\"BASE\",\"BONUS\"],"
                + "\"gradePublicId\":null,\"jobProfilePublicId\":null,"
                + "\"organizationPublicId\":\"00000000-0000-0000-0000-000000000031\","
                + "\"workerPublicId\":\"00000000-0000-0000-0000-000000000011\"}]}";
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(AS_OF, 7, lines);
        String expectedPage = "{\"asOf\":\"2026-10-13T00:00:00Z\","
                + "\"fullPopulationDigest\":\"" + fullDigest + "\","
                + "\"lines\":[{\"assignmentOwnerRevision\":9,"
                + "\"assignmentPublicId\":\"00000000-0000-0000-0000-000000000021\","
                + "\"eligibleComponentCodes\":[\"BASE\",\"BONUS\"],"
                + "\"gradePublicId\":null,\"jobProfilePublicId\":null,"
                + "\"organizationPublicId\":\"00000000-0000-0000-0000-000000000031\","
                + "\"workerPublicId\":\"00000000-0000-0000-0000-000000000011\"}],"
                + "\"nextCursor\":null,\"ownerRevision\":7,"
                + "\"projection\":\"COMPENSATION_POPULATION_V1\","
                + "\"purposeCode\":\"HRIS_PER_COMPENSATION_POPULATION\","
                + "\"snapshotPublicId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}";

        assertThat(new String(CompensationPopulationCanonicalJson.fullPopulationContent(
                AS_OF, 7, lines), StandardCharsets.UTF_8)).isEqualTo(expectedContent);
        assertThat(fullDigest)
                .isEqualTo("9a77efd2cc8c87166a8e55bbb567eb93df792349007639518b2963d066080d69");
        assertThat(new String(CompensationPopulationCanonicalJson.page(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, lines, null), StandardCharsets.UTF_8)).isEqualTo(expectedPage);
        assertThat(CompensationPopulationSnapshotPage.calculatePageDigest(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                fullDigest, lines, null))
                .isEqualTo("9e0a611fdd8aaf42f531ae2ea14f2a0bff7539ff5ed8656b1515cca9db062ec1");
    }

    @Test
    void publicRequestHasNoTenantAuthorityAndRejectsOpenOrInvalidVariants() {
        CompensationPopulationSnapshotQuery query = query(null);

        assertThat(recordFields(CompensationPopulationSnapshotQuery.class).keySet())
                .containsExactly(
                        "snapshotPublicId", "ownerRevision", "asOf", "projection",
                        "purposeCode", "limit", "cursorToken")
                .doesNotContain("tenantId", "callerModule");
        assertThatThrownBy(() -> new CompensationPopulationSnapshotQuery(
                SNAPSHOT_ID, 7, AS_OF, WorkforceSnapshotProjection.PERFORMANCE_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompensationPopulationSnapshotQuery(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                "OTHER", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompensationPopulationSnapshotQuery(
                SNAPSHOT_ID, 0, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompensationPopulationSnapshotQuery(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE, 501, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(query.cursorTokenDigest()).isNull();
    }

    @Test
    void canonicalSchemaMatchesTheSealedRequestPageAndLineShapes() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(schemaPath()));

        assertShape(schema, "CompensationPopulationSnapshotQuery",
                CompensationPopulationSnapshotQuery.class);
        assertShape(schema, "VerifiedCompensationPopulationRequest",
                VerifiedCompensationPopulationRequest.class);
        assertShape(schema, "VerifiedCompensationPopulationCursor",
                VerifiedCompensationPopulationCursor.class);
        assertShape(schema, "CompensationPopulationSnapshotPage",
                CompensationPopulationSnapshotPage.class);
        assertShape(schema, "CompensationPopulationLine", CompensationPopulationLine.class);
        JsonNode amendment = schema.path("x-port").path("compensationPopulation");
        assertThat(amendment.path("method").asText())
                .isEqualTo("queryCompensationPopulation");
        assertThat(amendment.path("projection").asText())
                .isEqualTo("COMPENSATION_POPULATION_V1");
        assertThat(amendment.path("purposeCode").asText())
                .isEqualTo("HRIS_PER_COMPENSATION_POPULATION");
        assertThat(amendment.path("cursorBinding"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "tenantId", "callerModule", "snapshotPublicId", "ownerRevision",
                        "asOf", "projection", "purposeCode", "fullPopulationDigest",
                        "lastEmittedAssignmentPublicId", "lastEmittedWorkerPublicId");
        assertThat(schema.path("$defs").path("CompensationPopulationSnapshotQuery")
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("$defs").path("CompensationPopulationSnapshotPage")
                .path("properties").path("lines").path("maxItems").asInt()).isEqualTo(500);
        assertThat(schema.path("$defs").path("CompensationPopulationLine")
                .path("properties").path("eligibleComponentCodes")
                .path("maxItems").asInt()).isEqualTo(1_000);
    }

    private static CompensationPopulationSnapshotQuery query(String cursor) {
        return new CompensationPopulationSnapshotQuery(
                SNAPSHOT_ID, 7, AS_OF,
                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                CompensationPopulationSnapshotQuery.PURPOSE_CODE, 500, cursor);
    }

    private static CompensationPopulationLine line(
            String workerSuffix,
            String assignmentSuffix) {
        return new CompensationPopulationLine(
                uuid(workerSuffix), uuid(assignmentSuffix), uuid("31"),
                null, null, 9, List.of("BASE", "BONUS"));
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
    }

    private static WorkforceSnapshotProvider compensationProvider(
            java.util.function.Function<VerifiedCompensationPopulationRequest,
                    CompensationPopulationSnapshotPage> function) {
        return new WorkforceSnapshotProvider() {
            @Override
            public WorkforceSnapshotPage query(VerifiedWorkforceSnapshotRequest request) {
                throw new AssertionError("legacy provider must not run");
            }

            @Override
            public CompensationPopulationSnapshotPage queryCompensationPopulation(
                    VerifiedCompensationPopulationRequest request) {
                return function.apply(request);
            }
        };
    }

    private static WorkforceSnapshotRequestVerifier legacyVerifier() {
        return new WorkforceSnapshotRequestVerifier() {
            @Override
            public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery query) {
                throw new AssertionError("legacy verifier must not run");
            }

            @Override
            public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
                    VerifiedWorkforceSnapshotRequest request,
                    WorkforceSnapshotPage page) {
                throw new AssertionError("legacy cursor verifier must not run");
            }
        };
    }

    private static Map<String, Class<?>> recordFields(Class<?> type) {
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            fields.put(component.getName(), component.getType());
        }
        return fields;
    }

    private static void assertShape(JsonNode schema, String definition, Class<?> type) {
        assertThat(schema.path("$defs").path(definition).path("properties")
                .propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyElementsOf(recordFields(type).keySet());
    }

    private static Path schemaPath() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("contracts"))
                ? workingDirectory
                : workingDirectory.getParent();
        return repositoryRoot.resolve(
                "contracts/in-process/hris/workforce-snapshot-query-port.v1.schema.json");
    }

    private record CursorClaims(
            UUID snapshotPublicId,
            long ownerRevision,
            Instant asOf,
            String fullPopulationDigest,
            String position) {
    }

    private static final class TestVerifier implements WorkforceSnapshotRequestVerifier {

        private final Map<String, CursorClaims> issued = new LinkedHashMap<>();
        private final AtomicInteger requestChecks = new AtomicInteger();
        private final AtomicInteger issuedCursorChecks = new AtomicInteger();

        @Override
        public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery query) {
            throw new AssertionError("legacy verifier must not run");
        }

        @Override
        public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
                VerifiedWorkforceSnapshotRequest request,
                WorkforceSnapshotPage page) {
            throw new AssertionError("legacy cursor verifier must not run");
        }

        @Override
        public VerifiedCompensationPopulationRequest verifyCompensationPopulation(
                CompensationPopulationSnapshotQuery query) {
            requestChecks.incrementAndGet();
            if (query.cursorToken() == null) {
                return verified(query, null, null, null);
            }
            CursorClaims claims = authenticate(query.cursorToken());
            requireQueryBindings(query, claims);
            return verified(
                    query, claims.fullPopulationDigest(), claims.position(),
                    query.cursorTokenDigest());
        }

        @Override
        public VerifiedCompensationPopulationCursor verifyIssuedCompensationPopulationCursor(
                VerifiedCompensationPopulationRequest request,
                CompensationPopulationSnapshotPage page) {
            issuedCursorChecks.incrementAndGet();
            CursorClaims claims = authenticate(page.nextCursor());
            return new VerifiedCompensationPopulationCursor(
                    request.tenantId(), request.callerModule(), claims.snapshotPublicId(),
                    claims.ownerRevision(), claims.asOf(), request.projection(),
                    request.purposeCode(), claims.fullPopulationDigest(), claims.position(),
                    WorkforceSnapshotQuery.digestToken(page.nextCursor()));
        }

        String issue(
                UUID snapshotPublicId,
                long ownerRevision,
                Instant asOf,
                String fullPopulationDigest,
                String position) {
            String token = "authenticated-compensation-cursor-" + (issued.size() + 1);
            issued.put(token, new CursorClaims(
                    snapshotPublicId, ownerRevision, asOf, fullPopulationDigest, position));
            return token;
        }

        int requestChecks() {
            return requestChecks.get();
        }

        int issuedCursorChecks() {
            return issuedCursorChecks.get();
        }

        private CursorClaims authenticate(String token) {
            CursorClaims claims = issued.get(token);
            if (claims == null) {
                throw new IllegalArgumentException("cursor authentication failed");
            }
            return claims;
        }

        private static void requireQueryBindings(
                CompensationPopulationSnapshotQuery query,
                CursorClaims claims) {
            if (!claims.snapshotPublicId().equals(query.snapshotPublicId())
                    || claims.ownerRevision() != query.ownerRevision()
                    || !claims.asOf().equals(query.asOf())) {
                throw new IllegalArgumentException("cursor binding is invalid");
            }
        }

        private static VerifiedCompensationPopulationRequest verified(
                CompensationPopulationSnapshotQuery query,
                String fullPopulationDigest,
                String position,
                String cursorTokenDigest) {
            return new VerifiedCompensationPopulationRequest(
                    1, 11, 13, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                    CORRELATION_ID, query.snapshotPublicId(), query.ownerRevision(), query.asOf(),
                    query.projection(), query.purposeCode(), query.limit(),
                    fullPopulationDigest, position, cursorTokenDigest);
        }
    }
}
