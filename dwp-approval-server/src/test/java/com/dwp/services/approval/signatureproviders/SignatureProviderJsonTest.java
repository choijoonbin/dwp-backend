package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.*;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.ProbeInput;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.DraftInput;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.InitializeInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class SignatureProviderJsonTest {
    private static final String SHA = "a".repeat(64);
    private final ObjectMapper mapper = configured();
    private final SignatureProviderPolicyCompiler compiler = new SignatureProviderPolicyCompiler(new ObjectMapper());

    @Test void decodesTheExactDisabledPolicyAndPreservesLegacyUnknownFields() throws Exception {
        assertThat(mapper.readValue(initial(), InitializeInput.class).rules().signingEnabled()).isFalse();
        assertThat(mapper.readValue("{\"name\":\"legacy\",\"unknown\":true}", Legacy.class).name()).isEqualTo("legacy");
    }

    @Test void rejectsUnknownRootAndNestedPolicyProperties() throws Exception {
        var body = new ObjectMapper().readTree(initial());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("unexpected", true); reject(body.toString(), InitializeInput.class);
        body = new ObjectMapper().readTree(initial());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("rules")).put("unexpected", true); reject(body.toString(), InitializeInput.class);
    }

    @Test void rejectsMissingAndNullSecurityFlagsRatherThanDefaultingToFalse() throws Exception {
        var body = new ObjectMapper().readTree(initial());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("rules")).remove("signingEnabled"); reject(body.toString(), InitializeInput.class);
        body = new ObjectMapper().readTree(initial());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("rules")).putNull("requireAuthenticatedWebhook"); reject(body.toString(), InitializeInput.class);
    }

    @Test void rejectsFractionalUnsafeAndStringIntegerVersions() throws Exception {
        String body = draft();
        for (String value : new String[]{"1.0", "1.1", "9007199254740992", "9223372036854775808", "\"1\"", "null"})
            reject(body.replace("\"expectedVersion\":1", "\"expectedVersion\":" + value), DraftInput.class);
        assertThat(mapper.readValue(body, DraftInput.class).expectedVersion()).isEqualTo(1L);
    }

    @Test void rejectsNumericAndStringBooleans() throws Exception {
        for (String value : new String[]{"0", "1", "\"true\"", "\"false\"", "null"})
            reject(initial().replace("\"expectedAbsent\":true", "\"expectedAbsent\":" + value), InitializeInput.class);
    }

    @Test void rejectsDuplicatesAtRootAndInsideRules() throws Exception {
        reject(initial().replace("\"expectedAbsent\":true", "\"expectedAbsent\":true,\"expectedAbsent\":true"), InitializeInput.class);
        reject(initial().replace("\"signingEnabled\":false", "\"signingEnabled\":false,\"signingEnabled\":false"), InitializeInput.class);
    }

    @Test void rejectsNoncanonicalUuidAndDuplicateProviderTargets() {
        String target = "{\"providerId\":\"ABCDEFAB-1234-1234-1234-123456789012\",\"expectedProviderVersion\":0,\"expectedProviderSha256\":\"" + SHA + "\",\"expectedConfiguration\":null}";
        String probe = "{\"expectedSourceRevision\":\"sigp-" + SHA + "\",\"expectedSourceSha256\":\"" + SHA + "\",\"allProviders\":true,\"targets\":[" + target + "],\"idempotencyKey\":\"probe-1\"}";
        reject(probe, ProbeInput.class);
        String lower = target.replace("ABCDEFAB", "abcdefab");
        reject(probe.replace(target, lower).replace("[" + lower + "]", "[" + lower + "," + lower + "]"), ProbeInput.class);
    }

    @Test void rejectsUtf16BomMalformedUtf8AndTrailingDocumentsInPrivateCodec() throws Exception {
        var json = new SignatureProviderJson(new ObjectMapper());
        assertThatThrownBy(() -> json.input(initial().getBytes(StandardCharsets.UTF_16LE), InitializeInput.class)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> json.input(("\ufeff" + initial()).getBytes(StandardCharsets.UTF_8), InitializeInput.class)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> json.input(new byte[]{(byte) 0xc3, (byte) 0x28}, InitializeInput.class)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> json.input((initial() + "{}").getBytes(StandardCharsets.UTF_8), InitializeInput.class)).isInstanceOf(java.io.IOException.class);
    }

    private String initial() throws Exception {
        return new ObjectMapper().writeValueAsString(new InitializeInput(true, "sigp-" + SHA, SHA, compiler.disabledInitialRules(), "initialize-1"));
    }
    private String draft() throws Exception {
        return new ObjectMapper().writeValueAsString(new DraftInput(1L, null, "sigp-" + SHA, SHA, compiler.disabledInitialRules(), "draft-1"));
    }
    private void reject(String value, Class<?> type) { assertThatThrownBy(() -> mapper.readValue(value, type)).isInstanceOf(java.io.IOException.class); }
    private ObjectMapper configured() {
        var builder = new Jackson2ObjectMapperBuilder();
        new SignatureProviderJsonConfiguration().approvalSignatureProviderScopedInputs().customize(builder);
        return builder.build();
    }
    private record Legacy(String name) { }
}
