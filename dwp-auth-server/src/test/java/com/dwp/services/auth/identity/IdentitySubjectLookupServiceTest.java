package com.dwp.services.auth.identity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentitySubjectLookupServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private RoleMemberRepository roleMembers;

    @Mock
    private RoleRepository roles;

    @Test
    void returnsOnlyTheRequestedTenantUser() {
        User user = User.builder()
                .userId(19L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .displayName("Custody Target")
                .email("target@example.com")
                .status("ACTIVE")
                .build();
        when(users.findByUserIdAndTenantId(19L, 4L)).thenReturn(Optional.of(user));

        IdentitySubjectLookupService.Subject result =
                service().subject(4L, 19L);

        assertThat(result.tenantId()).isEqualTo(4L);
        assertThat(result.userId()).isEqualTo(19L);
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    void masksMissingOrCrossTenantUsersAsNotFound() {
        when(users.findByUserIdAndTenantId(19L, 4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().subject(4L, 19L))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void searchesOnlyActiveUsersWithinTheRequestedTenant() {
        User user = User.builder()
                .userId(21L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .personPublicId(UUID.randomUUID())
                .displayName("Approval Delegate")
                .email("delegate@sk.com")
                .jobTitle("Finance manager")
                .status("ACTIVE")
                .build();
        when(users.searchActiveDirectoryUsers(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq("delegate"),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(user));

        List<IdentitySubjectLookupService.DirectorySubject> result =
                service().search(4L, " delegate ", 100);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(21L);
        assertThat(result.getFirst().personPublicId()).isEqualTo(user.getPersonPublicId());
        assertThat(result.getFirst().jobTitle()).isEqualTo("Finance manager");
    }

    private IdentitySubjectLookupService service() {
        return new IdentitySubjectLookupService(users, roleMembers, roles);
    }
}
