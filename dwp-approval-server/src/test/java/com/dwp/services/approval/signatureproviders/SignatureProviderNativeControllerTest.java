package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.ExternalCreateInput;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.ProviderTarget;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.SourcePin;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.PublishInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SignatureProviderNativeControllerTest {
    private static final String SHA = "a".repeat(64);
    private final ApprovalSignatureProviderService admin = mock(ApprovalSignatureProviderService.class);
    private final ApprovalExternalSignatureService external = mock(ApprovalExternalSignatureService.class);
    private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var builder = new Jackson2ObjectMapperBuilder();
        new SignatureProviderJsonConfiguration().approvalSignatureProviderScopedInputs().customize(builder);
        mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(
                        new ApprovalSignatureProviderController(admin),
                        new ApprovalExternalSignatureController(external))
                .setControllerAdvice(new SignatureProviderRequestBodyAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void actualControllersExposeExactlyTheNineteenNativeOperations() {
        Set<String> actual = new HashSet<>();
        collect(ApprovalSignatureProviderController.class, actual);
        collect(ApprovalExternalSignatureController.class, actual);
        Set<String> expected = new HashSet<>();
        for (SignatureProviderOperation operation : SignatureProviderOperation.values())
            expected.add(operation.method() + " " + operation.pathTemplate());
        assertThat(actual).isEqualTo(expected).hasSize(19);
    }

    @Test
    void externalCreateUsesTheStrictScopedDtoBeforeCallingTheService() throws Exception {
        UUID requestId = UUID.randomUUID();
        SourcePin configuration = new SourcePin(UUID.randomUUID(), 2, SHA);
        ProviderTarget target = new ProviderTarget(configuration.sourceId(), 2, SHA, configuration);
        ExternalCreateInput input = new ExternalCreateInput(3L, "sigp-" + SHA, SHA,
                target, "external-create-1");
        String body = mapper.writeValueAsString(input);

        mvc.perform(post("/v1/requests/{requestId}/external-signature-requests", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(external).create(requestId, input);

        reset(external);
        mvc.perform(post("/v1/requests/{requestId}/external-signature-requests", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.substring(0, body.length() - 1) + ",\"foreign\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(external);
    }

    @Test
    void highRiskRoutesForwardOnlyTheNormalizedCommandBindingHeaders() throws Exception {
        UUID policyId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        PublishInput publish = new PublishInput(7L, draftId, "sigp-" + SHA, SHA,
                SHA, "publish-key");
        mvc.perform(post("/v1/admin/signatures/policies/{policyId}/publish", policyId)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(publish))
                        .header("X-DWP-Step-Up-Challenge", " challenge ")
                        .header("Idempotency-Key", " publish-key ")
                        .header("X-DWP-Expected-Decision-Revision", " psr-" + SHA + " ")
                        .header("X-DWP-Expected-Object-Version", "7"))
                .andExpect(status().isOk());
        var adminHeaders = ArgumentCaptor.forClass(ApprovalStepUpHeaders.class);
        verify(admin).publish(eq(policyId), eq(publish), adminHeaders.capture());
        assertHeaders(adminHeaders.getValue(), "challenge", "publish-key", 7L);

        UUID requestId = UUID.randomUUID();
        var command = new ApprovalSignatureProviderDtos.ExternalCommandInput(
                4L, "sigp-" + SHA, SHA, "handover-key");
        mvc.perform(post("/v1/external-signature-requests/{id}/handovers", requestId)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command))
                        .header("X-DWP-Step-Up-Challenge", " handover ")
                        .header("Idempotency-Key", " handover-key ")
                        .header("X-DWP-Expected-Decision-Revision", " psr-" + SHA + " ")
                        .header("X-DWP-Expected-Object-Version", "4"))
                .andExpect(status().isOk());
        var externalHeaders = ArgumentCaptor.forClass(ApprovalStepUpHeaders.class);
        verify(external).handover(eq(requestId), eq(command), externalHeaders.capture());
        assertHeaders(externalHeaders.getValue(), "handover", "handover-key", 4L);
    }

    private void assertHeaders(ApprovalStepUpHeaders headers, String challenge,
                               String idempotencyKey, long version) {
        assertThat(headers.challenge()).isEqualTo(challenge);
        assertThat(headers.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(headers.decisionRevision()).isEqualTo("psr-" + SHA);
        assertThat(headers.expectedObjectVersion()).isEqualTo(version);
    }

    private void collect(Class<?> controller, Set<String> routes) {
        String prefix = controller.getAnnotation(RequestMapping.class).value()[0];
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (get != null) add(routes, "GET", prefix, get.value());
            if (post != null) add(routes, "POST", prefix, post.value());
            if (put != null) add(routes, "PUT", prefix, put.value());
        }
    }

    private void add(Set<String> routes, String method, String prefix, String[] paths) {
        assertThat(paths).hasSize(1);
        assertThat(routes.add(method + " " + prefix + paths[0])).isTrue();
    }
}
