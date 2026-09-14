package com.dwp.services.approval.security;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.api.ApprovalFormLifecycleController;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full fresh Approval boot supplies the installed HTTP converters; facade doubles prove parser dispatch only. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true", "dwp.approval.policy-impact.source.enabled=false"})
class ApprovalRelease9JsonPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", PG::getJdbcUrl); properties.add("spring.datasource.username", PG::getUsername);
        properties.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired RequestMappingHandlerAdapter adapter;
    @Autowired ObjectMapper global;
    @Autowired ApprovalRelease9JsonConfiguration configuration;
    final ApprovalFormLifecycleFacade forms = mock(ApprovalFormLifecycleFacade.class);
    final ApprovalAttachmentManagementService attachments = mock(ApprovalAttachmentManagementService.class);
    static final UUID FORM = UUID.fromString("abcdabcd-abcd-abcd-abcd-abcdabcdabcd");
    record LegacyInput(String known) { }
    MappingJackson2HttpMessageConverter configuredConverter() {
        var converter = adapter.getMessageConverters().stream().filter(candidate -> candidate instanceof MappingJackson2HttpMessageConverter)
                .map(candidate -> (MappingJackson2HttpMessageConverter) candidate)
                .filter(candidate -> candidate.canRead(Branch.class, MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        var strict = converter.getObjectMappersForType(Branch.class).get(MediaType.APPLICATION_JSON);
        assertThat(strict).as("Installed new9 Form readers: %s", adapter.getMessageConverters().stream()
                .filter(candidate -> candidate instanceof MappingJackson2HttpMessageConverter)
                .map(candidate -> ((MappingJackson2HttpMessageConverter) candidate).getObjectMappersForType(Branch.class)).toList()).isNotNull();
        var problem = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        assertThat(strict.canDeserialize(strict.constructType(Branch.class), problem)).as("Installed reader failure: %s", problem.get()).isTrue();
        return converter;
    }
    MockMvc parser() {
        return MockMvcBuilders.standaloneSetup(new ApprovalFormLifecycleController(forms), new ApprovalAttachmentManagementController(attachments))
                .setMessageConverters(configuredConverter()).setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }
    @Test void actualFormReaderRejectsDuplicateAndTrailingInputBeforeDispatch() throws Exception {
        for (String raw : List.of("{\"expectedFormRevision\":9,\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null}",
                "{\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null} {}"))
            parser().perform(post("/v1/admin/forms/" + FORM + "/retire").contentType(MediaType.APPLICATION_JSON).content(raw)
                    .header("Idempotency-Key", "original")).andExpect(status().isBadRequest());
        verifyNoInteractions(forms, attachments);
    }
    @Test void actualAttachmentReaderRejectsDuplicateTrailingAndUnknownInputBeforeDispatch() throws Exception {
        for (String raw : List.of("{\"expectedAbsent\":false,\"expectedAbsent\":true,\"idempotencyKey\":\"original\"}",
                "{\"expectedAbsent\":true,\"idempotencyKey\":\"original\"} {}",
                "{\"expectedAbsent\":true,\"idempotencyKey\":\"original\",\"unknown\":true}"))
            parser().perform(post("/v1/admin/attachments/policies").contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());
        verifyNoInteractions(forms, attachments);
    }
    @Test void configuredFormUnknownHandlerAndExactIntegerDeserializerRemainInstalled() throws Exception {
        for (String raw : List.of("{\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null,\"unknown\":true}",
                "{\"expectedFormRevision\":0.0,\"expectedWorkspaceRevision\":null}"))
            parser().perform(post("/v1/admin/forms/" + FORM + "/retire").contentType(MediaType.APPLICATION_JSON).content(raw)
                    .header("Idempotency-Key", "original")).andExpect(status().isBadRequest());
        verifyNoInteractions(forms, attachments);
    }
    @Test void nestedSchemaDuplicatesAreRejectedWithoutRestrictingArbitraryUniqueSchemaMaps() throws Exception {
        var input = new UpdateWorkingDraft(FORM, 0L, null, Map.of("custom", Map.of("arbitrary", List.of("one", 2, false))),
                new MetadataInput(FORM, "Name", "Name", "Description", "Description", "OWNER", "REQUEST"), FORM);
        String raw = global.writeValueAsString(input);
        parser().perform(put("/v1/admin/forms/" + FORM + "/working-draft").contentType(MediaType.APPLICATION_JSON)
                .content(raw.replace("\"arbitrary\":", "\"arbitrary\":true,\"arbitrary\":"))
                .header("Idempotency-Key", "original")).andExpect(status().isBadRequest());
        verifyNoInteractions(forms);
        parser().perform(put("/v1/admin/forms/" + FORM + "/working-draft").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("Idempotency-Key", "original")).andExpect(status().isOk());
        verify(forms).update(FORM, input, "original", null);
    }
    @Test void legacyGlobalMapperAndUnrelatedTypedReadersAreUnmodified() throws Exception {
        assertThat(global.readValue("{\"known\":\"first\",\"known\":\"last\"}", Map.class)).containsEntry("known", "last");
        assertThat(global.readValue("{\"known\":\"value\",\"extra\":true}", LegacyInput.class)).isEqualTo(new LegacyInput("value"));
        assertThat(configuredConverter().getObjectMappersForType(LegacyInput.class)).isEmpty();
        assertThat(configuredConverter().getObjectMappersForType(com.dwp.services.approval.domain.ApprovalDtos.CreateRequest.class)).isEmpty();
    }
    @Test void binaryAndReceiptByteReadersAreNotRegisteredAsStrictJsonInputs() {
        assertThat(configuredConverter().getObjectMappersForType(byte[].class)).isEmpty();
        assertThat(configuredConverter().getObjectMappersForType(java.io.InputStream.class)).isEmpty();
        assertThat(adapter.getMessageConverters()).anyMatch(converter -> converter instanceof ByteArrayHttpMessageConverter);
    }
}
