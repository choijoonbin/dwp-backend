package com.dwp.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ApiCallHistoryFilter 테스트
 * 
 * 검증 항목:
 * - 일반 API 요청은 기존대로 전체 정보 적재
 * - SSE 요청(/api/aura/test/stream)은 "요약 1건"만 적재
 * - SSE 요청은 queryString, requestSizeBytes, responseSizeBytes 제외
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiCallHistoryFilter 테스트")
@SuppressWarnings("null")  // Null type safety 경고 억제
class ApiCallHistoryFilterTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private ApiCallHistoryFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // WebClient Mock 설정
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.empty());

        filter = new ApiCallHistoryFilter(webClient);
    }

    @Test
    @DisplayName("일반 API 요청은 전체 정보 기록")
    void testNormalApiRequestRecordsFullInfo() {
        // Given: 일반 API 요청
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/admin/users")
                .header("X-Tenant-ID", "1")
                .header("X-User-ID", "100")
                .header("Content-Length", "100")
                .build();
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().set("Content-Length", "500");

        // When: 필터 실행
        StepVerifier.create(filter.filter(exchange, exchange2 -> Mono.empty()))
                .verifyComplete();

        // Then: 전체 정보가 기록되어야 함
        ArgumentCaptor<ApiCallHistoryFilter.ApiCallHistoryRequest> captor = 
                ArgumentCaptor.forClass(ApiCallHistoryFilter.ApiCallHistoryRequest.class);
        
        verify(requestBodySpec, times(1)).bodyValue(captor.capture());
        
        ApiCallHistoryFilter.ApiCallHistoryRequest recorded = captor.getValue();
        assertThat(recorded.getPath()).isEqualTo("/api/admin/users");
        assertThat(recorded.getQueryString()).isNotNull(); // 일반 요청은 쿼리스트링 기록
        assertThat(recorded.getRequestSizeBytes()).isNotNull(); // 일반 요청은 크기 기록
        assertThat(recorded.getResponseSizeBytes()).isNotNull(); // 일반 요청은 크기 기록
    }

    @Test
    @DisplayName("SSE 요청은 요약 1건만 기록 (queryString, 크기 제외)")
    void testSseRequestRecordsSummaryOnly() {
        // Given: SSE 요청
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/aura/test/stream")
                .header("X-Tenant-ID", "1")
                .header("X-User-ID", "100")
                .header("X-Agent-ID", "agent_123")
                .header("Accept", "text/event-stream")
                .header("Content-Length", "200")
                .build();
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponse().getHeaders().set("Content-Length", "1000");

        // When: 필터 실행
        StepVerifier.create(filter.filter(exchange, exchange2 -> Mono.empty()))
                .verifyComplete();

        // Then: 요약 정보만 기록되어야 함
        ArgumentCaptor<ApiCallHistoryFilter.ApiCallHistoryRequest> captor = 
                ArgumentCaptor.forClass(ApiCallHistoryFilter.ApiCallHistoryRequest.class);
        
        verify(requestBodySpec, times(1)).bodyValue(captor.capture());
        
        ApiCallHistoryFilter.ApiCallHistoryRequest recorded = captor.getValue();
        assertThat(recorded.getPath()).isEqualTo("/api/aura/test/stream");
        assertThat(recorded.getQueryString()).isNull(); // SSE 요청은 쿼리스트링 기록 안 함
        assertThat(recorded.getRequestSizeBytes()).isNull(); // SSE 요청은 크기 기록 안 함
        assertThat(recorded.getResponseSizeBytes()).isNull(); // SSE 응답은 크기 기록 안 함
        assertThat(recorded.getAgentId()).isEqualTo("agent_123");
        assertThat(recorded.getTraceId()).isNotNull(); // traceId는 기록됨
        assertThat(recorded.getStatusCode()).isEqualTo(200);
        assertThat(recorded.getLatencyMs()).isNotNull();
    }

    @Test
    @DisplayName("SSE 요청은 경로로도 식별 가능 (/stream 포함)")
    void testSseRequestIdentifiedByPath() {
        // Given: SSE 요청 (Accept 헤더 없지만 경로에 /stream 포함)
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/aura/test/stream")
                .header("X-Tenant-ID", "1")
                .build();
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().set("Content-Type", "text/event-stream");

        // When: 필터 실행
        StepVerifier.create(filter.filter(exchange, exchange2 -> Mono.empty()))
                .verifyComplete();

        // Then: SSE 요청으로 식별되어 요약만 기록
        ArgumentCaptor<ApiCallHistoryFilter.ApiCallHistoryRequest> captor = 
                ArgumentCaptor.forClass(ApiCallHistoryFilter.ApiCallHistoryRequest.class);
        
        verify(requestBodySpec, times(1)).bodyValue(captor.capture());
        
        ApiCallHistoryFilter.ApiCallHistoryRequest recorded = captor.getValue();
        assertThat(recorded.getQueryString()).isNull(); // SSE 요청은 쿼리스트링 기록 안 함
        assertThat(recorded.getRequestSizeBytes()).isNull(); // SSE 요청은 크기 기록 안 함
    }

    @Test
    @DisplayName("SSE 요청 비정상 종료 시 errorCode 기록")
    void testSseRequestErrorCodeRecorded() {
        // Given: SSE 요청이 499 (Client Closed)로 종료
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/aura/test/stream")
                .header("X-Tenant-ID", "1")
                .header("Accept", "text/event-stream")
                .build();
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(499)); // Client Closed
        exchange.getResponse().getHeaders().set("Content-Type", "text/event-stream");

        // When: 필터 실행
        StepVerifier.create(filter.filter(exchange, exchange2 -> Mono.empty()))
                .verifyComplete();

        // Then: errorCode가 기록되어야 함
        ArgumentCaptor<ApiCallHistoryFilter.ApiCallHistoryRequest> captor = 
                ArgumentCaptor.forClass(ApiCallHistoryFilter.ApiCallHistoryRequest.class);
        
        verify(requestBodySpec, times(1)).bodyValue(captor.capture());
        
        ApiCallHistoryFilter.ApiCallHistoryRequest recorded = captor.getValue();
        assertThat(recorded.getStatusCode()).isEqualTo(499);
        assertThat(recorded.getErrorCode()).isEqualTo("CLIENT_CLOSED");
    }
}
