package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

final class CalendarP0GovernanceRepositoryFixture {
    private CalendarP0GovernanceRepositoryFixture() {}

    static CalendarRepository roomRepository(JdbcTemplate jdbc, long tenantId, UUID resourceId) {
        CalendarRepository repository = spy(new CalendarRepository(jdbc, new ObjectMapper()));
        CalendarRepository.ResourceRow selectedRoom = new CalendarRepository.ResourceRow(
                resourceId, "GROUP-ROOM", "Group room", "그룹 회의실", "Group room",
                CalendarTypes.ResourceType.ROOM, "Test site", "1F", 8, List.of(),
                "UTC", false, CalendarTypes.ResourceState.AVAILABLE, true, 0L);
        // V192 tests intentionally avoid later Workplace closure tables introduced in V249.
        doReturn(Optional.of(selectedRoom))
                .when(repository).resource(tenantId, resourceId, false);
        return repository;
    }
}
