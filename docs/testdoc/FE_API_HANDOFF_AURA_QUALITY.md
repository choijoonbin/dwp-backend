# FE API Handoff (Aura Quality / RAG Governance)

## 공통
- Base URL: `http://localhost:8080/api`
- 필수 헤더: `X-Tenant-Id: 1`
- 공통 응답 래퍼:
```json
{
  "status": "SUCCESS|ERROR",
  "message": "...",
  "data": {},
  "errorCode": null,
  "timestamp": "2026-02-26T...",
  "success": true
}
```

## 1) Aura 품질 지표 조회
- `GET /api/synapse/aura/quality-metrics`
- Query:
  - `from` (optional, ISO-8601 datetime)
  - `to` (optional, ISO-8601 datetime)
- 기본 기간: 최근 30일(`from/to` 미지정 시)

### Response `data`
```json
{
  "from": "2026-01-27T00:00:00Z",
  "to": "2026-02-26T00:00:00Z",
  "totalCount": 120,
  "sentenceCitationMissingCount": 5,
  "evidenceCoverageLowCount": 12,
  "policyReevalAppliedCount": 9,
  "ragZeroCount": 3,
  "sentenceCitationMissingRatio": 0.0417,
  "evidenceCoverageLowRatio": 0.1000,
  "policyReevalAppliedRatio": 0.0750,
  "ragZeroRatio": 0.0250
}
```

## 2) Analysis Replay Gate 결과 저장
- `POST /api/synapse/aura/analysis-replay-gate-runs`

### Request Body
```json
{
  "runKey": "deploy-2026-02-26.1",
  "gatePassed": true,
  "resultJson": {
    "zero_rate": 0.12,
    "hit_at_k": 0.83,
    "strict_hit_top1": 0.71,
    "total_cases": 240
  }
}
```

### Response `data`
```json
{
  "id": 7,
  "runKey": "deploy-2026-02-26.1",
  "gatePassed": true,
  "resultJson": {
    "zero_rate": 0.12,
    "hit_at_k": 0.83,
    "strict_hit_top1": 0.71,
    "total_cases": 240
  },
  "createdAt": "2026-02-26T06:10:40.123Z"
}
```

## 3) 최신 Analysis Replay Gate 결과 조회
- `GET /api/synapse/aura/analysis-replay-gate-runs/latest`

### 성공 Response
`data` 구조는 2번과 동일.

### 데이터 없을 때
- `200` + `data: null`

---

## 4) 문서 청크 원자 교체
- `POST /api/synapse/rag/documents/{docId}/chunks/replace`
- 용도: 기존 active 청크 비활성화 후 신규 청크 활성 저장(트랜잭션)

### Request Body 예시
```json
{
  "chunks": [
    {
      "chunkId": "doc-101-art-9-1",
      "chunkText": "제9조 1항 본문...",
      "chunkIndex": 0,
      "metadata": {
        "regulation_article": "제9조",
        "title": "출장비 기준"
      }
    },
    {
      "chunkId": "doc-101-art-9-2",
      "chunkText": "제9조 2항 본문...",
      "chunkIndex": 1,
      "metadata": {
        "regulation_article": "제9조",
        "title": "출장비 기준"
      }
    }
  ]
}
```

### Response
- 성공 시 `data: null` (200)

## 5) RAG 버전 활성 전환
- `POST /api/synapse/rag/documents/{docId}/versions/activate?version={version}`
- 예: `/api/synapse/rag/documents/101/versions/activate?version=v2026.02`

### Response
- 성공 시 `data: null` (200)

## 6) 최신 RAG Eval Run 조회
- `GET /api/synapse/rag/eval-runs/latest`

### 데이터 없을 때
- `200` + `data: null`

---

## FE 처리 규약
- `.../latest` 계열은 "데이터 없음"을 `200 + data:null`로 반환합니다.
- FE는 `data === null`이면 "아직 결과 없음" empty state로 처리하세요.
- 그 외 목록 API는 기존처럼 0건이면 `200 + empty content` 정책입니다.
