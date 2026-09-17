package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.CreateImpactPreview;
import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ExecuteImpactCommand;
import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ReconcileNotifications;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkplaceFacilityClosureImpactAdminControllerTest {
    private static final String UPDATE = "ADMIN.WORKPLACE:UPDATE";

    private final WorkplaceScopedFacilityClosureImpactService service =
            mock(WorkplaceScopedFacilityClosureImpactService.class);
    private final WorkplaceDelegatedAdminScopeGuard guard =
            mock(WorkplaceDelegatedAdminScopeGuard.class);
    private final WorkplaceFacilityClosureImpactAdminController controller =
            new WorkplaceFacilityClosureImpactAdminController(service, guard);
    private final UUID site = UUID.randomUUID();

    @Test
    void everySensitiveRepresentationIsPrivateNoStore() {
        OffsetDateTime starts = OffsetDateTime.parse("2026-09-20T09:00:00+09:00");
        UUID resource = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        var request = new MockHttpServletRequest();

        assertNoStore(response -> controller.preview(
                7L, 9L, UPDATE, "preview-key", site, resource,
                new CreateImpactPreview(starts, starts.plusHours(1), 0L), request, response));
        assertNoStore(response -> controller.preview(
                7L, UPDATE, site, preview, request, response));
        assertNoStore(response -> controller.execute(
                7L, 9L, UPDATE, "execute-key", "ELEVATED", "corr", site, preview,
                new ExecuteImpactCommand(1L, "token", "Maintenance", true, List.of()),
                request, response));
        assertNoStore(response -> controller.command(
                7L, UPDATE, site, command, request, response));
        assertNoStore(response -> controller.receipt(
                7L, UPDATE, site, command, request, response));
        assertNoStore(response -> controller.reconcile(
                7L, 9L, UPDATE, "ELEVATED", "reconcile-key", site, command,
                new ReconcileNotifications(1L, "Recover unknown result", true), request, response));
        assertNoStore(response -> controller.retry(
                7L, 9L, UPDATE, "ELEVATED", "retry-key", site, command,
                new ReconcileNotifications(1L, "Retry failed delivery", true), request, response));
    }

    @Test
    void executeRetryAndReconcileRequireCurrentElevatedAccessAndStillDisableCaching() {
        UUID preview = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        var executeResponse = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.execute(
                7L, 9L, UPDATE, "execute-key", "NORMAL", null, site, preview,
                new ExecuteImpactCommand(1L, "token", "Maintenance", true, List.of()),
                request, executeResponse))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        assertThat(executeResponse.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store, max-age=0");

        for (String operation : List.of("reconcile", "retry")) {
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> {
                ReconcileNotifications input = new ReconcileNotifications(
                        1L, "Explicit recovery", true);
                if (operation.equals("reconcile")) {
                    controller.reconcile(7L, 9L, UPDATE, "NORMAL", operation + "-key",
                            site, command, input, request, response);
                } else {
                    controller.retry(7L, 9L, UPDATE, "NORMAL", operation + "-key",
                            site, command, input, request, response);
                }
            }).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("private, no-store, max-age=0");
        }
    }

    @Test
    void aConfirmedZeroImpactClosureAcceptsAnEmptySelectionSet() {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            assertThat(validators.getValidator().validate(new ExecuteImpactCommand(
                    1L, "token", "Maintenance without affected bookings", true, List.of())))
                    .isEmpty();
        }
    }

    private void assertNoStore(java.util.function.Consumer<MockHttpServletResponse> call) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        call.accept(response);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store, max-age=0");
    }
}
