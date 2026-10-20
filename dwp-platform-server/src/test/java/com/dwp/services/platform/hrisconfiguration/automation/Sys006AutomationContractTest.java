package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sys006AutomationContractTest {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final UUID ID_1 =
            UUID.fromString("00000000-0000-4000-8000-000000000601");
    private static final UUID ID_2 =
            UUID.fromString("00000000-0000-4000-8000-000000000602");
    private static final UUID ID_3 =
            UUID.fromString("00000000-0000-4000-8000-000000000603");

    @Test
    void reusesTheCanonicalPdxArtifactsAndEnforcesTheSys006NumericSubset() {
        AutomationHandlerManifestV1 manifest = manifest(
                "ACTIVE", List.of("TENANT"), "FAILED_ITEMS_ONLY", 3);
        AutomationInvocationV1 invocation = invocation(
                Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER);
        AutomationRunItemReceiptV1 receipt = receipt(
                Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER,
                Sys006TypedArtifactContract.MAX_ITEM_SEQUENCE);

        assertThat(Sys006TypedArtifactContract.requireInteroperable(manifest))
                .isSameAs(manifest);
        assertThat(Sys006TypedArtifactContract.requireInteroperable(invocation))
                .isSameAs(invocation);
        assertThat(Sys006TypedArtifactContract.requireInteroperable(receipt))
                .isSameAs(receipt);

        AutomationInvocationV1 unsafeVersion = invocation(
                Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER + 1L);
        AutomationRunItemReceiptV1 unsafeRevision = receipt(
                Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER + 1L, 1L);
        AutomationRunItemReceiptV1 unsafeSequence = receipt(
                1L, Sys006TypedArtifactContract.MAX_ITEM_SEQUENCE + 1L);

        assertThatThrownBy(() -> Sys006TypedArtifactContract
                .requireInteroperable(unsafeVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("automationVersion");
        assertThatThrownBy(() -> Sys006TypedArtifactContract
                .requireInteroperable(unsafeRevision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runRevision");
        assertThatThrownBy(() -> Sys006TypedArtifactContract
                .requireInteroperable(unsafeSequence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemSequence");
    }

    @Test
    void canonicalPdxTransportsRejectUnknownDuplicateAndTrailingContent()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        String manifestJson = mapper.writeValueAsString(manifest(
                "ACTIVE", List.of("TENANT"), "FAILED_ITEMS_ONLY", 3));
        String invocationJson = mapper.writeValueAsString(invocation(1L));
        String receiptJson = mapper.writeValueAsString(receipt(1L, 1L));

        assertClosedTransport(
                mapper,
                AutomationHandlerManifestV1.class,
                manifestJson,
                manifestJson.replace(
                        "\"handlerKey\":\"hris.payroll\"",
                        "\"handlerKey\":\"hris.payroll\","
                                + "\"handlerKey\":\"hris.payroll\""));
        assertClosedTransport(
                mapper,
                AutomationInvocationV1.class,
                invocationJson,
                invocationJson.replace(
                        "\"automationVersion\":1",
                        "\"automationVersion\":1,\"automationVersion\":1"));
        assertClosedTransport(
                mapper,
                AutomationRunItemReceiptV1.class,
                receiptJson,
                receiptJson.replace(
                        "\"runRevision\":1",
                        "\"runRevision\":1,\"runRevision\":1"));
    }

    @Test
    void handlerRegistryRevalidatesFreshExactActiveEvidenceWithoutCaching() {
        SignedManifest approved = signed(manifest(
                "ACTIVE", List.of("TENANT"), "FAILED_ITEMS_ONLY", 3));
        AtomicInteger lookups = new AtomicInteger();
        AutomationHandlerRegistryPort registry = identity -> {
            lookups.incrementAndGet();
            assertThat(identity).isEqualTo(approved.identity());
            return Optional.of(approved);
        };

        assertThat(registry.verifyCurrentActive(approved))
                .hasValueSatisfying(value -> assertThat(value)
                        .isSameAs(approved.manifest()));
        assertThat(registry.verifyCurrentActive(approved)).isPresent();
        assertThat(lookups).hasValue(2);
    }

    @Test
    void handlerRegistryFailsClosedWhenUnavailableInactiveOrMismatched() {
        SignedManifest approved = signed(manifest(
                "ACTIVE", List.of("TENANT"), "FAILED_ITEMS_ONLY", 3));

        assertThat(((AutomationHandlerRegistryPort) identity -> Optional.empty())
                .verifyCurrentActive(approved)).isEmpty();
        assertThat(((AutomationHandlerRegistryPort) identity -> {
            throw new IllegalStateException("registry unavailable");
        }).verifyCurrentActive(approved)).isEmpty();

        List<SignedManifest> rejected = List.of(
                signed(manifest(
                        "SUSPENDED", List.of("TENANT"), "FAILED_ITEMS_ONLY", 3)),
                signed(manifest(
                        "ACTIVE", List.of("TENANT", "ORGANIZATION"),
                        "FAILED_ITEMS_ONLY", 3)),
                signed(manifest("ACTIVE", List.of("TENANT"),
                        "WHOLE_INVOCATION", 3)),
                signed(manifest("ACTIVE", List.of("TENANT"),
                        "FAILED_ITEMS_ONLY", 4)),
                new SignedManifest(
                        approved.manifest(), "different-signing-key", DIGEST_B,
                        DIGEST_C, DIGEST_D, 7L),
                new SignedManifest(
                        approved.manifest(), "signing-key-1", DIGEST_A,
                        DIGEST_C, DIGEST_D, 7L),
                new SignedManifest(
                        approved.manifest(), "signing-key-1", DIGEST_B,
                        DIGEST_C, DIGEST_D, 8L));

        rejected.forEach(current -> {
            AutomationHandlerRegistryPort registry = identity -> Optional.of(current);
            assertThat(registry.verifyCurrentActive(approved)).isEmpty();
        });
    }

    private static <T> void assertClosedTransport(
            ObjectMapper mapper,
            Class<T> type,
            String json,
            String duplicatePropertyJson) {
        String unknownPropertyJson =
                json.substring(0, json.length() - 1) + ",\"unknown\":true}";
        for (String malformed : List.of(
                unknownPropertyJson, duplicatePropertyJson, json + " {}")) {
            assertThatThrownBy(() -> mapper.readValue(malformed, type))
                    .isInstanceOf(Exception.class);
        }
    }

    private static SignedManifest signed(AutomationHandlerManifestV1 manifest) {
        return new SignedManifest(
                manifest, "signing-key-1", DIGEST_B, DIGEST_C, DIGEST_D, 7L);
    }

    private static AutomationHandlerManifestV1 manifest(
            String state,
            List<String> allowedScopeTypes,
            String retryMode,
            int maxAttempts) {
        return new AutomationHandlerManifestV1(
                "hris.payroll",
                "dwp-pay-server",
                "AutomationInput.v1",
                "schema:automation-input.v1",
                "schema:automation-receipt.v1",
                DIGEST_A,
                allowedScopeTypes,
                retryMode,
                maxAttempts,
                state);
    }

    private static AutomationInvocationV1 invocation(long automationVersion) {
        return new AutomationInvocationV1(
                ID_1,
                ID_2,
                automationVersion,
                "hris.payroll",
                "AutomationInput.v1",
                "TENANT",
                ID_3,
                Instant.parse("2026-10-20T00:00:00Z"),
                "actor:84",
                "idem-key-006",
                "input:006",
                DIGEST_A,
                1,
                ID_3);
    }

    private static AutomationRunItemReceiptV1 receipt(
            long runRevision, long itemSequence) {
        return new AutomationRunItemReceiptV1(
                ID_1,
                runRevision,
                ID_2,
                itemSequence,
                "subject:006",
                "FAILED",
                1,
                Instant.parse("2026-10-20T00:00:00Z"),
                Instant.parse("2026-10-20T00:01:00Z"),
                null,
                null,
                "OWNER_TEMPORARILY_UNAVAILABLE",
                true,
                Instant.parse("2026-10-20T00:02:00Z"),
                ID_3);
    }
}
