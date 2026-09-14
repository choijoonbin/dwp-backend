package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.InitializeInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class SignatureProviderRequestBodyAdviceTest {
    private final InputController controller = new InputController();
    private MockMvc mvc;
    private String valid;

    @BeforeEach void configureActualMvcMessageConversion() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new SignatureProviderJsonConfiguration().approvalSignatureProviderScopedInputs().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new SignatureProviderRequestBodyAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build())).build();
        String sha = "a".repeat(64);
        valid = new ObjectMapper().writeValueAsString(new InitializeInput(true, "sigp-" + sha, sha,
                new SignatureProviderPolicyCompiler(new ObjectMapper()).disabledInitialRules(), "initialize-1"));
    }

    @Test void acceptsUtf8AndLeavesLegacyUnknownFieldBehaviorUnchanged() throws Exception {
        mvc.perform(post("/test/typed").contentType("application/json;charset=UTF-8").content(valid)).andExpect(status().isOk());
        mvc.perform(post("/test/legacy").contentType("application/json").content("{\"name\":\"legacy\",\"unknown\":true}"))
                .andExpect(status().isOk());
        assertThat(controller.typedCalls.get()).isEqualTo(1);
        assertThat(controller.legacyCalls.get()).isEqualTo(1);
    }

    @Test void rejectsUtf16WithOrWithoutBomBeforeTheFacadeIsCalled() throws Exception {
        for (var encoding : new java.nio.charset.Charset[]{StandardCharsets.UTF_16, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE})
            mvc.perform(post("/test/typed").contentType("application/json").content(valid.getBytes(encoding)))
                    .andExpect(status().isBadRequest());
        mvc.perform(post("/test/typed").contentType("application/json;charset=UTF-16").content(valid.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
        assertThat(controller.typedCalls.get()).isZero();
    }

    @Test void rejectsBomMalformedUtf8AndOversizedWireBytesBeforeDispatch() throws Exception {
        for (byte[] raw : new byte[][]{("\ufeff" + valid).getBytes(StandardCharsets.UTF_8), {(byte) 0xc3, 0x28},
                new byte[SignatureProviderJson.MAX_BODY_BYTES + 1]})
            mvc.perform(post("/test/typed").contentType("application/json").content(raw)).andExpect(status().isBadRequest());
        assertThat(controller.typedCalls.get()).isZero();
    }

    @Test void rejectsUnknownNestedFieldsDuplicatesAndTrailingDocumentsBeforeDispatch() throws Exception {
        for (String body : new String[]{valid.replace("\"signingEnabled\":false", "\"signingEnabled\":false,\"foreign\":true"),
                valid.replace("\"expectedAbsent\":true", "\"expectedAbsent\":true,\"expectedAbsent\":true"), valid + "{}"})
            mvc.perform(post("/test/typed").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertThat(controller.typedCalls.get()).isZero();
    }

    @RestController static class InputController {
        final AtomicInteger typedCalls = new AtomicInteger(), legacyCalls = new AtomicInteger();
        @PostMapping("/test/typed") void typed(@RequestBody InitializeInput body) { typedCalls.incrementAndGet(); }
        @PostMapping("/test/legacy") void legacy(@RequestBody Legacy body) { legacyCalls.incrementAndGet(); }
    }
    private record Legacy(String name) { }
}
