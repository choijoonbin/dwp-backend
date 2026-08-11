package com.dwp.services.people.workforce;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkforceReferenceServiceTest {

    private final WorkforceReferenceRepository repository = mock(WorkforceReferenceRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final WorkforceReferenceService service = new WorkforceReferenceService(repository, audit);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void peopleAdministratorCannotChangeWorkforceReferenceData() {
        PeopleRequestContext.set(17L, 3L, Set.of("PEOPLE_ADMIN"));

        assertThatThrownBy(() -> service.update(
                        "JOB_GRADE",
                        "G4",
                        "ko-KR",
                        new WorkforceReferenceDtos.UpdateReferenceValueRequest(
                                "Senior",
                                "Senior grade",
                                Map.of("ko-KR", "선임", "en-US", "Senior"),
                                "ACTIVE",
                                2L),
                        "corr-workforce-reference"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("HR administrator");

        verifyNoInteractions(repository, audit);
    }
}
