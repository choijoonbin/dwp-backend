package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Installed same-DB bootstrap only; real private V26 completion and replay proof are separate joint gates. */
class WorkflowInformationActualAuthNineHarnessTest {
    @Test void genuineNineBootstrapInstallsTwoDisjointDefaultHttpChainsOnOneAuthDatabase() throws Exception {
        var keys = keys();
        try (var auth = new WorkflowInformationActualAuthNineHarness(keys.get(0), keys.get(1), keys.get(2), keys.get(3), keys.get(4), keys.get(5));
                var http = HttpClient.newHttpClient()) {
            assertEquals(9, auth.registryVersion());
            assertEquals("02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7", auth.sealedRegistryChecksum());
            assertEquals(9, auth.jdbc().queryForObject("SELECT b.version FROM auth_product_authorization_active a JOIN auth_product_authorization_bundle b ON b.bundle_id=a.bundle_id WHERE a.bundle_key='product-surfaces'", Long.class));
            assertNotEquals(auth.runtimeEndpoint().getPort(), auth.replayEndpoint().getPort());
            for (var endpoint : List.of(auth.runtimeEndpoint(), auth.replayEndpoint())) {
                var response = http.send(HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode()); assertFalse(response.body().contains("attestation"));
            }
            assertTrue(auth.redis().keys("dwp:auth:*workflow*:*" ).isEmpty());
            assertTrue(auth.redis().keys("dwp:auth:information-replay:v1:*" ).isEmpty());
        }
    }
    @Test void crossPurposeKeyReuseIsRejectedBeforeStartingAnyDatabase() throws Exception {
        var keys = keys();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowInformationActualAuthNineHarness(
                keys.get(0), keys.get(1), keys.get(2), keys.get(0), keys.get(4), keys.get(5)));
    }
    private static List<RSAKey> keys() throws Exception {
        var keys = new java.util.ArrayList<RSAKey>();
        for (int index = 0; index < 6; index++) keys.add(new RSAKeyGenerator(2048).keyID("combined-nine-" + index).generate());
        return keys;
    }
}
