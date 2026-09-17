package com.dwp.services.platform.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class HomeStudioAuditPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.locations",
                () -> "filesystem:src/main/resources/db/migration");
    }

    @Autowired private PlatformAuditEventRepository repository;

    @Test
    void filtersTheExactHomeFamilyBeforeApplyingStableDatabasePagination() {
        long tenantId = 98_765L;
        save(tenantId, "HOME_TEMPLATE", "home-template.published", 2);
        save(tenantId, "HOME_EXPERIENCE", "home-experience.rolled-back", 3);
        save(tenantId, "HOME_VIEW", "personal-home-view", 4);
        save(tenantId, "HOME_VIEW_DEVICE_LAYOUT", "personal-device-layout", 5);
        save(tenantId, "HOME_PREFERENCE", "personal-preference", 6);
        save(tenantId, "HOME_RECOMMENDATION", "personal-recommendation", 7);
        save(tenantId, "HOME_COMPOSER_PROPOSAL", "personal-composer", 8);
        save(tenantId, "HOME_WIDGET_CONFIGURATION", "personal-widget-config", 9);
        save(tenantId, "HOME_WIDGET_ACTION", "personal-widget-action", 10);
        save(tenantId, "HOME_EXPERIENCE_LEGACY", "lookalike", 11);
        save(tenantId + 1, "HOME_EXPERIENCE", "other-tenant", 12);
        Sort sort = Sort.by(
                Sort.Order.desc("occurredAt"), Sort.Order.desc("auditEventId"));

        var first = repository.findByTenantIdAndTargetTypeIn(
                tenantId, PlatformAuditService.HOME_STUDIO_TARGET_TYPES,
                PageRequest.of(0, 1, sort));
        var second = repository.findByTenantIdAndTargetTypeIn(
                tenantId, PlatformAuditService.HOME_STUDIO_TARGET_TYPES,
                PageRequest.of(1, 1, sort));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).singleElement()
                .extracting(PlatformAuditEvent::getAction)
                .isEqualTo("home-experience.rolled-back");
        assertThat(second.getContent()).singleElement()
                .extracting(PlatformAuditEvent::getTargetType)
                .isEqualTo("HOME_TEMPLATE");
    }

    private void save(long tenantId, String targetType, String action, long second) {
        repository.saveAndFlush(PlatformAuditEvent.builder()
                .auditEventId(UUID.randomUUID())
                .tenantId(tenantId)
                .actorType("USER")
                .actorId(11L)
                .action(action)
                .targetType(targetType)
                .targetId("target-" + second)
                .outcome("SUCCESS")
                .occurredAt(Instant.parse("2026-09-16T10:00:00Z").plusSeconds(second))
                .build());
    }
}
