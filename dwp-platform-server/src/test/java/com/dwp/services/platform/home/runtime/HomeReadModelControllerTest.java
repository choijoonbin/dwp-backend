package com.dwp.services.platform.home.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HomeReadModelControllerTest {

    @Test
    void returnsRecipientBoundEtagAndShadowHeaders() throws Exception {
        HomeReadModelService service = mock(HomeReadModelService.class);
        HomeReadModelDtos.HomeReadModel model = model();
        when(service.read(any(), eq("CLASSIC"), eq("DESKTOP_STANDARD")))
                .thenReturn(new HomeReadModelDtos.ReadResult(model, "\"etag-1\""));
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties())).build();

        mvc.perform(request().header("If-None-Match", "\"older\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"etag-1\""))
                .andExpect(header().string("Cache-Control", HomeReadModelController.CACHE_CONTROL))
                .andExpect(header().string("X-DWP-Home-Runtime-Mode", "SHADOW"))
                .andExpect(header().string("X-DWP-Widget-Registry-Authoritative", "false"))
                .andExpect(jsonPath("$.data.schemaVersion").value(2));
    }

    @Test
    void matchingEtagReturns304WithTheSameCacheContract() throws Exception {
        HomeReadModelService service = mock(HomeReadModelService.class);
        when(service.read(any(), eq("CLASSIC"), eq("DESKTOP_STANDARD")))
                .thenReturn(new HomeReadModelDtos.ReadResult(model(), "\"etag-1\""));
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties())).build();

        mvc.perform(request().header("If-None-Match", "W/\"etag-1\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"etag-1\""))
                .andExpect(header().string("Cache-Control", HomeReadModelController.CACHE_CONTROL))
                .andExpect(header().string("Vary", HomeReadModelController.VARY));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get("/v2/home")
                .queryParam("mode", "CLASSIC")
                .queryParam("deviceClass", "DESKTOP_STANDARD")
                .queryParam("timeZone", "Asia/Seoul")
                .header("X-DWP-Tenant-ID", "71")
                .header("X-DWP-User-ID", "82")
                .header("X-DWP-Permissions", "APP.WORK:VIEW")
                .header("X-DWP-Roles", "MEMBER")
                .header("X-DWP-Current-Decision-Revision", "decision-17")
                .header("X-DWP-Current-Revalidate-At",
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString())
                .header("Accept-Language", "ko-KR");
    }

    private HomeReadModelDtos.HomeReadModel model() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeReadModelDtos.HomeReadModel(
                2, "CLASSIC", null, null, List.of(), List.of(), now, now.plusSeconds(30),
                false, List.of(), "etag-1", "SHADOW");
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                false, true, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
