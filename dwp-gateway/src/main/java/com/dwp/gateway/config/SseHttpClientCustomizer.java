package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 다운스트림(예: SynapseX) SSE 스트리밍 시 연결이 첫 청크 직후 끊기는 현상 완화용.
 * Reactor Netty HttpClient의 responseTimeout(청크 간 읽기 대기 시간)을 길게 설정하여,
 * 이벤트 간 간격이 길어도 연결이 닫히지 않도록 한다.
 *
 * @see reactor.netty.http.client.HttpClient#responseTimeout(Duration)
 */
@Slf4j
@Component
public class SseHttpClientCustomizer implements HttpClientCustomizer, Ordered {

    /** 청크 간 최대 대기 시간: 30분 (SSE 분석 스트림과 동일) */
    private static final Duration STREAM_READ_INTERVAL = Duration.ofMinutes(30);

    @Override
    public HttpClient customize(HttpClient httpClient) {
        HttpClient customized = httpClient.responseTimeout(STREAM_READ_INTERVAL);
        log.info("Gateway HttpClient: responseTimeout (max read interval) set to {} for SSE streaming", STREAM_READ_INTERVAL);
        return customized;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
