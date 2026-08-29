package com.dwp.services.space.security;

import com.dwp.services.space.api.SpaceAdminController;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.domain.SpaceService;
import com.dwp.services.space.operations.SpaceOperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaceSecurityFilterAuthorityTest {

    private static final UUID TEMPLATE_ID =
            UUID.fromString("b1000000-0000-0000-0000-000000000001");
    private static final UUID REVIEW_ID =
            UUID.fromString("c1000000-0000-0000-0000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SpaceService service = mock(SpaceService.class);
    private final SpaceOperationsService operations = mock(SpaceOperationsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, operations);
        mvc = MockMvcBuilders.standaloneSetup(new SpaceAdminController(service, operations))
                .addFilters(new SpaceSecurityFilter("space-token", objectMapper))
                .build();
    }

    @Test
    void viewAndApproveCanListAndDecideLifecycleReview() throws Exception {
        mvc.perform(verified(get("/v1/admin/lifecycle"),
                        "ADMIN.SPACE_ACCESS_REVIEW:VIEW,ADMIN.SPACE_ACCESS_REVIEW:APPROVE"))
                .andExpect(status().isOk());
        mvc.perform(verified(post("/v1/admin/lifecycle/{id}/decision", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleDecision()),
                        "ADMIN.SPACE_ACCESS_REVIEW:VIEW,ADMIN.SPACE_ACCESS_REVIEW:APPROVE"))
                .andExpect(status().isOk());

        verify(service).lifecycleReviews("ALL");
        verify(service).decideLifecycle(
                eq(REVIEW_ID), any(SpaceDtos.LifecycleDecision.class), eq(null));
    }

    @Test
    void viewOnlyCannotDecideLifecycleReview() throws Exception {
        mvc.perform(verified(post("/v1/admin/lifecycle/{id}/decision", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleDecision()),
                        "ADMIN.SPACE_ACCESS_REVIEW:VIEW"))
                .andExpect(status().isForbidden());

        verify(service, never()).decideLifecycle(any(), any(), any());
    }

    @Test
    void manageCanDecideLifecycleReview() throws Exception {
        mvc.perform(verified(post("/v1/admin/lifecycle/{id}/decision", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleDecision()),
                        "ADMIN.SPACE_ACCESS_REVIEW:MANAGE"))
                .andExpect(status().isOk());

        verify(service).decideLifecycle(
                eq(REVIEW_ID), any(SpaceDtos.LifecycleDecision.class), eq(null));
    }

    @Test
    void viewAndCreateCanCreateTemplate() throws Exception {
        mvc.perform(verified(post("/v1/admin/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template(null)),
                        "ADMIN.SPACE_TEMPLATES:VIEW,ADMIN.SPACE_TEMPLATES:CREATE"))
                .andExpect(status().isOk());

        verify(service).createTemplate(any(SpaceDtos.SaveTemplateRequest.class), eq(null));
    }

    @Test
    void viewAndUpdateCanUpdateTemplate() throws Exception {
        mvc.perform(verified(put("/v1/admin/templates/{id}", TEMPLATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template(3L)),
                        "ADMIN.SPACE_TEMPLATES:VIEW,ADMIN.SPACE_TEMPLATES:UPDATE"))
                .andExpect(status().isOk());

        verify(service).updateTemplate(
                eq(TEMPLATE_ID), any(SpaceDtos.SaveTemplateRequest.class), eq(null));
    }

    private MockHttpServletRequestBuilder verified(
            MockHttpServletRequestBuilder request,
            String permissions) {
        return request.header(SpaceSecurityFilter.SERVICE_TOKEN_HEADER, "space-token")
                .header(SpaceSecurityFilter.USER_HEADER, "17")
                .header(SpaceSecurityFilter.TENANT_HEADER, "42")
                .header(SpaceSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(SpaceSecurityFilter.PERMISSIONS_HEADER, permissions);
    }

    private String lifecycleDecision() {
        return """
                {
                  "recommendation": "KEEP",
                  "note": "Reviewed and approved."
                }
                """;
    }

    private String template(Long expectedVersion) throws Exception {
        return objectMapper.writeValueAsString(new SpaceDtos.SaveTemplateRequest(
                "expert-community",
                "전문가 커뮤니티",
                "Expert community",
                "검증된 전문가 협업 공간입니다.",
                "A governed space for expert collaboration.",
                "COMMUNITY",
                "APPROVAL",
                "REQUEST",
                "INTERNAL",
                java.util.List.of("POST", "FILE"),
                java.util.List.of("knowledge"),
                "users-round",
                "teal",
                "PUBLISHED",
                expectedVersion));
    }
}
