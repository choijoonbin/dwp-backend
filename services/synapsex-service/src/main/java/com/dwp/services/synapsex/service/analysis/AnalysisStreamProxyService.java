package com.dwp.services.synapsex.service.analysis;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Phase3 옵션 B: BE SSE 프록시 — Aura 스트림을 server-side로 연결하여 FE에 그대로 중계.
 * GET /api/synapse/analysis-runs/{runId}/stream 호출 시 사용.
 * <p>
 * Aura 연동 계약 (Aura 문서 BE 측 필수 조치 반영):
 * <ul>
 *   <li>Streaming HTTP Client: BodyHandlers.ofLines(UTF_8) 로 라인 단위 스트리밍, 받은 라인 즉시 FE 전달.</li>
 *   <li>요청 헤더: Accept: text/event-stream, Connection: keep-alive. Upgrade 없음 (HTTP/1.1 전용).</li>
 *   <li>타임아웃: connect 10초, read 30분.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisStreamProxyService {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30분
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Aura 문서: Read timeout 5분 이상 권장. 30분 유지. */
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofMinutes(30);

    private final CaseAnalysisRunRepository runRepository;

    @Value("${aura.base-url:http://localhost:9000}")
    private String auraBaseUrl;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-proxy-" + r.hashCode());
        t.setDaemon(true);
        return t;
    });

    /**
     * Aura 분석 스트림을 프록시하여 SseEmitter로 전달.
     * runId로 run 조회 후 caseId로 Aura URL 구성, Authorization 전파.
     */
    public SseEmitter streamFromAura(Long tenantId, UUID runId, Long caseIdParam, String authorization) {
        var run = runRepository.findByRunIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "분석 실행을 찾을 수 없습니다."));
        if (caseIdParam != null && !run.getCaseId().equals(caseIdParam)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "caseId가 run과 일치하지 않습니다.");
        }
        Long caseId = run.getCaseId();
        String path = "/aura/cases/" + caseId + "/analysis/stream?runId=" + URLEncoder.encode(runId.toString(), StandardCharsets.UTF_8);
        String auraUrl = auraBaseUrl.replaceAll("/$", "") + path;
        log.info("SSE proxy connecting to Aura: runId={} caseId={} url={}", runId, caseId, auraUrl);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> log.info("SSE proxy emitter onCompletion: runId={} (suspected disconnect trace)", runId));
        emitter.onTimeout(() -> log.warn("SSE proxy emitter onTimeout: runId={}", runId));
        emitter.onError(e -> log.warn("SSE proxy emitter onError: runId={} {}", runId, e.getMessage()));

        executor.submit(() -> {
            try {
                // HTTP/1.1 only: avoid Upgrade header (Aura logs "Unsupported upgrade request" when client sends Upgrade, e.g. h2c).
                var client = java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(HTTP_CONNECT_TIMEOUT)
                        .build();
                // SSE: Accept only. Connection은 Java HttpClient에서 restricted header라 설정 불가(HTTP/1.1은 기본 keep-alive).
                var reqBuilder = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create(auraUrl))
                        .timeout(HTTP_READ_TIMEOUT)
                        .header("Accept", "text/event-stream")
                        .GET();
                if (authorization != null && !authorization.isBlank()) {
                    reqBuilder.header("Authorization", authorization);
                }
                var request = reqBuilder.build();
                // Aura 문서 권장: 스트리밍 읽기 — ofLines() 로 라인 단위 수신, 수신 즉시 FE 전달 (버퍼링 금지).
                HttpResponse<Stream<String>> response = client.send(request,
                        HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() != 200) {
                    log.warn("SSE proxy Aura returned non-200: runId={} status={}", runId, response.statusCode());
                    sendFailedEvent(emitter, runId, "Aura stream returned " + response.statusCode());
                    emitter.complete();
                    return;
                }
                log.info("SSE proxy Aura responded 200, streaming: runId={}", runId);
                // work.txt §5: 서버가 flush 안 함(Spring에서 종종) → 연결 직후 빈 코멘트 1회 전송으로 flush 유도
                try {
                    emitter.send(SseEmitter.event().comment("").build());
                } catch (IllegalStateException e) {
                    log.warn("SSE proxy client already disconnected before first data: runId={} exception={} message={}", runId, e.getClass().getName(), e.getMessage(), e);
                    try { emitter.complete(); } catch (IllegalStateException ignored) {}
                    return;
                }
                long totalBytes = 0;
                long lineCount = 0;
                try (Stream<String> lines = response.body()) {
                    for (java.util.Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                        String line = it.next();
                        byte[] chunk = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        totalBytes += chunk.length;
                        lineCount++;
                        if (lineCount == 1) {
                            log.debug("SSE proxy first line received: runId={} lineLength={}", runId, line.length());
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("SSE line received: runId={} bytes={} total={}", runId, chunk.length, totalBytes);
                        }
                        if (lineCount == 1) {
                            log.info("SSE proxy about to send first chunk: runId={} bytes={} (write/flush trace)", runId, chunk.length);
                        }
                        try {
                            // String 사용: SseEmitter.send(Object,MediaType)는 HttpMessageConverter 사용. ByteBuffer는 converter 없음(No suitable converter for HeapByteBuffer). text/event-stream에는 String이 안전.
                            String payload = new String(chunk, StandardCharsets.UTF_8);
                            emitter.send(payload, MediaType.TEXT_EVENT_STREAM);
                            if (lineCount == 1) {
                                log.info("SSE proxy first chunk sent to client: runId={} bytes={} (suspected disconnect trace)", runId, chunk.length);
                            }
                        } catch (IllegalStateException e) {
                            // 연결이 이미 끊어진 상태. 끊는 쪽은 Gateway 또는 FE(브라우저)이며, SynapseX는 전달만 함.
                            log.warn("SSE proxy client disconnected while forwarding: runId={} totalBytesForwarded={} lineCount={} exception={} message={} (connection already closed by client or gateway)", runId, totalBytes, lineCount, e.getClass().getName(), e.getMessage(), e);
                            log.info("SSE proxy completing emitter after client disconnect: runId={} (suspected disconnect trace)", runId);
                            try {
                                emitter.complete();
                            } catch (IllegalStateException ignored) {}
                            return;
                        }
                    }
                }
                log.info("SSE proxy Aura stream ended: runId={} totalBytesForwarded={} lineCount={} (0 bytes = Aura sent no data)", runId, totalBytes, lineCount);
                if (totalBytes == 0) {
                    log.info("SSE proxy: stream closed by remote (Aura) without sending any bytes: runId={}", runId);
                    sendFailedEvent(emitter, runId, "Aura returned 200 but sent no SSE data. Check Aura endpoint GET /aura/cases/{caseId}/analysis/stream?runId=.");
                }
                log.info("SSE proxy completing emitter (normal end): runId={} totalBytesForwarded={} (suspected disconnect trace)", runId, totalBytes);
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // 이미 끊긴 연결에 complete 시도한 경우 무시
                }
            } catch (Exception e) {
                log.warn("SSE proxy Aura stream error: runId={} {}", runId, e.getMessage());
                String userMessage = toUserFriendlyMessage(e);
                try {
                    sendFailedEvent(emitter, runId, userMessage);
                } catch (Exception ignored) {}
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {}
            }
        });

        return emitter;
    }

    private String toUserFriendlyMessage(Throwable e) {
        if (e == null) return "Aura connection failed";
        if (e instanceof java.net.ConnectException) {
            return "Aura Platform is not reachable (connection refused). Ensure Aura is running at " + auraBaseUrl + " (config: aura.base-url or AURA_BASE_URL).";
        }
        String msg = e.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName();
    }

    private void sendFailedEvent(SseEmitter emitter, UUID runId, String message) {
        try {
            String data = "{\"status\":\"failed\",\"runId\":\"" + runId + "\",\"message\":\"" + (message != null ? message.replace("\"", "\\\"") : "") + "\"}";
            emitter.send(SseEmitter.event().name("failed").data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Failed to send failed event: {}", e.getMessage());
        }
    }

    /**
     * 스트림 개시 전 오류(예: runId 미존재) 시 컨트롤러에서 사용.
     * Accept: text/event-stream 요청에 대해 JSON 예외 응답을 하면 HttpMediaTypeNotAcceptableException이 나므로,
     * 200 + SSE "failed" 이벤트 1회 전송 후 완료하는 emitter를 반환한다.
     */
    public SseEmitter createFailedEmitter(UUID runId, String message) {
        SseEmitter emitter = new SseEmitter(5_000L);
        try {
            sendFailedEvent(emitter, runId, message);
            emitter.complete();
        } catch (Exception e) {
            log.debug("createFailedEmitter: {}", e.getMessage());
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
