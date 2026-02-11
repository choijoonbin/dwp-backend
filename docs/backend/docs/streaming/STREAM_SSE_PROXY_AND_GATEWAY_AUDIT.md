# SSE 스트리밍 구간별 감사: “첫 청크 직후 onComplete” 구간 특정

**목적**: SynapseX가 Aura에서 첫 라인을 받아 보내려는 순간 "client disconnected"가 발생하고, Gateway가 "first chunk → onComplete"로 끝나는 현상의 원인 구간을 (1) Aura→SynapseX, (2) SynapseX SSE 프록시, (3) Gateway→FE 로 분해해 확정한다.

**작성일**: 2026-02-10

---

## A. 엔드포인트/흐름 요약 (필수)

### 흐름 개요

1. **분석 트리거**  
   - `POST /api/synapse/cases/{caseId}/analysis-runs`  
   - 응답: `202 Accepted`, body에 `runId`, `streamUrl` 포함.

2. **SSE 스트림 (BE 프록시)**  
   - `GET /api/synapse/analysis-runs/{runId}/stream?caseId=...` (선택)  
   - SynapseX가 Aura `GET /aura/cases/{caseId}/analysis/stream?runId={runId}` 에 연결 후, 수신 라인을 그대로 FE로 전달.

3. **경로**
   - **로컬**: `http://localhost:8080/api/synapse/...` (Gateway) → `http://localhost:8085/synapse/...` (SynapseX) → `http://localhost:9000/aura/...` (Aura).
   - **운영 (Gateway 경유)**: `https://<GATEWAY_HOST>/api/synapse/analysis-runs/{runId}/stream`  
     - Gateway가 `/api` StripPrefix 후 SynapseX로 전달: `GET /synapse/analysis-runs/{runId}/stream`.

### 엔드포인트 정리

| 구간 | 메서드 | URL (로컬) | 비고 |
|------|--------|------------|------|
| 트리거 | POST | `http://localhost:8080/api/synapse/cases/{caseId}/analysis-runs` | 202 + runId/streamUrl |
| 스트림 (Client→Gateway) | GET | `http://localhost:8080/api/synapse/analysis-runs/{runId}/stream` | SSE, Gateway 경유 |
| 스트림 (Client→SynapseX 직접) | GET | `http://localhost:8085/synapse/analysis-runs/{runId}/stream` | 헤더에 X-Tenant-ID 등 필요 |
| 스트림 (SynapseX→Aura) | GET | `http://<AURA_HOST>/aura/cases/{caseId}/analysis/stream?runId={runId}` | Aura Platform (예: 9000) |

---

## B. 구간별 curl 재현 3종 (필수)

아래 3종을 실제 환경에서 실행하고, 결과(헤더·첫 이벤트·유지 여부)를 채워 넣어 구간을 특정한다.

**실측 환경 (2026-02-10)**  
- Gateway: localhost:8080 (기동됨)  
- SynapseX: localhost:8085 (기동됨, 2차 실측)  
- Aura: localhost:9000 (기동됨, 인증 필요 시 401)  
- runId: 테스트용 `00000000-0000-0000-0000-000000000001` 사용. **60초 스트림 검증** 시 DB에 존재하는 caseId로 `POST /api/synapse/cases/{caseId}/analysis-runs` 호출 후 발급한 runId 사용.

### B.1 Client → SynapseX 직접 (Gateway 미경유)

```bash
# runId, tenantId는 실제 값으로 교체. SynapseX 포트 8085.
curl -N -v \
  -H "Accept: text/event-stream" \
  -H "X-Tenant-ID: 1" \
  "http://localhost:8085/synapse/analysis-runs/{runId}/stream"
```

**확인 사항**  
- `Content-Type: text/event-stream` 유지 여부  
- 60초 이상 스트림 유지 여부  
- “첫 chunk 직후 종료” 재현 여부  

**결과 (실측)**  
- **1차 (SynapseX 미기동)**: `Connection refused` (localhost:8085).  
- **2차 (SynapseX 기동 후, 2026-02-10)**:  
  - 연결 성공 → `HTTP/1.1 500`, `Content-Length: 0`, `Connection: close`.  
  - 원인: runId 무효 또는 SynapseX→Aura 연결 실패(또는 Aura 401)로 스트림 미개시.  
- [ ] Content-Type: text/event-stream *(유효 runId 발급 후 200 SSE 시 확인)*  
- [ ] 60초 이상 유지 *(유효 runId로 재측정)*  
- [ ] 첫 chunk 직후 종료 발생 구간: *(유효 runId로 재측정)*  

**60초 유지 검증**: DB에 존재하는 caseId로 `POST /api/synapse/cases/{caseId}/analysis-runs` → runId 추출 후 위 curl에 넣고 `-m` 제거, 60초 이상 대기.

---

### B.2 SynapseX → Aura 직접 (SynapseX 서버 또는 동일 네트워크에서 실행 권장)

```bash
# Aura가 localhost:9000 일 때. caseId, runId 실제 값으로 교체.
curl -N -v \
  -H "Accept: text/event-stream" \
  "http://localhost:9000/aura/cases/{caseId}/analysis/stream?runId={runId}"
```

**확인 사항**  
- Aura가 200 + `text/event-stream` 반환 여부  
- 60초 이상 스트림 유지 여부  
- 첫 청크 직후 끊김 여부 (upstream 구간 특정)  

**결과 (실측)**  
- **실행 일시**: 2026-02-10  
- **실행 명령**: `caseId=1`, `runId=00000000-0000-0000-0000-000000000001`, `-m 15`  
- **실측**: `HTTP/1.1 401 Unauthorized`, `Content-Type: application/json`, `{"detail":"Not authenticated"}` — Aura(uvicorn) 인증 필요.  
- [ ] Content-Type: text/event-stream *(Bearer 토큰 등 인증 후 재측정)*  
- [ ] 60초 이상 유지 *(인증 후 재측정)*  
- [ ] 첫 chunk 직후 종료: *(인증 후 재측정)*  

**60초 유지 검증**: Aura에 인증 헤더 적용 후 동일 curl로 60초 이상 대기하여 upstream 스트림 유지 여부 확인.

---

### B.3 Client → Gateway 경유 (운영 경로)

```bash
# 로컬 Gateway 8080. 운영 시 <GATEWAY_HOST> 교체.
curl -N -v \
  -H "Accept: text/event-stream" \
  -H "X-Tenant-ID: 1" \
  "http://localhost:8080/api/synapse/analysis-runs/{runId}/stream"
```

**확인 사항**  
- `Content-Type: text/event-stream` 유지 여부  
- 60초 이상 유지 여부  
- “첫 chunk 직후 onComplete”가 이 경로에서만 재현되는지 (downstream 구간 특정)  

**결과 (실측)**  
- **1차 (SynapseX 미기동)**: Gateway → 500, `Content-Type: application/json`. 원인: 다운스트림 연결 실패.  
- **2차 (SynapseX 기동 후, 2026-02-10)**:  
  - Gateway(8080) 연결 성공 → `HTTP/1.1 500 Internal Server Error`.  
  - 응답 헤더: **`Content-Type: text/event-stream`**, `Cache-Control: no-cache`, `Connection: keep-alive`, `X-Accel-Buffering: no`, `Content-Length: 0`.  
  - 해석: Gateway가 SynapseX의 500 응답을 **SSE 라우트로 인식해 text/event-stream 헤더를 부여**하여 전달. SynapseX가 스트림을 열지 못해 500 반환.  
- [ ] Content-Type: text/event-stream ✅ *(500 응답 시에도 Gateway가 SSE 경로로 text/event-stream 부여)*  
- [ ] 60초 이상 유지 *(유효 runId로 200 SSE 시 재측정)*  
- [ ] 첫 chunk 직후 종료: *(유효 runId로 재측정)*  

**60초 유지 검증**: 유효 runId 발급 후 위 curl에 넣고 60초 이상 대기. B.1 유지·B.3만 끊기면 Gateway 구간 의심.

---

**구간 특정 요약 (실측 후 채움)**  
- **1차 실측**: B.1 Connection refused, B.2 Aura 401, B.3 Gateway 500.  
- **2차 실측 (SynapseX 기동 후)**: B.1 SynapseX 500(Connection: close), B.2 Aura 401, B.3 Gateway 500 + **Content-Type: text/event-stream** (Gateway가 SSE 라우트 헤더 적용).  
- **60초 스트림 검증 대기**: 유효 runId는 `POST /api/synapse/cases/{caseId}/analysis-runs` 필요(실제 DB caseId). 해당 runId로 B.1/B.3 각각 60초 이상 유지 시 → “첫 chunk 직후 onComplete” 구간 판단.  
- **판단 기준**:  
  - B.1만 끊김 → SynapseX SSE 프록시 또는 Aura 응답 의심.  
  - B.2만 끊김 → Aura(upstream) 의심.  
  - B.1은 유지·B.3만 끊김 → **Gateway(downstream) 구간** 의심.

**다음 단계 (실측 완료 후)**  
1. **유효 caseId 확보**: `GET /api/synapse/cases` 로 케이스 목록 조회 후 존재하는 caseId 사용.  
2. **유효 runId 발급**: DB에 존재하는 caseId로 `curl -X POST "http://localhost:8080/api/synapse/cases/{caseId}/analysis-runs" -H "X-Tenant-ID: 1" -H "Content-Type: application/json" -d '{}'` → 202 응답 body에서 runId 추출.  
3. **60초 유지 측정**: B.1·B.3 curl에 해당 runId 넣고 `-m 65` 또는 타임아웃 없이 60초 이상 대기 → Content-Type, 스트림 유지, “첫 chunk 직후 종료” 여부 기입.  
4. **Aura 인증 후 B.2**: Bearer 토큰 등 적용 후 `curl ... -H "Authorization: Bearer <token>"` 로 B.2 재측정 → upstream 스트림 유지 여부 기입.

---

## C. SynapseX SSE 프록시 구현 체크 (필수)

### C.1 Response headers 강제 설정

| 헤더 | 기대값 | 구현 위치 | 결과 |
|------|--------|-----------|------|
| Content-Type | text/event-stream; charset=utf-8 | Controller: `produces = MediaType.TEXT_EVENT_STREAM_VALUE`, ResponseEntity `.header(CACHE_CONTROL, "no-cache")`, `.header(CONNECTION, "keep-alive")` | ✅ Controller에서 설정. Body는 SseEmitter가 TEXT_EVENT_STREAM으로 전송. |
| Cache-Control | no-cache | `ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-cache")` | ✅ |
| Connection | keep-alive | `ResponseEntity.ok().header(HttpHeaders.CONNECTION, "keep-alive")` | ✅ |

- SynapseX는 Spring MVC `SseEmitter` 사용. `produces = MediaType.TEXT_EVENT_STREAM_VALUE` 및 ResponseEntity 헤더로 위 헤더 보장.

### C.2 SSE 프레임 형식

- Aura에서 받은 라인을 `(line + "\n")`으로 그대로 전송.  
- `emitter.send(ByteBuffer.wrap(chunk), MediaType.TEXT_EVENT_STREAM)` 사용.  
- **\n\n 종료**: 라인 단위이므로 이벤트 경계는 Aura 출력에 따름. `data:` 최소 포맷은 Aura 책임.

### C.3 keep-alive comment 한 번만 보내고 끝내는 경로

- **있음**: 연결 직후 빈 코멘트 1회 전송 (`emitter.send(SseEmitter.event().comment("").build())`)으로 flush 유도.  
- 그 후 Aura 스트림 라인을 계속 전달. 빈 코멘트만 보내고 끝내는 경로는 없음.

### C.4 WebClient/HttpClient 버퍼링

- **사용**: `java.net.http.HttpClient` + `HttpResponse.BodyHandlers.ofLines()`.  
- **버퍼링 없음**: `Stream<String>`으로 라인 단위 수신, 수신 즉시 `emitter.send(...)` 호출. `collectList()` 등으로 모으지 않음.

### C.5 disconnect 예외 로깅 (권장 보강)

- 현재: `IllegalStateException` catch 시 "client disconnected while forwarding" 로그.  
- **권장**: 예외의 **class name**, **message**, **stack 상위 5~10줄** 로깅 추가.  
- “진짜 FE cancel”인지 vs Gateway/프록시 종료인지 구분하기 위함.

**권장 수정 (SynapseX)**  
- `AnalysisStreamProxyService`에서 `catch (IllegalStateException e)` 블록에  
  `log.warn("SSE proxy client disconnect detail: runId={} exception={} message={}", runId, e.getClass().getName(), e.getMessage(), e);`  
  및 stack 일부 출력 추가.

---

## D. Gateway SSE 패스스루 점검 (필수, 가장 의심)

### D.1 응답 바디를 소비/가공하는 필터

| 필터 | 역할 | 바디 소비/가공 | 비고 |
|------|------|----------------|------|
| **SseResponseHeaderFilter** | SSE 요청 시 헤더 보장, writeWith 데코레이터에서 Flux에 doOnNext/doOnComplete 등 로깅만 추가 | ❌ 소비하지 않음. 동일 Flux를 그대로 전달. | 완료를 유발하지 않음. |
| **SseReconnectionFilter** | SSE 응답 본문에 `id:` 라인 추가 | ✅ **가공함**. `writeWith`/`writeAndFlushWith`에서 `Flux.from(body).map(this::processChunk)`로 청크 단위 변환 후 전달. | 본문을 읽어 수정 후 다시 쓰므로, 예외 시 스트림이 error/complete 될 수 있음. |
| **ApiCallHistoryFilter** | 요청 종료 후 로깅·API 이력 전송 | ❌ 응답 body 구독하지 않음. `chain.filter(...).then(Mono.fromRunnable(...))` 로 응답 후 콜백만 실행. | 완료 유발 없음. |

- **SseReconnectionFilter.processChunk**:  
  - `DataBuffer`를 읽어 문자열로 변환 후 `addEventIdIfNeeded(content)` 호출.  
  - 여기서 예외가 나면 `RuntimeException("SSE chunk processing failed")`로 Flux가 error 되며 스트림이 끊김.  
  - **권장**: 예외 시 로그 후 원본 청크를 그대로 반환하거나, fallback 처리로 스트림을 유지.

### D.2 modifyResponseBody / response caching

- **modifyResponseBody**: 사용하지 않음.  
- **response caching**: Gateway 기본 설정에 응답 캐싱 필터 없음.

### D.3 metrics/tracing에서 body subscribe

- ApiCallHistoryFilter는 응답 body를 구독하지 않음.  
- Micrometer/메트릭은 일반적으로 헤더/상태만 사용. body 구독 여부는 기본 설정 기준으로 없음.

### D.4 gzip/압축

- SSE는 스트리밍이므로 압축 시 flush 타이밍 이슈가 생길 수 있음.  
- Spring Cloud Gateway 기본으로 SSE 경로를 별도로 압축 비활성화한 설정은 없음.  
- **권장**: `/stream` 경로는 gzip 비활성화 확인 또는 적용.

### D.5 proxy buffering

- Gateway(Reactor Netty) 기준으로 proxy buffering 설정은 application.yml에 없음.  
- Nginx 등 앞단에 있을 경우 `proxy_buffering off;` 권장.

### D.6 read timeout / idle timeout

- **application.yml**  
  - `spring.cloud.gateway.httpclient.response-timeout: 300s` (전역).  
  - `synapsex-analysis-runs-stream` 라우트 `metadata.response-timeout: 1800000` (숫자만 있음).  
- **SseHttpClientCustomizer**: `httpClient.responseTimeout(Duration.ofMinutes(30))` 로 30분 설정.  
- **주의**: 라우트 메타데이터 `response-timeout: 1800000`은 Spring Cloud Gateway 기본 동작만으로는 적용되지 않을 수 있음. HttpClient 레벨에서의 SseHttpClientCustomizer 30분이 실제로 적용됨.

### D.7 HTTP/2 및 flush

- SSE는 HTTP/1.1 chunked flush에 의존.  
- Gateway는 Reactor Netty 기반이며, `writeAndFlushWith` 사용 시 청크 단위 flush.  
- SseReconnectionFilter가 `writeWith`와 `writeAndFlushWith` 둘 다 데코레이트. NettyWriteResponseFilter가 `writeAndFlushWith`를 사용하면 flush는 이루어짐.  
- HTTP/2 업그레이드가 SSE와 충돌하는 설정은 코드 상 없음.

### D.8 결론 (Gateway)

- **의심 요인 1**: **SseReconnectionFilter.processChunk** 에서 예외 발생 시 Flux가 error 되어 스트림이 곧바로 종료됨.  
  - 첫 청크 내용(빈 코멘트, 짧은 data 등)에 따라 `addEventIdIfNeeded` 또는 문자열 변환에서 예외가 날 가능성.  
- **의심 요인 2**: 라우트별 `metadata.response-timeout`이 실제 HttpClient에 반영되지 않아, 전역 300초로 끊길 수 있음. (현재는 SseHttpClientCustomizer로 30분 적용.)  
- **권장**: SseReconnectionFilter에서 processChunk 예외 시 원본 청크를 그대로 넘기거나, 예외 로그만 남기고 fallback 전달하도록 수정.

---

## E. 결론 및 수정 PR (필수)

### E.1 “첫 chunk 후 onComplete” 유발 후보

1. **Gateway – SseReconnectionFilter (가장 유력)**  
   - **위치**: `SseReconnectionFilter.processChunk()`  
   - **내용**: DataBuffer → String → `addEventIdIfNeeded(content)` → 새 DataBuffer.  
   - **위험**: 특정 청크 내용(빈 문자열, 특수 문자, 매우 긴 라인 등)에서 예외 발생 시 전체 Flux가 error → onComplete/onError로 스트림 종료.  
   - **조치**: try/catch로 예외 시 원본 `DataBuffer`를 그대로 반환하거나, 예외만 로깅하고 내용을 그대로 wrap 해서 전달.

2. **SynapseX – disconnect 로깅 부족**  
   - **위치**: `AnalysisStreamProxyService` catch (IllegalStateException e)  
   - **내용**: “client disconnected”로만 로깅하면, Gateway가 스트림을 끊은 것인지 FE가 끊은 것인지 구분 불가.  
   - **조치**: 예외 class, message, stack 상위 N줄 로깅 추가.

3. **FE/클라이언트**  
   - FE 검증 문서(`STREAM_DISCONNECT_FE_VERIFICATION.md`)상 첫 청크 수신 직후 abort/close 코드는 없음.  
   - 다만 EventSource 미사용, fetch+getReader 사용 시 브라우저/프록시에 따른 조기 종료 가능성은 배제할 수 없음.  
   - B.1 vs B.3 curl 결과로 “Gateway 경유 시에만 끊김”이 재현되면 Gateway 쪽 원인 우선.

### E.2 수정 PR 제안 및 적용 현황

1. **dwp-gateway – SseReconnectionFilter (필수)** — **적용됨**  
   - `processChunk`에서 예외 발생 시 원본 바이트를 그대로 `bufferFactory().wrap(bytes)`로 반환하도록 변경.  
   - 로그: `log.error("SseReconnectionFilter processChunk failed, passing through original chunk: path=... exception=... message=...", e);`  
   - 처리 예외가 나도 스트림이 끊기지 않음.

2. **synapsex-service – disconnect 로깅 보강 (권장)** — **적용됨**  
   - `AnalysisStreamProxyService`에서 `IllegalStateException` catch 시  
     - `e.getClass().getName()`, `e.getMessage()`, `e`(stack) 인자 추가하여 `log.warn(..., e)` 로깅.

3. **curl 3종 실행 및 본 문서 결과란 채우기**  
   - B.1, B.2, B.3 각각 실행 후  
     - Content-Type, 60초 유지, “첫 chunk 직후 종료” 여부를 문서에 기입해 구간 특정 확정.

4. **synapsex-service – 스트림 엔드포인트 예외 시 SSE 응답 (권장)** — **적용됨**  
   - runId 미존재 등 `BaseException` 시 `GlobalExceptionHandler`가 JSON 반환 → `Accept: text/event-stream`과 불일치 → 500 빈 body.  
   - 조치: `streamRun`에서 `BaseException` catch 후 `createFailedEmitter(runId, message)`로 200 + text/event-stream, `event: failed` 1회 전송 후 완료.

---

## SynapseX 서버 로그 분석 (2차 실측 500 원인)

테스트 시 SynapseX 로그에 다음이 기록되었다.

### 1. 흐름 요약

1. **SSE stream request received**: `runId=00000000-0000-0000-0000-000000000001`, `caseId=null`
2. **BaseException [E3000]**: `분석 실행을 찾을 수 없습니다.`  
   - 발생 위치: `AnalysisStreamProxyService.streamFromAura` 61행  
   - `runRepository.findByRunIdAndTenantId(runId, tenantId).orElseThrow(...)` — DB에 해당 runId가 없어 `Optional.orElseThrow`에서 예외 발생.
3. **HttpMediaTypeNotAcceptableException**: `No acceptable representation`  
   - `GlobalExceptionHandler#handleBaseException`이 `ApiResponse`(JSON)를 반환.  
   - 클라이언트 요청 헤더가 `Accept: text/event-stream`이므로 Spring이 JSON을 응답으로 사용할 수 없어 발생.
4. **결과**: 500 + 빈 body (`Content-Length: 0`). 스트림 엔드포인트인데 예외 시 JSON을 내려보내려다 실패한 이중 오류.

### 2. 결론 및 수정 사항

- **500 직접 원인**: (1) 유효하지 않은 runId → E3000, (2) SSE 엔드포인트에서 예외 처리 시 JSON 반환 → Accept 불일치로 406/500.
- **적용한 수정**: `CaseAnalysisController.streamRun`에서 `BaseException`을 catch하고, **SSE 형식**으로만 응답하도록 처리.  
  - `analysisStreamProxyService.createFailedEmitter(runId, e.getMessage())`로 `failed` 이벤트 1회 전송 후 완료하는 `SseEmitter`를 반환.  
  - 이제 runId 미존재 등 사전 오류 시에도 **200 + Content-Type: text/event-stream** 으로 `event: failed`, `data: {"status":"failed","runId":"...","message":"..."}` 한 번 전송 후 스트림 종료.

---

## 부록: curl 실측 원본 출력 (2026-02-10)

### 1차 실측 (SynapseX 미기동)

**B.1**: `Connection refused` (localhost:8085).  
**B.2**: `HTTP/1.1 401 Unauthorized`, `Content-Type: application/json`, `{"detail":"Not authenticated"}`.  
**B.3**: `HTTP/1.1 500`, `Content-Type: application/json`, Gateway 다운스트림 연결 실패.

---

### 2차 실측 (SynapseX 기동 후)

**B.1 원본**

```
* Connected to localhost (::1) port 8085
> GET /synapse/analysis-runs/00000000-0000-0000-0000-000000000001/stream HTTP/1.1
> Host: localhost:8085
> Accept: text/event-stream
> X-Tenant-ID: 1
< HTTP/1.1 500
< Content-Length: 0
< Date: Tue, 10 Feb 2026 12:27:13 GMT
< Connection: close
```

**B.2 원본** (변화 없음)

```
* Connected to localhost (127.0.0.1) port 9000
> GET /aura/cases/1/analysis/stream?runId=00000000-0000-0000-0000-000000000001 HTTP/1.1
< HTTP/1.1 401 Unauthorized
< content-type: application/json
{"detail":"Not authenticated"}
```

**B.3 원본**

```
* Connected to localhost (::1) port 8080
> GET /api/synapse/analysis-runs/00000000-0000-0000-0000-000000000001/stream HTTP/1.1
> Accept: text/event-stream
> X-Tenant-ID: 1
< HTTP/1.1 500 Internal Server Error
< Content-Type: text/event-stream
< Cache-Control: no-cache
< Connection: keep-alive
< X-Accel-Buffering: no
< Content-Length: 0
```

**3차 실측 (수정 적용 후)**  
- 무효 runId로 B.1 또는 B.3 호출 시: **200** + `Content-Type: text/event-stream`, 본문에 `event: failed`, `data: {"status":"failed","runId":"...","message":"분석 실행을 찾을 수 없습니다."}` 1회 수신 후 스트림 종료. (500 빈 body 아님.)

---

## F. [DONE] 이후 빈 이벤트 (Aura 확인 및 권장 처리)

### Aura 측 확인 결과

- Aura는 **`data: [DONE]\n\n` 한 번만 yield** 한 뒤 제너레이터가 끝남.
- `yield "\n"` 또는 `yield "data:\n"` 등 [DONE] 이후에 의도적으로 보내는 코드는 **없음**. 따라서 [DONE] 이후 애플리케이션 코드에서 보내는 바이트는 **0바이트**가 맞음.

### 빈 이벤트 1~2개가 나올 수 있는 쪽

1. **Starlette/uvicorn**  
   스트림 종료 시 빈 청크나 trailing flush를 한 번 보내는 동작이 있을 수 있음 (Aura 애플리케이션 코드 밖).
2. **Gateway의 `\n\n` 분할**  
   연결 종료/버퍼 플러시 시점에 남은 버퍼를 `\n\n` 기준으로 나누다 보면, 빈 문자열 블록이나 `"data:"`만 있는 블록이 생길 수 있음.

### 권장 처리 (구현 반영)

- **Gateway(우리 백엔드, dwp-gateway)**: `data: [DONE]` 수신 시점을 스트림 종료로 간주하고, **이후 downstream에서 오는 빈 payload 이벤트 1~2개는 Gateway에서 걸러서** 프론트에는 전달하지 않음. (`SseReconnectionFilter#stripEmptyEventsAfterDone`)
- **프론트**: `data: [DONE]` 수신 시 스트림 종료로 간주하면 됨. Gateway가 이미 빈 이벤트를 제거하므로 추가 처리 불필요.

---

## 참고 코드 위치

| 구간 | 파일 |
|------|------|
| SynapseX SSE 엔드포인트 | `CaseAnalysisController#streamRun`, `GetMapping("/analysis-runs/{runId}/stream")` |
| SynapseX 스트림 예외 시 SSE 응답 | `CaseAnalysisController#streamRun` BaseException catch, `AnalysisStreamProxyService#createFailedEmitter` |
| SynapseX Aura 프록시 | `AnalysisStreamProxyService#streamFromAura` |
| Gateway SSE 헤더 | `SseResponseHeaderFilter` |
| Gateway SSE id: 주입 / [DONE] 이후 빈 이벤트 제거 | `SseReconnectionFilter` (processChunk, addEventIdIfNeeded, stripEmptyEventsAfterDone) |
| Gateway HttpClient 타임아웃 | `SseHttpClientCustomizer`, `application.yml` (response-timeout, route metadata) |
