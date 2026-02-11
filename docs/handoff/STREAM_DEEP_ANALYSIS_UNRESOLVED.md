# 스트림 끊김 정교 분석 — 미해결 상태

> **상황**: FE 수정(500ms cleanup 지연) 적용 후에도 여전히 스트림이 조기 종료됨  
> **FE 결론**: FE는 abort() 호출하지 않음. Gateway 구간 의심.  
> **분석 목적**: BE 로그와 FE 관찰 간 불일치 정밀 분석 + Gateway 원인 규명

---

## 1. 핵심 불일치 (Critical Discrepancy)

### 1.1 BE 로그 (runId 254b2b5b, **17:11** 시각)

**Gateway (gateway.txt)**:
- 17:11:14.837: SSE stream started
- 17:11:15.743: stream path, **latency=833ms, status=200**
- 17:11:15.785: **Completed 200 OK**
- **누락된 로그**: `SSE first chunk received from downstream`, `SSE stream cancelled by client`, `SSE stream completed by downstream` 중 **어느 것도 없음**.

**SynapseX (synapse.txt)**:
- 17:11:15.734: first line received (11 chars)
- 17:11:15.735: SSE line received bytes=**12** total=12
- **17:11:15.741**: **SSE proxy client disconnected while forwarding** totalBytesForwarded=**12** lineCount=**1** (connection already closed)
- 17:11:15.742: SSE proxy completing emitter after client disconnect

**해석 (BE)**:
- SynapseX가 Aura에서 첫 줄(12바이트)을 수신하고, 그걸 **클라이언트로 보내려는 순간** `emitter.send()` 에서 **IllegalStateException** 발생.
- 즉, **첫 청크를 성공적으로 전송하지 못함**. (totalBytesForwarded=12는 send 시도 *전*에 카운트된 값이며, send는 실패.)
- 결론: **첫 SSE 데이터를 보내기 전에 이미 클라이언트(Gateway) 연결이 끊어진 상태.**

### 1.2 FE 로그 (front.txt, **08:11** 시각)

FE 콘솔 (runId 254b2b5b라고 주장):
- 08:10:49.174: unmount → schedule 500ms abort
- 08:10:49.178: **remount** → cancel abort (정상)
- 08:11:13.185: startStream
- 08:11:14.789: fetch start
- 08:11:15.781: fetch response ok
- **08:11:16.344**: **first chunk received { byteLength: 5 }** ← FE는 5바이트를 받았다고 주장
- **08:11:16.351**: **reader.read() done=true** — stream ended by server
- FE 측: `abort()` / `AbortError` 로그 없음. 스트림이 서버 쪽에서 종료됨.

**해석 (FE)**:
- FE는 abort() 호출하지 않았고, **5바이트를 수신한 뒤** 스트림이 done=true로 종료됨.
- FE 관점: **서버/Gateway가 5바이트를 보낸 뒤 연결을 끊은 것**으로 보임.

### 1.3 불일치 분석

| 관점 | 관찰 | 의미 |
|------|------|------|
| **BE (SynapseX)** | emitter.send() 실패 (12바이트 전송 시도 → IllegalStateException) | **첫 청크를 전송하지 못함** |
| **FE** | first chunk received (5바이트) → done=true | **일부 데이터를 받은 뒤 서버가 끊음** |
| **Gateway** | 새 로그(first chunk received/cancelled/completed) **모두 누락** | 로그가 없어 Gateway 동작 불명 |

**가능한 해석**:

1. **두 런이 다름**  
   - BE 로그(17:11)와 FE 로그(08:11)는 **시각이 9시간 차이** (오전 vs 오후).  
   - FE가 "동일 runId"라고 했지만, 실제로는 **별도 테스트**일 가능성. 동일 runId(254b2b5b)를 재현한 것인지, 아니면 다른 런의 로그를 혼동한 것인지 불명확.

2. **데이터 불일치 (12바이트 vs 5바이트)**  
   - SynapseX: Aura에서 받은 첫 줄 = 11글자 → `(line + "\n")` = 12바이트.  
   - FE: 수신한 첫 청크 = 5바이트.  
   - **Gateway의 SseReconnectionFilter**가 중간에 변환할 수 있음:  
     - 원본 12바이트(`: connected\n`) → 필터가 `id: <timestamp>\n` 추가 → 출력 바이트가 증가/감소.  
     - 하지만 5바이트는 12바이트보다 **적음** → 청크가 잘렸거나, 다른 데이터가 왔거나, 측정 오류 가능성.

3. **Gateway의 역할 (의심 지점)**  
   - BE(SynapseX)는 "client disconnected"를 보고 → SynapseX 입장의 "client" = **Gateway**.  
   - 즉, **Gateway↔SynapseX 연결이 끊어졌거나, Gateway가 응답을 조기 종료**했을 가능성.  
   - FE는 abort 안 했고 done=true를 받음 → **Gateway↔FE 구간에서 Gateway가 먼저 끊었을 가능성**.

---

## 2. Gateway 의심 지점 상세 분석

### 2.1 누락된 로그 (Suspected Disconnect Trace)

우리가 추가한 로그:
- `SSE first chunk received from downstream: path=... (suspected disconnect trace)` (SseResponseHeaderFilter.writeWith, INFO)
- `SSE stream cancelled by client ... (suspected disconnect trace)` (doOnCancel, INFO)
- `SSE stream completed by downstream ... (suspected disconnect trace)` (doOnComplete, INFO)

**gateway.txt에 위 로그가 하나도 없음** → 가능한 이유:

1. **Gateway 빌드가 최신 코드 미포함**  
   - 사용자가 로그를 수집한 Gateway 인스턴스가 **우리가 수정한 코드 배포 전** 버전일 가능성.  
   - 확인 방법: Gateway 재빌드 후 재배포 여부 점검.

2. **로그 레벨 문제**  
   - `log.info(...)` 로 작성했으므로 INFO 레벨이면 보여야 함.  
   - gateway.txt에 다른 INFO 로그(e.g. "SSE stream started")는 보이므로, 로그 레벨 문제는 아님.

3. **코드 경로가 타지 않음**  
   - `writeWith(Publisher<DataBuffer> body)` 가 호출되지 않았거나,  
   - Flux.index().doOnNext(...) 체인이 구독되지 않았을 가능성.  
   - 하지만 스트림이 833ms 동안 진행되고 Completed 200 OK가 나왔으므로, writeWith가 호출되었어야 함.

**결론**: 가장 가능성 높은 이유는 **Gateway 빌드가 최신 코드를 포함하지 않았다**는 것.

### 2.2 SseReconnectionFilter 동작 검토

```java
public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
    Flux<DataBuffer> modifiedFlux = Flux.from(body)
            .map(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);  // 원본 버퍼 해제
                String content = new String(bytes, StandardCharsets.UTF_8);
                String modifiedContent = addEventIdIfNeeded(content);
                return originalResponse.bufferFactory().wrap(
                        modifiedContent.getBytes(StandardCharsets.UTF_8)
                );
            });
    return originalResponse.writeWith(modifiedFlux);
}
```

**문제 가능성**:

1. **addEventIdIfNeeded()에서 에러**  
   - `: connected\n` 같은 SSE 주석 라인을 처리할 때, `split("\n\n")` 로직이 예상과 다르게 동작하면 예외 발생 가능.  
   - 예외 발생 시 Flux가 error로 종료 → 스트림 끊김.

2. **버퍼 release 후 재사용**  
   - `DataBufferUtils.release(dataBuffer)` 후, 원본 버퍼가 이미 해제된 상태에서 무언가 참조하면 문제.  
   - 하지만 코드에서는 bytes 배열에 복사 후 release하므로, 안전해 보임.

3. **청크 변환 시 크기 변화**  
   - 12바이트 `: connected\n` → addEventIdIfNeeded → `id: 1234567890\n: connected\n\n` (예시: ~30바이트 이상).  
   - FE가 "5바이트"를 받았다면, 이 필터를 거치지 않았거나, 중간에 잘렸거나, FE 측정이 잘못되었을 가능성.

### 2.3 Gateway 타임아웃/버퍼링 설정

**application.yml 확인**:
- `response-timeout: 1800000` (30분) for stream route → 타임아웃은 충분함.
- `X-Accel-Buffering: no` 설정함 → Nginx 버퍼링 방지.
- `Connection: keep-alive` 설정함 → 연결 유지.

**문제 가능성**:
- Gateway의 **Netty** 기본 설정에서, 연결이 idle 상태일 때 타임아웃?  
  - 하지만 833ms 만에 끊겼으므로, idle 타임아웃(보통 수십 초)은 아님.
- **Reactive 체인의 버그**:  
  - ApiCallHistoryFilter의 `.then(Mono.fromRunnable(...))` 는 스트림이 **끝난 뒤** 실행되므로 조기 종료 원인 아님.
  - 하지만 다른 필터가 스트림을 조기 종료할 가능성은 있음.

---

## 3. 근본 원인 가설 (Root Cause Hypothesis)

### 가설 A: Gateway의 SseReconnectionFilter에서 에러

- **시나리오**: SseReconnectionFilter의 addEventIdIfNeeded()에서 첫 청크 처리 시 예외 발생 → Flux.error() → 스트림 종료 → downstream(SynapseX) 취소 → SynapseX의 다음 send()에서 IllegalStateException.
- **증거**: (1) FE가 일부 데이터(5바이트)를 받았다 = 필터를 일부 통과했을 가능성. (2) SynapseX가 send 실패 = Gateway가 연결 끊음.
- **검증 방법**: SseReconnectionFilter의 map() 안에 try-catch + log 추가해, 예외 발생 여부 확인.

### 가설 B: Browser/FE↔Gateway 연결이 먼저 끊어짐 (FE 부정, 하지만 네트워크/브라우저 버그 가능성)

- **시나리오**: FE는 abort() 안 했지만, **브라우저/네트워크**가 연결을 끊음 (예: 브라우저 버그, 개발자도구 영향, 프록시). → Gateway가 클라이언트 끊김 감지 → downstream 취소 → SynapseX send 실패.
- **증거**: (1) FE 로그에 abort 없음. (2) 하지만 done=true = 연결이 상대 쪽에서 끊김. (3) Gateway 로그에 "cancelled by client" 없음 = Gateway가 클라이언트 끊김을 감지 못 했거나, 로그가 누락됨.
- **검증 방법**: 브라우저 Network 탭에서 동일 요청의 종료 사유 확인 (Canceled, Connection closed 등).

### 가설 C: Gateway의 Reactive 체인 버그 (미확인)

- **시나리오**: Spring Cloud Gateway의 NettyRoutingFilter 또는 다른 필터가, SSE 스트림을 일반 응답으로 오인하고 첫 청크 후 complete() 호출.
- **증거**: (1) 짧은 시간(~800ms) 안에 종료. (2) Gateway 로그에 "completed by downstream" 없음 (= 로그가 찍히지 않았거나, downstream이 아닌 Gateway가 먼저 complete 호출).
- **검증 방법**: NettyRoutingFilter 동작 분석 (Spring Cloud Gateway 소스코드 레벨).

---

## 4. 권장 조치 (Recommended Actions)

### 4.1 즉시 확인 (Immediate Checks)

| 항목 | 확인 내용 | 담당 |
|------|-----------|------|
| **Gateway 빌드** | 최신 코드(suspected disconnect trace 로그 포함) 배포 여부 확인. 재빌드 후 재배포. | BE/Infra |
| **Gateway 로그 재수집** | 재배포 후 동일 시나리오 재현. `SSE first chunk received`, `cancelled by client`, `completed by downstream` 로그 확인. | BE |
| **FE 콘솔 + BE 로그 동시 수집** | **동일 runId/동일 시각**에 FE 콘솔과 Gateway+SynapseX 로그 함께 수집. 타임스탬프로 매칭. | FE + BE |
| **브라우저 Network 탭** | FE 테스트 시, 브라우저 Network 탭에서 GET .../stream 요청의 **상태**(Canceled/Finished), **수신 바이트**, **종료 사유** 확인. | FE |

### 4.2 Gateway 상세 디버깅 (Detailed Debugging)

1. **SseReconnectionFilter에 예외 처리 로그 추가**

```java
.map(dataBuffer -> {
    try {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        String content = new String(bytes, StandardCharsets.UTF_8);
        log.debug("SseReconnectionFilter processing chunk: bytes={} content={}", bytes.length, content.substring(0, Math.min(50, content.length())));
        String modifiedContent = addEventIdIfNeeded(content);
        return originalResponse.bufferFactory().wrap(modifiedContent.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
        log.error("SseReconnectionFilter error while processing chunk: {}", e.getMessage(), e);
        throw e;  // re-throw to error the Flux
    }
})
```

2. **SseResponseHeaderFilter의 writeWith에 구독 시작/종료 로그 추가**

현재 코드에 이미 doOnNext/doOnCancel/doOnComplete가 있지만, 실제로 호출되는지 확인하기 위해:

```java
return super.writeWith(
        Flux.from(body)
                .doOnSubscribe(s -> log.info("SSE stream subscribed: path={}", pathForSseLog))
                .index()
                .doOnNext(tuple -> {
                    if (tuple.getT1() == 0) {
                        log.info("SSE first chunk received from downstream: path={} size={} (suspected disconnect trace)", pathForSseLog, tuple.getT2().readableByteCount());
                    }
                })
                .map(tuple -> tuple.getT2())
                .doOnCancel(() -> log.info("SSE stream cancelled by client (e.g. FE abort/navigate): path={} (suspected disconnect trace)", pathForSseLog))
                .doOnComplete(() -> log.info("SSE stream completed by downstream: path={} (suspected disconnect trace)", pathForSseLog))
                .doOnError(e -> log.error("SSE stream error: path={} {}", pathForSseLog, e.getMessage(), e))
                .doFinally(signal -> log.info("SSE stream finalized: path={} signal={}", pathForSseLog, signal)));
```

3. **NettyRoutingFilter 동작 모니터링**  
   - Spring Cloud Gateway의 NettyRoutingFilter가 SSE 스트림을 어떻게 처리하는지, downstream이 정상이면 클라이언트가 끊을 때까지 스트림을 유지하는지 확인.  
   - 로그가 부족하면, Spring Cloud Gateway 소스코드에 breakpoint를 걸거나, 로컬에서 재현.

### 4.3 중기 대책 (Mid-term Solutions)

- **SseReconnectionFilter 제거 테스트**  
  - 이 필터가 문제라면, 임시로 비활성화(주석 처리 또는 조건부 skip)하고 재현 여부 확인.  
  - `id:` 라인이 없어도 FE가 동작한다면, 필터를 제거하고 스트림이 유지되는지 확인.

- **Gateway 우회 직접 연결 테스트**  
  - FE → Gateway 우회 → SynapseX(8085) 직접 호출 테스트.  
  - Gateway 없이도 같은 현상이 나오면 FE/네트워크 문제, 안 나오면 Gateway 문제로 확정.

---

## 5. 최종 결론 (Conclusion)

| 항목 | 상태 |
|------|------|
| **FE 측** | 500ms cleanup 지연 적용 완료. abort() 호출 없음 확인. **FE는 clean**. |
| **SynapseX 측** | Aura에서 데이터 수신 후, 클라이언트로 전달 시도 시 실패. **SynapseX는 relay 역할만**. |
| **Gateway 측** | (1) 새 로그가 없어 동작 불명. (2) **의심 지점: SseReconnectionFilter 에러 또는 Netty 조기 종료**. (3) **Gateway가 원인일 가능성 가장 높음**. |
| **권장** | (1) Gateway 최신 빌드 배포. (2) 상세 로그 추가(SseReconnectionFilter try-catch, writeWith doFinally). (3) FE+BE 로그 동시 수집. (4) SseReconnectionFilter 제거 테스트. |

**핵심**: 현재까지의 증거는 **Gateway가 스트림을 조기 종료**하고 있음을 시사. FE/SynapseX는 각자 정상 동작 중이며, Gateway가 중간에서 연결을 끊는 것으로 추정. Gateway 상세 디버깅 필수.
