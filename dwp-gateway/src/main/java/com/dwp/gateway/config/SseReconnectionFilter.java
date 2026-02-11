package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 재연결 지원 필터
 * 
 * SSE 응답에 id: 라인을 추가하여 재연결을 지원합니다.
 * - Last-Event-ID 헤더를 Aura-Platform으로 전달
 * - SSE 응답에 id: 라인 추가 (이벤트 ID 생성)
 * 
 * SSE 표준:
 * - 각 이벤트는 id: 라인을 포함할 수 있음
 * - 클라이언트는 Last-Event-ID 헤더로 마지막 수신한 이벤트 ID를 전송
 * - 서버는 Last-Event-ID를 기반으로 재연결 시 중단된 지점부터 재개 가능
 */
@Slf4j
@Component
public class SseReconnectionFilter implements GlobalFilter, Ordered {

    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    /** Aura 스트림 종료 선언. 수신 후 빈 이벤트는 우리(Gateway) 쪽에서 걸러서 프론트에 전달하지 않음. */
    private static final String SSE_DONE_MARKER = "[DONE]";
    private static final AtomicLong eventIdCounter = new AtomicLong(0);

    @Value("${gateway.sse.reconnection.enabled:true}")
    private boolean reconnectionEnabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String acceptHeader = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        String path = request.getURI().getPath();
        
        // SSE 요청인지 확인
        boolean isSseRequest = (acceptHeader != null && acceptHeader.contains(TEXT_EVENT_STREAM))
                || (path != null && path.contains("/stream"));
        
        if (!isSseRequest) {
            return chain.filter(exchange);
        }

        // 재연결 기능 비활성화 옵션 (테스트/디버깅용: gateway.sse.reconnection.enabled=false)
        if (!reconnectionEnabled) {
            log.debug("SSE reconnection filter disabled (skipping id: line injection): path={}", path);
            return chain.filter(exchange);
        }

        // Last-Event-ID 헤더 확인 및 로깅
        String lastEventId = request.getHeaders().getFirst(LAST_EVENT_ID_HEADER);
        if (lastEventId != null && !lastEventId.isEmpty()) {
            log.info("SSE reconnection detected: Last-Event-ID={}, path={}", lastEventId, path);
            // Last-Event-ID 헤더를 Aura-Platform으로 전달 (HeaderPropagationFilter가 처리)
        }

        // SSE 응답에 id: 라인 추가를 위한 데코레이터
        // 주의: 변환된 Flux를 반드시 originalResponse.writeWith(...)로 써야 클라이언트에 전달됨
        ServerHttpResponse originalResponse = exchange.getResponse();
        String pathForLog = path;
        final AtomicLong chunkCount = new AtomicLong(0);
        /** [DONE] 수신 여부. 우리(Gateway) 쪽에서 [DONE] 이후 빈 이벤트를 걸러 프론트에 전달하지 않음. */
        final AtomicBoolean doneSeen = new AtomicBoolean(false);
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            @SuppressWarnings("null")
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                log.info("SSE writeWith() entered: path={} (suspected disconnect trace)", pathForLog);
                Flux<DataBuffer> modifiedFlux = Flux.from(body)
                        .doOnSubscribe(s -> log.info("SSE body subscribed: path={} (suspected disconnect trace)", pathForLog))
                        .index()
                        .doOnNext(tuple -> {
                            if (tuple.getT1() == 0) {
                                log.info("SSE first chunk from downstream: path={} size={} bytes (suspected disconnect trace)", pathForLog, tuple.getT2().readableByteCount());
                            }
                        })
                        .map(tuple -> tuple.getT2())
                        .doOnCancel(() -> log.warn("SSE stream CANCELLED (writeWith): path={} chunkCount={} (suspected disconnect trace)", pathForLog, chunkCount.get()))
                        .doOnComplete(() -> log.info("SSE stream completed by downstream (writeWith): path={} totalChunks={} (suspected disconnect trace)", pathForLog, chunkCount.get()))
                        .doOnError(e -> log.error("SSE stream error (writeWith): path={} chunkCount={} exception={} message={} (suspected disconnect trace)", pathForLog, chunkCount.get(), e.getClass().getName(), e.getMessage(), e))
                        .doFinally(sig -> log.info("SSE stream finally (writeWith): path={} signal={} chunkCount={} (suspected disconnect trace)", pathForLog, sig, chunkCount.get()))
                        .map(this::processChunk);
                return originalResponse.writeWith(modifiedFlux);
            }

            @Override
            @SuppressWarnings("null")
            public Mono<Void> writeAndFlushWith(org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
                // 스트리밍 시 NettyWriteResponseFilter가 writeWith 대신 writeAndFlushWith 호출함 → 여기서 로깅
                log.info("SSE writeAndFlushWith() entered: path={} (suspected disconnect trace)", pathForLog);
                Flux<org.reactivestreams.Publisher<? extends DataBuffer>> modifiedOuter = Flux.from(body)
                        .doOnSubscribe(s -> log.info("SSE body subscribed (writeAndFlushWith): path={} (suspected disconnect trace)", pathForLog))
                        .index()
                        .doOnNext(tuple -> {
                            if (tuple.getT1() == 0) {
                                log.info("SSE first chunk (writeAndFlushWith): path={} (suspected disconnect trace)", pathForLog);
                            }
                        })
                        .map(tuple -> tuple.getT2())
                        .doOnCancel(() -> log.warn("SSE stream CANCELLED (writeAndFlushWith): path={} chunkCount={} → downstream(FE) likely closed (suspected disconnect trace)", pathForLog, chunkCount.get()))
                        .doOnComplete(() -> log.info("SSE stream completed (writeAndFlushWith): path={} totalChunks={} (suspected disconnect trace)", pathForLog, chunkCount.get()))
                        .doOnError(e -> log.error("SSE stream error (writeAndFlushWith): path={} chunkCount={} exception={} message={} (suspected disconnect trace)", pathForLog, chunkCount.get(), e.getClass().getName(), e.getMessage(), e))
                        .doFinally(sig -> log.info("SSE stream finally (writeAndFlushWith): path={} signal={} chunkCount={} (suspected disconnect trace)", pathForLog, sig, chunkCount.get()))
                        .map(innerPub -> Flux.from(innerPub)
                                .map(dataBuffer -> processChunk(dataBuffer))
                                .doOnComplete(() -> log.debug("SSE inner chunk write completed to client: path={}", pathForLog))
                                .doOnError(e -> log.warn("SSE inner chunk write FAILED: path={} chunkCount={} exception={} message={} (suspected disconnect trace)", pathForLog, chunkCount.get(), e.getClass().getName(), e.getMessage(), e)));
                return originalResponse.writeAndFlushWith(modifiedOuter);
            }

            @SuppressWarnings("null")
            private DataBuffer processChunk(DataBuffer dataBuffer) {
                int readableBytes = dataBuffer.readableByteCount();
                long idx = chunkCount.incrementAndGet();
                if (idx == 1) {
                    log.info("SSE processChunk FIRST chunk: path={} bytes={} (suspected disconnect trace)", pathForLog, readableBytes);
                }
                byte[] bytes = new byte[readableBytes];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                try {
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    log.debug("SseReconnectionFilter processing chunk: chunkIndex={} bytes={} preview={}",
                            idx, readableBytes,
                            content.length() > 0 ? content.substring(0, Math.min(50, content.length())).replace("\n", "\\n") : "");
                    String withIds = addEventIdIfNeeded(content);
                    String modifiedContent = stripEmptyEventsAfterDone(withIds, doneSeen);
                    int outBytes = modifiedContent.getBytes(StandardCharsets.UTF_8).length;
                    if (idx == 1) {
                        log.info("SSE processChunk FIRST chunk done (id injected): path={} bytesIn={} bytesOut={} (suspected disconnect trace)", pathForLog, readableBytes, outBytes);
                    }
                    return originalResponse.bufferFactory().wrap(modifiedContent.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Pass-through: do not fail the stream on one bad chunk (e.g. empty/malformed)
                    log.error("SseReconnectionFilter processChunk failed, passing through original chunk: path={} chunkIndex={} bytes={} exception={} message={}",
                            pathForLog, idx, readableBytes, e.getClass().getName(), e.getMessage(), e);
                    log.info("SSE processChunk PASSTHROUGH (no id injection): path={} chunkIndex={} bytes={} (suspected disconnect trace)", pathForLog, idx, readableBytes);
                    return originalResponse.bufferFactory().wrap(bytes);
                }
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * SSE 이벤트에 id: 라인이 없으면 추가
     * 
     * @param content 원본 SSE 이벤트 내용
     * @return id: 라인이 추가된 SSE 이벤트 내용
     */
    private String addEventIdIfNeeded(String content) {
        // 이미 id: 라인이 있으면 그대로 반환
        if (content.contains("id:")) {
            log.debug("SseReconnectionFilter addEventIdIfNeeded: already has id:, contentLength={}", content.length());
            return content;
        }
        
        // SSE 이벤트 형식: data: ...\n\n 또는 data: ...\n\n\n
        // 각 이벤트 블록에 id: 라인 추가
        String[] events = content.split("\n\n");
        log.debug("SseReconnectionFilter addEventIdIfNeeded: contentLength={} eventBlocks={}", content.length(), events.length);
        StringBuilder result = new StringBuilder();
        
        for (String event : events) {
            if (event.trim().isEmpty()) {
                result.append("\n\n");
                continue;
            }
            
            // 이벤트 ID 생성 (타임스탬프 + 카운터)
            long eventId = System.currentTimeMillis() * 1000 + eventIdCounter.incrementAndGet() % 1000;
            
            // id: 라인을 맨 앞에 추가
            result.append("id: ").append(eventId).append("\n");
            result.append(event);
            result.append("\n\n");
        }
        
        return result.toString();
    }

    /**
     * [DONE] 수신 이후 downstream(Starlette 등)에서 올 수 있는 빈 이벤트를 제거.
     * 우리(Gateway) 백엔드에서 걸러서 프론트에는 [DONE]까지만 전달.
     *
     * @param content id:가 붙은 SSE 본문(청크 단위)
     * @param doneSeen 이 스트림에서 [DONE]을 이미 본 여부 (출력 파라미터로 갱신)
     * @return 빈 이벤트가 제거된 본문 (전부 제거되면 빈 문자열)
     */
    private String stripEmptyEventsAfterDone(String content, AtomicBoolean doneSeen) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String[] events = content.split("\n\n");
        StringBuilder result = new StringBuilder();
        for (String event : events) {
            if (event.trim().isEmpty()) {
                if (doneSeen.get()) {
                    continue;
                }
                result.append("\n\n");
                continue;
            }
            if (doneSeen.get()) {
                if (isEmptyOrDataOnlyEvent(event)) {
                    continue;
                }
            }
            if (event.contains(SSE_DONE_MARKER)) {
                doneSeen.set(true);
            }
            result.append(event);
            result.append("\n\n");
        }
        return result.toString();
    }

    /** 빈 페이로드 이벤트 여부 (data: 없음 또는 data: 만 있는 블록) */
    private boolean isEmptyOrDataOnlyEvent(String eventBlock) {
        String t = eventBlock.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.equals("data:") || t.startsWith("data:\n") || t.startsWith("data:\r\n")) {
            return true;
        }
        // id: xxx\ndata: 또는 id: xxx\ndata:\n 형태
        String withoutId = t.replaceFirst("^id:\\s*[^\\n]+\\n?", "").trim();
        return withoutId.isEmpty() || withoutId.equals("data:") || withoutId.startsWith("data:\n") || withoutId.startsWith("data:\r\n");
    }

    @Override
    public int getOrder() {
        // SseResponseHeaderFilter 이후에 실행되어 응답 본문을 수정
        return -40;
    }
}
