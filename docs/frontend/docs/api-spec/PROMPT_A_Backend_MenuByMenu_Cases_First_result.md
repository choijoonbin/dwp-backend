# PROMPT_A_Backend_MenuByMenu_Cases_First — 작업 결과

> 기준: docs/job/PROMPT_A_Backend_MenuByMenu_Cases_First.txt

---

## 1) 완료 항목

### P0-2: Case Detail 계약 확정

**GET /api/synapse/cases/{caseId}** 응답 확장:

- `keys`: `{ sourceType, bukrs, belnr, gjahr, buzei, dedupKey }` — 핵심 식별자
- `links`: `{ openItems, lineage }` — 관련 API URL
  - `openItems`: `/api/synapse/open-items?caseId={caseId}`
  - `lineage`: `/api/synapse/lineage?caseId={caseId}`

### P0-2: Case List summary

**GET /api/synapse/cases** 응답 확장:

- `summary`: `{ total, open, triage, inReview }` — 건수 집계

### P0-3: Open Items caseId 지원

**GET /api/synapse/open-items?caseId={caseId}**

- `caseId` 파라미터 추가
- caseId가 있으면 해당 case의 bukrs/belnr/gjahr로 **관련 미결재 항목만** 조회
- audit tags에 `caseId`, `related: true` 포함

### P0-4: Lineage audit

**GET /api/synapse/lineage**, **GET /api/synapse/lineage/time-travel**

- `LINEAGE_VIEW` audit 이벤트 기록
- `AuditEventConstants.TYPE_LINEAGE_VIEW` 추가

---

## 2) 변경 파일

| 경로 | 변경 내용 |
|------|----------|
| `CaseDetailDto.java` | keys, links 필드 및 CaseKeysDto, CaseLinksDto 추가 |
| `CaseQueryService.java` | keys/links/summary 빌드, resolveSourceType, buildCaseSummary |
| `PageResponse.java` | summary 필드 추가 |
| `OpenItemController.java` | caseId 파라미터, audit tags |
| `OpenItemQueryService.java` | caseId 필터 (case keys 기반) |
| `LineageController.java` | AuditWriter, LINEAGE_VIEW audit |
| `AuditEventConstants.java` | TYPE_LINEAGE_VIEW |
| `docs/prompts/PHASE_Cases_Backend_MenuHardening_P0-P2.md` | 산출 문서 |

---

## 3) FE 연동 참고

- Case Detail의 `links.openItems`, `links.lineage`로 하드코딩 URL 제거
- Open Items 목록: Case 상세에서 "관련 미결재항목" 클릭 시 `?caseId={caseId}` 호출
- Lineage: Case 상세에서 "데이터 계보보기" 클릭 시 `?caseId={caseId}` 호출
