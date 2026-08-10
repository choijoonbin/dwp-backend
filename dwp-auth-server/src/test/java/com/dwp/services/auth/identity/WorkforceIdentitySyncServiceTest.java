package com.dwp.services.auth.identity;

import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.IdentityAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceIdentitySyncServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final IdentityAccountService accounts = mock(IdentityAccountService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WorkforceIdentitySyncService service =
            new WorkforceIdentitySyncService(tenants, users, accounts, jdbc);

    @Test
    void createsHrisUserAndNormalizesWorkEmail() {
        UUID tenantPublicId = UUID.randomUUID();
        UUID personPublicId = UUID.randomUUID();
        WorkforceIdentityDtos.WorkforceIdentityEvent event = event(tenantPublicId, personPublicId);
        when(tenants.findByPublicId(tenantPublicId)).thenReturn(Optional.of(Tenant.builder()
                .tenantId(1L).publicId(tenantPublicId).code("default").name("Default").build()));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(users.findByTenantIdAndPersonPublicId(1L, personPublicId)).thenReturn(Optional.empty());
        when(users.findByTenantIdAndSourceTypeAndExternalId(1L, "HRIS", "worker-1"))
                .thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setUserId(10L);
            return user;
        });

        WorkforceIdentityDtos.SyncResult result = service.synchronize(event);

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        verify(accounts).synchronizeManagedUser(captor.getValue());
        assertThat(captor.getValue().getEmail()).isEqualTo("employee@example.com");
        assertThat(captor.getValue().getPersonPublicId()).isEqualTo(personPublicId);
        assertThat(result.lifecycleState()).isEqualTo("CREATED");
    }

    private WorkforceIdentityDtos.WorkforceIdentityEvent event(
            UUID tenantPublicId,
            UUID personPublicId) {
        return new WorkforceIdentityDtos.WorkforceIdentityEvent(
                UUID.randomUUID(), tenantPublicId, personPublicId, "worker-1", "Employee",
                "Em", "Ployee", "Employee@Example.COM", "Engineer", "ko-KR",
                "ACTIVE", "v1");
    }
}
