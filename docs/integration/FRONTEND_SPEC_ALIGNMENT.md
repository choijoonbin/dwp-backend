# Frontend Spec Alignment (FE 반영 요약)

**기준 문서**: [LEVEL4_FINAL_API_SPEC.md](./LEVEL4_FINAL_API_SPEC.md) (Level 4 최종 API 규격서)  
**반영일**: 2026-02-11  
**공유**: 프론트엔드 팀 정합성 문서 반영 — Backend는 동일 계약 유지

---

## 1. GET /api/synapse/cases/{id} (규격서 B.2, 4.3)

| BE 응답 | FE 반영 |
|--------|--------|
| 루트 **fi_doc_items** (배열: buzei, hkont, wrbtr, sgtxt, lifnr, kunnr, bschl, shkzg, dmbtr, waers, isTarget) | `CaseDetailDto.fi_doc_items` 타입 추가. `useCaseDetail`에서 **dto.fi_doc_items** 1순위 사용, 없으면 evidence 내부 items |
| **keys** (bukrs, belnr, gjahr, buzei 등) | `CaseDetailDto.keys` 추가. targetBuzei = **dto.keys?.buzei** 우선 |
| **links** (openItems, lineage 등) | `CaseDetailDto.links` 타입 추가 |
| wrbtr → number | 기존 FiDocItem.wrbtr number 유지, mapRawLineItemToFiDoc에서 BE 필드 그대로 매핑 |

---

## 2. GET /api/synapse/workbench/cases/{caseId}/history (규격서 B.3, 4.3)

| BE | FE 반영 |
|----|--------|
| **Endpoint** | `getWorkbenchCaseHistory(caseId)` — `GET /api/synapse/workbench/cases/{caseId}/history` |
| **Response** | `ApiResponse<List<CaseActionHistoryItemDto>>` (data[]), action_at DESC |
| **CaseActionHistoryItemDto** | id, caseId, actionType, **actorId**, **commentText**, **actionAt** (ISO8601), metadataJson, createdAt |
| **워크벤치 타임라인** | `useWorkbenchCaseHistoryQuery` 사용. audit-events 대신 **history** API만 사용. actorName = actorId, comment = commentText, actionAt = actionAt |

---

## 3. WebSocket /ws/notifications (규격서 4.3)

| BE NotificationDto | FE 반영 |
|-------------------|--------|
| **content** (메시지 본문) | `message = payload.content ?? payload.message ?? payload.body` |
| **type** | 기존대로 `normalizeCategory(payload.category ?? payload.type)` → 아이콘·색상 매칭 |
| id, tenantId, title, channel, occurredAt, createdAt, readAt, payload | IncomingNotificationPayload 타입에 필드 명시, 필요 시 payload에서 link 등 확장 가능 |

---

## 4. FE → Backend (규격서 4.4)

| Endpoint | 비고 |
|----------|------|
| POST `/api/synapse/actions/{actionId}/approve` | body `{ comment?: string }` — 기존 구현 유지 |
| POST `/api/synapse/actions/{actionId}/reject` | body `{ comment?: string }` — 기존 구현 유지 |
| POST `/api/synapse/cases/{caseId}/analysis-runs` | mode, requestedBy, (optional) evidenceSnapshot — 기존 구현 유지 |

---

## 5. 참고: GET /api/synapse/documents/detail

규격서 4.3: `GET /api/synapse/documents/detail` (bukrs, belnr, gjahr) → header(1), **items**(배열), derived, reversalChain 등.  
문서 상세 전용 API이며, 케이스 상세의 fi_doc_items는 **GET /api/synapse/cases/{id}** 루트 **fi_doc_items**로 정렬 완료.
