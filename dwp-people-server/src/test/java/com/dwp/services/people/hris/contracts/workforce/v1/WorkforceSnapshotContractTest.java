package com.dwp.services.people.hris.contracts.workforce.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkforceSnapshotContractTest {

    private static final Instant AS_OF = Instant.parse("2026-09-11T00:00:00Z");
    private static final String POSITION =
            "22222222-2222-2222-2222-222222222222:"
                    + "11111111-1111-1111-1111-111111111111";
    private static final String SCHEMA_SHA256 =
            "848d526cf6789350bbc6a85648b265be3de85c545c50643516c28bd9c659d178";
    private static final String SCHEMA_SEMANTICS_SHA256 =
            "b5fb2775cf41d186343a0cf5a6a4535a5d6be23703232ab7ebe011dda3810778";

    @Test
    void publicQueryContainsNoCallerSuppliedTenantOrCallerAuthority() {
        WorkforceSnapshotQuery firstPage = query(null);
        WorkforceSnapshotQuery nextPage = query("opaque-cursor");

        assertThat(firstPage.purposeCode()).isEqualTo(WorkforceSnapshotQuery.PURPOSE_CODE);
        assertThat(firstPage.projection()).isEqualTo(WorkforceSnapshotProjection.PERFORMANCE_V1);
        assertThat(firstPage.cursorToken()).isNull();
        assertThat(firstPage.cursorTokenDigest()).isNull();
        assertThat(nextPage.cursorTokenDigest()).matches("[0-9a-f]{64}");
        assertThat(recordFields(WorkforceSnapshotQuery.class).keySet())
                .containsExactly("purposeCode", "asOf", "projection", "limit", "cursorToken")
                .doesNotContain("tenantId", "callerModule", "ownerRevision");
        assertThat(WorkforceSnapshotQueryPort.CONTRACT_VERSION)
                .isEqualTo(WorkforceSnapshotContract.VERSION)
                .isEqualTo("v1");
    }

    @Test
    void queryAndVerifiedRequestFailClosedOnInvalidOrMismatchedValues() {
        assertThatThrownBy(() -> new WorkforceSnapshotQuery(
                "OTHER", AS_OF, WorkforceSnapshotProjection.PERFORMANCE_V1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkforceSnapshotQuery(
                WorkforceSnapshotQuery.PURPOSE_CODE, null,
                WorkforceSnapshotProjection.PERFORMANCE_V1, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkforceSnapshotQuery(
                WorkforceSnapshotQuery.PURPOSE_CODE, AS_OF, null, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkforceSnapshotQuery(
                WorkforceSnapshotQuery.PURPOSE_CODE, AS_OF,
                WorkforceSnapshotProjection.PERFORMANCE_V1, 501, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("가".repeat(WorkforceSnapshotQuery.MAX_CURSOR_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 bytes");

        WorkforceSnapshotQuery query = query("valid-token");
        assertThatThrownBy(() -> verified(query, 0L, POSITION, query.cursorTokenDigest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified(query, 7L, null, query.cursorTokenDigest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified(query, 7L, POSITION, "bad-digest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified(
                new WorkforceSnapshotQuery(
                        WorkforceSnapshotQuery.PURPOSE_CODE,
                        AS_OF.plusSeconds(1),
                        WorkforceSnapshotProjection.PERFORMANCE_V1,
                        500,
                        "valid-token"),
                7L,
                POSITION,
                query.cursorTokenDigest()).verifyDerivedFrom(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not derive");
    }

    @Test
    void guardVerifiesAuthorityBeforeProviderAndIssuedCursorAfterPage() {
        TestAuthorityVerifier verifier = new TestAuthorityVerifier();
        AtomicInteger providerCalls = new AtomicInteger();
        String nextCursor = verifier.issue(
                1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1", AS_OF, 7, POSITION);
        WorkforceSnapshotProvider provider = request -> {
            assertThat(verifier.requestChecks()).isEqualTo(1);
            providerCalls.incrementAndGet();
            return WorkforceSnapshotPage.verified(
                    request.tenantId(), request.callerModule(), request.purposeCode(),
                    request.projection(), request.asOf(), 7, List.of(snapshot()), nextCursor);
        };
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(verifier, provider);

        WorkforceSnapshotPage page = port.query(query(null));

        assertThat(page.snapshots()).containsExactly(snapshot());
        assertThat(providerCalls).hasValue(1);
        assertThat(verifier.issuedCursorChecks()).isEqualTo(1);
    }

    @Test
    void issuedCursorMustBindTheLastEmittedCanonicalKey() {
        TestAuthorityVerifier verifier = new TestAuthorityVerifier();
        String wrongPosition =
                "33333333-3333-3333-3333-333333333333:"
                        + "11111111-1111-1111-1111-111111111111";
        String cursor = verifier.issue(
                1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1",
                AS_OF, 7, wrongPosition);
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                verifier,
                request -> WorkforceSnapshotPage.verified(
                        request.tenantId(), request.callerModule(), request.purposeCode(),
                        request.projection(), request.asOf(), 7, List.of(snapshot()), cursor));

        assertThatThrownBy(() -> port.query(query(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last canonical key");
    }

    @Test
    void authenticatedCursorReplayOrTamperIsRejectedBeforeProviderAccess() {
        TestAuthorityVerifier verifier = new TestAuthorityVerifier();
        AtomicInteger providerCalls = new AtomicInteger();
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                verifier, request -> {
                    providerCalls.incrementAndGet();
                    throw new AssertionError("provider must not run for a rejected cursor");
                });
        String valid = verifier.issue(
                1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1", AS_OF, 7, POSITION);
        List<String> rejected = List.of(
                verifier.issue(2, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1", AS_OF, 7, POSITION),
                verifier.issue(1, "HRIS-PAY", WorkforceSnapshotQuery.PURPOSE_CODE,
                        "PERFORMANCE_V1", AS_OF, 7, POSITION),
                verifier.issue(1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        "OTHER_PURPOSE", "PERFORMANCE_V1", AS_OF, 7, POSITION),
                verifier.issue(1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        WorkforceSnapshotQuery.PURPOSE_CODE, "OTHER_PROJECTION", AS_OF, 7, POSITION),
                verifier.issue(1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1",
                        AS_OF.plusSeconds(1), 7, POSITION),
                verifier.issue(1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        WorkforceSnapshotQuery.PURPOSE_CODE, "PERFORMANCE_V1", AS_OF, 8, POSITION),
                valid.substring(0, valid.length() - 1)
                        + (valid.endsWith("0") ? "1" : "0"));

        for (String cursor : rejected) {
            assertThatThrownBy(() -> port.query(query(cursor)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void forgedVerifierOutputIsRejectedBeforeProviderAccess() {
        WorkforceSnapshotQuery query = query("cursor");
        AtomicInteger providerCalls = new AtomicInteger();
        WorkforceSnapshotRequestVerifier forged = new WorkforceSnapshotRequestVerifier() {
            @Override
            public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery ignored) {
                return new VerifiedWorkforceSnapshotRequest(
                        1, 11, 13, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                        UUID.randomUUID(), WorkforceSnapshotQuery.PURPOSE_CODE,
                        WorkforceSnapshotProjection.PERFORMANCE_V1, AS_OF.plusSeconds(1),
                        query.limit(), 7L, POSITION, query.cursorTokenDigest());
            }

            @Override
            public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
                    VerifiedWorkforceSnapshotRequest request, WorkforceSnapshotPage page) {
                throw new AssertionError("issued cursor check must not run");
            }
        };
        WorkforceSnapshotQueryPort port = WorkforceSnapshotQueryPorts.guarded(
                forged, request -> {
                    providerCalls.incrementAndGet();
                    throw new AssertionError("provider must not run");
                });

        assertThatThrownBy(() -> port.query(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not derive");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void pageIsContentAddressedBoundAndDefensivelyCopied() {
        WorkforceSnapshotQuery query = query(null);
        VerifiedWorkforceSnapshotRequest request = verified(query, null, null, null);
        List<WorkforceSnapshotV1> mutable = new ArrayList<>(List.of(snapshot()));
        WorkforceSnapshotPage page = WorkforceSnapshotPage.verified(
                1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF, 7, mutable, null);
        mutable.clear();

        page.verifyFor(request);
        assertThat(page.snapshots()).containsExactly(snapshot());
        assertThat(page.payloadDigest()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> page.snapshots().add(snapshot()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new WorkforceSnapshotPage(
                1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF, 7, "a".repeat(64), List.of(snapshot()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadDigest");
        WorkforceSnapshotPage otherTenant = WorkforceSnapshotPage.verified(
                2, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF, 7, List.of(snapshot()), null);
        assertThatThrownBy(() -> otherTenant.verifyFor(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void ownerExtensionApiIsPublicButConsumerPackagesCannotUseIt() throws IOException {
        List<Class<?>> ownerExtensions = List.of(
                WorkforceSnapshotRequestVerifier.class,
                WorkforceSnapshotProvider.class,
                VerifiedWorkforceSnapshotRequest.class,
                VerifiedWorkforceSnapshotCursor.class,
                WorkforceSnapshotQueryPorts.class);
        assertThat(ownerExtensions).allSatisfy(type ->
                assertThat(Modifier.isPublic(type.getModifiers())).isTrue());
        assertThat(Modifier.isPublic(GuardedWorkforceSnapshotQueryPort.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isFinal(WorkforceSnapshotQueryPort.class.getModifiers())).isTrue();
        WorkforceSnapshotQueryPort guarded = WorkforceSnapshotQueryPorts.guarded(
                new TestAuthorityVerifier(), request -> WorkforceSnapshotPage.verified(
                        request.tenantId(), request.callerModule(), request.purposeCode(),
                        request.projection(), request.asOf(), 7, List.of(), null));
        assertThat(guarded).isExactlyInstanceOf(WorkforceSnapshotQueryPort.class);
        assertThat(guarded.enforcementType())
                .isEqualTo(GuardedWorkforceSnapshotQueryPort.class);

        Path mainSource = repositoryRoot().resolve("dwp-people-server/src/main/java");
        try (var sources = Files.walk(mainSource)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertOwnerExtensionUsageAllowed(
                        mainSource.relativize(source), Files.readString(source));
            }
        }
        Path compiledConsumers = repositoryRoot().resolve(
                "dwp-people-server/build/classes/java/main/"
                        + "com/dwp/services/people/hris/performance");
        if (Files.isDirectory(compiledConsumers)) {
            try (var classes = Files.walk(compiledConsumers)) {
                for (Path bytecode : classes.filter(
                        path -> path.toString().endsWith(".class")).toList()) {
                    assertConsumerBytecode(Files.readAllBytes(bytecode));
                }
            }
        }

        assertOwnerExtensionUsageAllowed(
                Path.of("com/dwp/services/people/hris/performance/AllowedConsumer.java"),
                "import " + WorkforceSnapshotQueryPort.class.getName() + ";");
        assertOwnerExtensionUsageAllowed(
                Path.of("com/dwp/services/people/hris/people/AllowedOwner.java"),
                "import " + WorkforceSnapshotQueryPorts.class.getName() + ";");
        assertThatThrownBy(() -> assertOwnerExtensionUsageAllowed(
                Path.of("com/dwp/services/people/hris/performance/ForbiddenConsumer.java"),
                "import " + WorkforceSnapshotProvider.class.getName() + ";"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner-only");
        assertThatThrownBy(() -> assertOwnerExtensionUsageAllowed(
                Path.of("com/dwp/services/people/hris/performance/WildcardConsumer.java"),
                "import com.dwp.services.people.hris.contracts.workforce.v1.*;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> assertConsumerBytecode(
                ("class-data/" + WorkforceSnapshotProvider.class.getName().replace('.', '/'))
                        .getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bytecode");
    }

    @Test
    void recordsAndCanonicalSchemaHaveExactlyTheRegisteredSemantics() throws IOException {
        assertRecordTypes(WorkforceSnapshotQuery.class,
                List.of(String.class, Instant.class, WorkforceSnapshotProjection.class,
                        int.class, String.class));
        assertRecordTypes(VerifiedWorkforceSnapshotRequest.class,
                List.of(long.class, long.class, long.class, String.class, UUID.class,
                        String.class, WorkforceSnapshotProjection.class, Instant.class,
                        int.class, Long.class, String.class, String.class));
        assertRecordTypes(VerifiedWorkforceSnapshotCursor.class,
                List.of(long.class, String.class, String.class,
                        WorkforceSnapshotProjection.class, Instant.class, long.class,
                        String.class, String.class));
        assertRecordTypes(WorkforceSnapshotPage.class,
                List.of(long.class, String.class, String.class,
                        WorkforceSnapshotProjection.class, Instant.class, long.class,
                        String.class, List.class, String.class));
        assertRecordTypes(WorkforceSnapshotV1.class,
                List.of(UUID.class, UUID.class, String.class, UUID.class,
                        UUID.class, UUID.class, UUID.class));
        assertShape("WorkforceSnapshotQuery", WorkforceSnapshotQuery.class);
        assertShape("VerifiedWorkforceSnapshotRequest", VerifiedWorkforceSnapshotRequest.class);
        assertShape("VerifiedWorkforceSnapshotCursor", VerifiedWorkforceSnapshotCursor.class);
        assertShape("WorkforceSnapshotPage", WorkforceSnapshotPage.class);
        assertShape("WorkforceSnapshotV1", WorkforceSnapshotV1.class);

        JsonNode schema = schema();
        byte[] schemaBytes = Files.readAllBytes(schemaPath());
        verifySchemaBytes(schemaBytes);
        verifySchemaSemantics(schema);
        assertThat(schema.path("x-port").path("facade").asText())
                .isEqualTo(WorkforceSnapshotQueryPort.class.getSimpleName());
        assertThat(schema.path("x-port").path("facadeKind").asText())
                .isEqualTo("FINAL_NON_IMPLEMENTABLE");
        assertThat(schema.path("x-port").path("enforcement").asText())
                .isEqualTo(GuardedWorkforceSnapshotQueryPort.class.getSimpleName());
        assertThat(schema.path("x-port").path("tenantContext").asText())
                .isEqualTo("OWNER_RESOLVED_NOT_CALLER_SUPPLIED");
        assertThat(schema.path("x-port").path("cursorBinding")).hasSize(7);
        assertThat(schema.path("x-port").path("orderingVersion").asText())
                .isEqualTo(WorkforceSnapshotPage.ORDERING_VERSION);
        assertThat(schema.path("$defs").path("VerifiedWorkforceSnapshotRequest")
                .path("properties").path("verifiedCursorPosition")
                .path("oneOf").path(1).path("pattern"))
                .isEqualTo(schema.path("$defs").path("VerifiedWorkforceSnapshotCursor")
                        .path("properties").path("position").path("pattern"));
        assertThat(schema.path("$defs").path("WorkforceSnapshotPage")
                .path("properties").path("snapshots").path("maxItems").asInt())
                .isEqualTo(WorkforceSnapshotQuery.MAX_LIMIT);
        assertThat(schema.path("$defs").path("WorkforceSnapshotV1")
                .path("properties").path("status").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactlyElementsOf(WorkforceSnapshotV1.STATUS_CODES);
        JsonNode canonicalWorkerStatus = new ObjectMapper().readTree(Files.readString(
                canonicalCrossModuleSchemaPath())).path("$defs").path("XCON_001")
                .path("properties").path("workerStatus");
        assertThat(schema.path("$defs").path("WorkforceSnapshotV1")
                .path("properties").path("status").path("enum"))
                .isEqualTo(canonicalWorkerStatus.path("enum"));
        assertThat(schema.path("$defs").path("WorkforceSnapshotV1")
                .path("properties").path("status").path("x-authoritativePointer").asText())
                .isEqualTo("contracts/hris/canonical/schemas/"
                        + "cross-module-canonical-schemas.v1.json"
                        + "#/$defs/XCON_001/properties/workerStatus");
        assertThat(schema.path("x-port").path("consumerVisibleTypes"))
                .allSatisfy(node -> assertThat(node.asText()).isNotBlank());
        List<String> ownerExtensionTypes = new ArrayList<>();
        schema.path("x-port").path("ownerExtensionTypes")
                .forEach(node -> ownerExtensionTypes.add(node.asText()));
        assertThat(ownerExtensionTypes)
                .containsExactly(
                        "WorkforceSnapshotRequestVerifier",
                        "WorkforceSnapshotProvider",
                        "VerifiedWorkforceSnapshotRequest",
                        "VerifiedWorkforceSnapshotCursor",
                        "WorkforceSnapshotQueryPorts");
        assertThat(schema.path("$defs").path("WorkforceSnapshotQuery")
                .path("properties").path("limit").path("maximum").asInt()).isEqualTo(500);

        byte[] mutated = new String(schemaBytes, StandardCharsets.UTF_8)
                .replace("\"maximum\": 500", "\"maximum\": 501")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifySchemaBytes(mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema digest");

        JsonNode semanticMutation = schema.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) semanticMutation.path("$defs")
                .path("WorkforceSnapshotQuery").path("properties").path("limit"))
                .put("maximum", 501);
        assertThatThrownBy(() -> verifySchemaSemantics(semanticMutation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic digest");
    }

    @Test
    void neutralContractSourcesHaveNoFrameworkPersistenceOrDomainImports() throws IOException {
        Path sourceDirectory = repositoryRoot().resolve(
                "dwp-people-server/src/main/java/com/dwp/services/people/hris/contracts/workforce/v1");
        try (var sources = Files.list(sourceDirectory)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(source))
                        .doesNotContain("import org.springframework.")
                        .doesNotContain("import jakarta.persistence.")
                        .doesNotContain("import javax.persistence.")
                        .doesNotContain(".repository.")
                        .doesNotContain(".domain.");
            }
        }
    }

    private static WorkforceSnapshotQuery query(String cursorToken) {
        return new WorkforceSnapshotQuery(
                WorkforceSnapshotQuery.PURPOSE_CODE, AS_OF,
                WorkforceSnapshotProjection.PERFORMANCE_V1, 500, cursorToken);
    }

    private static void assertOwnerExtensionUsageAllowed(Path relativePath, String source) {
        List<Class<?>> ownerOnly = List.of(
                WorkforceSnapshotRequestVerifier.class,
                WorkforceSnapshotProvider.class,
                VerifiedWorkforceSnapshotRequest.class,
                VerifiedWorkforceSnapshotCursor.class,
                WorkforceSnapshotQueryPorts.class,
                GuardedWorkforceSnapshotQueryPort.class);
        String normalized = relativePath.toString().replace('\\', '/');
        boolean controlPackage = normalized.startsWith(
                "com/dwp/services/people/hris/contracts/workforce/v1/");
        boolean hrmOwnerPackage = List.of(
                "people", "employment", "organization", "employeeservice", "compatibility")
                .stream()
                .anyMatch(owner -> normalized.startsWith(
                        "com/dwp/services/people/hris/" + owner + "/"));
        String contractWildcard =
                "com.dwp.services.people.hris.contracts.workforce.v1.*";
        if (!controlPackage && source.contains(contractWildcard)) {
            throw new IllegalArgumentException(
                    "consumer source may not use the workforce contract wildcard import");
        }
        for (Class<?> type : ownerOnly) {
            if (!controlPackage && !hrmOwnerPackage
                    && (source.contains(type.getName())
                    || source.contains(type.getSimpleName()))) {
                throw new IllegalArgumentException(
                        "non-HRM consumer source references owner-only type "
                                + type.getSimpleName());
            }
        }
    }

    private static void assertConsumerBytecode(byte[] bytecode) {
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        List<Class<?>> ownerOnly = List.of(
                WorkforceSnapshotRequestVerifier.class,
                WorkforceSnapshotProvider.class,
                VerifiedWorkforceSnapshotRequest.class,
                VerifiedWorkforceSnapshotCursor.class,
                WorkforceSnapshotQueryPorts.class,
                GuardedWorkforceSnapshotQueryPort.class);
        for (Class<?> type : ownerOnly) {
            if (constantPool.contains(type.getName().replace('.', '/'))) {
                throw new IllegalArgumentException(
                        "HRIS-PER bytecode depends on owner-only type " + type.getSimpleName());
            }
        }
    }

    private static VerifiedWorkforceSnapshotRequest verified(
            WorkforceSnapshotQuery query,
            Long cursorOwnerRevision,
            String cursorPosition,
            String cursorDigest) {
        return new VerifiedWorkforceSnapshotRequest(
                1, 11, 13, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                query.purposeCode(), query.projection(), query.asOf(), query.limit(),
                cursorOwnerRevision, cursorPosition, cursorDigest);
    }

    private static WorkforceSnapshotV1 snapshot() {
        return new WorkforceSnapshotV1(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ACTIVE",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                null, null, null);
    }

    private static void assertShape(String definition, Class<?> type) throws IOException {
        assertThat(fieldNames(schema(), definition))
                .containsExactlyElementsOf(recordFields(type).keySet());
    }

    private static void assertRecordTypes(Class<?> type, List<Class<?>> expectedTypes) {
        assertThat(type.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactlyElementsOf(expectedTypes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void verifySchemaBytes(byte[] bytes) {
        String actual = sha256(bytes);
        if (!SCHEMA_SHA256.equals(actual)) {
            throw new IllegalArgumentException(
                    "workforce snapshot schema digest drift: " + actual);
        }
    }

    private static void verifySchemaSemantics(JsonNode schema) {
        String semanticContract = schema.path("x-port") + "\n" + schema.path("$defs");
        String actual = sha256(semanticContract.getBytes(StandardCharsets.UTF_8));
        if (!SCHEMA_SEMANTICS_SHA256.equals(actual)) {
            throw new IllegalArgumentException(
                    "workforce snapshot schema semantic digest drift: " + actual);
        }
    }

    private static Map<String, Class<?>> recordFields(Class<?> type) {
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            fields.put(component.getName(), component.getType());
        }
        return fields;
    }

    private static List<String> fieldNames(JsonNode schema, String definition) {
        return schema.path("$defs").path(definition).path("properties")
                .propertyStream().map(Map.Entry::getKey).toList();
    }

    private static JsonNode schema() throws IOException {
        return new ObjectMapper().readTree(Files.readString(schemaPath()));
    }

    private static Path schemaPath() {
        return repositoryRoot().resolve(
                "contracts/in-process/hris/workforce-snapshot-query-port.v1.schema.json");
    }

    private static Path canonicalCrossModuleSchemaPath() {
        return repositoryRoot().resolve(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");
    }

    private static Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(workingDirectory.resolve("contracts"))
                ? workingDirectory
                : workingDirectory.getParent();
    }

    private record CursorClaims(
            long tenantId,
            String caller,
            String purpose,
            String projection,
            Instant asOf,
            long ownerRevision,
            String position) {
    }

    private static final class TestAuthorityVerifier implements WorkforceSnapshotRequestVerifier {

        private static final long CURRENT_OWNER_REVISION = 7;
        private static final byte[] SECRET = "test-only-owner-secret".getBytes(StandardCharsets.UTF_8);
        private final AtomicInteger requestChecks = new AtomicInteger();
        private final AtomicInteger issuedCursorChecks = new AtomicInteger();

        @Override
        public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery query) {
            requestChecks.incrementAndGet();
            if (query.cursorToken() == null) {
                return verified(query, null, null, null);
            }
            CursorClaims claims = authenticate(query.cursorToken());
            requireBindings(claims, 1, VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                    query.purposeCode(), query.projection().name(), query.asOf(),
                    CURRENT_OWNER_REVISION);
            return verified(
                    query, claims.ownerRevision(), claims.position(), query.cursorTokenDigest());
        }

        @Override
        public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
                VerifiedWorkforceSnapshotRequest request, WorkforceSnapshotPage page) {
            issuedCursorChecks.incrementAndGet();
            CursorClaims claims = authenticate(page.nextCursor());
            requireBindings(claims, request.tenantId(), request.callerModule(),
                    request.purposeCode(), request.projection().name(), request.asOf(),
                    page.ownerRevision());
            return new VerifiedWorkforceSnapshotCursor(
                    claims.tenantId(), claims.caller(), claims.purpose(),
                    WorkforceSnapshotProjection.valueOf(claims.projection()),
                    claims.asOf(), claims.ownerRevision(), claims.position(),
                    WorkforceSnapshotQuery.digestToken(page.nextCursor()));
        }

        int requestChecks() {
            return requestChecks.get();
        }

        int issuedCursorChecks() {
            return issuedCursorChecks.get();
        }

        String issue(
                long tenantId,
                String caller,
                String purpose,
                String projection,
                Instant asOf,
                long ownerRevision,
                String position) {
            String payload = String.join("|", Long.toString(tenantId), caller, purpose,
                    projection, asOf.toString(), Long.toString(ownerRevision), position);
            return payload + "." + hmac(payload);
        }

        private static CursorClaims authenticate(String token) {
            int separator = token.lastIndexOf('.');
            if (separator < 1 || separator == token.length() - 1) {
                throw new IllegalArgumentException("cursor authentication failed");
            }
            String payload = token.substring(0, separator);
            String signature = token.substring(separator + 1);
            if (!constantTimeEquals(signature, hmac(payload))) {
                throw new IllegalArgumentException("cursor authentication failed");
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 7) {
                throw new IllegalArgumentException("cursor claim shape is invalid");
            }
            try {
                CursorClaims claims = new CursorClaims(
                        Long.parseLong(fields[0]), fields[1], fields[2], fields[3],
                        Instant.parse(fields[4]), Long.parseLong(fields[5]), fields[6]);
                if (claims.position().isBlank()) {
                    throw new IllegalArgumentException("cursor position is blank");
                }
                return claims;
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("cursor claims are invalid", exception);
            }
        }

        private static void requireBindings(
                CursorClaims actual,
                long tenantId,
                String caller,
                String purpose,
                String projection,
                Instant asOf,
                long ownerRevision) {
            if (actual.tenantId() != tenantId
                    || !actual.caller().equals(caller)
                    || !actual.purpose().equals(purpose)
                    || !actual.projection().equals(projection)
                    || !actual.asOf().equals(asOf)
                    || actual.ownerRevision() != ownerRevision) {
                throw new IllegalArgumentException("cursor binding is invalid");
            }
        }

        private static String hmac(String value) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
                return HexFormat.of().formatHex(
                        mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("HmacSHA256 is unavailable", exception);
            }
        }

        private static boolean constantTimeEquals(String left, String right) {
            return java.security.MessageDigest.isEqual(
                    left.getBytes(StandardCharsets.US_ASCII),
                    right.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
