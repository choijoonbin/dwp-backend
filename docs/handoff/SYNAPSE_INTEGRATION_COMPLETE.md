# Aura–Synapse 연동 완료 확인 (2026-02-06)

> **작성**: dwp-backend  
> **대상**: Aura 팀  
> **배경**: Aura 측 Synapse 연동 조치 완료, Gateway 라우팅 및 헤더 전파 확인

---

## 1. Aura 측 조치 완료 사항

| 항목 | 내용 |
|------|------|
| **synapse_base_url** | `http://localhost:8080/api/synapse/agent-tools` |
| **경로** | /cases, /documents, /open-items, /lineage (GET) |
| **search_documents, get_open_items** | POST → GET 변경 |
| **get_lineage** | belnr/gjahr/bukrs Field 메타데이터 검증 추가 |
| **agent/events** | Gateway 8080 경유 (`POST /api/synapse/agent/events`) |

---

## 2. Gateway 라우팅 확인 ✅

| 경로 | 대상 | 상태 |
|------|------|------|
| `/api/synapse/agent-tools/*` | Synapse Agent Tool API (8085) | ✅ |
| `/api/synapse/agent/events` | Synapse Agent Stream (8085) | ✅ |

**라우팅 상세**:
- `GET/POST /api/synapse/agent-tools/**` → `http://localhost:8085/synapse/agent-tools/**` (StripPrefix=1)
- `POST /api/synapse/agent/**` → `http://localhost:8085/synapse/agent/**` (StripPrefix=1)
- 단, `/api/synapse/agent-tools/agents/**` 는 Aura SSE 스트림용으로 Aura-Platform(9000)으로 전달

---

## 3. 헤더 전파 확인 ✅

| 헤더 | 전파 | 비고 |
|------|------|------|
| **X-Tenant-ID** | ✅ | RequiredHeaderFilter로 필수 검증, Gateway가 다운스트림으로 전달 |
| **Authorization** | ✅ | Spring Cloud Gateway 기본 전파 |
| X-User-ID, X-Agent-ID | ✅ | 전파 |

**필수 헤더**: `/api/synapse/**` 호출 시 `X-Tenant-ID` 필수 (누락 시 400 Bad Request)

---

## 4. 연동 테스트 권장

1. **Agent Tool API**
   ```bash
   curl -X GET "http://localhost:8080/api/synapse/agent-tools/cases/85114" \
     -H "X-Tenant-ID: 1" \
     -H "Authorization: Bearer <JWT>"
   ```

2. **agent/events**
   ```bash
   curl -X POST "http://localhost:8080/api/synapse/agent/events" \
     -H "Content-Type: application/json" \
     -H "X-Tenant-ID: 1" \
     -d '{"events":[{"tenantId":"1","timestamp":"2026-02-06T12:00:00Z","stage":"SCAN","message":"테스트"}]}'
   ```

---

## 5. 관련 문서

- `docs/integration/SYNAPSE_API_500_VERIFICATION_RESULT.md` — 500 오류 확인 결과
- `docs/integration/AURA_AGENT_STREAM_CONFIRMATION.md` — Agent Stream REST push 확인
- `services/synapsex-service/docs/20260203/AGENT_TOOL_API_SPEC.md` — Agent Tool API 명세
