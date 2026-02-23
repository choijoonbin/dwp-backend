# Aura 전달용: 에이전트 스트림 API 경로 가이드

Synapse 백엔드와의 중복 적재 방지를 위해, **에이전트 스트림(thought/AGENT_STREAM/step)을 Synapse에 전달할 때 사용하는 API는 하나만** 쓰라는 가이드입니다.  
**“케이스당 1번만 호출”이 아니라, “한 종류의 API만 사용하라”**는 의미입니다.

---

## 1. 우리가 요청하는 것 (한 API만 사용)

- **에이전트 활동 타임라인(aiThoughts)** 을 Synapse `agent_activity_log`에 쌓을 때 사용하는 **경로는 하나만** 사용해 주세요.
- **REST API만 사용**해 주세요.  
  → thought/AGENT_STREAM/step이 **발생할 때마다** `POST /api/synapse/agent/events`를 호출하는 **지금처럼 실시간으로 여러 번 호출해도 됩니다.**  
  → **변경 불필요:** “케이스당 1회 배치로 모아서 보내기”로 바꿀 필요 없습니다.

**요청 요약**

| 구분 | 요청 내용 |
|------|-----------|
| **호출 횟수** | **제한 없음.** 이벤트(thought/step 등) 발생 시마다 REST 호출하는 **현재 방식 유지** 가능. |
| **사용할 API** | **에이전트 스트림용은 REST 하나만.** 동일한 thought/AGENT_STREAM/step을 **Redis 감사 채널로는 보내지 말 것.** |

---

## 2. 하지 말아야 할 것 (같은 이벤트를 두 경로에 보내기)

- **같은** thought / AGENT_STREAM / step 이벤트를 **REST와 Redis 양쪽에 모두** 보내지 마세요.
- **에이전트 스트림** → Synapse 타임라인(aiThoughts)에 보여줄 때: **REST만** 사용.  
  **감사(audit)** 용도로 남길 이벤트는 Redis만 사용 (이때 동일한 thought/stream payload를 Redis로 중복 발행하지 않으면 됨).

| 목적 | 사용할 경로 | 비고 |
|------|-------------|------|
| **에이전트 활동 타임라인** (thought, AGENT_STREAM, step → aiThoughts) | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 OK (실시간 유지). **동일 내용을 Redis로 보내지 않기.** |
| **감사 로그** (audit_event_log) | **Redis만** `audit:events:ingest` | 감사 전용 이벤트만 Redis로 발행. thought/stream과 **동일한 payload**를 Redis에도 보내지 않기. |

---

## 3. 케이스 유형별로 “어떤 API만 쓸지” 명시

**모든 케이스 유형에서 동일합니다.**  
에이전트 스트림(thought/AGENT_STREAM/step)을 Synapse에 보낼 때는 **항상 REST API만** 사용하고, **동일 이벤트를 Redis로는 보내지 않습니다.**

| case_type (예시) | 에이전트 스트림 전송 시 사용할 API | 호출 횟수 |
|------------------|-------------------------------------|-----------|
| DUPLICATE_INVOICE | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |
| THRESHOLD_BREACH | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |
| BANK_CHANGE | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |
| ANOMALY | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |
| DEFAULT | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |
| 시연 시나리오 (LATE_NIGHT, WEEKEND_MEAL 등) | **REST만** `POST /api/synapse/agent/events` | 이벤트 발생 시마다 호출 (현재 방식 유지) |

- **이 케이스는 REST만 / 저 케이스는 Redis만** 같은 식으로 **나누는 것이 아니라**,  
  **“에이전트 스트림을 Synapse 타임라인에 넣을 때는 어떤 케이스든 REST 하나만 사용”**이면 됩니다.

---

## 4. 오해하기 쉬웠던 부분 정리

| 잘못된 이해 (이번에 Aura가 이해한 내용) | 올바른 요청 |
|----------------------------------------|-------------|
| “케이스당 1번만 호출하라” → 이벤트를 모아서 분석 완료 시 1회만 REST 호출 | **X.** 호출 횟수 제한 아님. **이벤트 발생 시마다 REST 호출(실시간 전송) 유지해도 됨.** |
| “케이스당 1회 배치”로 전송 방식 변경 필요 | **X.** 배치로 바꿀 필요 없음. **현재처럼 이벤트 단위로 REST 호출해도 됨.** |

| 올바른 이해 |
|-------------|
| **“에이전트 스트림용으로는 REST 이 API 하나만 써라.”** 같은 thought/AGENT_STREAM/step을 **REST와 Redis 둘 다에 보내지 말라.** 그래야 Synapse에서 중복 적재가 나지 않음. |

---

## 5. Aura 측에서 확인할 것 (체크리스트)

- [ ] thought/AGENT_STREAM/step 발생 시마다 `POST /api/synapse/agent/events` 호출하는 **현재 방식(실시간 전송)을 유지**해도 됨. “케이스당 1회 배치”로 바꾸지 않아도 됨.
- [ ] **에이전트 스트림(타임라인용)** 을 Synapse에 보낼 때 **REST만** 사용하는지 확인.
- [ ] **동일한** thought/reasoning/AGENT_STREAM 이벤트를 **Redis `audit:events:ingest` 로는 보내지 않는지** 확인. (감사용 다른 이벤트는 Redis만 사용 가능)
- [ ] “한 API만 사용” = **경로(API)를 하나로 통일**하라는 의미이지, **호출 횟수를 1번으로 하라는 의미가 아님.**

---

## 6. 두 경로 정리 (참고)

| 경로 | 용도 | Synapse 동작 |
|------|------|----------------|
| **REST** `POST /api/synapse/agent/events` | 에이전트 활동 스트림 → aiThoughts 타임라인 | `agent_activity_log` 에 저장 |
| **Redis** `audit:events:ingest` | 감사 이벤트 | `audit_event_log` 만 저장 (Synapse는 Redis 수신 시 `agent_activity_log` 에 저장하지 않음) |

- **두 경로 모두 Aura가 호출/발행**합니다.
- **같은 이벤트**를 REST와 Redis **양쪽에 보내지만 않으면** 되고, **에이전트 스트림용은 REST 하나만** 쓰면 됩니다.

이 문서를 Aura 팀에 다시 전달해 주시면, “한 API만 사용(경로 통일)” 요청이 “호출 1번”이 아님을 명확히 할 수 있습니다.
