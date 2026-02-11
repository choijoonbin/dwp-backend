# aura.txt 검토 회신 — 백엔드 검토 결과 및 Aura 측 확인사항

> **대상 문서**: Aura 전달 `aura.txt` (기능 제거 후 백엔드–Aura 협업 규격)  
> **검토 일자**: 2026-02-11  
> **전달 대상**: Aura 플랫폼 담당자

---

## 1. 백엔드 검토 요약

aura.txt 내용을 반영하여 아래를 확인·수정했습니다.

| 항목 | 백엔드 상태 | 조치 |
|------|-------------|------|
| 조치 이력·전표 상태 DB 쓰기 | ✅ **백엔드 단일 담당** | 이미 구현됨. `ActionCommandService`에서 `agent_case_action_history` INSERT, `fi_doc_header.status_code` UPDATE, `agent_case.status` RESOLVED 처리 |
| `POST /api/aura/action/record` 호출 | ✅ **호출하지 않음** | 조치 확정은 백엔드 DB에서만 수행. Aura API 응답의 `history_id`, `fi_doc_updated` 의존 코드 없음 → **수정 불필요** |
| Redis 구독 시 `history_id`/`fi_doc_updated` 파싱 | ✅ **백엔드는 구독하지 않음** | 백엔드는 조치 완료 이벤트를 **발행만** 함. 구독·파싱 로직 없음 → **수정 불필요** |
| Redis 채널명 | ✅ **Aura와 통일** | 기본 채널을 **`workbench:case:action`** 으로 변경함 (기존 `workbench:action:completed` → aura.txt 규격에 맞춤) |

---

## 2. 백엔드에서 수정한 내용

### 2.1 Redis 채널명 통일

- **변경**: 기본 발행 채널을 **`workbench:case:action`** 으로 통일 (aura.txt §1.3과 동일).
- **이유**: 구독자(프론트/Aura)가 하나의 채널만 구독해도 백엔드·Aura 발행 메시지를 모두 수신할 수 있도록 하기 위함.
- **적용 위치**:
  - `workbench.redis.action-channel` 기본값: `workbench:case:action`
  - `WorkbenchActionCompletionPublisher.CHANNEL_DEFAULT`: `workbench:case:action`

### 2.2 백엔드 Redis 페이로드 (발행 측)

- 백엔드는 **DB 커밋 직후** `workbench:case:action` 채널에 아래 필드를 포함해 발행합니다.
- Aura에서 제거한 `history_id`, `fi_doc_updated`는 **백엔드 발행 시에는 포함**합니다 (백엔드가 DB를 갱신하므로 값 보유).
- 구독 측에서는 `history_id`, `fi_doc_updated`를 **optional**로 처리하면 됨 (Aura 발행분에는 없을 수 있음).

**백엔드 발행 필드**:  
`type`, `case_id`, `request_id`, `executor_id`, `action_type`, `approved`, `status_code`, `at`, `tenant_id`, `action_id`,  
그리고 **`history_id`**, **`fi_doc_updated`**, (선택) **`new_kpi_summary`**

---

## 3. Aura 측에서 확인·작업할 사항

아래는 aura.txt 및 위 검토 결과를 바탕으로, **Aura에서 확인하시면 좋을 항목**입니다.

### 3.1 Redis 구독 시 (필드 처리)

- 채널 **`workbench:case:action`** 을 구독할 경우, 같은 채널에 **백엔드도 발행**합니다.
- 백엔드 발행분에는 `history_id`, `fi_doc_updated`(및 `new_kpi_summary`)가 포함됩니다.
- **요청**: Aura 구독 로직에서 `history_id`, `fi_doc_updated`를 **optional**로 처리해 주세요 (필드 없으면 무시).  
  → aura.txt §1.3에서 제거하신 것은 “Aura 발행분에서 제거”로 이해했으며, “구독 시 다른 발행자(백엔드) 메시지에는 있을 수 있음”으로 반영했습니다.

### 3.2 채널 설정 일치

- 백엔드 기본 채널: **`workbench:case:action`**
- Aura 설정 `case_action_redis_channel` 도 **`workbench:case:action`** 으로 맞춰 주시면, 양쪽 발행·구독이 동일 채널로 정렬됩니다.

### 3.3 Phase2 분석 요청 시 doc_id / item_id (선택)

- aura.txt §1.4 규격 확인했습니다.
- 백엔드에서 특정 문서·항목 기준 규정 준수 판단이 필요해지면, 분석 요청 시 **`body_evidence.doc_id`**(또는 `document.docKey`), **`body_evidence.item_id`** 를 넘기겠습니다.
- Aura Phase2에서 해당 필드를 읽어 프롬프트에 반영해 주시면 됩니다 (aura.txt §2.2 Aura 확인사항 #4).

### 3.4 `/action/record` 호출 실패 시 (공통 협의)

- aura.txt §2.3 공통 확인 #3: **Aura `POST /api/aura/action/record` 호출 실패 시 백엔드 처리**에 대해,  
  현재 백엔드는 **조치 확정을 위해 해당 API를 호출하지 않습니다.**  
  (조치 이력·전표 상태는 백엔드 DB에서만 갱신하고, Redis는 백엔드가 직접 발행합니다.)  
- 따라서 “백엔드 → Aura /action/record 호출 실패” 시나리오는 **현재 플로우에 없습니다.**  
- 다만, **다른 클라이언트(예: Aura 자체 플로우)가** `/action/record`를 호출하는 경우, 그 실패 시 재시도·알림 정책은 Aura·해당 클라이언트 측에서 정해 주시면 됩니다.

---

## 4. 체크리스트 요약

### 4.1 백엔드 (완료)

- [x] 승인/거절 시 자체 DB에 조치 이력·전표 상태 저장
- [x] `POST /api/aura/action/record` 응답의 `history_id`, `fi_doc_updated` 미사용 (호출 자체 없음)
- [x] Redis 발행 채널을 `workbench:case:action`으로 통일
- [x] (선택) Phase2에서 `doc_id`/`item_id` 전달 시 `body_evidence` 규격 사용 가능

### 4.2 Aura 요청 사항

- [ ] Redis 구독 시 `history_id`, `fi_doc_updated` optional 처리 (백엔드 발행분에는 존재 가능)
- [ ] `case_action_redis_channel` = `workbench:case:action` 확인
- [ ] Phase2에서 `body_evidence.doc_id`, `document.docKey`, `body_evidence.item_id` 수신·프롬프트 반영 확인

---

## 5. 참조 문서 (백엔드 내부용 — Aura 전달 대상 아님)

- `docs/integration/ACTION_SINGLE_SOURCE_OF_TRUTH.md` — 조치 확정 로직 및 Redis 발행
- `docs/integration/ACTION_INTEGRITY_WORKBENCH_REFETCH.md` — Redis 채널·메시지 형식
- `docs/integration/AURA_ACTION_INTEGRATION_SPEC.md` — 연동 계약 요약 (내부 참조용, **Aura에는 전달하지 않음**)
