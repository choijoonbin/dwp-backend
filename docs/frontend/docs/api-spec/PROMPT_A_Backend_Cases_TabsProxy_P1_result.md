# PROMPT_A_Backend_Cases_TabsProxy_P1 — 작업 결과

> 기준: docs/job/PROMPT_A_Backend_Cases_TabsProxy_P1_v2.txt

---

## 1) 완료 항목

### Case Detail 탭 API (Aura 프록시)

| Method | Path | 설명 |
|--------|------|------|
| GET | /api/synapse/cases/{caseId}/analysis | Case analysis |
| GET | /api/synapse/cases/{caseId}/confidence | Case confidence breakdown |
| GET | /api/synapse/cases/{caseId}/similar | Similar cases |
| GET | /api/synapse/cases/{caseId}/rag/evidence | RAG evidence list |

- **프록시**: BE → Aura `/aura/cases/{caseId}/*`
- **헤더 전달**: X-Tenant-ID, Authorization, X-User-ID
- **Audit**: CASE_VIEW_ANALYSIS, CASE_VIEW_CONFIDENCE, CASE_VIEW_SIMILAR, CASE_VIEW_RAG

### Error Contract

| Aura | BE |
|------|-----|
| 404 | 404 |
| 401/403 | 401/403 |
| 5xx | 502 |
| timeout | 504 |

---

## 2) 변경 파일

- `client/AuraCaseTabClient.java` — FeignClient
- `config/AuraClientConfig.java` — Retryer
- `service/case_/CaseTabProxyService.java` — 프록시 + 에러 처리
- `controller/CaseController.java` — 4개 endpoint
- `audit/AuditEventConstants.java` — 탭 audit 타입
- `application.yml` — aura.base-url, feign config

---

## 3) 환경 변수

- `AURA_BASE_URL` 또는 `AURA_PLATFORM_URL` (기본: http://localhost:9000)

---

## 4) 검증 curl

```bash
curl -H "Authorization: Bearer <TOKEN>" -H "X-Tenant-ID: 1" \
  "http://localhost:8080/api/synapse/cases/1/analysis"

curl -H "Authorization: Bearer <TOKEN>" -H "X-Tenant-ID: 1" \
  "http://localhost:8080/api/synapse/cases/1/confidence"

curl -H "Authorization: Bearer <TOKEN>" -H "X-Tenant-ID: 1" \
  "http://localhost:8080/api/synapse/cases/1/similar"

curl -H "Authorization: Bearer <TOKEN>" -H "X-Tenant-ID: 1" \
  "http://localhost:8080/api/synapse/cases/1/rag/evidence"
```

※ caseId는 agent_case.case_id (Long, 예: 1)
