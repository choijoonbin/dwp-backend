package com.dwp.services.platform.security;

import com.dwp.services.platform.auditcontrol.AdminAuditControlController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HcmAuditGenericFenceContractTest {

    private static final String ROOT = "/v1/admin/audit-control";
    private static final String UUID = "018f47a2-7b3c-7def-8abc-0123456789ab";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted",
            "runtime",
            false,
            objectMapper,
            mock(PlatformCanaryPepRegistry.class),
            mock(PlatformApprovalsPepRegistry.class),
            mock(HcmPlatformPepRegistry.class));

    @Test
    void inventoryCoversEveryGenericAuditControllerReadAndWrite() {
        assertThat(genericAuditRoutes()).hasSize(31);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("genericAuditRoutes")
    void anyValidatedAuditorDutyIsFencedFromEveryGenericRoute(
            String httpMethod, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, path);
        request.addHeader(PlatformSecurityHeaders.SERVICE_TOKEN, "trusted");
        request.addHeader(PlatformSecurityHeaders.USER, "101");
        request.addHeader(PlatformSecurityHeaders.TENANT, "7");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(PlatformSecurityHeaders.ROLES, "TENANT_ADMIN");
        request.addHeader(PlatformSecurityHeaders.PERMISSIONS,
                "ADMIN.AUDIT_VIEW:VIEW,ADMIN.AUDIT_CONTROL:MANAGE");
        request.addHeader(PlatformSecurityHeaders.RESOURCE_ROLES,
                "HRIS_DUTY_AUDITOR@RS_ANOTHER_VALID_RESOURCE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"errorCode\":\"E2001\"");
    }

    private static Stream<Arguments> genericAuditRoutes() {
        return Arrays.stream(AdminAuditControlController.class.getDeclaredMethods())
                .flatMap(HcmAuditGenericFenceContractTest::routes);
    }

    private static Stream<Arguments> routes(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) return arguments("GET", get.value());
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) return arguments("POST", post.value());
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) return arguments("PUT", put.value());
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) return arguments("PATCH", patch.value());
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) return arguments("DELETE", delete.value());
        return Stream.empty();
    }

    private static Stream<Arguments> arguments(String method, String[] paths) {
        return Arrays.stream(paths)
                .map(path -> ROOT + path.replaceAll("\\{[^}]+}", UUID))
                .map(path -> Arguments.of(method, path));
    }
}
