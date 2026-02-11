# Gateway 상세 디버깅 강화 — 스트림 끊김 원인 규명

> **배경**: FE 측 500ms cleanup 적용 후에도 스트림이 조기 종료. FE는 abort() 없음 확인. Gateway 원인 의심.  
> **목표**: Gateway의 SSE 처리 과정을 상세히 추적할 수 있도록 로그 강화 + 필터 에러 처리 보완.

---

## 1. 적용한 변경사항

### 1.1 SseResponseHeaderFilter — writeWith 상세 로깅

**파일**: `dwp-gateway/src/main/java/com/dwp/gateway/config/SseResponseHeaderFilter.java`

**추가한 로그**:

| 시점 | 로그 메시지 | 레벨 |
|------|-------------|------|
| 구독 시작 | `SSE stream body subscribed: path=...` | INFO |
| 첫 청크 수신 | `SSE first chunk received from downstream: path=... size=N bytes` | INFO |
| 클라이언트 취소 | `SSE stream cancelled by client (e.g. FE abort/navigate): path=...` | INFO |
| 다운스트림 완료 | `SSE stream completed by downstream: path=...` | INFO |
| 에러 | `SSE stream error: path=... <message>` | ERROR |
| 최종 완료 | `SSE stream finalized: path=... signal=<onComplete/onCancel/onError>` | INFO |

**의미**:

- `subscribed` 가 찍히면 Gateway가 downstream(SynapseX) 응답 body를 구독했다는 뜻.
- `first chunk received` 가 찍히면 SynapseX에서 첫 데이터가 Gateway로 왔다는 뜻.
- `cancelled by client` vs `completed by downstream` 로 **누가 먼저 끊었는지** 구분 가능.
- `finalized: signal=onCancel` = 클라이언트가 끊음, `signal=onComplete` = downstream이 끝냄, `signal=onError` = 에러 발생.

### 1.2 SseReconnectionFilter — 에러 처리 강화

**파일**: `dwp-gateway/src/main/java/com/dwp/gateway/config/SseReconnectionFilter.java`

**변경 내용**:

1. **청크 처리 시 예외 catch + 상세 로그**

```java
.map(dataBuffer -> {
    try {
        // 기존 로직: read, release, addEventIdIfNeeded
        log.debug("SseReconnectionFilter processing chunk: bytes=... preview=...");
        // ... 변환 ...
        return modifiedBuffer;
    } catch (Exception e) {
        log.error("SseReconnectionFilter error while processing chunk (suspected disconnect cause): {}", e.getMessage(), e);
        throw new RuntimeException("SSE chunk processing failed", e);
    }
})
```

- 이 필터에서 에러가 나면 **Flux가 error로 종료** → 스트림 끊김 → downstream 취소.
- 로그에 `SseReconnectionFilter error` 가 찍히면, **이 필터가 원인**임을 확정할 수 있음.

2. **필터 비활성화 옵션 추가**

- **설정**: `gateway.sse.reconnection.enabled=false` (기본값 true)
- **용도**: 이 필터를 의심할 경우, 일시적으로 비활성화해 재현 여부 확인.  
  필터를 끄고 스트림이 유지되면, **SseReconnectionFilter가 원인**으로 확정.

```yaml
# application.yml 또는 application-dev.yml
gateway:
  sse:
    reconnection:
      enabled: false  # 테스트 시에만 false
```

---

## 2. 디버깅 시나리오

### 시나리오 1: SseReconnectionFilter가 원인

**재현 시 로그 패턴**:

```
INFO  SSE stream started: method=GET, path=.../stream, ...
INFO  SSE stream body subscribed: path=.../stream
INFO  SSE first chunk received from downstream: path=.../stream size=12 bytes
DEBUG SseReconnectionFilter processing chunk: bytes=12 preview=: connected\n
ERROR SseReconnectionFilter error while processing chunk (suspected disconnect cause): <exception>
ERROR SSE stream error: path=.../stream <exception>
INFO  SSE stream finalized: path=.../stream signal=onError
```

**조치**: `gateway.sse.reconnection.enabled=false` 로 필터 비활성화 후 재현. 스트림이 유지되면 **필터 버그 확정** → addEventIdIfNeeded() 로직 수정.

### 시나리오 2: 클라이언트(FE/브라우저)가 끊음

**재현 시 로그 패턴**:

```
INFO  SSE stream started: ...
INFO  SSE stream body subscribed: ...
INFO  SSE first chunk received from downstream: ... size=12 bytes
DEBUG SseReconnectionFilter processing chunk: bytes=12 ...
INFO  SSE stream cancelled by client (e.g. FE abort/navigate): ...
INFO  SSE stream finalized: ... signal=onCancel
```

**조치**: FE 측에서 브라우저 Network 탭 확인. "Canceled by client" 또는 네트워크 오류 확인. 브라우저 버그 또는 개발자도구/확장 프로그램 영향 점검.

### 시나리오 3: Gateway가 스트림을 조기 완료

**재현 시 로그 패턴**:

```
INFO  SSE stream started: ...
INFO  SSE stream body subscribed: ...
INFO  SSE first chunk received from downstream: ... size=12 bytes
DEBUG SseReconnectionFilter processing chunk: bytes=12 ...
INFO  SSE stream completed by downstream: ...
INFO  SSE stream finalized: ... signal=onComplete
```

**그런데 SynapseX 로그**에는 "Aura stream ended" 없이 **"client disconnected while forwarding"** 이 먼저 찍혀 있음.

**해석**: Gateway의 response body Flux가 "onComplete"를 받았는데, 실제로는 downstream(SynapseX)이 complete()를 호출하지 않았고 오히려 "client disconnected"를 본 경우 → **Reactive 체인 버그 또는 Gateway의 조기 완료** 가능성.

**조치**: Spring Cloud Gateway 버전 확인. 알려진 SSE/streaming 버그 여부 확인. 필요 시 버전 업그레이드 또는 custom routing filter 구현.

---

## 3. 다음 단계 (Immediate Next Steps)

### Step 1: Gateway 재빌드 및 재배포 (필수)

- 위 변경사항(상세 로그 + 에러 처리)이 포함된 Gateway를 빌드해 배포.
- 확인: 재배포 후 로그에 `(suspected disconnect trace)` 메시지가 나오는지.

### Step 2: 동일 runId로 로그 동시 수집 (필수)

- FE + Gateway + SynapseX 로그를 **같은 요청(동일 runId, 동일 시각)**에 대해 수집.
- **타임스탬프 대조**: FE 콘솔 08:11 vs BE 로그 17:11 같은 불일치가 없도록, 테스트 시작 전 시각 동기화.

### Step 3: SseReconnectionFilter 비활성화 테스트 (선택)

- `application-dev.yml` 에 아래 추가:

```yaml
gateway:
  sse:
    reconnection:
      enabled: false
```

- 재배포 후 스트림 재현. 유지되면 **이 필터가 원인** 확정.

### Step 4: 브라우저 Network 탭 확인 (FE 협조)

- FE 테스트 시, Network 탭에서 GET .../stream 요청 선택:
  - **Status**: 200 / Pending / Canceled 등
  - **Size**: 수신 바이트 수
  - **Timing**: 얼마나 연결이 유지되었는지
  - **Initiator**: 어떤 코드가 요청을 시작했는지
  - **종료 사유**: (failed) / (canceled) 등

---

## 4. 예상 결과 및 조치

| 로그 패턴 | 원인 | 조치 |
|-----------|------|------|
| `SseReconnectionFilter error` 발생 | **SseReconnectionFilter 버그** | addEventIdIfNeeded() 로직 수정. 또는 필터 제거. |
| `SSE stream cancelled by client` + FE abort 없음 | **브라우저/네트워크** | FE: 브라우저 변경, 확장 프로그램 비활성화, 네트워크 점검. |
| `SSE stream completed by downstream` + SynapseX "client disconnected" | **Gateway Reactive 체인 버그** | Spring Cloud Gateway 버전 확인/업그레이드. 또는 custom filter 구현. |
| 위 로그가 **여전히 안 찍힌다** | **Gateway 빌드 미반영** | 재빌드/재배포 확인. 또는 코드 경로 점검. |

---

## 5. 기술 부채 및 장기 대책

- **SseReconnectionFilter 필요성 재검토**  
  - 현재 FE는 `Last-Event-ID` 를 활용한 재연결을 구현하지 않았음.  
  - 필터가 에러/복잡도 원인이라면, **일단 제거 후** 향후 필요 시 재구현 고려.

- **Gateway 대안 (최후 수단)**  
  - SseReconnectionFilter를 제거해도 문제 지속 시, Spring Cloud Gateway 대신:  
    - **커스텀 Reactive HTTP client**(WebClient 기반)로 SynapseX → FE 직통 프록시 구현.  
    - 또는 **Nginx 레벨 SSE 프록시** (Gateway 우회).

---

*작성일: 2026-02-10, FE front.txt 기반 정밀 분석 반영*
