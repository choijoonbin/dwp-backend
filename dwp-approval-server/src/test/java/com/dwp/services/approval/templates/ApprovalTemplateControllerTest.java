package com.dwp.services.approval.templates;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.templates.ApprovalTemplateModels.CloneTemplate;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalTemplateControllerTest {
    private final ApprovalTemplateService templates = mock(ApprovalTemplateService.class);
    private final UUID templateId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(templates);
        mapper = new ObjectMapper().findAndRegisterModules()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalTemplateController(templates))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }

    @Test
    void listDetailComparisonCloneAndInstallDispatchTypedBoundedInputs() throws Exception {
        mvc.perform(get("/v1/admin/forms/templates").param("categoryKey", "FINANCE")
                        .param("tag", "finance", "capex").param("limit", "20"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/templates/" + templateId)).andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/templates/" + templateId + "/comparison")
                        .param("installedVersion", "1"))
                .andExpect(status().isOk());

        String clone = """
                {"templateKey":"TEAM_CAPEX","ownerGroupRef":"FINANCE_CONTROLLERS",
                 "categoryKey":"FINANCE","defaultWorkflowKey":"CAPEX_PURCHASE",
                 "nameKo":"팀 설비 투자","nameEn":"Team capital purchase",
                 "descriptionKo":"초안","descriptionEn":"Draft","expectedTemplateVersion":7}
                """;
        mvc.perform(post("/v1/admin/forms/templates/" + templateId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON).content(clone)
                        .header("Idempotency-Key", "clone-1")
                        .header("X-DWP-Expected-Object-Version", "7"))
                .andExpect(status().isOk());

        String install = """
                {"formKey":"TEAM_CAPEX_FORM","nameKo":"팀 설비 투자","nameEn":"Team capital purchase",
                 "descriptionKo":"초안","descriptionEn":"Draft","ownerGroupRef":"FINANCE_CONTROLLERS",
                 "categoryKey":"FINANCE","defaultWorkflowKey":"CAPEX_PURCHASE","expectedTemplateVersion":7}
                """;
        mvc.perform(post("/v1/admin/forms/templates/versions/" + versionId + "/install")
                        .contentType(MediaType.APPLICATION_JSON).content(install)
                        .header("Idempotency-Key", "install-1")
                        .header("X-DWP-Expected-Object-Version", "7"))
                .andExpect(status().isOk());

        verify(templates).cloneTemplate(eq(templateId), eq(new CloneTemplate("TEAM_CAPEX", "FINANCE_CONTROLLERS",
                "FINANCE", "CAPEX_PURCHASE", "팀 설비 투자", "Team capital purchase", "초안", "Draft", 7)),
                eq("clone-1"), isNull());
        verify(templates).install(eq(versionId), any(), eq("install-1"), isNull());
    }

    @Test
    void previewAndPackageImportDispatchOnlyValidatedVersionFencedInputs() throws Exception {
        mvc.perform(get("/v1/admin/forms/templates/versions/" + versionId + "/preview")
                        .param("viewport", "MOBILE"))
                .andExpect(status().isOk());

        UUID packageId = UUID.randomUUID();
        String payload = """
                {"packageId":"%s","packageVersion":"1.0.0","packageSha256":"%s",
                 "templateKey":"TEAM_TRAVEL","ownerGroupRef":"FINANCE_CONTROLLERS",
                 "categoryKey":"FINANCE","defaultWorkflowKey":"TRAVEL_REQUEST",
                 "nameKo":"출장 신청","nameEn":"Travel request",
                 "descriptionKo":"패키지 가져오기","descriptionEn":"Imported package",
                 "schema":{},"locales":["ko","en"],"tags":["travel"],
                 "filterMetadata":{},"dependencies":[],
                 "changeSummaryKo":"초기 가져오기","changeSummaryEn":"Initial import",
                 "expectedTemplateVersion":0}
                """.formatted(packageId, "a".repeat(64));
        mvc.perform(post("/v1/admin/forms/templates/packages/import")
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Idempotency-Key", "package-1")
                        .header("X-DWP-Expected-Object-Version", "0"))
                .andExpect(status().isOk());

        verify(templates).preview(versionId, "MOBILE");
        verify(templates).importPackage(any(), eq("package-1"), isNull());

        mvc.perform(post("/v1/admin/forms/templates/packages/import")
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Idempotency-Key", "package-2")
                        .header("X-DWP-Expected-Object-Version", "1"))
                .andExpect(status().isConflict());
    }

    @Test
    void authorizationConflictAndAuthorityUnavailableMapTo403409And503() throws Exception {
        when(templates.template(templateId)).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        mvc.perform(get("/v1/admin/forms/templates/" + templateId)).andExpect(status().isForbidden());

        String clone = """
                {"templateKey":"TEAM_CAPEX","ownerGroupRef":"FINANCE_CONTROLLERS",
                 "categoryKey":"FINANCE","defaultWorkflowKey":"CAPEX_PURCHASE",
                 "nameKo":"팀 설비 투자","nameEn":"Team capital purchase",
                 "descriptionKo":"초안","descriptionEn":"Draft","expectedTemplateVersion":7}
                """;
        mvc.perform(post("/v1/admin/forms/templates/" + templateId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON).content(clone)
                        .header("Idempotency-Key", "clone-1")
                        .header("X-DWP-Expected-Object-Version", "8"))
                .andExpect(status().isConflict());

        when(templates.catalog(any())).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        mvc.perform(get("/v1/admin/forms/templates")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void callerSuppliedScopeAndUnknownBodyStateFailClosedBeforeService() throws Exception {
        mvc.perform(get("/v1/admin/forms/templates").param("managementResourceSetKey", "RS_OTHER"))
                .andExpect(status().isBadRequest());
        String escaped = """
                {"formKey":"SCOPE_ESCAPE","nameKo":"범위 이탈","nameEn":"Scope escape",
                 "descriptionKo":"","descriptionEn":"","ownerGroupRef":"SECURITY_GOVERNANCE",
                 "categoryKey":"ACCESS","defaultWorkflowKey":"ACCESS_EXCEPTION",
                 "expectedTemplateVersion":7,"tenantId":43}
                """;
        mvc.perform(post("/v1/admin/forms/templates/versions/" + versionId + "/install")
                        .contentType(MediaType.APPLICATION_JSON).content(escaped)
                        .header("Idempotency-Key", "scope-escape")
                        .header("X-DWP-Expected-Object-Version", "7"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(templates);
    }

    @Test
    void mutationHeadersAndCanonicalIdentifiersAreExact() throws Exception {
        String install = """
                {"formKey":"TEAM_CAPEX_FORM","nameKo":"팀 설비 투자","nameEn":"Team capital purchase",
                 "descriptionKo":"","descriptionEn":"","ownerGroupRef":"FINANCE_CONTROLLERS",
                 "categoryKey":"FINANCE","defaultWorkflowKey":"CAPEX_PURCHASE","expectedTemplateVersion":7}
                """;
        mvc.perform(post("/v1/admin/forms/templates/versions/" + versionId + "/install")
                        .contentType(MediaType.APPLICATION_JSON).content(install))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/templates/versions/" + versionId + "/install")
                        .contentType(MediaType.APPLICATION_JSON).content(install)
                        .header("Idempotency-Key", "one", "two")
                        .header("X-DWP-Expected-Object-Version", "7"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/forms/templates/" + templateId.toString().toUpperCase()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/templates/" + templateId + "/publish"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(templates);
    }
}
