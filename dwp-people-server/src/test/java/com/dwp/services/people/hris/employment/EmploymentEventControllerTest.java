package com.dwp.services.people.hris.employment;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hris.experience.HrisExperienceCommandReceiptService;
import com.dwp.services.people.security.HcmStepUpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmploymentEventControllerTest {

    private static final UUID EVENT =
            UUID.fromString("00000000-0000-4000-8000-000000000021");
    private static final UUID CORRELATION =
            UUID.fromString("00000000-0000-4000-8000-000000000051");
    private static final String KEY = "hire-tenant-100-0001";
    private static final URI LOCATION = URI.create(
            "/api/people/v1/hris/assignment-events/" + EVENT);

    private final EmploymentEventService service = mock(EmploymentEventService.class);
    private final EmploymentSnapshotAuthority snapshots =
            mock(EmploymentSnapshotAuthority.class);
    private final HrisExperienceCommandReceiptService receipts =
            mock(HrisExperienceCommandReceiptService.class);
    private final EmploymentEventController controller =
            new EmploymentEventController(service, snapshots, receipts);
    private final CommandReceiptV1 receipt = mock(CommandReceiptV1.class);

    @Test
    void commandReceiptsCarryTheCanonicalEventLocationAndVersion() {
        when(receipt.correlationId()).thenReturn(CORRELATION);
        EmploymentEventDtos.CreateCommand command =
                mock(EmploymentEventDtos.CreateCommand.class);
        EmploymentEventDtos.EmptyCommand empty = new EmploymentEventDtos.EmptyCommand();
        EmploymentEventDtos.CommandResult created = result(1);
        EmploymentEventDtos.CommandResult submitted = result(3);
        EmploymentEventDtos.CommandResult published = result(4);
        when(service.create(eq(command), eq(KEY), eq("\"v:0\""), eq(CORRELATION),
                eq(EmploymentEventDtos.PURPOSE))).thenReturn(created);
        when(service.submit(eq(EVENT), eq(KEY), eq("\"v:2\""), eq(CORRELATION),
                eq(EmploymentEventDtos.PURPOSE))).thenReturn(submitted);
        when(service.publish(eq(EVENT), eq(KEY), eq("\"v:3\""), eq(CORRELATION),
                eq(EmploymentEventDtos.PURPOSE), any(HcmStepUpHeaders.class)))
                .thenReturn(published);

        assertAccepted(controller.create(
                KEY, "\"v:0\"", CORRELATION, EmploymentEventDtos.PURPOSE, command), 1);
        assertAccepted(controller.submit(
                EVENT, KEY, "\"v:2\"", CORRELATION,
                EmploymentEventDtos.PURPOSE, empty), 3);
        assertAccepted(controller.publish(
                EVENT, KEY, "\"v:3\"", "challenge", "decision:1", 3L,
                CORRELATION, EmploymentEventDtos.PURPOSE, empty), 4);
    }

    @Test
    void validationReturnsTheCanonicalImpactInsteadOfTheReceipt() {
        when(receipt.correlationId()).thenReturn(CORRELATION);
        EmploymentEventDtos.AssignmentImpact impact =
                new EmploymentEventDtos.AssignmentImpact(
                        EVENT, LocalDate.of(2026, 10, 1), List.of(),
                        List.of("EFFECTIVE_DATE_IN_FUTURE"), "a".repeat(64));
        EmploymentEventDtos.EventView event = event(2);
        when(service.validate(eq(EVENT), eq(KEY), eq("\"v:1\""), eq(CORRELATION),
                eq(EmploymentEventDtos.PURPOSE)))
                .thenReturn(new EmploymentEventDtos.CommandResult(
                        receipt, event, impact, false));

        ResponseEntity<EmploymentEventDtos.AssignmentImpact> response =
                controller.validate(
                        EVENT, KEY, "\"v:1\"", CORRELATION,
                        EmploymentEventDtos.PURPOSE,
                        new EmploymentEventDtos.EmptyCommand());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(impact);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v:2\"");
        assertThat(response.getHeaders().getFirst(EmploymentEventController.CORRELATION))
                .isEqualTo(CORRELATION.toString());
        assertThat(response.getHeaders().getLocation()).isNull();
    }

    @Test
    void receiptProjectionCarriesOnlyTheAuthorizedResultLocation() {
        when(receipt.correlationId()).thenReturn(CORRELATION);
        when(receipts.read(EVENT)).thenReturn(
                new HrisExperienceCommandReceiptService.ProjectedReceipt(
                        receipt, LOCATION));

        ResponseEntity<CommandReceiptV1> response = controller.receipt(EVENT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(receipt);
        assertThat(response.getHeaders().getLocation()).isEqualTo(LOCATION);
        assertThat(response.getHeaders().getFirst(EmploymentEventController.CORRELATION))
                .isEqualTo(CORRELATION.toString());
    }

    private EmploymentEventDtos.CommandResult result(long version) {
        EmploymentEventDtos.EventView event = event(version);
        return new EmploymentEventDtos.CommandResult(
                receipt, event, mock(EmploymentEventDtos.AssignmentImpact.class), false);
    }

    private EmploymentEventDtos.EventView event(long version) {
        EmploymentEventDtos.EventView event = mock(EmploymentEventDtos.EventView.class);
        when(event.eventPublicId()).thenReturn(EVENT);
        when(event.version()).thenReturn(version);
        return event;
    }

    private void assertAccepted(ResponseEntity<CommandReceiptV1> response, long version) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(receipt);
        assertThat(response.getHeaders().getLocation()).isEqualTo(LOCATION);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v:" + version + "\"");
        assertThat(response.getHeaders().getFirst(EmploymentEventController.CORRELATION))
                .isEqualTo(CORRELATION.toString());
    }
}
