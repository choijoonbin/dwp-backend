# 스트림 실패 로그 분석 — runId 254b2b5b

> **로그**: gateway.txt + synapse.txt  
> **runId**: `254b2b5b-5b5f-4495-b4d8-144ec40d92b3`  
> **분석 목적**: 실패 원인 규명

---

## 1. 타임라인 (Gateway + SynapseX 매칭)

| 시각 | Gateway (gateway.txt) | SynapseX (synapse.txt) |
|------|------------------------|------------------------|
| 17:11:13.215 | POST analysis-runs | — |
| 17:11:14.651 | — | Aura analyze trigger accepted, runId=254b2b5b... |
| 17:11:14.796 | OPTIONS .../stream | — |
| 17:11:14.809 | **GET** .../254b2b5b.../stream | — |
| 17:11:14.837 | **SSE stream started** | — |
| 17:11:14.927 | — | **SSE stream request received** runId=254b2b5b |
| 17:11:14.943 | — | SSE proxy connecting to Aura |
| 17:11:14.949 | — | SSE stream returning emitter to client |sv
| 17:11:14.910–14.913 | Content-Type, Connection, X-Accel-Buffering 설정 | — |
| 17:11:15.731 | — | **Aura responded 200, streaming** |
| 17:11:15.734 | — | first line received (lineLength=11) |
| 17:11:15.735 | — | SSE line received bytes=12 total=12 |
| **17:11:15.741** | — | **SSE proxy client disconnected while forwarding** totalBytesForwarded=**12** lineCount=**1** |
| 17:11:15.742 | — | SSE proxy completing emitter after client disconnect |
| 17:11:15.743 | ApiCallHistoryFilter: .../stream **status=200, latency=833ms** | — |
| 17:11:15.785 | **[2e8d11e9] Completed 200 OK** | — |
| 17:11:15.784 | — | SSE proxy emitter onCompletion |

---

## 2. 핵심 관찰

- SynapseX는 **Aura에서 첫 줄(12바이트)만 수신**한 뒤, **그 청크를 클라이언트(Gateway 경유)로 보내려는 순간** `emitter.send()` 에서 **IllegalStateException** 이 발생함.
- 즉, **첫 번째 청크를 보내기 전에 이미 연결이 끊어진 상태**임.  
  “first chunk sent to client” 로그가 없는 이유도, `emitter.send()` 가 첫 시도에서 예외를 던져서 그 아래 로그가 실행되지 않았기 때문임.

---

## 3. 실패 원인 (결론)

**클라이언트(Gateway 또는 그 앞단 FE)가 스트림을 연 직후, SynapseX가 첫 SSE 청크를 보내기 전에 연결을 끊었다.**

- **가능한 시나리오**
  1. **FE가 먼저 끊음**  
     - Strict Mode unmount, 500ms 이전에 실행된 cleanup, 또는 다른 이유로 `abort()` / 연결 종료가 매우 빠르게 발생.
  2. **Gateway↔FE 구간에서 끊김**  
     - 브라우저/프록시가 연결을 닫거나, Gateway가 클라이언트 연결을 조기 종료.

- **SynapseX 관점**  
  - “client” = SynapseX에게 요청을 보낸 쪽 = **Gateway**.  
  - 따라서 “client disconnected” = **Gateway 쪽 연결이 이미 닫힌 상태**에서 `emitter.send()` 를 시도했다는 의미.

- **Gateway 로그**  
  - 이번 gateway.txt에는 `SSE first chunk received from downstream`, `SSE stream cancelled by client`, `SSE stream completed by downstream` 중 **어느 것도 없음**.  
  - 스트림이 **약 833ms 만에** Completed 200 OK 로 끝났고, 그 전에 SynapseX에서 “client disconnected” 가 찍힌 것으로 보아, **Gateway가 다운스트림(SynapseX) 응답을 구독한 뒤 곧바로 취소되었거나**, **클라이언트(FE)가 먼저 끊어서** Gateway가 다운스트림 구독을 취소한 흐름으로 해석 가능.

---

## 4. 정리

| 항목 | 내용 |
|------|------|
| **실패 원인** | SynapseX가 **첫 SSE 청크를 전송하기 전에** 이미 클라이언트(Gateway/FE) 쪽 연결이 끊어짐. |
| **끊는 쪽** | Gateway 또는 FE. (SynapseX는 전달만 시도하다가 `emitter.send()` 에서 실패.) |
| **권장 확인** | 1) FE: 해당 runId/시각에 `abort()` 호출·cleanup·unmount 여부. 2) 동일 runId로 Gateway 로그에 `SSE stream cancelled by client` vs `SSE stream completed by downstream` 수집해 끊김 방향 재확인. |

이 분석을 FE/Gateway 측과 공유해, **스트림 오픈 직후 ~ 첫 청크 전송 전** 구간에서 연결을 닫는 코드나 동작이 있는지 함께 확인하는 것이 좋습니다.

---

## 5. 추가 테스트 — runId dfcc6af6 (2026-02-10 17:42, synapse.txt 첨부)

### 5.1 SynapseX 로그 (synapse.txt) 요약

| 시각 | 이벤트 |
|------|--------|
| 17:42:49.544 | Aura analyze trigger accepted, **runId=dfcc6af6-b7c7-4637-bc60-92f27fbaab22** |
| 17:42:49.739 | **SSE stream request received** (suspected disconnect trace) |
| 17:42:49.747 | SSE proxy connecting to Aura, returning emitter |
| 17:42:49.796 | Aura responded 200, streaming; first line received lineLength=11 |
| 17:42:49.796 | SSE line received bytes=12 total=12 |
| **17:42:49.797** | **SSE proxy client disconnected while forwarding** totalBytesForwarded=**12** lineCount=**1** (connection already closed by client or gateway) |
| 17:42:49.798 | completing emitter after client disconnect |
| 17:42:49.803 | emitter onCompletion |

동일 패턴: **첫 12바이트(1줄)만 전달한 직후** “client disconnected” → **Gateway 또는 FE가 연결을 먼저 끊은 것으로 해석**.

### 5.2 Gateway 로그 (gateway-trace.log)와의 매칭

| 시각 | Gateway |
|------|---------|
| 17:42:49.719 | GET .../dfcc6af6.../stream |
| 17:42:49.724 | **SSE stream started** (SseResponseHeaderFilter) |
| 17:42:49.821 | **[7aeed75d-4] Completed 200 OK** |

- **`writeWith() called` 로그 없음** → `ServerHttpResponseDecorator.writeWith()` 가 호출되지 않음.  
- Spring Cloud Gateway가 응답 body를 우리가 꾸민 `decoratedResponse`가 아닌 **다른 경로(예: Netty 직접)**로 쓰고 있는 것으로 보임.  
- 따라서 “SSE stream cancelled by client” / “completed by downstream” 등 **body 구독/종료 로그는 Gateway에서 수집 불가**한 상태.

### 5.3 정리 (dfcc6af6)

| 항목 | 내용 |
|------|------|
| **SynapseX** | 첫 청크(12B) 전송 직후 client disconnect → **끊는 쪽 = Gateway 또는 FE** |
| **Gateway** | SSE stream started 까지만 확인, **writeWith() 미호출** → body 구독 로그로 끊김 방향 판별 불가 |
| **다음 단계** | Gateway에서 body 경로가 decorator를 타지 않는 원인 조사(NettyWriteResponseFilter 등), 또는 Netty 레벨 훅으로 끊김 방향 로깅 검토 |
