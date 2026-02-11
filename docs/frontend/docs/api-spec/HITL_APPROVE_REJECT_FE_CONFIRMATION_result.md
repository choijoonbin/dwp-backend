# HITL 승인/거절 API 확인 회신 (FE 확인 요청 대응)

> **요청**: 프론트엔드 확인 요청 — 승인/거절 시 case_action_history 기록 및 Aura 전달 담당  
> **회신 일자**: 2026-02-11

---

## 1. 확인 요청에 대한 답변

**네. case_action_history 기록 및 필요 시 Aura 쪽 전달은 모두 백엔드에서 처리합니다.**  
프론트엔드는 아래 API만 호출하면 됩니다.

---

## 2. 실제 엔드포인트 및 Body (백엔드 기준)

프론트에서 안내하신 경로와 **path 일부만 다릅니다**. 백엔드 실제 경로는 아래와 같습니다.

| 구분 | 프론트 안내 경로 | 백엔드 실제 경로 |
|------|------------------|------------------|
| 승인 | `POST /api/synapse/actions/hitl/{requestId}/approve` | **`POST /api/synapse/actions/{actionId}/approve`** |
| 거절 | `POST /api/synapse/actions/hitl/{requestId}/reject`  | **`POST /api/synapse/actions/{actionId}/reject`**  |

- **Path**: `hitl/{requestId}` 가 아니라 **`{actionId}`** 입니다.  
  (HITL 요청 ID와 동일한 값은 **action ID** 이므로, `requestId` 자리에 **actionId** 를 넣어 호출하시면 됩니다.)
- **Body**: 동일하게 **선택(optional)**  
  - `{ "comment": "선택 사유 또는 비움" }`  
  - 비우거나 생략 가능.

---

## 3. 스펙 요약

| 항목 | 내용 |
|------|------|
| **승인** | `POST /api/synapse/actions/{actionId}/approve` |
| **거절** | `POST /api/synapse/actions/{actionId}/reject` |
| **Body** | `{ "comment": "string (optional)" }` |
| **헤더** | `X-Tenant-ID` 필수, `X-User-ID` 선택(조치자 식별용) |
| **백엔드 처리** | ① `agent_case_action_history` INSERT ② `fi_doc_header` / `agent_case` 상태 갱신 ③ DB 커밋 후 Redis `workbench:case:action` 발행 ④ (선택) Aura 웹훅 POST |

---

## 4. 정리

- **case_action_history 기록**: 백엔드에서 처리합니다.
- **Aura 전달(Redis 발행·웹훅)**: 백엔드에서 처리합니다.
- **FE 호출**: `POST /api/synapse/actions/{actionId}/approve`, `.../reject` 에 **actionId** path, 필요 시 body `{ "comment": "..." }` 만 보내시면 됩니다.
