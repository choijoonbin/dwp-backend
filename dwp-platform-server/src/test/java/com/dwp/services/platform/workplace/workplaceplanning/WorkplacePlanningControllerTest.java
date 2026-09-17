package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningController.*;
import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplacePlanningControllerTest {
    private final WorkplacePlanningService service = mock(WorkplacePlanningService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplacePlanningController(service)).build();

    @Test
    void sourceReadRequiresViewAndUsesNoStore() throws Exception {
        UUID siteId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        OffsetDateTime to = from.plusDays(7);
        when(service.sources(eq(42L), any())).thenReturn(List.of());

        mvc.perform(get("/v1/admin/workplace/space-planning/sources")
                        .header(TENANT, 42).header(PERMISSIONS, VIEW)
                        .param("siteId", siteId.toString())
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void bookingImpactPreviewReturnsGovernedReceipt() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        BookingImpactPreview result = new BookingImpactPreview(UUID.randomUUID(), scenarioId, 4,
                BookingImpactState.SCOPE_INCOMPLETE, null, List.of(),
                List.of("Affected resources are required"), now.plusMinutes(15), now);
        CommandReceipt receipt = new CommandReceipt(UUID.randomUUID(),
                "BOOKING_IMPACT_PREVIEW", CommandState.SUCCEEDED, scenarioId,
                ScenarioState.DRAFT, 4, UUID.randomUUID(), OutboxState.PENDING,
                false, "corr-22", now);
        when(service.previewBookingImpact(eq(42L), eq(7L), eq(scenarioId),
                eq("impact-key"), eq("corr-22"), any()))
                .thenReturn(new BookingImpactCommandResult(result, receipt));

        mvc.perform(post("/v1/admin/workplace/space-planning/scenarios/{scenarioId}/booking-impact:preview",
                        scenarioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, MANAGE)
                        .header(IDEMPOTENCY, "impact-key")
                        .header(CORRELATION, "corr-22")
                        .content(mapper.writeValueAsBytes(new BookingImpactPreviewRequest(
                                4, "Inspect affected reservations", true))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.preview.state").value("SCOPE_INCOMPLETE"))
                .andExpect(jsonPath("$.data.preview.impactedBookingCount").doesNotExist())
                .andExpect(jsonPath("$.data.preview.bookings").isEmpty())
                .andExpect(jsonPath("$.data.receipt.commandType")
                        .value("BOOKING_IMPACT_PREVIEW"));
    }

    @Test
    void elevatedTransitionsRequireExactPermissionsAndFreshAccess() {
        assertThatThrownBy(() -> authorizeElevated(VIEW, "ELEVATED", MANAGE))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> authorizeElevated(MANAGE + "," + APPROVE,
                "NORMAL", MANAGE, APPROVE))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        authorizeElevated(MANAGE + "," + APPROVE, "ELEVATED", MANAGE, APPROVE);
    }
}
