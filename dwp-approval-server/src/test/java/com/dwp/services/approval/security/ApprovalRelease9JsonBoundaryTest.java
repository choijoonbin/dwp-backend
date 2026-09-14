package com.dwp.services.approval.security;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.api.ApprovalFormLifecycleController;
import com.dwp.services.approval.attachment.ApprovalAttachmentManagementController;
import com.dwp.services.approval.attachment.ApprovalAttachmentManagementService;
import com.dwp.services.approval.forms.ApprovalFormLifecycleFacade;
import com.dwp.services.approval.forms.ApprovalFormLifecycleJsonConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Parser dispatch only; no signed authority or business mutation success is claimed. */
class ApprovalRelease9JsonBoundaryTest {
    record LegacyInput(String known) { }
    @Test void repeatedFieldsInNewFormAndAttachmentInputsNeverReachTheirNativeFacade() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ApprovalFormLifecycleJsonConfiguration.class)) {
            var builder = new Jackson2ObjectMapperBuilder().featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            context.getBeansOfType(Jackson2ObjectMapperBuilderCustomizer.class).values().forEach(customizer -> customizer.customize(builder));
            var converter = new MappingJackson2HttpMessageConverter(builder.build());
            new ApprovalRelease9JsonConfiguration().extendMessageConverters(List.of(converter));
            var forms = mock(ApprovalFormLifecycleFacade.class); var attachments = mock(ApprovalAttachmentManagementService.class);
            var mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormLifecycleController(forms), new ApprovalAttachmentManagementController(attachments))
                    .setMessageConverters(converter).setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
            for (String body : List.of("{\"expectedFormRevision\":9,\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null}",
                    "{\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null} {}"))
                mvc.perform(post("/v1/admin/forms/abcdabcd-abcd-abcd-abcd-abcdabcdabcd/retire")
                        .contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "original")).andExpect(status().isBadRequest());
            mvc.perform(post("/v1/admin/attachments/policies").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedAbsent\":false,\"expectedAbsent\":true,\"idempotencyKey\":\"original\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(forms, attachments);
            assertThat(converter.getObjectMapper().readValue("{\"known\":\"first\",\"known\":\"last\"}", java.util.Map.class))
                    .containsEntry("known", "last");
        }
    }
}
