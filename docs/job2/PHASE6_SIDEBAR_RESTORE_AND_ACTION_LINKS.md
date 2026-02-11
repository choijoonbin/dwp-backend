# Phase 6: 사이드바 복원 및 action_links 설계

## 1. Flyway V42 (auth-server)

### 1.1 목적

- V38에서 GNB 비노출 처리한 **anomalies, cases, actions** 메뉴를 다시 사이드바에 노출 (`is_visible = 'Y'`).
- Synapse 하위 메뉴의 **menu_path**를 FE 라우팅 규칙(`/synapse/*`)에 맞게 정리.

### 1.2 SQL 요약

| 구분 | 내용 |
|------|------|
| **1. is_visible 복원** | `menu.autonomous-operations.anomalies`, `.cases`, `.actions` → `is_visible = 'Y'` |
| **2. 자율 운영 센터 하위** | cases → `/synapse/cases`, anomalies → `/synapse/anomalies`, optimization → `/synapse/optimization`, actions → `/synapse/actions`, archive → `/synapse/archive` |
| **3. 지식·정책 허브 하위** | rag → `/synapse/rag`, policies → `/synapse/policies`, guardrails → `/synapse/guardrails`, dictionary → `/synapse/dictionary`, feedback → `/synapse/feedback` |
| **4. 원천 데이터·이력** | documents, open-items, entities, lineage → `/synapse/documents`, `/synapse/open-items`, `/synapse/entities`, `/synapse/lineage` |
| **5. 대사·감사 센터** | reconciliation, action-recon, audit, analytics → `/synapse/reconciliation`, `/synapse/action-recon`, `/synapse/audit`, `/synapse/analytics` |
| **6. 거버넌스·설정** | governance, agent-config, integrations, admin → `/synapse/governance`, `/synapse/agent-config`, `/synapse/integrations`, `/synapse/admin` |

- **파일**: `dwp-auth-server/src/main/resources/db/migration/V42__restore_sidebar_menus_and_synapse_paths.sql`

### 1.3 com_resources 연동

- 권한/라우트 메타는 **com_resources.metadata_json** 의 `route` 에도 보관됨.
- V42는 **sys_menus** 만 갱신함. FE가 메뉴 트리 조회 시 **sys_menus.menu_path** 를 사용하므로 즉시 반영됨.
- 필요 시 추후 마이그레이션에서 `com_resources` 의 `metadata_json->>'route'` 를 위 menu_path 와 동일하게 맞추는 UPDATE 를 추가할 수 있음.

---

## 2. action_links (Workbench 케이스 상세 연동)

### 2.1 목적

- 워크벤치 케이스 상세에서, 해당 케이스와 연관된 **지식(RAG)**·**정책** 메뉴로 바로 이동할 수 있는 링크 제공.

### 2.2 DTO 규격

**WorkbenchCaseDetailResponseDto** 에 필드 추가:

```java
/** 지식(RAG)·정책 등 관련 메뉴로 바로 이동용 링크 */
private List<ActionLinkDto> actionLinks;
```

**ActionLinkDto**:

| 필드 | 타입 | 설명 |
|------|------|------|
| label | String | 표시 라벨 (예: 규정·문서 라이브러리, 정책 프로파일) |
| deepLink | String | 프론트 라우트 경로 (예: /synapse/rag, /synapse/policies) |
| type | String | 링크 유형: RAG, POLICY (FE 배지/아이콘용) |
| queryParams | String | 선택. 케이스 컨텍스트 (예: caseId=123) — FE에서 해당 케이스 관련 문서/정책 강조용 |

### 2.3 응답 예시

**GET** `/api/v1/synapse/workbench/cases/{caseId}` 응답에 포함:

```json
{
  "data": {
    "case_": { ... },
    "latestAnalysis": { ... },
    "timeline": [ ... ],
    "actionLinks": [
      { "label": "규정·문서 라이브러리", "deepLink": "/synapse/rag", "type": "RAG", "queryParams": "caseId=123" },
      { "label": "정책 프로파일", "deepLink": "/synapse/policies", "type": "POLICY", "queryParams": "caseId=123" }
    ]
  }
}
```

### 2.4 구현 위치

- **ActionLinkDto**: `services/synapsex-service/.../dto/workbench/ActionLinkDto.java`
- **WorkbenchCaseDetailResponseDto**: `actionLinks` 필드 추가
- **WorkbenchQueryService.getCaseDetailWithTimeline**: `buildActionLinksForCase(caseId)` 로 목록 생성 후 응답에 설정.  
  - deepLink 는 V42 의 **sys_menus.menu_path** 와 동일 (`/synapse/rag`, `/synapse/policies`).

---

## 3. 요약

| 항목 | 내용 |
|------|------|
| V42 | anomalies/cases/actions is_visible='Y' 복원, Synapse 하위 메뉴 menu_path → /synapse/* 정리 |
| action_links | 케이스 상세 응답에 RAG/정책 deepLink + queryParams(caseId) 포함, Resource(메뉴) 경로와 일치 |
