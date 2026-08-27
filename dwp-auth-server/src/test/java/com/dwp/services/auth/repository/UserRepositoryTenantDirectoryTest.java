package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryTenantDirectoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UserRepository users;

    @Test
    void exactCustodyLookupRejectsProviderAndCrossTenantIdentities() {
        User tenantUser = users.saveAndFlush(user(4L, "Tenant owner", "ACTIVE", "TENANT"));
        User provider = users.saveAndFlush(user(4L, "Provider operator", "ACTIVE", "PROVIDER"));
        User otherTenant = users.saveAndFlush(user(5L, "Other tenant", "ACTIVE", "TENANT"));

        assertThat(users.findTenantIdentityByUserIdAndTenantId(tenantUser.getUserId(), 4L))
                .contains(tenantUser);
        assertThat(users.findTenantIdentityByUserIdAndTenantId(provider.getUserId(), 4L))
                .isEmpty();
        assertThat(users.findTenantIdentityByUserIdAndTenantId(otherTenant.getUserId(), 4L))
                .isEmpty();
    }

    @Test
    void sourceSearchReturnsActiveAndInactiveTenantIdentitiesOnly() {
        User active = users.save(user(4L, "Active tenant", "ACTIVE", "TENANT"));
        User inactive = users.save(user(4L, "Inactive tenant", "INACTIVE", "TENANT"));
        users.save(user(4L, "Invited tenant", "INVITED", "TENANT"));
        users.save(user(4L, "Provider operator", "ACTIVE", "PROVIDER"));
        users.save(user(5L, "Other tenant", "INACTIVE", "TENANT"));
        users.flush();

        assertThat(users.searchTenantDirectoryUsers(
                4L, "", false, PageRequest.of(0, 30)))
                .extracting(User::getUserId)
                .containsExactly(inactive.getUserId(), active.getUserId());
    }

    @Test
    void targetSearchReturnsActiveTenantIdentitiesOnly() {
        User active = users.save(user(4L, "Active tenant", "ACTIVE", "TENANT"));
        users.save(user(4L, "Inactive tenant", "INACTIVE", "TENANT"));
        users.save(user(4L, "Invited tenant", "INVITED", "TENANT"));
        users.save(user(4L, "Provider operator", "ACTIVE", "PROVIDER"));
        users.flush();

        assertThat(users.searchTenantDirectoryUsers(
                4L, "tenant", true, PageRequest.of(0, 30)))
                .extracting(User::getUserId)
                .containsExactly(active.getUserId());
    }

    private User user(Long tenantId, String displayName, String status, String identityPlane) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .tenantId(tenantId)
                .publicId(UUID.randomUUID())
                .displayName(displayName)
                .email(displayName.toLowerCase().replace(' ', '.') + "@example.com")
                .status(status)
                .identityPlane(identityPlane)
                .build();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }
}
