package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.TreeSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Full fresh migrations and actual DB rows. No grants or fabricated active registry authorize these catalog reads. */
class StoredDescriptorSealPostgresTest {
    static WorkflowRuntimeActualAuthHarness auth;
    static ProductAuthorizationContractRepository repository;
    static ProductAuthorizationContractValidator validator;
    static ObjectMapper mapper;
    static StoredDescriptorSeal reader;
    static TransactionTemplate transactions;
    @BeforeAll static void start() throws Exception {
        auth = new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("seal-owner").generate(),
                new RSAKeyGenerator(2048).keyID("seal-transport").generate(), new RSAKeyGenerator(2048).keyID("seal-attestation").generate());
        var field = WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context"); field.setAccessible(true);
        var context = (AnnotationConfigApplicationContext) field.get(auth);
        repository = context.getBean(ProductAuthorizationContractRepository.class); validator = context.getBean(ProductAuthorizationContractValidator.class);
        mapper = context.getBean(ObjectMapper.class); transactions = context.getBean(TransactionTemplate.class);
        reader = new StoredDescriptorSeal(auth.jdbc(), repository, validator, mapper);
    }
    @AfterAll static void close() { if (auth != null) auth.close(); }
    @ParameterizedTest @ValueSource(longs={1,2,3,4,5,6,7,8,9,10})
    void actualStoredImmutableVersionsRetainExactOriginalChecksums(long version) throws Exception {
        var bundle = repository.find("product-surfaces", version).orElseThrow();
        if (version == 8) {
            var reference = reference(8); ((ObjectNode) reference).put("bundleStatus", bundle.bundleStatus());
            var typed = mapper.valueToTree(repository.loadContract(bundle));
            assertNotEquals(validator.checksum(reference), validator.checksum(typed));
            System.out.println("Exact canonical vs typed DB first difference: " + firstDifference(reference, typed, ""));
            for (String[] category : new String[][]{{"capabilities", "contractKey"}, {"accessPolicies", "accessPolicyKey"},
                    {"entitlementExpressions", "expressionKey"}, {"predicatePolicies", "predicatePolicyKey"}, {"routes", "routeContractKey"}, {"authorityEndpoints", "endpointKey"}}) {
                for (int index = 0; index < reference.path(category[0]).size(); index++) {
                    var expectedKey = reference.path(category[0]).get(index).path(category[1]);
                    var actualKey = typed.path(category[0]).get(index).path(category[1]);
                    if (!expectedKey.equals(actualKey)) {
                        System.out.println("Actual DB category order difference: /" + category[0] + '/' + index + " expected=" + expectedKey + " actual=" + actualKey);
                        break;
                    }
                }
            }
        }
        var observed = org.mockito.Mockito.spy(validator);
        var diagnostic = new StoredDescriptorSeal(auth.jdbc(), repository, observed, mapper);
        com.dwp.services.auth.dto.ProductAuthorizationContractDtos.BundleContract loaded;
        try { loaded = diagnostic.loadVersion(bundle); }
        finally {
            var documents = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
            org.mockito.Mockito.verify(observed, org.mockito.Mockito.atLeastOnce()).validateDocument(documents.capture());
            var actual = documents.getValue(); var expected = (ObjectNode) reference(version);
            expected.put("bundleStatus", bundle.bundleStatus());
            if (!semanticEquals(expected, actual)) System.out.println("Reconstructed DB v" + version + " first difference: " + firstDifference(expected, actual, ""));
        }
        assertEquals(bundle.checksum(), loaded.checksum()); assertEquals(version, loaded.version());
        assertEquals(bundle.bundleStatus(), loaded.bundleStatus());
    }
    @Test void actualCurrentPointerIsRequiredAndCheckedBeforeAndAfterSeal() {
        var bundle = repository.findActive("product-surfaces").orElseThrow(); var pointer = repository.findActivePointer("product-surfaces").orElseThrow();
        assertEquals(bundle.checksum(), reader.loadActive(bundle, pointer).checksum());
        assertThrows(IllegalArgumentException.class, () -> reader.loadActive(repository.find("product-surfaces", 7).orElseThrow(), pointer));
        transactions.execute(status -> {
            auth.jdbc().update("UPDATE auth_product_authorization_active SET revision=revision+1, activated_at=activated_at+interval '1 millisecond' WHERE bundle_key='product-surfaces'");
            assertThrows(IllegalArgumentException.class, () -> reader.loadActive(bundle, pointer)); status.setRollbackOnly(); return null;
        });
        transactions.execute(status -> {
            var observed = org.mockito.Mockito.spy(repository); var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                var value = invocation.callRealMethod();
                if (calls.incrementAndGet() == 1) auth.jdbc().update("UPDATE auth_product_authorization_active SET revision=revision+1, activated_at=activated_at+interval '1 millisecond' WHERE bundle_key='product-surfaces'");
                return value;
            }).when(observed).findActivePointer("product-surfaces");
            var changing = new StoredDescriptorSeal(auth.jdbc(), observed, validator, mapper);
            assertThrows(IllegalArgumentException.class, () -> changing.loadActive(bundle, pointer));
            assertEquals(2, calls.get()); status.setRollbackOnly(); return null;
        });
    }
    @Test void actualDescriptorValueTypeMissingKeyAndUnknownFieldsNeverPassTheImmutableSeal() {
        var bundle = repository.find("product-surfaces", 7).orElseThrow();
        String key = auth.jdbc().queryForObject("SELECT min(contract_key) FROM auth_product_capability_contract WHERE bundle_id=?", String.class, bundle.bundleId());
        String original = auth.jdbc().queryForObject("SELECT descriptor::text FROM auth_product_capability_contract WHERE bundle_id=? AND contract_key=?", String.class, bundle.bundleId(), key);
        for (String mutation : new String[]{"value", "type", "missing", "unknown", "key"}) transactions.execute(status -> {
            // Disposable corruption fixture only: this transactional DDL is restored by rollback.
            auth.jdbc().execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
            try {
                var raw = (ObjectNode) mapper.readTree(original);
                switch (mutation) {
                    case "value" -> raw.put("action", "MANAGE");
                    case "type" -> raw.put("mappingVersion", 1.0);
                    case "missing" -> raw.remove("scopeResolver");
                    case "unknown" -> raw.putNull("fabricatedField");
                    case "key" -> raw.put("contractKey", "fabricated.key");
                    default -> throw new AssertionError();
                }
                auth.jdbc().update("UPDATE auth_product_capability_contract SET descriptor=CAST(? AS jsonb) WHERE bundle_id=? AND contract_key=?", mapper.writeValueAsString(raw), bundle.bundleId(), key);
                assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(bundle));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            status.setRollbackOnly(); return null;
        });
        assertEquals(bundle.checksum(), reader.loadVersion(bundle).checksum());
    }
    @Test void metadataCompletionAndDuplicateKeysRemainClosed() {
        var bundle = repository.find("product-surfaces", 7).orElseThrow();
        transactions.execute(status -> {
            auth.jdbc().execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
            auth.jdbc().update("UPDATE auth_product_capability_contract SET lifecycle_state='RETIRED' WHERE bundle_id=?", bundle.bundleId());
            assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(bundle)); status.setRollbackOnly(); return null;
        });
        transactions.execute(status -> {
            auth.jdbc().execute("ALTER TABLE auth_product_authority_endpoint DISABLE TRIGGER USER");
            auth.jdbc().update("DELETE FROM auth_product_authority_endpoint WHERE bundle_id=?", bundle.bundleId());
            assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(bundle)); status.setRollbackOnly(); return null;
        });
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> auth.jdbc().update(
                "INSERT INTO auth_product_capability_contract SELECT * FROM auth_product_capability_contract WHERE bundle_id=? LIMIT 1", bundle.bundleId()));
        assertEquals(bundle.checksum(), reader.loadVersion(bundle).checksum());
    }
    @Test void recomputedMaliciousReferenceCannotReplacePinnedCanonicalContent() throws Exception {
        var bundle = repository.find("product-surfaces", 8).orElseThrow(); var malicious = (ObjectNode) reference(8);
        ((ObjectNode) malicious.get("capabilities").get(0)).put("action", "MANAGE"); malicious.put("checksum", validator.checksum(malicious));
        var resources = new DefaultResourceLoader(); var location = "classpath:product-authorization/product-surfaces-v1.bundle-v8.generated.json";
        var fake = new StoredDescriptorSeal(auth.jdbc(), repository, validator, mapper,
                new DefaultResourceLoader() { @Override public org.springframework.core.io.Resource getResource(String requested) {
                    return location.equals(requested) ? new ByteArrayResource(bytes(malicious)) : resources.getResource(requested);
                }});
        assertThrows(IllegalArgumentException.class, () -> fake.loadVersion(bundle));
    }
    @Test void unsupportedRecomputedIndexAndReorderedRecomputedArtifactAreRejected() throws Exception {
        var bundle = repository.find("product-surfaces", 8).orElseThrow();
        var loader = new DefaultResourceLoader(); String indexLocation = "classpath:product-authorization/product-surfaces-v1.index.generated.json";
        ObjectNode index;
        try (var input = loader.getResource(indexLocation).getInputStream()) { index = (ObjectNode) mapper.readTree(input); }
        index.put("latestVersion", 13); index.put("indexChecksum", validator.indexChecksum(index));
        assertThrows(IllegalArgumentException.class, () -> overridden(indexLocation, index).loadVersion(bundle));
        var reordered = (ObjectNode) reference(8); var capabilities = (com.fasterxml.jackson.databind.node.ArrayNode) reordered.get("capabilities");
        var first = capabilities.remove(0); capabilities.add(first); reordered.put("checksum", validator.checksum(reordered));
        assertThrows(IllegalArgumentException.class, () -> overridden("classpath:product-authorization/product-surfaces-v1.bundle-v8.generated.json", reordered).loadVersion(bundle));
    }
    @Test void extraUnknownActualRowAndActualBundleMetadataCannotPassEvenWithRecomputedChecksum() throws Exception {
        var bundle = repository.find("product-surfaces", 7).orElseThrow();
        transactions.execute(status -> {
            auth.jdbc().update("""
                    INSERT INTO auth_product_capability_contract
                    SELECT bundle_id, 'fabricated.extra', product_key, surface_key, lifecycle_state,
                           jsonb_set(descriptor, '{contractKey}', '\"fabricated.extra\"'::jsonb)
                      FROM auth_product_capability_contract WHERE bundle_id=? LIMIT 1
                    """, bundle.bundleId());
            assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(bundle)); status.setRollbackOnly(); return null;
        });
        transactions.execute(status -> {
            auth.jdbc().execute("ALTER TABLE auth_product_authorization_bundle DISABLE TRIGGER USER");
            auth.jdbc().update("UPDATE auth_product_authorization_bundle SET owner='fabricated-owner' WHERE bundle_id=?", bundle.bundleId());
            var altered = repository.find(bundle.bundleKey(), bundle.version()).orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(altered)); status.setRollbackOnly(); return null;
        });
        transactions.execute(status -> {
            auth.jdbc().execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
            auth.jdbc().execute("ALTER TABLE auth_product_authorization_bundle DISABLE TRIGGER USER");
            auth.jdbc().update("UPDATE auth_product_capability_contract SET descriptor=jsonb_set(descriptor, '{owner}', '\"fabricated-owner\"'::jsonb) WHERE bundle_id=?", bundle.bundleId());
            var malicious = mapper.valueToTree(repository.loadContract(bundle));
            auth.jdbc().update("UPDATE auth_product_authorization_bundle SET checksum=? WHERE bundle_id=?", validator.checksum(malicious), bundle.bundleId());
            var altered = repository.find(bundle.bundleKey(), bundle.version()).orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> reader.loadVersion(altered)); status.setRollbackOnly(); return null;
        });
        assertEquals(bundle.checksum(), reader.loadVersion(bundle).checksum());
    }
    @Test void actualImmutableDatabaseGuardStillRejectsDescriptorWritesWithoutCorruptionFixture() {
        var bundle = repository.find("product-surfaces", 7).orElseThrow();
        assertThrows(org.springframework.jdbc.UncategorizedSQLException.class, () -> auth.jdbc().update(
                "UPDATE auth_product_capability_contract SET descriptor=descriptor WHERE bundle_id=?", bundle.bundleId()));
        assertEquals(bundle.checksum(), reader.loadVersion(bundle).checksum());
    }
    @Test void actualDescriptorSourceVectorDriftAfterFirstSealIsRejected() {
        var bundle = repository.findActive("product-surfaces").orElseThrow(); var pointer = repository.findActivePointer("product-surfaces").orElseThrow();
        transactions.execute(status -> {
            var observed = org.mockito.Mockito.spy(validator); var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                var value = invocation.callRealMethod();
                if (calls.incrementAndGet() == 2) {
                    auth.jdbc().execute("ALTER TABLE auth_product_capability_contract DISABLE TRIGGER USER");
                    auth.jdbc().update("UPDATE auth_product_capability_contract SET descriptor=jsonb_set(descriptor, '{owner}', '\"drifted-owner\"'::jsonb) WHERE bundle_id=?", bundle.bundleId());
                }
                return value;
            }).when(observed).validateDocument(org.mockito.ArgumentMatchers.any(JsonNode.class));
            var changing = new StoredDescriptorSeal(auth.jdbc(), repository, observed, mapper);
            assertThrows(IllegalArgumentException.class, () -> changing.loadActive(bundle, pointer));
            assertEquals(2, calls.get()); status.setRollbackOnly(); return null;
        });
        assertEquals(bundle.checksum(), reader.loadActive(bundle, pointer).checksum());
    }
    static StoredDescriptorSeal overridden(String location, JsonNode document) {
        var loader = new DefaultResourceLoader();
        return new StoredDescriptorSeal(auth.jdbc(), repository, validator, mapper, new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String requested) {
                return location.equals(requested) ? new ByteArrayResource(bytes(document)) : loader.getResource(requested);
            }
        });
    }
    static byte[] bytes(JsonNode node) { try { return mapper.writeValueAsBytes(node); } catch (java.io.IOException error) { throw new AssertionError(error); } }
    static JsonNode reference(long version) throws Exception {
        try (var input = new DefaultResourceLoader().getResource("classpath:product-authorization/product-surfaces-v1.bundle-v" + version + ".generated.json").getInputStream()) { return mapper.readTree(input); }
    }
    static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected == null || actual == null) return path + " expected=" + (expected == null ? "<MISSING>" : expected) + " actual=" + (actual == null ? "<MISSING>" : actual);
        if (expected.isObject() && actual.isObject()) {
            var keys = new TreeSet<String>(); expected.fieldNames().forEachRemaining(keys::add); actual.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) if (!semanticEquals(expected.get(key), actual.get(key))) return firstDifference(expected.get(key), actual.get(key), path + '/' + key);
        }
        if (expected.isArray() && actual.isArray()) for (int index = 0; index < Math.min(expected.size(), actual.size()); index++)
            if (!semanticEquals(expected.get(index), actual.get(index))) return firstDifference(expected.get(index), actual.get(index), path + '/' + index);
        return path + " expected=" + expected + " actual=" + actual;
    }
    static boolean semanticEquals(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        var a = mapper.createObjectNode(); a.set("value", left);
        var b = mapper.createObjectNode(); b.set("value", right);
        return validator.checksum(a).equals(validator.checksum(b));
    }
}
