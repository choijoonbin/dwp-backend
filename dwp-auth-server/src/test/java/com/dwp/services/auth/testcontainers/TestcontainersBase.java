package com.dwp.services.auth.testcontainers;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers 기반 통합 테스트 베이스 클래스 (C24)
 * <p>
 * 목적: 로컬에서는 되는데 CI에서 깨지는 것을 방지
 * - 실제 PostgreSQL 컨테이너 사용 (H2 금지)
 * - 모든 통합 테스트는 이 클래스를 상속
 * </p>
 */
@SpringBootTest
@Testcontainers
public abstract class TestcontainersBase {

    protected static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("dwp_auth_test")
                .withUsername("test_user")
                .withPassword("test_password")
                .withReuse(true);
    }

    @BeforeAll
    static void startContainers() {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        
        // Flyway 활성화 (마이그레이션 자동 적용)
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
