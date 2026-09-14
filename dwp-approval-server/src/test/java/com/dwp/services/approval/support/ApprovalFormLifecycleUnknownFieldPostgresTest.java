package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.api.ApprovalFormLifecycleController;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true", "dwp.approval.policy-impact.source.enabled=false"})
class ApprovalFormLifecycleUnknownFieldPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr12-forms-strict-input");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl); registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired ObjectMapper json;
    record LegacyProbe(String known) { }
    @Test void actualConfiguredMapperRejectsUnknownKeysInAllFiveFormInputRecords() throws Exception {
        UUID id = UUID.randomUUID(); var metadata = new MetadataInput(id, "Name", "Name", "Description", "Description", "OWNER", "REQUEST");
        var inputs = List.of(metadata, new Branch(0L, null), new AvailabilityChange(0L, null),
                new PublishReviewed(id, null, 0L, 0L, "a".repeat(64), "b".repeat(64)),
                new UpdateWorkingDraft(id, 0L, null, Map.of("schemaVersion", 1, "fields", List.of()), metadata, id));
        var soft = new SoftAssertions();
        for (var input : inputs) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(input);
            tree.put("unexpectedField", "must not be silently ignored");
            soft.assertThatThrownBy(() -> json.readValue(tree.toString(), input.getClass())).as(input.getClass().getSimpleName())
                    .isInstanceOf(UnrecognizedPropertyException.class);
        }
        soft.assertAll();
    }
    @Test void legacyUnknownFieldHandlingAndArbitrarySchemaMapsStayUnchanged() throws Exception {
        assertThat(json.readValue("{\"known\":\"value\",\"legacyExtra\":true}", LegacyProbe.class)).isEqualTo(new LegacyProbe("value"));
        var metadata = new MetadataInput(UUID.randomUUID(), "Name", "Name", "Description", "Description", "OWNER", "REQUEST");
        var schema = Map.<String, Object>of("custom", Map.of("arbitrary", List.of("one", 2, false)));
        var input = new UpdateWorkingDraft(UUID.randomUUID(), 0L, null, schema, metadata, UUID.randomUUID());
        assertThat(json.readValue(json.writeValueAsString(input), UpdateWorkingDraft.class)).isEqualTo(input);
    }
    @Test void nestedMetadataUnknownKeysAreRejectedByTheActualConfiguredMapper() throws Exception {
        var metadata = new MetadataInput(UUID.randomUUID(), "Name", "Name", "Description", "Description", "OWNER", "REQUEST");
        var input = new UpdateWorkingDraft(UUID.randomUUID(), 0L, null, Map.of("arbitrary", List.of("value")), metadata, UUID.randomUUID());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(input);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("metadata")).put("unexpectedMetadataField", true);
        assertThatThrownBy(() -> json.readValue(tree.toString(), UpdateWorkingDraft.class)).isInstanceOf(UnrecognizedPropertyException.class);
    }
    @Test void actualConfiguredHttpParserRejectsAllMutationUnknownKeysBeforeFacadeDispatch() throws Exception {
        var facade = mock(ApprovalFormLifecycleFacade.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormLifecycleController(facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
        UUID form = UUID.randomUUID(), version = UUID.randomUUID(); String base = "/v1/admin/forms/" + form;
        var metadata = new MetadataInput(form, "Name", "Name", "Description", "Description", "OWNER", "REQUEST");
        var update = new UpdateWorkingDraft(version, 0L, null, Map.of("arbitrary", Map.of("custom", true)), metadata, form);
        var publish = new PublishReviewed(version, null, 0L, 0L, "a".repeat(64), "b".repeat(64));
        var cases = Map.of("/versions/" + version + "/branch", new Branch(0L, null),
                "/retire", new AvailabilityChange(0L, null), "/reinstate", new AvailabilityChange(0L, null),
                "/working-draft", update, "/publish-reviewed", publish);
        // Parser-only dispatch test: no Product Surface or HIGH authority is fabricated by this fixture.
        for (var input : cases.entrySet()) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(input.getValue()); tree.put("unexpectedField", true);
            var request = input.getKey().equals("/working-draft") ? put(base + input.getKey()) : post(base + input.getKey());
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(tree.toString()).header("Idempotency-Key", "original")
                    .header("X-DWP-Step-Up-Challenge", "parser-only-placeholder")
                    .header("X-DWP-Expected-Decision-Revision", "psr-" + "a".repeat(64))
                    .header("X-DWP-Expected-Object-Version", "0")).andExpect(status().isBadRequest());
        }
        var nested = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(update);
        ((com.fasterxml.jackson.databind.node.ObjectNode) nested.path("metadata")).put("unexpectedMetadataField", true);
        mvc.perform(put(base + "/working-draft").contentType(MediaType.APPLICATION_JSON).content(nested.toString())
                .header("Idempotency-Key", "original")).andExpect(status().isBadRequest());
        verifyNoInteractions(facade);
    }
}
