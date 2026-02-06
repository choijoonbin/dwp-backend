# PHASE Cases Backend Tabs Proxy P1

> 기준: docs/job/PROMPT_A_Backend_Cases_TabsProxy_P1_v2.txt

---

## 0) 결론/결정안

- **BE proxy 확정**: FE 호출 경로를 `/api/synapse/**` 하나로 고정
- Aura(`/aura/cases/{id}/*`)를 BE에서 프록시하여 반환
- Tenant Scope(X-Tenant-ID), Authorization, X-User-ID BE에서 일원화
- **Audit**: 옵션 A — 탭 조회도 READ audit (CASE_VIEW_ANALYSIS, CASE_VIEW_CONFIDENCE, CASE_VIEW_SIMILAR, CASE_VIEW_RAG)

---

## 1) 구현 범위

### IN

| Method | Path | 설명 |
|--------|------|------|
| GET | /api/synapse/cases/{caseId}/analysis | Case analysis |
| GET | /api/synapse/cases/{caseId}/confidence | Case confidence breakdown |
| GET | /api/synapse/cases/{caseId}/similar | Similar cases |
| GET | /api/synapse/cases/{caseId}/rag/evidence | RAG evidence list |

### OUT

- Aura trigger endpoint BE 노출 (2차)
- stream/meta (선택, P2)

---

## 2) AuraClient

- **baseUrl**: `aura.base-url` (환경변수 `AURA_BASE_URL`, 기본 `http://localhost:9000`)
- **헤더 전달**: X-Tenant-ID, Authorization, X-User-ID
- **timeout**: connect 3s, read 5s
- **retry**: 1회
- **circuit breaker**: 가능 시 (P2)

---

## 3) Error Contract

| Aura 응답 | BE 응답 |
|-----------|---------|
| 404 | 404 passthrough |
| 401/403 | 401/403 passthrough |
| 5xx | 502 |
| timeout | 504 |

---

## 4) Audit event_type

- CASE_VIEW_ANALYSIS
- CASE_VIEW_CONFIDENCE
- CASE_VIEW_SIMILAR
- CASE_VIEW_RAG

---

## 5) 구현 완료 (변경 파일)

| 경로 | 내용 |
|------|------|
| `AuraCaseTabClient.java` | FeignClient — /aura/cases/{id}/analysis, confidence, similar, rag/evidence |
| `AuraClientConfig.java` | Retryer (1회), timeout은 application.yml |
| `CaseTabProxyService.java` | Aura 호출 + 에러 변환 (404/401/403/502/504) |
| `CaseController.java` | 4개 탭 endpoint + audit |
| `AuditEventConstants.java` | TYPE_VIEW_ANALYSIS, CONFIDENCE, SIMILAR, RAG |
| `application.yml` | aura.base-url, feign.client.config.aura-case-tab |
