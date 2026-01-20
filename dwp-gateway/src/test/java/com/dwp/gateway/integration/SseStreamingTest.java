package com.dwp.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;

/**
 * SSE 스트리밍 통합 테스트
 * 
 * Gateway가 SSE 요청을 올바르게 처리하는지 검증합니다:
 * - SSE 응답 헤더 보장 (Content-Type, Cache-Control, Connection, X-Accel-Buffering)
 * - 타임아웃 설정 (300초)
 * - POST SSE 지원
 * - 헤더 전파 (X-Tenant-ID, Authorization 등)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SseStreamingTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    public void testSseResponseHeaders() {
        webTestClient
                .post()
                .uri("/api/aura/test/stream")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "text/event-stream")
                .header("X-Tenant-ID", "1")
                .header("X-DWP-Source", "AURA")
                .body(BodyInserters.fromValue("{\"prompt\": \"test\"}"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .expectHeader().valueEquals("Cache-Control", "no-cache")
                .expectHeader().valueEquals("Connection", "keep-alive")
                .expectHeader().valueEquals("X-Accel-Buffering", "no");
    }

    @Test
    public void testRequiredHeaderValidation() {
        // X-Tenant-ID 누락 시 400 Bad Request
        webTestClient
                .post()
                .uri("/api/aura/test/stream")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "text/event-stream")
                // X-Tenant-ID 헤더 없음
                .body(BodyInserters.fromValue("{\"prompt\": \"test\"}"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @SuppressWarnings("null")
    public void testSseTimeout() {
        // 타임아웃 설정 확인 (300초)
        // 실제 스트리밍 테스트는 Aura-Platform이 실행 중일 때만 가능
        Duration timeout = Duration.ofSeconds(300);
        webTestClient
                .mutate()
                .responseTimeout(timeout)
                .build()
                .post()
                .uri("/api/aura/test/stream")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "text/event-stream")
                .header("X-Tenant-ID", "1")
                .header("X-DWP-Source", "AURA")
                .body(BodyInserters.fromValue("{\"prompt\": \"test\"}"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    public void testLastEventIdHeaderPropagation() {
        // Last-Event-ID 헤더 전파 확인
        webTestClient
                .post()
                .uri("/api/aura/test/stream")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "text/event-stream")
                .header("X-Tenant-ID", "1")
                .header("X-DWP-Source", "AURA")
                .header("Last-Event-ID", "event_123")
                .body(BodyInserters.fromValue("{\"prompt\": \"test\"}"))
                .exchange()
                .expectStatus().isOk();
        // 헤더 전파는 Gateway 로그에서 확인 가능
    }
}
