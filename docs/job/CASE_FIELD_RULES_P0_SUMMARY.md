# 케이스 필드 규칙 표 (P0 요약)

> 근거: `docs/job/PROMPT_BE_CASE_FIELD_RULES_AND_DEDUP_P0.txt`

## 1. 필드별 결정 규칙

| 필드 | 결정 규칙 | 비고 |
|------|-----------|------|
| **case_id** | DB PK/sequence | 기존 방식 유지 |
| **tenant_id** | DetectRun/X-Tenant-ID | 배치 실행 컨텍스트 |
| **detected_at** | 최초 탐지 시점 유지 | 재탐지 시 갱신하지 않음 |
| **bukrs/belnr/gjahr/buzei** | 원천테이블 도메인 키 | doc는 buzei null |
| **case_type** | window 기반 매핑 | WINDOW_DOC_ENTRY→DOC_WINDOW, WINDOW_OPEN_ITEM→OPEN_ITEM_WINDOW |
| **severity** | 금액 기반 | ≥100M→HIGH, ≥10M→MEDIUM, else LOW |
| **score** | severity 매핑 | CRITICAL=95, HIGH=80, MEDIUM=60, LOW=30, INFO=10 |
| **reason_text** | 1줄 설명 | 예: "Detected in document window during scheduled run" |
| **evidence_json** | source, keys, window, amount 등 | fi_doc_header / fi_open_item |
| **rag_refs_json** | 빈 배열/NULL | AURA 연계 후 채움 |
| **status** | 생성 시 OPEN | CLOSED/RESOLVED는 재오픈 안 함 |
| **owner_user / assignee_user_id** | null | P0 미할당 |
| **saved_view_key** | null | P0 |
| **dedup_key** | tenant:case_type:sourceType:bukrs-belnr-gjahr-buzei | run_id/timestamp 포함 금지 |
| **last_detect_run_id** | 이번 run_id | 재실행 시 갱신 |

## 2. case_type 매핑 (코드 관리)

| window(rule_id) | case_type | sys_codes(auth) | app_codes(synapse) |
|-----------------|-----------|-----------------|--------------------|
| WINDOW_DOC_ENTRY | DOC_WINDOW | ✅ | DRIVER_TYPE |
| WINDOW_OPEN_ITEM | OPEN_ITEM_WINDOW | ✅ | DRIVER_TYPE |

- **공통(sys_codes)**: 라벨(name_ko, name_en) — API 응답, i18n
- **Synapse(app_codes)**: 드릴다운 필터 검증용

## 3. dedup_key 형식

```
tenant_id:case_type:sourceType:bukrs-belnr-gjahr-buzei
```

- **DOC**: `1:DOC_WINDOW:DOC:1000-1900000001-2024-_`
- **OPEN_ITEM**: `1:OPEN_ITEM_WINDOW:OPEN_ITEM:1000-1900000001-2024-001`

## 4. DB 제약

- **UNIQUE**: `(tenant_id, dedup_key)` — V21 `ux_agent_case_dedup_key`
- **INDEX**: `(tenant_id, dedup_key)`, `(tenant_id, status)`, `(tenant_id, case_type)`, `(tenant_id, detected_at desc)`, `last_detect_run_id`

## 5. 이벤트 추적

- `detect_run_id` → `agent_case.last_detect_run_id`
- DETECT_RUN_* 이벤트: audit_event_log
