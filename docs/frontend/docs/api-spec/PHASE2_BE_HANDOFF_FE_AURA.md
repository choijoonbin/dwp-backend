# Phase2 — BE → FE/Aura 전달 사항

> 최근 BE 변경사항 중 FE/Aura 팀에 전달할 내용 정리

---

## 1. FE 전달 사항

### 1.1 evidenceSnapshot (POST analysis-runs body)

**변경**: BE에서 `evidenceSnapshot` 필드 수용 완료

- **요청**: `POST /api/synapse/cases/{caseId}/analysis-runs` body에 `evidenceSnapshot` (JSON) 포함 가능
- **동작**: FE가 보내면 그대로 Aura에 전달. 없으면 BE가 `agent_case` DB에서 조회해 전달
- **구조**: `useCaseDetail().evidence` 구조 그대로 전달 가능 (별도 스키마 제약 없음)
- **선택**: 필수 아님. FE가 최신 증적을 넘기고 싶을 때만 사용

```json
// 예시
{
  "mode": "LIVE",
  "requestedBy": "HUMAN",
  "evidenceSnapshot": { "evidence": [...], "ragRefs": [...] }
}
```

---

### 1.2 requiresApproval (action-proposals 응답)

**변경**: `requiresApproval` 필드 추가됨

- **응답**: `GET /api/synapse/cases/{caseId}/action-proposals` 및 `GET .../analysis?runId=`
- **필드**: `proposals[].requiresApproval` (Boolean, nullable)
- **용도**: FE 승인 플로우에서 “승인 필요 여부” 판단에 사용
- **출처**: Aura가 콜백 시 `proposals[].requiresApproval`로 전달 → BE 저장 → FE 응답에 포함

```json
{
  "proposals": [
    {
      "proposalId": "...",
      "type": "HOLD_PAYMENT",
      "requiresApproval": true,
      ...
    }
  ]
}
```

---

### 1.3 streamUrl 사용 원칙

- **응답의 `streamUrl`을 그대로 사용** (하드코딩 금지)
- demoMode: `/api/synapse/analysis-runs/{runId}/stream`
- Aura 연동 시: `/aura/analysis-runs/{runId}/stream` (Aura가 반환)
- FE: `{NX_API_URL}` + 응답의 `streamUrl` 사용

---

## 2. Aura 전달 사항

### 2.1 evidence (트리거 요청 body)

**변경**: BE가 `evidence`를 이제 전달함

- **구성**: `{ evidence: agent_case.evidence_json, ragRefs: agent_case.rag_refs_json }`
- **FE 연동**: FE가 `evidenceSnapshot`을 보내면 그 값을 우선 사용
- **null**: evidence·ragRefs 모두 없으면 `null` 전달

---

### 2.2 proposals.requiresApproval (콜백)

**변경**: BE에서 `requiresApproval` 수신·저장·FE 응답 반영 완료

- **수신**: `finalResult.proposals[].requiresApproval` (Boolean)
- **저장**: `case_action_proposal.requires_approval`
- **노출**: FE `action-proposals` / `analysis` 응답에 포함
- **호환**: Jackson이 extra 필드를 무시하므로, Aura가 기존처럼 보내도 동작에 문제 없음

---

### 2.3 dedup_key (rationale normalize)

**변경**: BE `ProposalDedupKeyUtil`에서 rationale에 `toLowerCase` 적용

- **영향**: rationale 대소문자만 다른 경우에도 동일 dedup_key → UNIQUE로 1건만 유지
- **Aura**: 별도 변경 불필요. BE 멱등 처리만 강화됨

---

## 3. 요약 체크리스트

| 수신 | 항목 | 전달 내용 |
|------|------|-----------|
| FE | evidenceSnapshot | POST body에 선택적 포함 가능 |
| FE | requiresApproval | proposals 응답에 포함됨 |
| FE | streamUrl | 응답 값 그대로 사용 |
| Aura | evidence | BE가 이제 전달함 |
| Aura | requiresApproval | 콜백에서 수신·저장 완료 |
| Aura | dedup_key | rationale toLowerCase 적용 (BE 처리) |

---

*작성: BE 팀 | 2026-02*
