package com.dwp.services.approval.formsv3;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.formsv3.ApprovalFormV3Models.ArchiveDraft;
import com.dwp.services.approval.formsv3.ApprovalFormV3Models.UpdateDraft;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalFormV3ControllerTest {
    private final ApprovalFormV3Service forms = mock(ApprovalFormV3Service.class);
    private final UUID formId = UUID.randomUUID();
    private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(forms);
        mapper = new ObjectMapper().findAndRegisterModules()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormV3Controller(forms))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }

    @Test
    void readsValidateReviewEvaluateAndVersionFencedDraftCommandsDispatch() throws Exception {
        mvc.perform(get("/v1/admin/forms/studio-v3").param("lifecycleState", "DRAFT").param("limit", "20"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId)).andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId + "/versions").param("size", "20"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/validate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schema\":{}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/review"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isOk());

        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceVersion\":4,\"schema\":{}}")
                        .header("Idempotency-Key", "update-4")
                        .header("X-DWP-Expected-Object-Version", "4"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceVersion\":5}")
                        .header("Idempotency-Key", "archive-5")
                        .header("X-DWP-Expected-Object-Version", "5"))
                .andExpect(status().isOk());

        verify(forms).workspaces(new ApprovalFormV3Models.WorkspaceFilter(null, "DRAFT", null, 20));
        verify(forms).workspace(formId);
        verify(forms).history(formId, 20);
        verify(forms).validateSchema(Map.of());
        verify(forms).review(formId);
        verify(forms).evaluate(formId, Map.of());
        verify(forms).update(formId, new UpdateDraft(4, Map.of()), "update-4", null);
        verify(forms).archive(formId, new ArchiveDraft(5), "archive-5", null);
    }

    @Test
    void structuredEditorAndVersionDiffDispatchExactCommands() throws Exception {
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId + "/versions/diff")
                        .param("fromVersion", "1").param("toVersion", "2"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceVersion":2,"pageKey":"mainPage",
                                 "sectionKey":"requestInfo","index":1,
                                 "field":{"key":"costCenter","type":"TEXT","control":"TEXT"},
                                 "compatibilityMode":"BACKWARD_COMPATIBLE"}
                                """)
                        .header("Idempotency-Key", "add-field")
                        .header("X-DWP-Expected-Object-Version", "2"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/fields/summary/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceVersion":3,"targetFieldKey":"summaryCopy",
                                 "index":1,"compatibilityMode":"BACKWARD_COMPATIBLE"}
                                """)
                        .header("Idempotency-Key", "clone-field")
                        .header("X-DWP-Expected-Object-Version", "3"))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/fields/summary/properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceVersion":4,"properties":{"required":false},
                                 "compatibilityMode":"BACKWARD_COMPATIBLE"}
                                """)
                        .header("Idempotency-Key", "field-properties")
                        .header("X-DWP-Expected-Object-Version", "4"))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId
                        + "/sections/requestInfo/field-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceVersion":5,"pageKey":"mainPage",
                                 "fieldKeys":["summary","summaryCopy","costCenter"]}
                                """)
                        .header("Idempotency-Key", "reorder-fields")
                        .header("X-DWP-Expected-Object-Version", "5"))
                .andExpect(status().isOk());
        mvc.perform(delete("/v1/admin/forms/studio-v3/" + formId + "/fields/summaryCopy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceVersion":6,
                                 "compatibilityMode":"BACKWARD_COMPATIBLE"}
                                """)
                        .header("Idempotency-Key", "delete-field")
                        .header("X-DWP-Expected-Object-Version", "6"))
                .andExpect(status().isOk());

        verify(forms).diff(formId, 1, 2);
        verify(forms).addField(eq(formId), any(), eq("add-field"), isNull());
        verify(forms).cloneField(eq(formId), any(), eq("clone-field"), isNull());
        verify(forms).updateFieldProperties(eq(formId), any(), eq("field-properties"), isNull());
        verify(forms).reorderFields(eq(formId), any(), eq("reorder-fields"), isNull());
        verify(forms).deleteField(eq(formId), any(), eq("delete-field"), isNull());
    }

    @Test
    void authorizationConflictAndAuthorityUnavailableMapTo403409And503() throws Exception {
        when(forms.workspace(formId)).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId)).andExpect(status().isForbidden());

        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceVersion\":4,\"schema\":{}}")
                        .header("Idempotency-Key", "update-4")
                        .header("X-DWP-Expected-Object-Version", "3"))
                .andExpect(status().isConflict());

        when(forms.validateSchema(any())).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        mvc.perform(post("/v1/admin/forms/studio-v3/validate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schema\":{}}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void callerSuppliedScopeRolesAndUnknownQueriesFailClosedBeforeService() throws Exception {
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId)
                        .param("managementResourceSetKey", "RS_OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{},\"roles\":[\"TENANT_ADMIN\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/studio-v3/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schema\":{},\"tenantId\":43}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forms);
    }

    @Test
    void mutationsRequireSingleKeysSafeVersionsAndCanonicalIdentifiers() throws Exception {
        String body = "{\"expectedWorkspaceVersion\":4,\"schema\":{}}";
        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-DWP-Expected-Object-Version", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "one", "two")
                        .header("X-DWP-Expected-Object-Version", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/admin/forms/studio-v3/" + formId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "update")
                        .header("X-DWP-Expected-Object-Version", "9007199254740992"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/forms/studio-v3/" + formId.toString().toUpperCase()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/studio-v3/" + formId + "/publish"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(forms);
    }
}
