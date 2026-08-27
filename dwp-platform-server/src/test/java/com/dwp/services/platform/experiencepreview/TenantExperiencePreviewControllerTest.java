package com.dwp.services.platform.experiencepreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TenantExperiencePreviewControllerTest {

    @Test
    void publishesTheVersionedSafePreviewContractAtTheDedicatedEndpoint() throws Exception {
        TenantExperiencePreviewService service = mock(TenantExperiencePreviewService.class);
        when(service.get(42L)).thenReturn(
                new TenantExperiencePreviewDtos.TenantExperiencePreviewResponse(
                        "tenant-experience-preview.v1",
                        "TENANT_CONFIGURATION_ONLY",
                        Instant.parse("2026-08-26T12:00:00Z"),
                        new TenantExperiencePreviewDtos.BrandingConfiguration(
                                "Acme", "#123456", true, 240, 80, 7L),
                        new TenantExperiencePreviewDtos.HomeConfiguration(
                                "Welcome", "Acme workspace", java.util.Map.of(), "ko-KR",
                                true, "CENTER", 30, 1920, 1080, null, null,
                                "CLASSIC", 9L),
                        List.of("USER_PERSONALIZATION", "WORKFORCE_DATA")));
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = standaloneSetup(new TenantExperiencePreviewController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        mvc.perform(get("/v1/admin/tenant-experience-preview")
                        .header("X-DWP-Tenant-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion")
                        .value("tenant-experience-preview.v1"))
                .andExpect(jsonPath("$.data.previewMode")
                        .value("TENANT_CONFIGURATION_ONLY"))
                .andExpect(jsonPath("$.data.generatedAt")
                        .value("2026-08-26T12:00:00Z"))
                .andExpect(jsonPath("$.data.branding.logoConfigured").value(true))
                .andExpect(jsonPath("$.data.excludedData[0]")
                        .value("USER_PERSONALIZATION"));

        verify(service).get(42L);
    }
}
