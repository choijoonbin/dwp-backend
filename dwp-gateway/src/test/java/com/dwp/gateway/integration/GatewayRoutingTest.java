package com.dwp.gateway.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Gateway 라우팅 통합 테스트
 * 
 * 모든 서비스에 대한 Gateway 라우팅이 정상적으로 동작하는지 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@DisplayName("Gateway 라우팅 통합 테스트")
class GatewayRoutingTest {
    
    @Autowired
    private WebTestClient webTestClient;
    
    @Test
    @DisplayName("Aura-Platform 라우팅 검증")
    void testAuraPlatformRouting() {
        webTestClient
            .get()
            .uri("/api/aura/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("Main Service 라우팅 검증")
    void testMainServiceRouting() {
        webTestClient
            .get()
            .uri("/api/main/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("Auth Server 라우팅 검증")
    void testAuthServerRouting() {
        webTestClient
            .get()
            .uri("/api/auth/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("Mail Service 라우팅 검증")
    void testMailServiceRouting() {
        webTestClient
            .get()
            .uri("/api/mail/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("Chat Service 라우팅 검증")
    void testChatServiceRouting() {
        webTestClient
            .get()
            .uri("/api/chat/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("Approval Service 라우팅 검증")
    void testApprovalServiceRouting() {
        webTestClient
            .get()
            .uri("/api/approval/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful();
    }
    
    @Test
    @DisplayName("존재하지 않는 경로 처리 검증")
    void testNonExistentRoute() {
        webTestClient
            .get()
            .uri("/api/nonexistent/health")
            .exchange()
            .expectStatus().isNotFound();
    }
}
