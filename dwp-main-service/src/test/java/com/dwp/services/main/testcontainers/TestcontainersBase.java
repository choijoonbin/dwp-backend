package com.dwp.services.main.testcontainers;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers 기반 통합 테스트 베이스 클래스 (C26)
 * <p>
 * main-service용 Testcontainers 베이스
 * </p>
 */
@SpringBootTest
@Testcontainers
public abstract class TestcontainersBase {

    protected static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("dwp_main_test")
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
        
        // Redis는 테스트에서 비활성화 (HITL은 Redis 없이도 테스트 가능)
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
    }
}
