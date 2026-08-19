package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(service.roomAvailability(3L, 17L, "group-a,group-b", from, to, "ko-KR"))
                .thenReturn(new CalendarDtos.RoomAvailabilityResponse(
                        List.of(), List.of(), OffsetDateTime.now()));
        MockMvc mvc = standaloneSetup(new RoomsController(service)).build();

        mvc.perform(get("/v1/rooms/availability")
                        .header("X-DWP-Tenant-ID", "3")
                        .header("X-DWP-User-ID", "17")
                        .header("X-DWP-Group-Refs", "group-a,group-b")
                        .header("Accept-Language", "ko-KR")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk());

        verify(service).roomAvailability(
                3L, 17L, "group-a,group-b", from, to, "ko-KR");
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
                eq("group-a,group-b"), any(CalendarDtos.CreateEventRequest.class));
    }
}
