# Aura 분석 스트림 연동 — BE 확인 차 전달

> **전달 대상**: Aura Platform 개발팀  
> **목적**: 분석 스트림(`/stream`) 연동 계약 확인 및 참고 사항  
> **작성일**: 2026-02-10

---

## 1. Aura 측 수정 사항

**이번 BE 변경에 따른 Aura 코드 수정은 없습니다.**

- BE에서 “Aura 미기동 시” 연결 실패 처리를 개선했습니다(BE만 수정).
- Aura는 **기존 계약대로** 스트림 엔드포인트를 제공하면 됩니다.

---

## 2. BE가 Aura를 호출하는 방식 (확인용)

### 2.1 스트림 URL (BE → Aura)

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `{aura.base-url}/aura/cases/{caseId}/analysis/stream?runId={runId}` |
| **설정** | `aura.base-url` (기본값 `http://localhost:9000`), 환경변수 `AURA_BASE_URL` 또는 `AURA_PLATFORM_URL` |
| **헤더** | `Authorization` (Bearer 토큰) — 있으면 그대로 전달 |

SynapseX(BE)는 분석 실행 후 FE가 **GET /api/synapse/analysis-runs/{runId}/stream** 을 호출하면, 위 URL로 Aura에 연결해 스트림을 그대로 FE에 중계합니다.

### 2.2 BE → Aura 경유 경로 (점검 결과)

| 구간 | 경유 | 비고 |
|------|------|------|
| **FE → BE(스트림 요청)** | FE → **Gateway(8080)** → SynapseX(8085) | 클라이언트는 반드시 Gateway 경유 |
| **BE → Aura(스트림 조회)** | SynapseX(8085) → **Aura(9000) 직연결** | **게이트웨이/프록시 미경유** |

SynapseX는 `aura.base-url`(기본 `http://localhost:9000`)로 **java.net.http.HttpClient**를 사용해 Aura에 **직접 HTTP GET** 합니다. 중간에 Gateway나 다른 프록시를 타지 않습니다.

### 2.3 Aura가 제공해야 하는 것

- **엔드포인트**: `GET /aura/cases/{caseId}/analysis/stream?runId={runId}`  
  - Content-Type: `text/event-stream`  
  - SSE 이벤트 스트리밍 (started, step, completed 등)
- **선택(권장)**: 정상 종료 시 `event: completed` + `data: [DONE]` — [ANALYSIS_STREAM_END_CONTRACT.md](../frontend/docs/api-spec/ANALYSIS_STREAM_END_CONTRACT.md) 참고

---

## 3. totalBytesForwarded 확인 제안 (BE 직연결 기준)

BE는 이미 Aura에 **직연결**하므로, 아래로 검증할 수 있습니다.

1. **Aura 측에서 동일 URL 직접 호출**  
   BE가 호출하는 것과 같은 URL·헤더로 Aura를 직접 호출해 본문이 나오는지 확인합니다.
   ```bash
   # 예: 동일 호스트에서 Aura 직접 호출 (runId, caseId는 실제 값으로 교체)
   curl -N -H "Accept: text/event-stream" \
     "http://localhost:9000/aura/cases/{caseId}/analysis/stream?runId={runId}"
   ```
   - 여기서 **본문(SSE 이벤트)이 나오면**, BE 경유 시에도 동일한 스트림을 읽어 전달하므로 **totalBytesForwarded > 0** 이 됩니다.
   - 여기서 **본문이 0바이트**이면, BE에서도 0바이트만 읽게 되어 totalBytesForwarded는 0입니다.

2. **BE 경유 시 로그 확인**  
   SynapseX 로그의 `SSE proxy Aura stream ended: runId=... totalBytesForwarded=N` 에서 N이 0보다 크면 Aura → BE 구간에서 데이터가 정상 전달된 것입니다.

---

## 3.1 추가 문의 — BE 답변

### Q1. BE가 쓰는 aura.base-url과 Aura 직접 검증 시 쓰는 9000 포트·인스턴스가 같은지

**확인 방법**:
- BE(SynapseX)가 실제로 사용하는 URL은 **로그에 출력**됩니다.  
  `SSE proxy connecting to Aura: runId=... caseId=... url=http://...` 에서 **url=** 값이 BE가 접속한 주소입니다.
- Aura 직접 검증(예: curl) 시 **이 url과 동일한 호스트·포트**를 사용해야 같은 인스턴스입니다.
- 실행 환경이 다르면 주소가 달라질 수 있습니다 (예: SynapseX는 Docker 내부에서 `http://host.docker.internal:9000`, Aura는 호스트에서 `http://localhost:9000`).  
  → **검증 시 SynapseX 로그의 url 값을 확인해, curl 등으로 쓸 주소를 그 값과 맞추면** 동일 인스턴스 대상으로 비교할 수 있습니다.

**설정 위치**: SynapseX `application.yml` 의 `aura.base-url`, 또는 환경변수 `AURA_BASE_URL` / `AURA_PLATFORM_URL`.

### Q2. totalBytesForwarded=0일 때 누가 연결을 먼저 끊는지(Aura vs BE) 로그로 구분 가능한지

**가능합니다.** SynapseX 로그로 구분할 수 있습니다.

| 로그 메시지 | 의미 |
|-------------|------|
| `SSE proxy Aura stream ended: runId=... totalBytesForwarded=0` 다음에 `SSE proxy: stream closed by remote (Aura) without sending any bytes: runId=...` | **Aura가 먼저 연결을 닫은 경우.** BE는 본문을 읽는 루프에서 `read()` 가 -1(스트림 종료)을 받아 정상 종료. 즉, Aura가 200 응답 후 0바이트 보내고 연결을 닫음. |
| `SSE proxy Aura stream error: runId=...` (예: TimeoutException, IOException 메시지) | **BE/HttpClient 측에서 끊긴 경우.** 예: read 타임아웃(30분), 네트워크 끊김 등. 이때는 BE가 예외를 받고 스트림을 종료한 것. |

요약: **"stream ended" + totalBytesForwarded=0** 이면 **Aura가 먼저 끊은 것**, **"stream error"** 가 나오면 **타임아웃/에러 등으로 BE 측에서 끊긴 것**으로 보면 됩니다.

---

## 4. "200이지만 응답 본문이 비어 있음" — Aura 쪽 확인

**증상**: `GET /api/synapse/analysis-runs/{runId}/stream` 호출 시 HTTP 200이 오지만, SSE 이벤트가 하나도 오지 않음.

**원인**: BE는 Aura의 스트림을 그대로 중계합니다. Aura가 **HTTP 200만 보내고 본문(SSE 이벤트)을 전혀 보내지 않으면** 클라이언트도 빈 응답을 보게 됩니다.

**Aura에서 확인할 것**:
- `GET /aura/cases/{caseId}/analysis/stream?runId={runId}` 엔드포인트가 **실제로 SSE 이벤트를 스트리밍**하는지 확인.
- `Content-Type: text/event-stream` 로 **바디에 이벤트**를 보내야 함 (예: `event: started\ndata: {...}\n\n`).
- 연결만 열고 데이터를 한 번도 flush 하지 않거나, 바로 연결을 닫으면 BE→클라이언트에도 아무 데이터가 전달되지 않음.

**BE 쪽 조치 (이미 반영)**:
- Aura가 200을 주지만 0바이트만 보내고 끝나면, BE가 클라이언트에게 `event: failed` 로 원인 안내를 보냄.
- SynapseX 로그에 `SSE proxy Aura stream ended: runId=... totalBytesForwarded=0` 이 보이면, Aura가 데이터를 보내지 않은 경우임.

---

## 5. Aura 미기동 시 BE 동작 (참고)

- Aura에 연결할 수 없으면 BE는 클라이언트(FE)에게  
  **`event: failed`** 로 실패 사유를 보낸 뒤 스트림을 정상 종료합니다.
- 메시지 예:  
  `"Aura Platform is not reachable (connection refused). Ensure Aura is running at http://localhost:9000 (config: aura.base-url or AURA_BASE_URL)."`
- 이 동작을 위해 **Aura 쪽 수정은 필요 없습니다.**

---

## 6. 관련 문서

| 문서 | 설명 |
|------|------|
| [ANALYSIS_STREAM_END_CONTRACT.md](../frontend/docs/api-spec/ANALYSIS_STREAM_END_CONTRACT.md) | 스트림 종료 이벤트 계약 (`event: completed` 권장) |
| [AURA_PHASE2_TRIGGER_ALIGNMENT.md](../integration/AURA_PHASE2_TRIGGER_ALIGNMENT.md) | 트리거/스트림 경로 정리 |
| [AURA_PLATFORM_INTEGRATION_GUIDE.md](../integration/AURA_PLATFORM_INTEGRATION_GUIDE.md) | Aura–Backend 연동 가이드 |

---

## 7. Aura → BE 확인 요청에 대한 BE 답변

**Aura 팀 확인 요청**:
1. totalBytesForwarded가 0이면, BE가 Aura에 연결한 뒤 본문을 계속 읽는 루프가 있는지, 타임아웃이 너무 짧지 않은지 확인.
2. Aura로 보내는 요청에 Upgrade 헤더를 넣지 말 것 (SSE는 Accept: text/event-stream만 사용).

**BE 답변**:

| 항목 | BE 동작 |
|------|--------|
| **본문 읽기 루프** | 있음. Aura 연결 후 `response.body()` InputStream을 `while ((n = in.read(buf)) != -1)` 루프로 **스트림이 닫힐 때까지 계속 읽고**, 읽은 바이트를 그대로 클라이언트(SseEmitter)로 전달함. |
| **타임아웃** | 짧지 않음. **Connect timeout 10초**, **Read timeout 30분**. 30분 동안 데이터가 오지 않아도 연결을 끊지 않음. |
| **Upgrade 헤더** | **보내지 않음.** Aura로 보내는 요청에는 **Accept: text/event-stream** 만 설정. Java HttpClient가 HTTP/2 업그레이드 시 Upgrade 헤더를 보내는 것을 막기 위해 **HTTP/1.1 전용** (`HttpClient.Version.HTTP_1_1`) 로 생성해 사용함. (Aura 로그 "Unsupported upgrade request" 방지, 코드 반영 완료) |

totalBytesForwarded가 0인 경우는 위 BE 동작과 무관하게, Aura 측에서 200 응답 후 **본문에 0바이트를 보내고 연결을 닫은 경우**에 해당합니다. BE는 본문을 계속 읽는 루프와 30분 read 타임아웃을 유지하고 있습니다.

---

## 8. 스트림 빈 응답 / 8ms 연결 종료 — BE 추가 조치 (타임라인 분석 반영)

Aura 측 타임라인: 첫 청크 전송 1ms 후, **8ms 시점에 클라이언트(BE) 연결 종료**로 스트림 취소. BE가 스트리밍을 제대로 읽지 않고 연결을 끊는 것으로 보임.

**BE에서 추가로 반영한 사항**:

| 항목 | 조치 |
|------|------|
| **Streaming 읽기** | `BodyHandlers.ofInputStream()` 으로 받은 **response.body()** 를 **BufferedInputStream 없이** 직접 읽음. (버퍼 레이어 제거로 첫 바이트 수신 직후 read() 반환) |
| **청크 크기** | **256바이트**로 축소. Aura가 `: connected\n\n` 등 소량을 보내도 즉시 한 번에 읽어 FE로 전달. |
| **연결 유지** | 요청 헤더에 **Connection: keep-alive** 명시. Read timeout 30분 유지. |
| **진단 로그** | 첫 청크 수신 시 `SSE proxy first chunk received: runId=... size=...` 로그 출력. totalBytesForwarded 로 전달 바이트 확인. |

**검증 방법**  
- BE 재기동 후 스트림 호출 → BE 로그에 `first chunk received` 및 `totalBytesForwarded > 0` 이면 정상.  
- 여전히 0이면 Aura 측 **즉시 `started` 이벤트 전송** 대응과 병행해 원인 추가 확인 권장.

---

## 9. Aura 문서 「BE 측 스트림 처리 필수 조치」 체크리스트 반영

Aura 팀 문서(aura.txt)의 **BE 측 필수 수정 사항·체크리스트** 대비 반영 여부:

| Aura 문서 항목 | BE 반영 |
|----------------|--------|
| **Streaming HTTP Client** — `BodyHandlers.ofLines()` 또는 `fromLineSubscriber()` 사용 | ✅ **ofLines()** 사용. 라인 단위 수신 후 `(line + "\n").getBytes(UTF_8)` 로 FE에 즉시 전달. |
| **Read Timeout** — 5분 이상 | ✅ **30분** (`Duration.ofMinutes(30)`). |
| **받은 라인 즉시 FE 전달** (버퍼링 금지) | ✅ 라인 수신 시마다 `emitter.send(chunk)` 호출. |
| **Connection: keep-alive** | ✅ 요청 헤더에 설정. |
| **totalBytesForwarded 로그** | ✅ `SSE proxy Aura stream ended: ... totalBytesForwarded=N lineCount=M` 로그 출력. |
| **Aura 응답 읽기 로그** (디버깅) | ✅ DEBUG 레벨에서 `SSE line received: runId=... bytes=... total=...` 출력. 첫 라인 수신 시 `SSE proxy first line received` 로그. |
| **HTTP/1.1 전용** (Upgrade 헤더 방지) | ✅ `HttpClient.Version.HTTP_1_1` 사용. |

**검증 방법 (Aura 문서와 동일)**  
- Aura 직접 호출: `curl -N http://localhost:9000/aura/cases/{caseId}/analysis/stream?runId=...` 또는 Aura 검증 스크립트.  
- BE 로그: `totalBytesForwarded > 0`, `lineCount > 0` 이면 정상.

---

## 10. Aura 확인 요청 — BE 답변 (스트림 “곧바로 끊김” 원인)

Aura 측: “스트림이 열리고 처음 두 개 메시지는 나갔지만, 곧바로 BE에서 끊긴 비정상 케이스” 확인 요청.

### BE 쪽에서 확인할 것 (3가지) — 답변

| 확인 항목 | 답변 |
|-----------|------|
| **ofLines() 변경이 실제 배포 환경에 반영되었는지** | ✅ 코드에 **BodyHandlers.ofLines()** 반영됨 (AnalysisStreamProxyService 94–95행). 배포 시 해당 빌드가 올라가면 반영됨. 배포 후 동작 확인 시 로그에 `SSE proxy first line received`, `SSE line received`, `totalBytesForwarded` 가 찍히면 ofLines() 경로로 동작 중인 것. |
| **첫 몇 줄만 읽고 예외/에러로 인해 스트림을 닫는 코드가 있는지** | ✅ **없음.** 스트림을 끊는 유일한 경우는 **emitter.send()** 시 **IllegalStateException**이 나는 경우뿐이며, 이는 **FE(클라이언트)가 이미 연결을 끊었을 때** SseEmitter에서 발생함. 즉, BE가 “첫 N줄만 보내고 끊는” 로직은 없고, **FE가 먼저 끊으면** 그 시점에서 Aura 읽기 루프를 break하고 스트림을 종료함. |
| **BE 로그에 SSE proxy first line received, totalBytesForwarded 값이 찍히는지 (0이면 아직 제대로 읽지 못한 것)** | ✅ **찍힘.** `SSE proxy first line received: runId=... lineLength=...` (DEBUG), `SSE proxy Aura stream ended: ... totalBytesForwarded=N lineCount=M` (INFO). totalBytesForwarded=0 이면 Aura에서 0바이트 수신한 경우. 현재 로그에서는 totalBytesForwarded=12, lineCount=1 → 첫 줄(예: `: connected`)은 정상 수신·전달됨. |

### “곧바로 BE에서 끊긴 비정상 케이스”에 대한 정리

- **BE는 스트림을 먼저 끊지 않음.**  
  Aura에서 계속 보내면 BE는 ofLines() 루프로 계속 읽어 FE로 전달함.
- **끊김 순서:**  
  **FE(브라우저)가 먼저 연결을 끊음** → BE가 다음 청크를 FE로 보내려 할 때 `emitter.send()` 에서 IllegalStateException → BE가 루프를 break하고 Aura 스트림도 종료.  
  따라서 Aura 입장에서는 “BE가 연결을 끊었다”처럼 보일 수 있으나, **원인은 FE의 조기 종료**임.
- **권장:** FE에서 EventSource/스트림을 유지하는지, 첫 이벤트(`: connected` 등) 수신 후 닫는 로직이 있는지 확인.

---

**요약**: Aura에서는 수정 사항 없음. 위 계약대로 `GET /aura/cases/{caseId}/analysis/stream?runId=` 를 제공해 주시면 되고, 확인 차 전달용으로 본 문서를 공유합니다.
