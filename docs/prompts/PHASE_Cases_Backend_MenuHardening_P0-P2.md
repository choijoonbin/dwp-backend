# PHASE Cases Backend Menu Hardening P0–P2

> 산출: PROMPT_A_Backend_MenuByMenu_Cases_First.txt 기반  
> 목표: Case Worklist → Case Detail → 탭 E2E 실데이터 동작 + FE 하드코딩 제거

---

## 0) 결론/결정안

1. **1차(P0)**: Case Worklist/Detail 데이터 계약 확정 + 탭별 최소 API 준비, 하드코딩 제거
2. **Tenant Scope(X-Tenant-ID)** + **audit_event_log** 기록(조회/상세/탭/링크 이동 API 포함)
3. AI 고도화(LLM RAG 성능 등)는 2차(Agentic 강화)로 미루고, 1차는 **데이터 파이프 + 계약** 완성

---

## 1) 현재 상태 분석 (Gap)

### 1.1 Case Worklist (`GET /api/synapse/cases`)

| 항목 | 현재 | 프롬프트 요구 | Gap |
|------|------|---------------|-----|
| 경로 | `/synapse/cases` | `/api/synapse/cases` | Gateway prefix로 동일 |
| 필터 | status, severity, dateFrom/To, q, page, size, sort | q, status[], severity[], dateFrom, dateTo, page, size, sort | status[] 다중 지원(parseMulti) |
| 응답 | PageResponse&lt;CaseListRowDto&gt; | items, page, summary | **summary 누락** |
| Audit | CASE_VIEW_LIST | CASE_VIEW_LIST | ✅ |
| Tenant | X-Tenant-ID 필수 | X-Tenant-ID | ✅ |

### 1.2 Case Detail (`GET /api/synapse/cases/{caseId}`)

| 항목 | 현재 | 프롬프트 요구 | Gap |
|------|------|---------------|-----|
| 응답 | caseId, status, evidence, reasoning, action | caseId, status, keys, evidence, **links** | **keys, links 누락** |
| keys | lineageLinkParams 내 docKey 등 | sourceType, bukrs, belnr, gjahr, buzei, dedupKey | **keys 객체 추가** |
| links | lineageLinkParams(파라미터만) | openItems, lineage URL | **links 객체 추가** |
| Audit | CASE_VIEW_DETAIL | CASE_VIEW_DETAIL | ✅ |

### 1.3 Open Items (`GET /api/synapse/open-items`)

| 항목 | 현재 | 프롬프트 요구 | Gap |
|------|------|---------------|-----|
| caseId | 미지원 | caseId= 또는 keys 기반 필터 | **caseId 파라미터 추가** |
| related 필터 | - | caseId 시 "관련 항목"만 | bukrs/belnr/gjahr 자동 적용 |
| Audit | OPENITEM_VIEW_LIST | OPENITEM_VIEW_LIST(related=true) | tags에 related 추가 |

### 1.4 Lineage (`GET /api/synapse/lineage`)

| 항목 | 현재 | 프롬프트 요구 | Gap |
|------|------|---------------|-----|
| caseId | 지원 | caseId= | ✅ |
| Audit | **없음** | LINEAGE_VIEW | **audit 추가** |

### 1.5 Case Tab APIs (P1)

| API | 현재 | 비고 |
|-----|------|------|
| GET /cases/{id}/analysis | 없음 | P1 최소 구현 |
| GET /cases/{id}/confidence | 없음 | P1 (reasoning.confidenceBreakdown 활용) |
| GET /cases/{id}/similar | 없음 | P1 (vendor/amount/time 기반) |
| GET /cases/{id}/rag/evidence | 없음 | P1 (evidence_json 정규화) |

---

## 2) API 스펙 (확정 계약)

### 2.1 Case Worklist

```
GET /api/synapse/cases
Headers: X-Tenant-ID, Authorization, X-User-ID, Accept-Language
Query: q, status, severity, dateFrom, dateTo, page, size, sort, order
```

**Response (기존 PageResponse + summary 확장)**  
- `items`: CaseListRowDto[]  
- `pageInfo`: { page, size, hasNext }  
- `total`: long  
- `summary` (optional): { total, open, triage, inReview }

### 2.2 Case Detail

```
GET /api/synapse/cases/{caseId}
```

**Response 확장**  
- `keys`: { sourceType, bukrs, belnr, gjahr, buzei, dedupKey }  
- `links`: { openItems, lineage } — 절대 URL 또는 상대 경로

### 2.3 Open Items (caseId 지원)

```
GET /api/synapse/open-items?caseId={caseId}
```

- caseId 있으면: 해당 case의 bukrs/belnr/gjahr로 fi_open_item 필터 (관련 미결재 항목만)

### 2.4 Lineage (audit)

```
GET /api/synapse/lineage?caseId={caseId}
```

- audit: LINEAGE_VIEW (event_type)

---

## 3) DB 매핑

| 테이블 | 용도 |
|--------|------|
| agent_case | case_id, tenant_id, status, severity, score, source_type, bukrs, belnr, gjahr, buzei, dedup_key, evidence_json, last_detect_run_id |
| fi_open_item | (tenant_id, bukrs, belnr, gjahr) — case keys 기반 조회 |
| audit_event_log | VIEW_LIST, VIEW_DETAIL, LINEAGE_VIEW |

**인덱스**  
- agent_case: (tenant_id, detected_at), (tenant_id, status), (tenant_id, severity), UNIQUE(tenant_id, dedup_key)  
- fi_open_item: (tenant_id, bukrs, belnr, gjahr)

---

## 4) 구현 작업 목록

### P0 (이번 라운드)

- [x] P0-1: Tenant 강제 — 이미 적용됨
- [x] P0-2: Case Detail에 keys, links 추가
- [x] P0-3: Open Items에 caseId 지원
- [x] P0-4: Lineage에 audit (LINEAGE_VIEW) 추가
- [x] P0-5: Case list에 summary 추가 (optional)

### P1

- [ ] 탭 API: analysis, confidence, similar, rag/evidence

### P2

- [ ] Simulation: POST /cases/{id}/simulation
- [ ] HITL: approve/reject/execute (기존 연계)

---

## 5) 스모크 플로우 (Audit 검증)

| 클릭 경로 | API | audit event_type |
|-----------|-----|------------------|
| Case Worklist 진입 | GET /cases | CASE_VIEW_LIST |
| 케이스 클릭 | GET /cases/{id} | CASE_VIEW_DETAIL |
| 관련 미결재항목 클릭 | GET /open-items?caseId=... | OPENITEM_VIEW_LIST (related=true) |
| 데이터 계보보기 클릭 | GET /lineage?caseId=... | LINEAGE_VIEW |

---

## 6) 체크리스트

- [ ] 케이스 5건 seed로 배치 2회 실행 시 dedup_key로 중복 생성 없음
- [ ] list/detail에서 하드코딩 필드 0개
- [ ] open-items/lineage는 "관련 필터" 적용 (전체 조회 금지)
- [ ] 모든 API X-Tenant-ID 스코프 + audit 기록
- [ ] paging/sort/index 포함
