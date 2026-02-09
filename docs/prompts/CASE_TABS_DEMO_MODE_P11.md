# CASE 탭 DEMO 모드 P1.1

> 기준: docs/frontend/docs/api-spec/PROMPT_BE_CASE_TABS_DEMO_MODE_P11.txt

---

## 0) 결론/결정안

- **SYNAPSE_DEMO_MODE** 플래그 기반 Non-Empty 샘플 응답
- DEMO OFF 시 기존 로직(200+빈/fallback) 유지 — 원복 리스크 0
- 기본값 OFF, 프로퍼티/환경변수로만 ON

---

## 1) 구현 범위

### IN

| 플래그 | 동작 |
|--------|------|
| SYNAPSE_DEMO_MODE=true | 200 + Non-Empty 샘플 JSON |
| SYNAPSE_DEMO_MODE=false (기본) | 기존 Aura 호출 + 빈 fallback |

### OUT

- SSE 프록시/스트림

---

## 2) 샘플 응답 구조

- analysis: summary, keyFindings, recommendations
- confidence: score, severity, factors
- similar: items (caseId, score, title)
- rag/evidence: items (title, source, excerpt, url, relevance)

---

## 3) 체크리스트

- [ ] 기본값 OFF
- [ ] DEMO ON에서만 Non-Empty
- [ ] tenant/caseId 불일치 시 404
- [ ] 표준 에러 포맷 준수
