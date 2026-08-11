package com.dwp.services.people.directory;

import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeopleDirectoryServiceTest {

    private static final long TENANT_ID = 3L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);

    private final PeopleDirectoryRepository repository = mock(PeopleDirectoryRepository.class);
    private final PeopleCursorCodec cursorCodec = mock(PeopleCursorCodec.class);
    private final PeopleDirectoryService service = new PeopleDirectoryService(repository, cursorCodec);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(9L, TENANT_ID, Set.of("USER"));
        when(cursorCodec.fingerprint(any(), any(), eq(AS_OF.toString())))
                .thenReturn("directory-fingerprint");
        when(repository.search(eq(TENANT_ID), anyLong(), any(), any(), eq(AS_OF), eq(21)))
                .thenReturn(List.of(directoryRow()));
    }

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void publicPeopleDirectorySuppressesWorkforceRestrictedFields() {
        PeopleDtos.PersonSummary person = service.search(null, null, null, 20, AS_OF)
                .items()
                .getFirst();

        assertThat(person.workerNumber()).isNull();
        assertThat(person.assignmentKey()).isNull();
        assertThat(person.jobGradeKey()).isNull();
        assertThat(person.jobGradeName()).isNull();
        assertThat(person.assignmentEffectiveFrom()).isNull();
        assertThat(person.dataAccess().workerNumberMasked()).isTrue();
        assertThat(person.dataAccess().excludedFieldGroups())
                .contains("workerIdentifiers", "employmentHistory", "jobGrade");
    }

    @Test
    void workforceDirectoryRetainsOperationalFields() {
        PeopleDtos.PersonSummary person = service.searchWorkforce(null, null, null, 20, AS_OF)
                .items()
                .getFirst();

        assertThat(person.workerNumber()).isEqualTo("SK000042");
        assertThat(person.assignmentKey()).isEqualTo("ASG-0042");
        assertThat(person.jobGradeKey()).isEqualTo("G4");
        assertThat(person.jobGradeName()).isEqualTo("Senior");
        assertThat(person.assignmentEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(person.dataAccess().workerNumberMasked()).isFalse();
        assertThat(person.dataAccess().excludedFieldGroups())
                .doesNotContain("workerIdentifiers", "employmentHistory", "jobGrade");
    }

    private PeopleDirectoryRepository.DirectoryRow directoryRow() {
        return new PeopleDirectoryRepository.DirectoryRow(
                42L,
                UUID.randomUUID(),
                "Kim DWP",
                "ko-KR",
                "Asia/Seoul",
                "ACTIVE",
                "SK000042",
                "EMPLOYEE",
                "ACTIVE",
                LocalDate.of(2020, 2, 3),
                "ASG-0042",
                "Enterprise Architect",
                "ASG-0001",
                LocalDate.of(2025, 1, 1),
                UUID.randomUUID(),
                "AI-PLATFORM",
                "AI Platform Team",
                "Enterprise Architect",
                "INDIVIDUAL",
                "G4",
                "Senior",
                "SEOUL",
                "Seoul",
                "SKAX",
                UUID.randomUUID(),
                "Manager Kim",
                0,
                "kim.dwp@example.com",
                "profiles/42.webp");
    }
}
