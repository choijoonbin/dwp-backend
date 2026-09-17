package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

class WidgetRegistryAccessGuardTest {
    private final WidgetRegistryAccessGuard guard = new WidgetRegistryAccessGuard();

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    void exactRouteRequiresItsDeclaredPermission(
            String method, String path, String query, String permission) {
        MockHttpServletRequest denied = request(method, path, query, null);
        assertThatThrownBy(() -> guard.authorize(denied))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        assertThatCode(() -> guard.authorize(request(method, path, query, permission)))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownRegistryRoutesFailClosedWhileUnrelatedRoutesRemainUntouched() {
        assertThatThrownBy(() -> guard.authorize(request(
                "GET", "/v1/admin/widget-definitions/x/not-a-route", null, null)))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatCode(() -> guard.authorize(request(
                "GET", "/v1/admin/workplace/rooms", null, null)))
                .doesNotThrowAnyException();
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of("GET", "/v1/admin/widget-registry/readiness", null,
                        "WIDGET_CATALOG_READ"),
                Arguments.of("HEAD", "/v1/admin/widget-registry/readiness", null,
                        "WIDGET_CATALOG_READ"),
                Arguments.of("POST", "/v1/admin/widget-definitions", null,
                        "WIDGET_DEFINITION_WRITE"),
                Arguments.of("GET", "/v1/admin/widget-definitions/a/retirement-impact", null,
                        "WIDGET_DEFINITION_RELEASE"),
                Arguments.of("POST", "/v1/admin/widget-definition-versions/a/decision", null,
                        "WIDGET_DEFINITION_REVIEW"),
                Arguments.of("GET", "/v1/admin/widget-definition-versions/a/impact",
                        "operation=QUARANTINE", "WIDGET_DEFINITION_REVOKE"),
                Arguments.of("POST", "/v1/admin/widget-definition-versions/a/publish", null,
                        "WIDGET_DEFINITION_RELEASE"),
                Arguments.of("POST", "/v1/admin/widget-definition-versions/a/revoke", null,
                        "WIDGET_DEFINITION_REVOKE"),
                Arguments.of("POST", "/v1/admin/widget-runtime-controls/a/enable-approvals", null,
                        "WIDGET_DEFINITION_REVIEW"),
                Arguments.of("GET", "/v1/admin/widget-catalog", null,
                        "ADMIN.HOME_WIDGET_POLICY:VIEW"),
                Arguments.of("GET", "/v1/admin/widget-catalog/a/explain", null,
                        "ADMIN.HOME_WIDGET_POLICY:EXPLAIN"),
                Arguments.of("POST", "/v1/admin/widget-policies/a/revisions", null,
                        "ADMIN.HOME_WIDGET_POLICY:MANAGE"),
                Arguments.of("POST", "/v1/admin/widget-policies/a/revoke", null,
                        "ADMIN.HOME_WIDGET_POLICY:PUBLISH"),
                Arguments.of("GET", "/v1/admin/widget-policies/a/history", null,
                        "ADMIN.HOME_WIDGET_POLICY:AUDIT"));
    }

    private static MockHttpServletRequest request(
            String method, String path, String query, String permission) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (query != null) {
            String[] pair = query.split("=", 2);
            request.setParameter(pair[0], pair[1]);
        }
        if (permission != null) request.addHeader("X-DWP-Permissions", permission);
        return request;
    }
}
