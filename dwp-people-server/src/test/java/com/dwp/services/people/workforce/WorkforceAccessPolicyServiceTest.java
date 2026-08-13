package com.dwp.services.people.workforce;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceAccessPolicyServiceTest {

    private final WorkforceAccessPolicyRepository repository =
            mock(WorkforceAccessPolicyRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final WorkforceAccessPolicyService service =
            new WorkforceAccessPolicyService(repository, audit);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void deniesDirectApiAccessWhenNoPopulationPolicyExists() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"));
        when(repository.resolve(eq(7L), eq(41L), eq(Set.of("HR_ADMIN")), any(Instant.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.require("READ"))
                .isInstanceOf(BaseException.class);

        verify(audit).record(any());
    }

    @Test
    void combinesFieldGroupsButKeepsTheResolvedOrganizationBoundary() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"));
        UUID organizationId = UUID.randomUUID();
        WorkforceAccessPolicyRepository.PolicyRow first = policy(
                organizationId, List.of("DIRECTORY", "EMPLOYMENT"));
        WorkforceAccessPolicyRepository.PolicyRow second = policy(
                organizationId, List.of("JOB_GRADE"));
        when(repository.resolve(eq(7L), eq(41L), eq(Set.of("HR_ADMIN")), any(Instant.class)))
                .thenReturn(List.of(first, second));
        when(repository.expandOrganizations(7L, List.of(first, second)))
                .thenReturn(Set.of(organizationId));

        WorkforceAccessPolicyService.Decision result = service.require("READ");

        assertThat(result.tenantWide()).isFalse();
        assertThat(result.organizationIds()).containsExactly(organizationId);
        assertThat(result.fieldGroups()).containsExactlyInAnyOrder(
                "DIRECTORY", "EMPLOYMENT", "JOB_GRADE");
        assertThat(result.field("WORKER_IDENTIFIERS")).isFalse();
    }

    @Test
    void blocksSelfGrantedUserBoundaries() {
        PeopleRequestContext.set(41L, 7L, Set.of("TENANT_ADMIN"));
        WorkforceAccessDtos.CreatePolicyRequest request =
                new WorkforceAccessDtos.CreatePolicyRequest(
                        "USER", "41", "TENANT", null,
                        List.of("DIRECTORY"), List.of("READ"), null, null,
                        "Self elevation must never be accepted.");

        assertThatThrownBy(() -> service.create(request, "corr-self"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void doesNotLetAnAdministratorRoleBypassExplicitPermissions() {
        PeopleRequestContext.set(41L, 7L, Set.of("ADMIN"), Set.of("DATA.WORKFORCE:MANAGE"));

        assertThatThrownBy(service::list)
                .isInstanceOf(BaseException.class);
    }

    private WorkforceAccessPolicyRepository.PolicyRow policy(
            UUID organizationId,
            List<String> fields) {
        return new WorkforceAccessPolicyRepository.PolicyRow(
                UUID.randomUUID(), "ROLE", "HR_ADMIN", "ORG_TREE",
                organizationId, "Platform", fields, List.of("READ"),
                null, null, "ACTIVE", "Approved scope", 0L);
    }
}
