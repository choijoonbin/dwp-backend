# Phase3: 옵션 B · FAILED · SSE 타임아웃 검증 답변

> 다음 3가지에 대한 BE·Gateway 코드 기준 답변 및 확인 권장 사항.

---

## 1. 운영 기본 옵션 B — FE SSE가 항상 BE 프록시로 붙는지

### 1.1 BE 쪽 (답변 가능)

| 항목 | 상태 | 근거 |
|------|------|------|
| **POST analysis-runs 응답 streamUrl** | ✅ 운영 기본 = BE 프록시 | `CaseAnalysisService`: 초기값 `streamUrl = "/api/synapse/analysis-runs/" + runId + "/stream"`, `streamUrlUseAuraDirect == false`(기본)이면 최종에 `streamUrl = beStreamUrl`로 덮어씀. |
| **옵션 A(직접 Aura)** | ✅ dev/로컬 전용 | `synapse.stream-url-use-aura-direct`가 **true**일 때만 Aura URL 반환. 기본값 **false**, 운영에서 별도 설정하지 않으면 옵션 B. |
| **설정 위치** | | `application.yml`: `synapse.stream-url-use-aura-direct: ${SYNAPSE_STREAM_URL_USE_AURA_DIRECT:false}` |

**정리**: BE는 “운영 기본 = 옵션 B, FE가 받는 streamUrl = `/api/synapse/analysis-runs/{runId}/stream`”을 보장합니다. **방향성 정상** 확인됨.

### 1.2 FE 쪽

- FE는 **POST analysis-runs 응답의 `data.streamUrl`만 사용**해 SSE 연결(옵션 B 유지).
- **step 라벨**, **failed 시 error 객체 표시**, **체크리스트 표시** 모두 FE 반영 완료.

---

## 2. FAILED stage — Aura(rag/llm/pipeline/background) 기준 FE 표시 / BE 저장이 깨지지 않는지

### 2.1 BE 저장 (문자열 vs 객체)

| 항목 | 현재 구현 |
|------|-----------|
| **콜백 수신** | `AuraCallbackPayload.error` = **JsonNode** (문자열/객체 모두 수신) |
| **DB 저장** | `normalizeCallbackError()`로 문자열 정규화 후 `setErrorMessage(...)` |
| **FE 응답** | `AnalysisRunStatusDto.error` = **String** (GET analysis-runs/{runId}). FE는 step 라벨·failed error 객체·체크리스트 표시 **반영 완료**. |

### 2.2 반영 완료 (문자열/객체 모두 대응)

- **BE 반영**: `AuraCallbackPayload.error`를 **JsonNode**로 수신. 저장 시 `normalizeCallbackError()`로 정규화: 문자열이면 `asText()`, 객체면 `toString()`(JSON 문자열)로 변환 후 `setErrorMessage(...)` 저장. Aura가 `{ "message": "...", "stage": "..." }` 등 객체로 보내도 저장/표시 깨지지 않음.

---

## 3. 리버스프록시/게이트웨이 — SSE 타임아웃/버퍼링/keep-alive (30분 포함)

### 3.1 Gateway 설정 (코드 기준)

| 항목 | 현재 값 | 30분 요구사항 |
|------|----------|----------------|
| **response-timeout** | **300s (5분)** | ❌ 30분(1800s) **미적용** |
| **connect-timeout** | 10s | - |
| **경로** | `/api/synapse/analysis-runs/**` → SynapseX | SSE 스트림 경로 포함 |
| **버퍼링** | Spring Cloud Gateway 기본 (스트리밍 응답은 버퍼링 없이 전달되는 것이 일반적) | 별도 “buffering off” 설정은 없음 |
| **keep-alive** | httpclient pool 설정 있음 (max-idle-time 30s 등) | - |

**설정 위치**  
- `dwp-gateway/src/main/resources/application.yml`  
- `dwp-gateway/src/main/resources/application-prod.yml`  
- 공통: `spring.cloud.gateway.httpclient.response-timeout: 300s`

### 3.2 반영 완료 (스트림 라우트 30분)

- **적용**: `/api/synapse/analysis-runs/*/stream` 전용 라우트 `synapsex-analysis-runs-stream` 추가. **metadata.response-timeout: 1800000**(ms, 30분) 적용. `order: -1`로 일반 analysis-runs 라우트보다 우선 매칭.
- **설정 파일**: `application.yml`, `application-dev.yml`, `application-prod.yml` 모두 동일하게 반영.
- **버퍼링/keep-alive**: SSE는 스트리밍 응답으로 전달. 앞단에 **nginx** 등이 있으면 `proxy_read_timeout`, `proxy_buffering off` 확인 권장.

---

## 4. 요약 표

| # | 질문 | 답변 가능 여부 | 요약 |
|---|------|----------------|------|
| 1 | 운영 기본 옵션 B로 FE SSE가 항상 BE 프록시(`/api/synapse/analysis-runs/{runId}/stream`)로 붙는지 (옵션 A는 dev/로컬 전용) | ✅ 예 | BE는 운영 기본으로 해당 URL 반환. FE가 이 URL만 사용하는지 확인 필요. |
| 2 | FAILED 시 Aura 기준 FE 표시/BE 저장이 문자열·객체 error 모두에서 깨지지 않는지 | ✅ 예 | BE에서 `error`를 JsonNode로 받아 문자열로 정규화 저장 반영 완료. |
| 3 | 리버스프록시/게이트웨이에 SSE 타임아웃(30분)/버퍼링/keep-alive 적용 여부 | ✅ 예 | analysis-runs/*/stream 라우트에 **30분(1800000ms)** per-route 적용 완료. |

---

**작성일**: 2026-02-10
