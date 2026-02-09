# Aura Phase2 트리거/스트림 계약 정렬

BE ↔ Aura 연동 정렬 문서 (Aura 서버 변경사항 반영)

## API 경로 (Aura 기준)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/aura/cases/{caseId}/analysis-runs` | 202 + JSON |
| GET | `/aura/analysis-runs/{runId}/stream` | SSE (runId 기반) |
| GET | `/aura/cases/{caseId}/analysis/stream?runId=` | SSE (legacy) |

## 1. 트리거 (POST /aura/cases/{caseId}/analysis-runs)

### Request body (BE → Aura)

| 필드 | 타입 | 설명 |
|------|------|------|
| caseId | Long | 케이스 ID |
| runId | UUID | 분석 실행 ID |
| mode | String | LIVE \| SIMULATION |
| requestedBy | String | HUMAN \| SYSTEM |
| evidence | JsonNode | 케이스 evidence snapshot |
| options | Map | model, policyVersion 등 |

### Response (Aura → BE)

| 필드 | 타입 | 설명 |
|------|------|------|
| status | String | ACCEPTED (STARTED → ACCEPTED) |
| caseId | Long | 케이스 ID |
| runId | UUID | 분석 실행 ID |
| streamUrl | String | /aura/analysis-runs/{runId}/stream |
| message | String | DEMO_OFF 시 메시지 |

## 2. 스트림 (runId 기반)

- **GET** `/aura/analysis-runs/{runId}/stream` — runId 경로로 전환 (caseId 제거)

## 3. 콜백 (Aura → BE)

### proposals

| 필드 | 타입 | 설명 |
|------|------|------|
| type | String | 액션 유형 |
| riskLevel | String | 위험도 |
| rationale | String | 근거 |
| payload | JsonNode | 페이로드 |
| createdAt | Instant | Aura 스펙 |
| requiresApproval | Boolean | 승인 필요 여부 (FE 승인 플로우용) |

### 멱등성

- BE `dedup_key` 기반 중복 제거
- 동일 run 내 (caseId, runId, dedupKey) UNIQUE

## BE 반영 사항

- `AuraAnalyzeRequest`: caseId, evidence, options(model, policyVersion)
- `AuraAnalyzeResponse`: status=ACCEPTED, caseId, runId, streamUrl
- `AuraCallbackPayload.ProposalItem`: createdAt, requiresApproval
- `CaseAnalysisService`: evidence 전달, 콜백 시 requiresApproval 저장

## 참고

- Aura: `docs/phase2/PHASE2_TRIGGER_STANDARD.md`
- FE: `FRONTEND_AURA_PHASE2_RESPONSE.md`
