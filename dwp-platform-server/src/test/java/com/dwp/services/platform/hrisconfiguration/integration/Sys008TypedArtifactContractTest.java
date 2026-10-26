package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.dwp.platform.contracts.hris.generated.ConnectorReconciliationReceiptV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sys008TypedArtifactContractTest {

    private static final UUID ID_1 =
            UUID.fromString("00000000-0000-4000-8000-000000000801");
    private static final UUID ID_2 =
            UUID.fromString("00000000-0000-4000-8000-000000000802");
    private static final UUID ID_3 =
            UUID.fromString("00000000-0000-4000-8000-000000000803");
    private static final UUID ID_4 =
            UUID.fromString("00000000-0000-4000-8000-000000000804");
    private static final UUID ID_5 =
            UUID.fromString("00000000-0000-4000-8000-000000000805");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test
    void reusesTheThreeCanonicalGeneratedTypesWithoutRedeclaration() throws Exception {
        ConnectorMappingVersionV1 mapping = mapping(1L);
        ConnectorExecutionRequestV1 execution = execution(
                1L, "EXECUTE", "LEGAL_ENTITY", ID_4);
        ConnectorReconciliationReceiptV1 receipt = receipt(1L);

        assertThat(Sys008TypedArtifactContract.requireInteroperable(mapping))
                .isSameAs(mapping);
        assertThat(Sys008TypedArtifactContract.requireInteroperable(execution))
                .isSameAs(execution);
        assertThat(Sys008TypedArtifactContract.requireInteroperable(receipt))
                .isSameAs(receipt);

        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(mapping),
                ConnectorMappingVersionV1.class)).isEqualTo(mapping);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(execution),
                ConnectorExecutionRequestV1.class)).isEqualTo(execution);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(receipt),
                ConnectorReconciliationReceiptV1.class)).isEqualTo(receipt);
    }

    @Test
    void rejectsUnsafeVersionsAndUnsupportedOrIncoherentScopes() {
        long unsafe = Sys008TypedArtifactContract.MAX_JSON_SAFE_INTEGER + 1L;
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(mapping(unsafe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-safe");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(execution(
                        unsafe, "EXECUTE", "LEGAL_ENTITY", ID_4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-safe");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(receipt(unsafe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-safe");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(execution(1L, "EXECUTE", "GLOBAL", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TENANT or LEGAL_ENTITY");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(execution(1L, "EXECUTE", "TENANT", ID_4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry scopeId");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(execution(
                        1L, "EXECUTE", "LEGAL_ENTITY", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires scopeId");
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .requireInteroperable(execution(
                        1L, "DRY_RUN", "LEGAL_ENTITY", ID_4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRY_RUN");
        assertThat(Sys008TypedArtifactContract.requireInteroperable(
                execution(1L, "DRY_RUN", "TENANT", null))).isNotNull();
    }

    @Test
    void projectsEveryOwnerStateAndNeverClaimsAReverseMapping() {
        Map.ofEntries(
                Map.entry("DRAFT", "DRAFT"),
                Map.entry("VALIDATED", "VALIDATED"),
                Map.entry("PENDING_APPROVAL", "PENDING_APPROVAL"),
                Map.entry("APPROVED", "APPROVED"),
                Map.entry("ACTIVE", "ACTIVE"),
                Map.entry("SUSPENDED", "SUSPENDED"),
                Map.entry("RETIRED", "REVOKED"))
                .forEach((owner, dep006) -> assertThat(
                        Sys008TypedArtifactContract.projectMappingState(owner))
                        .isEqualTo(dep006));
        Map.ofEntries(
                Map.entry("ACCEPTED", "ACCEPTED"),
                Map.entry("TRANSFORMING", "RUNNING"),
                Map.entry("DISPATCHED", "RUNNING"),
                Map.entry("RESULT_UNKNOWN", "RESULT_UNKNOWN"),
                Map.entry("ACKNOWLEDGED", "RUNNING"),
                Map.entry("RECONCILING", "RUNNING"),
                Map.entry("RECONCILED", "SUCCEEDED"),
                Map.entry("PARTIAL", "PARTIAL"),
                Map.entry("FAILED", "FAILED"),
                Map.entry("DEAD_LETTER", "FAILED"),
                Map.entry("CANCELLED", "CANCELED"))
                .forEach((owner, dep006) -> assertThat(
                        Sys008TypedArtifactContract.projectExecutionState(owner))
                        .isEqualTo(dep006));
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .projectExecutionState("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesEveryPdx008CommandProjectionWithoutCallerOverrides() {
        var population = population(ID_2, Set.of(ID_4), 7);
        var ownerContext = ownerContext(ID_1, ID_2, ID_3, 1L);
        var source = source("ref:source:008", DIGEST_A);
        var tenantEnvelope = tenantEnvelope(population, source);
        var legalEntityEnvelope = legalEntityEnvelope(population, ID_4, source);
        var pinnedInbound = Sys008TypedArtifactContract.pinInboundOwnerMapping(
                mapping(1L));
        var pinnedOutbound = Sys008TypedArtifactContract.pinOutboundOwnerMapping(
                mapping(1L));
        var dryRun = Sys008TypedArtifactContract.projectDryRun(
                ownerContext, pinnedOutbound, tenantEnvelope);
        assertThat(dryRun.mode()).isEqualTo("DRY_RUN");
        assertThat(dryRun.direction()).isEqualTo("OUTBOUND");
        assertThat(dryRun.scopeType()).isEqualTo("TENANT");
        assertThat(dryRun.scopeId()).isNull();
        assertThat(Sys008TypedArtifactContract.projectDryRun(
                ownerContext, pinnedInbound, tenantEnvelope).direction())
                .isEqualTo("INBOUND");

        var imported = Sys008TypedArtifactContract.projectImportExecution(
                ownerContext, tenantEnvelope);
        var exported = Sys008TypedArtifactContract.projectExportExecution(
                ownerContext, legalEntityEnvelope);
        var dispatched = Sys008TypedArtifactContract.projectDispatchExecution(
                ownerContext, legalEntityEnvelope);
        assertThat(imported.mode()).isEqualTo("EXECUTE");
        assertThat(imported.direction()).isEqualTo("INBOUND");
        assertThat(exported.direction()).isEqualTo("OUTBOUND");
        assertThat(dispatched).isEqualTo(exported);
        assertThat(exported.scopeType()).isEqualTo("LEGAL_ENTITY");
        assertThat(exported.scopeId()).isEqualTo(ID_4);
        assertThat(exported.sourceRef()).isEqualTo("ref:source:008");
        assertThat(exported.sourceDigest()).isEqualTo(DIGEST_A);

        var reconcileContext = ownerContext(ID_5, ID_2, ID_3, 1L);
        var reconciled = Sys008TypedArtifactContract.projectReconcile(
                reconcileContext, exported, population);
        assertThat(reconciled.mode()).isEqualTo("RECONCILE");
        assertThat(reconciled.direction()).isEqualTo(exported.direction());
        assertThat(reconciled.scopeType()).isEqualTo(exported.scopeType());
        assertThat(reconciled.scopeId()).isEqualTo(exported.scopeId());
        assertThat(reconciled.sourceRef()).isEqualTo(exported.sourceRef());
        assertThat(reconciled.sourceDigest()).isEqualTo(exported.sourceDigest());
        assertThat(reconciled.executionId()).isEqualTo(ID_5);
    }

    @Test
    void exposesOnlyOpaqueProofTokensAndPackageLocalOwnerIssuance() throws Exception {
        Set<String> publicMethodNames = Arrays.stream(
                        Sys008TypedArtifactContract.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(publicMethodNames)
                .doesNotContain(
                        "projectExecution",
                        "pinInboundOwnerMapping",
                        "pinOutboundOwnerMapping",
                        "issueOwnerExecutionContext",
                        "issueCurrentPopulationContext",
                        "freezeOwnerSource",
                        "issueTenantSourceEnvelope",
                        "issueLegalEntitySourceEnvelope",
                        "verifySourceEnvelope")
                .contains(
                        "projectDryRun",
                        "projectImportExecution",
                        "projectExportExecution",
                        "projectDispatchExecution",
                        "projectReconcile");
        assertThat(Arrays.stream(Sys008TypedArtifactContract.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain(
                        "MappingDirection",
                        "ExecutionIntent",
                        "ExecutionIdentity",
                        "PopulationAuthority");

        var dryRunProjection = Sys008TypedArtifactContract.class.getMethod(
                "projectDryRun",
                Sys008TypedArtifactContract.OwnerIssuedExecutionContext.class,
                Sys008TypedArtifactContract.PinnedMappingEnvelope.class,
                Sys008TypedArtifactContract.VerifiedSourceEnvelope.class);
        assertThat(dryRunProjection.getParameterTypes())
                .containsExactly(
                        Sys008TypedArtifactContract.OwnerIssuedExecutionContext.class,
                        Sys008TypedArtifactContract.PinnedMappingEnvelope.class,
                        Sys008TypedArtifactContract.VerifiedSourceEnvelope.class);
        for (Class<?> token : Set.of(
                Sys008TypedArtifactContract.OwnerIssuedExecutionContext.class,
                Sys008TypedArtifactContract.PinnedMappingEnvelope.class,
                Sys008TypedArtifactContract.CurrentPopulationContext.class,
                Sys008TypedArtifactContract.VerifiedSourceEnvelope.class)) {
            assertThat(token.getDeclaredConstructors())
                    .allMatch(constructor -> Modifier.isPrivate(
                            constructor.getModifiers()));
            assertThat(Arrays.stream(token.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers())))
                    .isEmpty();
        }
        Class<?> verifiedScope = Arrays.stream(
                        Sys008TypedArtifactContract.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("VerifiedScope"))
                .findFirst()
                .orElseThrow();
        assertThat(Modifier.isPrivate(verifiedScope.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(
                Sys008TypedArtifactContract.FrozenSource.class.getModifiers()))
                .isFalse();

        assertPackagePrivateStatic("pinInboundOwnerMapping", ConnectorMappingVersionV1.class);
        assertPackagePrivateStatic("pinOutboundOwnerMapping", ConnectorMappingVersionV1.class);
        assertPackagePrivateStatic(
                "issueOwnerExecutionContext",
                UUID.class, UUID.class, UUID.class, long.class, Instant.class,
                String.class, String.class, UUID.class);
        assertPackagePrivateStatic(
                "issueCurrentPopulationContext", UUID.class, Set.class, long.class);
        assertPackagePrivateStatic("freezeOwnerSource", String.class, String.class);
        assertPackagePrivateStatic(
                "issueTenantSourceEnvelope",
                Sys008TypedArtifactContract.CurrentPopulationContext.class,
                Sys008TypedArtifactContract.FrozenSource.class);
        assertPackagePrivateStatic(
                "issueLegalEntitySourceEnvelope",
                Sys008TypedArtifactContract.CurrentPopulationContext.class,
                UUID.class,
                Sys008TypedArtifactContract.FrozenSource.class);
    }

    @Test
    void rejectsUnverifiedConnectorLegalEntityAndReconcileSubstitution() {
        var population = population(ID_2, Set.of(ID_4), 7);
        assertThatThrownBy(() -> population(ID_2, Set.of(ID_4), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-safe");
        var source = source("ref:source:008", DIGEST_A);
        assertThatThrownBy(() -> Sys008TypedArtifactContract
                .issueLegalEntitySourceEnvelope(population, ID_5, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legal entity");
        assertThatThrownBy(() -> source("ref:source:008", "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectImportExecution(
                ownerContext(ID_1, ID_3, ID_3, 1L),
                tenantEnvelope(population, source)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector population");

        var original = Sys008TypedArtifactContract.projectExportExecution(
                ownerContext(ID_1, ID_2, ID_3, 1L),
                legalEntityEnvelope(population, ID_4, source));
        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectReconcile(
                ownerContext(ID_5, ID_2, ID_3, 2L), original, population))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frozen connector mapping");

        var ownerContext = ownerContext(ID_1, ID_2, ID_3, 1L);
        var tenantEnvelope = tenantEnvelope(population, source);
        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectDryRun(
                ownerContext,
                Sys008TypedArtifactContract.pinOutboundOwnerMapping(
                        mapping(ID_1, ID_3, 1L)),
                tenantEnvelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned owner mapping");
        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectDryRun(
                ownerContext,
                Sys008TypedArtifactContract.pinOutboundOwnerMapping(
                        mapping(ID_2, ID_5, 1L)),
                tenantEnvelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned owner mapping");
        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectDryRun(
                ownerContext,
                Sys008TypedArtifactContract.pinOutboundOwnerMapping(
                        mapping(ID_2, ID_3, 2L)),
                tenantEnvelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned owner mapping");
    }

    @Test
    void rejectsAReflectivelyTamperedScopeSourceBinding() throws Exception {
        var population = population(ID_2, Set.of(ID_4), 7);
        var valid = tenantEnvelope(
                population, source("ref:source:008", DIGEST_A));
        var scopeField = Sys008TypedArtifactContract.VerifiedSourceEnvelope.class
                .getDeclaredField("scope");
        var sourceField = Sys008TypedArtifactContract.VerifiedSourceEnvelope.class
                .getDeclaredField("source");
        scopeField.setAccessible(true);
        sourceField.setAccessible(true);
        var constructor = Sys008TypedArtifactContract.VerifiedSourceEnvelope.class
                .getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var tampered = (Sys008TypedArtifactContract.VerifiedSourceEnvelope)
                constructor.newInstance(
                        scopeField.get(valid), sourceField.get(valid), DIGEST_B);

        assertThatThrownBy(() -> Sys008TypedArtifactContract.projectImportExecution(
                ownerContext(ID_1, ID_2, ID_3, 1L), tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound to the verified scope");
    }

    private static ConnectorMappingVersionV1 mapping(long version) {
        return mapping(ID_2, ID_3, version);
    }

    private static ConnectorMappingVersionV1 mapping(
            UUID connectorId, UUID mappingId, long version) {
        return new ConnectorMappingVersionV1(
                connectorId, mappingId, version,
                "ACTIVE", "People.v1", "Provider.v1",
                DIGEST_A, DIGEST_B, "actor:author", "actor:approver", ID_3,
                LocalDate.parse("2026-10-26"), null, "EXACT");
    }

    private static ConnectorExecutionRequestV1 execution(
            long version, String mode, String scopeType, UUID scopeId) {
        return new ConnectorExecutionRequestV1(
                ID_1, ID_2, ID_3, version, mode, "INBOUND", scopeType, scopeId,
                "ref:source:008", DIGEST_A, Instant.parse("2026-10-26T00:00:00Z"),
                "actor:executor", "sys008-idempotency", ID_4);
    }

    private static ConnectorReconciliationReceiptV1 receipt(long version) {
        return new ConnectorReconciliationReceiptV1(
                ID_1, ID_2, version, "PARTIAL", 10, 7, 2, 1,
                DIGEST_A, DIGEST_B, "ref:error:008", DIGEST_B,
                Instant.parse("2026-10-26T00:01:00Z"), ID_4);
    }

    private static Sys008TypedArtifactContract.OwnerIssuedExecutionContext ownerContext(
            UUID executionId, UUID connectorId, UUID mappingId, long version) {
        return Sys008TypedArtifactContract.issueOwnerExecutionContext(
                executionId, connectorId, mappingId, version,
                Instant.parse("2026-10-26T00:00:00Z"), "actor:executor",
                "sys008-idempotency", ID_4);
    }

    private static Sys008TypedArtifactContract.CurrentPopulationContext population(
            UUID connectorId, Set<UUID> legalEntities, long authorizationRevision) {
        return Sys008TypedArtifactContract.issueCurrentPopulationContext(
                connectorId, legalEntities, authorizationRevision);
    }

    private static Sys008TypedArtifactContract.FrozenSource source(
            String reference, String digest) {
        return Sys008TypedArtifactContract.freezeOwnerSource(reference, digest);
    }

    private static Sys008TypedArtifactContract.VerifiedSourceEnvelope tenantEnvelope(
            Sys008TypedArtifactContract.CurrentPopulationContext population,
            Sys008TypedArtifactContract.FrozenSource source) {
        return Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                population, source);
    }

    private static Sys008TypedArtifactContract.VerifiedSourceEnvelope legalEntityEnvelope(
            Sys008TypedArtifactContract.CurrentPopulationContext population,
            UUID legalEntityPublicId,
            Sys008TypedArtifactContract.FrozenSource source) {
        return Sys008TypedArtifactContract.issueLegalEntitySourceEnvelope(
                population, legalEntityPublicId, source);
    }

    private static void assertPackagePrivateStatic(
            String methodName, Class<?>... parameterTypes) throws Exception {
        var method = Sys008TypedArtifactContract.class.getDeclaredMethod(
                methodName, parameterTypes);
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
    }
}
