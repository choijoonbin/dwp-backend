package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CalendarRoomsSecurityContextMockMvcTest {

    @Test
    void roomAvailabilityForwardsVerifiedUserAndGroupContext() throws Exception {
        RoomService service = mock(RoomService.class);
        OffsetDateTime from = OffsetDateTime.parse("2026-08-20T09:00:00+09:00");
        OffsetDateTime to = from.plusHours(8);
        UUID personId = UUID.randomUUID();
        UUID excludeEventId = UUID.randomUUID();
        when(service.roomAvailability(
                3L, 17L, personId, "group-a,group-b",
                from, to, excludeEventId, "ko-KR"))
                .thenReturn(new CalendarDtos.RoomAvailabilityResponse(
                        List.of(), List.of(), List.of(), OffsetDateTime.now()));
        MockMvc mvc = standaloneSetup(new RoomsController(service)).build();

        mvc.perform(get("/v1/rooms/availability")
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .header("X-DWP-Person-Public-ID", personId.toString())
                        .header("X-DWP-Group-Refs", "group-a,group-b")
                        .header("Accept-Language", "ko-KR")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("excludeEventId", excludeEventId.toString()))
                .andExpect(status().isOk());

        verify(service).roomAvailability(
                3L, 17L, personId, "group-a,group-b",
                from, to, excludeEventId, "ko-KR");
    }

    @Test
    void calendarCreateForwardsVerifiedGroupContext() throws Exception {
        CalendarService service = mock(CalendarService.class);
        MockMvc mvc = standaloneSetup(new CalendarController(service)).build();

        mvc.perform(post("/v1/calendar/events")
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .header("X-DWP-Group-Refs", "group-a,group-b")
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Secure room booking",
                                  "type": "MEETING",
                                  "startsAt": "2026-08-20T10:00:00+09:00",
                                  "endsAt": "2026-08-20T11:00:00+09:00",
                                  "timeZone": "Asia/Seoul",
                                  "allDay": false,
                                  "visibility": "DEFAULT",
                                  "recurrence": "NONE",
                                  "recurrenceInterval": 1,
                                  "responseRequired": false,
                                  "attendees": [],
                                  "resourceId": "11111111-1111-1111-1111-111111111111",
                                  "idempotencyKey": "22222222-2222-2222-2222-222222222222"
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).create(
                eq(3L), eq(17L), eq(null), eq(null), eq("en-US"), eq(null),
                eq("group-a,group-b"), any(CalendarDtos.CreateEventRequest.class), eq(null));
    }

    @Test
    void calendarResponseRejectsAnIncompleteConcurrencyContractBeforeServiceInvocation()
            throws Exception {
        CalendarService service = mock(CalendarService.class);
        MockMvc mvc = standaloneSetup(new CalendarController(service)).build();

        mvc.perform(post("/v1/calendar/events/{eventId}/response", UUID.randomUUID())
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"response":"ACCEPTED"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void calendarResponseForwardsTheVersionedIdempotentIntent() throws Exception {
        CalendarService service = mock(CalendarService.class);
        MockMvc mvc = standaloneSetup(new CalendarController(service)).build();
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request = new CalendarDtos.RespondRequest(
                CalendarTypes.ResponseStatus.ACCEPTED, 12L, key);

        mvc.perform(post("/v1/calendar/events/{eventId}/response", eventId)
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .header("X-DWP-Person-Public-ID", personId)
                        .header("X-DWP-Group-Refs", "group-a,group-b")
                        .header("Accept-Language", "en-US")
                        .header("X-Correlation-ID", "corr-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "response": "ACCEPTED",
                                  "expectedVersion": 12,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(key)))
                .andExpect(status().isOk());

        verify(service).respond(
                3L, 17L, personId, eventId, "en-US", "corr-response",
                "group-a,group-b", request);
    }

    @Test
    void roomResponseForwardsTheSameVersionedIdempotentIntent() throws Exception {
        RoomService service = mock(RoomService.class);
        MockMvc mvc = standaloneSetup(new RoomsController(service)).build();
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request = new CalendarDtos.RespondRequest(
                CalendarTypes.ResponseStatus.TENTATIVE, 8L, key);

        mvc.perform(post("/v1/rooms/bookings/{eventId}/response", eventId)
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .header("X-DWP-Person-Public-ID", personId)
                        .header("X-DWP-Group-Refs", "group-a,group-b")
                        .header("Accept-Language", "ko-KR")
                        .header("X-Correlation-ID", "corr-room-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "response": "TENTATIVE",
                                  "expectedVersion": 8,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(key)))
                .andExpect(status().isOk());

        verify(service).respondRoomBooking(
                3L, 17L, personId, eventId, "ko-KR", "corr-room-response",
                "group-a,group-b", request);
    }
}
