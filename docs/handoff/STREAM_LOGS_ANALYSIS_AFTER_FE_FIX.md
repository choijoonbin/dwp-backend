# 스트림 API 로그 분석 (FE 수정 후) — back.txt

> **로그 출처**: Gateway(dwp-gateway) only (back.txt)  
> **runId**: 6e5948bb-d632-42d5-8af5-3117e44ec640  
> **상황**: 프론트에서 끊는 부분 수정했다고 하나 여전히 동작하지 않음

---

## 1. Gateway 로그 타임라인 (요약)

| 시각 | 이벤트 |
|------|--------|
| 16:37:07.566 | POST /api/synapse/cases/85116/analysis-runs → **202 ACCEPTED** |
| 16:37:07.862 | **OPTIONS** .../stream (CORS preflight) → 200 |
| 16:37:07.867 | **GET** .../analysis-runs/6e5948bb-.../stream (SSE 요청) |
| 16:37:07.871 | Route matched: **synapsex-analysis-runs-stream**, response-timeout=1800000 |
| 16:37:07.873 | SseResponseHeaderFilter: **SSE stream started** |
| 16:37:07.876 | Content-Type: text/event-stream, **Connection: keep-alive**, X-Accel-Buffering: no |
| 16:37:07.883 | Downstream(SynapseX) **Response 200 OK** 수신 |
| 16:37:07.927 | ApiCallHistoryFilter: path=.../stream, **status=200, latency=51ms** |
| 16:37:07.930 | **[7e0f5f11] Completed 200 OK** (스트림 교환 완료) |

---

## 2. 결론

- **스트림이 시작된 지 약 51ms 만에 완료**됨.  
  Gateway 입장에서는 “다운스트림(SynapseX)이 51ms 만에 응답을 닫았다”로 보임.
- back.txt에는 **SynapseX 로그가 없음**.  
  같은 runId(6e5948bb-...)에 대해 **SynapseX 로그**에서 아래가 찍혀 있는지 확인 필요:
  - `SSE proxy first line received`
  - `SSE proxy client disconnected while forwarding`
  - `totalBytesForwarded=N`, `lineCount=M`

---

## 3. 해석

- **51ms 완료**는 이전과 같은 패턴임:
  - SynapseX가 Aura에서 첫 줄 수신 → FE(경유 Gateway)로 전송 → **그 직후 클라이언트 쪽에서 연결이 끊겨** `emitter.send()` 에서 `IllegalStateException` → SynapseX가 루프 break 후 `emitter.complete()` → Gateway는 “응답 완료”로 인식.
- 따라서 **“프론트에서 끊는 부분 수정”이 적용되지 않았거나**, **다른 경로에서 연결이 끊기는 경우**가 있을 수 있음.

---

## 4. BE 쪽에서 추가로 확인할 것

1. **SynapseX 로그**  
   동일 runId `6e5948bb-d632-42d5-8af5-3117e44ec640` 에 대해:
   - `SSE proxy first line received` / `totalBytesForwarded` / `lineCount` 가 찍히는지
   - `SSE proxy client disconnected while forwarding` 이 찍히는지  
   → 찍힌다면 **클라이언트(FE) 끊김**으로 해석 가능.

2. **Gateway 동작**  
   - SSE 구간에선 응답을 스트리밍으로 그대로 전달만 함.
   - `ApiCallHistoryFilter` 의 `.then(...)` 은 **스트림이 끝난 뒤** 한 번만 실행되므로, 51ms 는 “스트림이 51ms 만에 끝났다”는 의미일 뿐, Gateway가 스트림을 조기 종료했다는 의미는 아님.

3. **FE 쪽 재확인**  
   - 수정한 “끊는 부분”이 **실제 배포/빌드에 반영**되었는지
   - 스트림 URL 호출 시 **EventSource/fetch 옵션**에서 스트림을 유지하는지, 에러/타임아웃 시 바로 close 하지 않는지
   - **다른 컴포넌트/훅**에서 동일 EventSource를 닫거나, unmount 시 무조건 close 하는 코드가 없는지

---

## 5. 요약

| 항목 | 상태 |
|------|------|
| Gateway 로그 (back.txt) | GET stream → 200, **약 51ms 후 Completed** → 스트림이 짧게 끝남 |
| SynapseX 로그 | back.txt에 없음 → **동일 runId 로그 수집 필요** |
| 원인 추정 | 이전과 동일하게 **클라이언트(FE) 조기 종료** 가능성 큼. FE 수정 반영·다른 끊김 경로 확인 필요. |

*(이하 §6에서 gatelog.txt + synapselog.txt 기준으로 동일 runId 로그 상관 분석 반영.)*

동일 요청에 대한 **SynapseX 로그**(runId=6e5948bb-...)를 붙여서 다시 보내주시면, “client disconnected” 여부와 totalBytesForwarded까지 포함해 한 번 더 정확히 짚을 수 있습니다.

---

## 6. Gateway + SynapseX 로그 상관 분석 (gatelog.txt + synapselog.txt)

**runId**: `bb6178fe-4db6-4341-a77e-23440e51ef71`

### 6.1 타임라인 (매칭)

| 시각 | Gateway (gatelog.txt) | SynapseX (synapselog.txt) |
|------|------------------------|----------------------------|
| 16:40:41.385 | POST analysis-runs | — |
| 16:40:41.792 | — | Aura analyze trigger accepted, runId=bb6178fe-... |
| 16:40:41.930 | GET .../stream (SSE) | — |
| 16:40:41.982 | SSE stream started | — |
| 16:40:42.113 | — | **SSE proxy connecting to Aura** (runId=bb6178fe-...) |
| 16:40:42.051–42.078 | Content-Type/Connection/X-Accel-Buffering 설정 | — |
| 16:40:42.207 | — | **Aura responded 200, streaming** |
| 16:40:42.208 | — | **first line received** (lineLength=11), **line received bytes=12 total=12** |
| 16:40:42.209 | — | **SSE proxy client disconnected while forwarding** |
| 16:40:42.211 | — | Aura stream ended, **totalBytesForwarded=12, lineCount=1** |
| 16:40:42.209 | ApiCallHistoryFilter: stream **latency=160ms** | — |
| 16:40:42.219 | **[b742c3f] Completed 200 OK** | — |
| 16:40:42.218 | — | SSE proxy completed |

### 6.2 결론 (확정)

- SynapseX는 Aura에서 **첫 줄(12바이트)을 정상 수신**하고 클라이언트(Gateway 경유 FE)로 전달함.
- **그 직후(같은 초 42.208→42.209)** SynapseX에 **"SSE proxy client disconnected while forwarding"** 로그가 찍힘.
- 즉, **연결을 끊은 쪽은 클라이언트(FE)** 이며, BE/Gateway는 스트림을 유지하다가 클라이언트 끊김에 따라 정상 종료한 상태임.

### 6.3 FE 측 권장 확인 사항

1. **스트림 수정 코드 경로**  
   이 runId로 호출할 때, 수정한 EventSource/스트림 유지 로직이 **실제로 타는지** 확인 (배포/빌드/분기).

2. **첫 이벤트/첫 청크 수신 시 동작**  
   첫 번째 SSE 이벤트 또는 첫 청크 수신 시 `close()` 호출, 또는 다른 API 호출로 인한 **페이지 전환/컴포넌트 unmount** 가 없는지 확인.

3. **에러/타임아웃 핸들러**  
   `onerror` / `onmessage` 에서 에러로 해석하고 즉시 `close()` 하지 않는지 확인.

4. **동일 연결을 닫는 다른 코드**  
   같은 스트림 URL을 구독하는 다른 훅/컴포넌트가 **unmount 시 또는 조건부로** 동일 EventSource를 닫는지 확인.

위까지 확인 후에도 끊긴다면, **브라우저 개발자 도구 Network 탭**에서 해당 GET .../stream 요청의 **종료 사유**(클라이언트 취소/연결 끊김 등)와 **수신한 응답 바이트 수**를 함께 확인하는 것이 좋습니다.

---

## 7. FE 측 적용 수정 (front.txt 기준)

프론트에서 아래 수정을 적용했다고 전달받음.

### 7.1 원인 해석 (FE)

- BE 로그: 첫 줄 수신 직후 1ms 안에 **클라이언트(FE)가 연결을 끊은** 상황으로 확인.
- **120ms cleanup** 일 때, React **Strict Mode**에서 unmount 직후 예약된 **abort**가 **remount effect**보다 먼저 실행되면서 스트림이 끊긴 것으로 해석.

### 7.2 적용한 수정

| 항목 | 변경 내용 |
|------|-----------|
| 파일 | `libs/shared-utils/src/agent/use-analysis-run-stream.ts` |
| 변경 | cleanup 지연 시간 **120ms → 500ms** |
| 목적 | remount 시 effect에서 예약 취소가 먼저 일어나도록 하여, Strict Mode에서 abort가 remount 전에 실행되는 경우 방지. |

- 동일 해석(BE 로그상 첫 줄 수신 직후 끊김)을 주석으로 남김.
- 검증 문서 `docs/reference/STREAM_DISCONNECT_FE_VERIFICATION.md` 에 BE 타임라인·결론 요약 및 BE 제안 FE 확인 항목 검토 내용, 500ms 변경 이유 반영.

### 7.3 이후 검증

- 해당 빌드로 재현 테스트 진행.
- 여전히 끊기면 브라우저 Network 탭에서 GET .../stream 요청의 **종료 사유**와 **수신 바이트 수** 확인 권장.

---

## 8. FE 추가 관찰 및 BE 측 점검 (스트림 끊김을 백엔드에서 봐줄 것)

### 8.1 FE 추가 관찰 (front.txt)

- 한 런에서 FE는 **abort를 호출하지 않음** (cleanup / delayed abort / AbortError 로그 없음).
- `reader.read()` 가 **done=true** 로 반환됨 → **스트림이 서버/프록시 쪽에서 끝난 것**으로 해석.
- 정리: “클라이언트가 먼저 끊었다”기보다 **서버/프록시가 연결을 닫은 뒤 FE가 done=true로 정상 종료**한 상황에 가깝다는 FE 해석.
- FE 권장: BE에서 “client disconnected”가 나온 **동일 runId/동일 시각** FE 콘솔 로그와 비교해, 그때 `delayed abort fired` 또는 `AbortError` 가 찍히는지 확인.

### 8.2 BE 해석

- SynapseX의 **“client disconnected”** 는 **emitter.send() 에서 IllegalStateException** 이 난 경우에만 찍힘.  
  즉, **SynapseX 입장의 “클라이언트”(Gateway 또는 그 너머 FE)** 가 이미 연결을 닫은 상태에서 다음 청크를 보내려다 실패한 상황.
- 따라서 (1) **FE/브라우저가 먼저 끊어서** Gateway가 다운스트림을 취소했거나, (2) **Gateway가 먼저 끊었거나**, (3) **SynapseX가 첫 청크 전송 후 응답을 닫은 것**처럼 보이는 경우가 있을 수 있음.  
  (3)은 SynapseX가 IllegalStateException 후 `break` → `emitter.complete()` 로 정리하는데, 이때 “끊는 쪽”은 이미 연결이 끊어진 상태이므로, FE가 보는 “서버가 닫음”은 **SynapseX가 complete() 로 정리한 것**으로 보일 수 있음.

### 8.3 BE에서 적용한 점검·개선

| 구분 | 내용 |
|------|------|
| **SynapseX** | `IllegalStateException` 발생 시 로그에 **totalBytesForwarded, lineCount** 포함. “connection already closed by client or gateway” 문구로, 끊는 쪽이 FE 또는 Gateway일 수 있음을 명시. |
| **Gateway** | SSE 응답 body에 **doOnCancel** / **doOnComplete** / **doOnError** 로깅 추가. 같은 요청에서 **“SSE stream cancelled by client”** vs **“SSE stream completed by downstream”** 으로 끊김 방향 구분 가능. |
| **검토 결과** | ApiCallHistoryFilter 의 `.then()` 은 스트림 **종료 후** 1회만 실행되며, 스트림을 조기 종료하지 않음. SseReconnectionFilter 는 청크 단위 변환만 하며 조기 종료 원인 아님. response-timeout=1800000(30분) 유지. |

### 8.4 끊김 의심 구간 로그 (추가)

동일 runId 기준으로 시간순으로 보면 끊김 발생 지점을 좁힐 수 있도록, 의심 구간에 `(suspected disconnect trace)` 로그를 추가함.

| 구간 | 로그 메시지 (일부) | 위치 |
|------|-------------------|------|
| Controller | `SSE stream request received: runId=... caseId=...` | CaseAnalysisController.streamRun 진입 |
| Controller | `SSE stream returning emitter to client: runId=...` (DEBUG) | emitter 반환 직전 |
| SynapseX | `SSE proxy first chunk sent to client: runId=... bytes=...` | 첫 청크 emitter.send() 성공 직후 |
| SynapseX | `SSE proxy client disconnected while forwarding: runId=...` | send() IllegalStateException 시 |
| SynapseX | `SSE proxy completing emitter after client disconnect: runId=...` | 클라이언트 끊김 후 complete 직전 |
| SynapseX | `SSE proxy completing emitter (normal end): runId=...` | Aura 스트림 정상 종료 후 complete 직전 |
| SynapseX | `SSE proxy emitter onCompletion: runId=...` | emitter 생명주기 완료 시 |
| Gateway | `SSE first chunk received from downstream: path=...` | 다운스트림(SynapseX)에서 첫 청크 수신 시 |
| Gateway | `SSE stream cancelled by client ... path=...` | 클라이언트가 구독 취소 시 |
| Gateway | `SSE stream completed by downstream: path=...` | 다운스트림이 스트림 종료 시 |

재현 시 위 로그의 **출력 순서**를 보면, 어느 단계 직후에 끊겼는지 판단할 수 있음.

### 8.5 이후 재현 시 확인 방법

- **동일 runId** 로 한 번에 수집:
  - **Gateway**: `SSE stream cancelled by client` vs `SSE stream completed by downstream` 여부.
  - **SynapseX**: `SSE proxy client disconnected while forwarding` 시점의 totalBytesForwarded, lineCount.
  - **FE**: 해당 시각에 `delayed abort fired` / `AbortError` 유무.
- `cancelled by client` 이면 클라이언트(또는 그 앞단)에서 끊은 것, `completed by downstream` 이면 다운스트림(SynapseX)이 스트림을 끝낸 것.  
  후자인데 FE에서 abort를 하지 않았다면, SynapseX가 IllegalStateException 후 complete() 한 경우이므로 “누가 먼저 끊었는지”는 Gateway 로그의 cancel vs complete 로 추가로 구분 가능.
