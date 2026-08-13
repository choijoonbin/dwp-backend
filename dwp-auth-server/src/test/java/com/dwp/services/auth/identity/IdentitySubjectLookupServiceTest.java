package com.dwp.services.auth.identity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentitySubjectLookupServiceTest {

    @Mock
    private UserRepository users;

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
                new IdentitySubjectLookupService(users).subject(4L, 19L);

        assertThat(result.tenantId()).isEqualTo(4L);
        assertThat(result.userId()).isEqualTo(19L);
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    void masksMissingOrCrossTenantUsersAsNotFound() {
        when(users.findByUserIdAndTenantId(19L, 4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new IdentitySubjectLookupService(users).subject(4L, 19L))
                .isInstanceOf(BaseException.class);
    }
}
