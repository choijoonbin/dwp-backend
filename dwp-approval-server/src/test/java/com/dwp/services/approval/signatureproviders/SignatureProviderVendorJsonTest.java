package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignatureProviderVendorJsonTest {
    private final SignatureProviderVendorJson json = new SignatureProviderVendorJson(new ObjectMapper());

    @Test void acceptsActualPrivateVendorObjectWithoutReinterpretingItAsAuthority() throws Exception {
        var response = response("application/json;charset=UTF-8", "{\"account_id\":\"opaque-vendor-account\",\"unknown_vendor_field\":true}");
        assertThat(json.object(response).get("unknown_vendor_field").booleanValue()).isTrue();
        assertThat(SignatureProviderVendorJson.text(json.object(response), "account_id", 200)).isEqualTo("opaque-vendor-account");
        byte[] altered = response.body(); altered[0] = 0; assertThat(response.body()[0]).isEqualTo((byte) '{');
    }

    @Test void rejectsAutodetectedUtf16BomMalformedDuplicateAndTrailingJson() {
        for (var response : new SignatureProviderHttpTransport.Response[]{
                new SignatureProviderHttpTransport.Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_16), Instant.now()),
                response("application/json", "\ufeff{}"), response("application/json;charset=UTF-16", "{}"),
                response("application/json", "{\"account_id\":\"one\",\"account_id\":\"two\"}"), response("application/json", "{}{}"),
                new SignatureProviderHttpTransport.Response(200, "application/json", new byte[]{(byte) 0xc3, 0x28}, Instant.now())})
            assertThatThrownBy(() -> json.object(response)).isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsHttpFailureNonobjectsInvalidFieldTypesAndWireSizeOverflow() throws Exception {
        assertThatThrownBy(() -> json.object(new SignatureProviderHttpTransport.Response(503, "application/json", "{}".getBytes(StandardCharsets.UTF_8), Instant.now())))
                .isInstanceOf(java.io.IOException.class);
        for (String invalid : new String[]{"[]", "null", "true"}) assertThatThrownBy(() -> json.object(response("application/json", invalid)))
                .isInstanceOf(java.io.IOException.class);
        var object = json.object(response("application/json", "{\"account_id\":1}"));
        assertThatThrownBy(() -> SignatureProviderVendorJson.text(object, "account_id", 200)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> json.object(new SignatureProviderHttpTransport.Response(200, "application/json",
                new byte[SignatureProviderHttpTransport.MAX_BYTES + 1], Instant.now()))).isInstanceOf(java.io.IOException.class);
    }

    private SignatureProviderHttpTransport.Response response(String type, String body) {
        return new SignatureProviderHttpTransport.Response(200, type, body.getBytes(StandardCharsets.UTF_8), Instant.now());
    }
}
