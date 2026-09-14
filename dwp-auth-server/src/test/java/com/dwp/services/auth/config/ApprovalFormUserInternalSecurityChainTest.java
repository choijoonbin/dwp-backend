package com.dwp.services.auth.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dwp.services.auth.controller.ApprovalFormUserDirectoryController;
import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.Response;
import com.dwp.services.auth.service.ApprovalFormUserDirectoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ApprovalFormUserInternalSecurityChainTest {
    @Test
    void realSecurityChainAndControllerRejectAliasesDeputyAndDuplicateHeadersBeforeService() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("purpose-test",
                    Map.of("dwp.auth.approval-form-user-token", "dedicated-token")));
            context.register(TestConfig.class); context.refresh();
            var service = context.getBean(ApprovalFormUserDirectoryService.class);
            when(service.search(anyString())).thenReturn(new Response(List.of(), UUID.randomUUID(), "a".repeat(64), "auth-current", "policy-current"));
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            String path = ApprovalFormUserInternalSecurityConfig.PREFIX + "/search";
            mvc.perform(post(path).header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token")
                    .header("X-DWP-Service-Identity", "dwp-approval-server").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
            verify(service).search("{}"); clearInvocations(service);
            for (String suffix : List.of("/search/", "/search/extra", "/%73earch", "//search", "/search;purpose=OTHER")) {
                var response = mvc.perform(post(ApprovalFormUserInternalSecurityConfig.PREFIX + suffix)
                        .with(request -> { request.setRequestURI(ApprovalFormUserInternalSecurityConfig.PREFIX + suffix); return request; })
                        .header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token")
                        .header("X-DWP-Service-Identity", "dwp-approval-server").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse();
                assertThat(response.getStatus()).as(suffix).isBetween(400, 499);
            }
            mvc.perform(head(path).header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token")
                    .header("X-DWP-Service-Identity", "dwp-approval-server")).andExpect(status().isForbidden());
            mvc.perform(post(path).header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token", "dedicated-token")
                    .header("X-DWP-Service-Identity", "dwp-approval-server").content("{}")).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token")
                    .header("X-DWP-Service-Identity", "dwp-gateway").content("{}")).andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({ApprovalFormUserInternalSecurityConfig.class, ApprovalFormUserDirectoryController.class})
    static class TestConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean ApprovalFormUserDirectoryService directoryService() { return mock(ApprovalFormUserDirectoryService.class); }
    }
}
