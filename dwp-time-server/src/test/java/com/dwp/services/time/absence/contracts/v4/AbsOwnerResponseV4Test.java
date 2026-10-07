package com.dwp.services.time.absence.contracts.v4;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.*;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Format;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;

/** Candidate-only fixtures. Actual guard with explicit MOCK providers, never native authority. */
public class AbsOwnerResponseV4Test {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final UUID ACTOR = UUID.fromString("c0000000-0000-4000-8000-000000000009");
    public static final String SCOPE = "selected.native-owner-scope";
    public static final String SCHEMA_SHA = "9841d3d99bbc486e8d6ec41bb63d5b164aa23f2cba9d4c74d7e2a95ddbf8e14d";
    public static final String FIXTURE_SHA = "85757f558302d32b04f1b16c922f7a652c2e919d2fd377c98e149820bdce8225";
    private static final String RESOURCE_ROOT = "/com/dwp/services/time/absence/contracts/v4/";
    private static final String RESOURCE_SUFFIX = ".json.gz.b64";

    public static byte[] resource(String name) {
        String path = RESOURCE_ROOT + "tim-cross-context-boundary-successor.v4." + name + RESOURCE_SUFFIX;
        try (InputStream stream = AbsOwnerResponseV4Test.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("MISSING_PINNED_TEST_RESOURCE:" + path);
            String encoded = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).strip();
            byte[] compressed = Base64.getDecoder().decode(encoded);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] decoded = gzip.readAllBytes();
                String expected = switch (name) {
                    case "schemas" -> SCHEMA_SHA;
                    case "fixtures" -> FIXTURE_SHA;
                    default -> throw new IllegalArgumentException("UNKNOWN_PINNED_TEST_RESOURCE:" + name);
                };
                if (!expected.equals(AbsOwnerResponseV4.sha256(decoded))) {
                    throw new IllegalStateException("PINNED_TEST_RESOURCE_DIGEST_MISMATCH:" + path);
                }
                return decoded;
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("INVALID_PINNED_TEST_RESOURCE:" + path, ex);
        }
    }
    public static ObjectNode fixture(String key, int n) throws Exception {
        return (ObjectNode) JSON.readTree(resource("fixtures")).get(key).get(n).deepCopy();
    }
    public static ObjectNode wire(JsonNode node) {
        ObjectNode converted = (ObjectNode) AbsOwnerResponseV4.toInt64Wire(node);
        return converted;
    }
    public static VerifiedCurrentHrisAuthorizationV2 proof(ObjectNode input) {
        return proof(input,"tim.leave.entitlement.run","TIM_ENTITLEMENT_PREPARE");
    }
    public static VerifiedCurrentHrisAuthorizationV2 proof(ObjectNode input,String operation,String purpose) {
        var e = input.path("selectedItems").get(0).path("enrollment");
        UUID worker = UUID.fromString(e.path("workerPublicId").asText());
        UUID assignment = UUID.fromString(e.path("assignmentPublicId").asText());
        UUID person = UUID.fromString("c0000000-0000-4000-8000-000000000011");
        UUID relationship = UUID.fromString("c0000000-0000-4000-8000-000000000029");
        var req = new Requirements(operation, "hcm", "hcm.time",
                operation+".route", purpose, "HRIS_TIM",
                Plane.WORK, AccessMode.NORMAL, Selection.TARGET_POPULATION, TargetKind.EMPLOYMENT,
                Set.of("APP.HRIS:VIEW"), Set.of("TIM_ENTITLEMENT_PREPARE"),
                Set.of("person.publicId", "worker.publicId", "assignment.publicId"), true);
        var actor = new AuthPersonBindingV1(41, 9, ACTOR, null,
                AuthPersonBindingV1.IdentityPlane.TENANT, AuthPersonBindingV1.Status.ACTIVE,
                9007199254740993L, 3, NOW, NOW.plusSeconds(10));
        var target = new TargetSnapshot(TargetKind.EMPLOYMENT, 41, person, 11, "ACTIVE",
                worker, person, 23L, relationship, worker, 29L, assignment, relationship, 31L);
        var invocation = new Invocation(41, 9, ACTOR, null, 9007199254740993L, 3,
                req.operationId(), "session:explicit-mock", NOW, NOW.plusSeconds(10));
        var guard = new GuardedCurrentHrisAuthorizationPortV2(req, () -> invocation,
                lookup -> new DwpAuthoritySnapshot(req, actor, Decision.ALLOWED,
                        new AuthRevision("auth-"+"a".repeat(64)), new PolicyRevision("policy-candidate.v4"),
                        new ContextKey("psc-"+"c".repeat(64)), new DecisionRevision("psr-"+"d".repeat(64)),
                        SCOPE, req.requiredPermissions(), req.requiredAtomicDuties(), true, true,
                        NOW, NOW.plusSeconds(10)),
                lookup -> new PeopleAuthoritySnapshot(req, SCOPE, target,
                        revision(OwnerRevisionKind.RELATIONSHIP), revision(OwnerRevisionKind.POPULATION),
                        revision(OwnerRevisionKind.FIELD_POLICY), revision(OwnerRevisionKind.PURPOSE_POLICY),
                        revision(OwnerRevisionKind.DYNAMIC_SOD), req.fieldPaths(), true, true, true,
                        NOW, NOW.plusSeconds(10)), CLOCK);
        return guard.authorize(new TargetSelector(TargetKind.EMPLOYMENT, person, worker, relationship, assignment));
    }
    private static PeopleRevision revision(OwnerRevisionKind kind) {
        return new PeopleRevision(kind, "people-"+kind.name().toLowerCase()+":native.v2:opaque-ref");
    }
    /** Pinned Java Draft-07 engine. No coercion/default/removal; current schema-provider mock only. */
    public static AbsOwnerResponseV4.SchemaValidator engine() {
        return (schema, value) -> {
            try {
                byte[] bytes = resource("schemas");
                ObjectNode document = AbsOwnerResponseV4.int64Schema(bytes);
                ObjectNode validationRoot = JSON.createObjectNode();
                validationRoot.put("$schema", "http://json-schema.org/draft-07/schema#");
                validationRoot.set("definitions", document.path("definitions"));
                validationRoot.setAll((ObjectNode) document.path("schemas").path(schema));
                var failures = schemaFactory().getSchema(validationRoot, schemaConfig()).validate(value);
                if (!failures.isEmpty()) throw new IllegalArgumentException("FULL_DRAFT_07_REJECTED:" + failures);
            } catch (IllegalArgumentException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalStateException("SCHEMA_ENGINE_UNAVAILABLE", ex); }
        };
    }
    private static JsonSchemaFactory schemaFactory() {
        Format nativeInt64 = new Format() {
            @Override public String getName() { return "native-int64"; }
            @Override public boolean matches(ExecutionContext context, String value) {
                return value != null && value.matches("^(0|[1-9][0-9]*)$")
                        && new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
            }
        };
        JsonMetaSchema draft7 = JsonMetaSchema.builder(JsonMetaSchema.getV7()).format(nativeInt64).build();
        return JsonSchemaFactory.builder().defaultMetaSchemaIri(draft7.getIri()).metaSchema(draft7).build();
    }
    private static SchemaValidatorsConfig schemaConfig() {
        return SchemaValidatorsConfig.builder()
                .typeLoose(false)
                .failFast(false)
                .formatAssertionsEnabled(true)
                .build();
    }
    @Test void pinnedCandidateResourcesAreRepositoryLocalAndExact() {
        assertEquals(SCHEMA_SHA, AbsOwnerResponseV4.sha256(resource("schemas")));
        assertEquals(FIXTURE_SHA, AbsOwnerResponseV4.sha256(resource("fixtures")));
    }
    @Test void exactNative64StringAndSeparateBigIntegerRange() {
        assertEquals(9007199254740993L, AbsOwnerResponseV4.nativeLong(JSON.getNodeFactory().textNode("9007199254740993")));
        assertEquals(Long.MAX_VALUE, AbsOwnerResponseV4.nativeLong(JSON.getNodeFactory().textNode("9223372036854775807")));
        assertEquals("9223372036854775808", AbsOwnerResponseV4.canonicalBigInteger("9223372036854775808").toString());
        assertThrows(IllegalArgumentException.class, () -> AbsOwnerResponseV4.nativeLong(JSON.getNodeFactory().numberNode(9007199254740993L)));
        assertThrows(IllegalArgumentException.class, () -> AbsOwnerResponseV4.nativeLong(JSON.getNodeFactory().textNode("9223372036854775808")));
    }
    @ParameterizedTest @ValueSource(ints={0,1}) void independentBindingRecipeABLiteral(int n) throws Exception {
        // Independently serialized with Python json(sort_keys=True,separators=(',',':')), not production digest().
        String[] expected={"9b33d055f5db51819118f0f7b7cb342f3178cd724c1faa0ec196c8c312841255",
                "44812bf406cfa1153a01f68c94abc75d6e65dbf3472f036c7a660a1a189f0312"};
        var authority=proof(wire(fixture("inputsAB",n)));
        assertEquals(expected[n],AbsOwnerResponseV4.candidateTargetBindingDigest(authority));
        assertNotEquals(SCOPE,expected[n]); // Opaque native selected-scope key is not this candidate checksum.
    }
    @Test void verifiedOwnerCarriersHaveNoPublicConstructor() {
        for(Class<?> type:List.of(com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.VerifiedOwner.class,
                com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.VerifiedPolicy.class,
                com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.VerifiedTermination.class)) {
            assertEquals(0,type.getConstructors().length);
            assertTrue(Arrays.stream(type.getDeclaredConstructors()).allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
        }
    }
    @ParameterizedTest @ValueSource(strings={"01","+1","-0","-1","1.0","1e3"," 1","1 ","","NaN"})
    void rejectsNonCanonicalNative64(String value) {
        assertThrows(IllegalArgumentException.class, () -> AbsOwnerResponseV4.nativeLong(JSON.getNodeFactory().textNode(value)));
    }
    @Test void wireAbiIsExplicitAndFrozenSchemaUnchanged() throws Exception {
        byte[] base = resource("schemas");
        assertEquals(SCHEMA_SHA, AbsOwnerResponseV4.sha256(base));
        ObjectNode schema = AbsOwnerResponseV4.int64Schema(base);
        assertEquals("string", schema.path("definitions").path("V4.ActorAuthority").path("properties")
                .path("actorUserRowVersion").path("type").asText());
        assertEquals("integer", schema.path("definitions").path("TIM.EntitlementInputDocument.v2").path("properties")
                .path("selectedCount").path("type").asText());
        ObjectNode value = wire(fixture("inputsAB",0));
        value.put("wireContractId", AbsOwnerResponseV4.WIRE_ID);
        value.put("revision","1");
        engine().validate("INPUT",value);
        value.put("revision",1);
        assertThrows(IllegalArgumentException.class,()->engine().validate("INPUT",value));
        value.put("revision", "9223372036854775808");
        assertThrows(IllegalArgumentException.class,()->engine().validate("INPUT",value));
        assertEquals(SCHEMA_SHA, AbsOwnerResponseV4.sha256(resource("schemas")));
    }
    @Test void schemaPinMismatchDeniesNotCandidateOracleDrop() throws Exception {
        byte[] bytes=resource("schemas");
        bytes[10]^=1;
        assertThrows(IllegalArgumentException.class,()->AbsOwnerResponseV4.int64Schema(bytes));
    }
}
