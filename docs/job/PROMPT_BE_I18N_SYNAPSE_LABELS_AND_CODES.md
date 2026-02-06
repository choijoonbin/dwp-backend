# [백엔드 가이드] Synapse 화면 라벨/코드 다국어(ko/en) 동기화

> **목적**: FE에서 i18n 적용한 라벨·코드와 동일하게 BE에서 반영하여, Accept-Language 기반 API 응답 시 한/영 일관성 확보

---

## 1. FE 변경 요약 및 작업 현황

### 1.1 FE i18n 적용 완료 (화면 내 문자열 → common.json)

| 화면 | menuKey | namespace | 상태 |
|------|---------|-----------|------|
| 통합 관제 센터 (Dashboard) | menu.command-center | common.dashboard | ✅ 완료 |
| 조치 실행 센터 (Actions) | menu.autonomous-operations.actions | common.actions | ✅ 완료 |
| 조치 이력 보관함 (Archive) | menu.autonomous-operations.archive | common.archive | ✅ 완료 |
| 채권·채무 최적화 (Optimization) | menu.autonomous-operations.optimization | common.optimization | ✅ 완료 |
| 케이스 작업함 (Cases) | menu.autonomous-operations.cases | common.cases | ✅ 완료 (일부) |
| 공통 fallback | - | common.statusLabels, severityLabels, commonLabels | ✅ 완료 |

### 1.2 FE i18n 미적용 (하드코딩 한글 잔존)

| 화면 | menuKey | 비고 |
|------|---------|------|
| 전표 조회 (Documents) | menu.master-data-history.documents | ✅ 완료 (제목, 부제목, 테이블, 필터, KPI, 페이지네이션) |
| 미결제 항목 (Open Items) | menu.master-data-history.open-items | ✅ 완료 (제목, 부제목, 테이블, 상태 칩) |
| 거래처 허브 (Entities) | menu.master-data-history.entities | ✅ 완료 (제목, 부제목, 테이블, 필터, useCodes) |
| 계보·근거 뷰어 (Lineage) | menu.master-data-history.lineage | 탭, 섹션 제목 |
| 규정·문서 라이브러리 (RAG) | menu.knowledge-policy.rag | 제목, 버튼, 모달 |
| 정책 프로파일 (Policies) | menu.knowledge-policy.policies | 테이블, 필터 |
| 조치 가드레일 (Guardrails) | menu.knowledge-policy.guardrails | 제목, 평가 패널 |
| 용어·코드 사전 (Dictionary) | menu.knowledge-policy.dictionary | 테이블, 필터 |
| 피드백·라벨링 (Feedback) | menu.knowledge-policy.feedback | 제목, 폼 |
| 정합성 대사 리포트 (Reconciliation) | menu.reconciliation-audit.reconciliation | 제목, 모달, 버튼 |
| 조치 결과 대사 (Action Recon) | menu.reconciliation-audit.action-recon | 제목, 테이블 |
| 감사 추적 로그 (Audit) | menu.reconciliation-audit.audit | 테이블, 필터 |
| 효과·성과 분석 (Analytics) | menu.reconciliation-audit.analytics | 차트, KPI |
| 자율성·통제 설정 (Governance) | menu.governance-config.governance | 제목, 카드 |
| 에이전트 구성 관리 (Agent Config) | menu.governance-config.agent-config | 제목, 폼 |
| 연동·데이터 운영 (Integrations) | menu.governance-config.integrations | 제목, 카드 |
| 시스템 관리 (Admin) | menu.governance-config.admin | 탭, 테이블, Tenant Scope, PII |

### 1.3 FE 번역 키 구조 (적용 완료 화면 기준)

```json
{
  "actions": {
    "title": "조치 실행 센터",
    "actionTypes": {
      "post_reversal": "전기 반전",
      "block_payment": "결제 차단",
      "flag_review": "검토 요청",
      "clear_item": "항목 정리",
      "update_master": "마스터 데이터 업데이트"
    },
    "status": { "pending": "대기", "approved": "승인됨", "rejected": "거절됨", "executed": "실행됨", "failed": "실패", "completed": "완료" }
  },
  "archive": {
    "title": "조치 이력 보관함",
    "status": { "completed": "완료", "executed": "실행됨", "failed": "실패", "pending": "대기" }
  },
  "optimization": {
    "title": "채권·채무 최적화",
    "subtitle": "미결제 항목 우선순위화, 가드레일 적용..."
  },
  "statusLabels": {
    "open": "오픈", "in_progress": "진행 중", "pending_approval": "승인 대기",
    "resolved": "해결됨", "approved": "승인됨", "rejected": "거절됨", "executed": "실행됨",
    "failed": "실패", "completed": "완료", "triage": "트리아지", "review": "검토"
  },
  "severityLabels": {
    "critical": "긴급", "high": "높음", "medium": "보통", "low": "낮음"
  }
}
```

---

## 2. BE 반영 필수 사항

### 2.1 sys_codes (코드 그룹별 name_ko / name_en)

FE는 `GET /api/admin/codes?groupKey=XXX`로 코드를 조회하며, Accept-Language 헤더에 따라 `name`(또는 `name_ko`/`name_en`)을 반환해야 합니다.

| groupKey | codeKey | name_ko (또는 ko) | name_en (또는 en) |
|----------|---------|-------------------|-------------------|
| **ACTION_TYPE** | POST_REVERSAL | 전기 반전 | Post Reversal |
| | BLOCK_PAYMENT | 결제 차단 | Block Payment |
| | FLAG_REVIEW | 검토 요청 | Flag for Review |
| | CLEAR_ITEM | 항목 정리 | Clear Item |
| | UPDATE_MASTER | 마스터 데이터 업데이트 | Update Master Data |
| **CASE_STATUS** | OPEN | 오픈 | Open |
| | IN_PROGRESS | 진행 중 | In Progress |
| | PENDING_APPROVAL | 승인 대기 | Pending Approval |
| | PENDING | 대기 | Pending |
| | RESOLVED | 해결됨 | Resolved |
| | APPROVED | 승인됨 | Approved |
| | REJECTED | 거절됨 | Rejected |
| | EXECUTED | 실행됨 | Executed |
| | FAILED | 실패 | Failed |
| | COMPLETED | 완료 | Completed |
| | TRIAGE | 트리아지 | Triage |
| | REVIEW | 검토 | Review |
| **SEVERITY** | CRITICAL | 긴급 | Critical |
| | HIGH | 높음 | High |
| | MEDIUM | 보통 | Medium |
| | LOW | 낮음 | Low |
| **CASE_TYPE** | (기존 유지) | (기존) | (기존) |
| **ENTITY_TYPE** | (선택) | VENDOR→공급업체, CUSTOMER→고객 | Vendor, Customer |
| **COUNTRY** | (선택) | KOR→대한민국, USA→미국 등 ISO 3166-1 alpha-3 | (영문 국가명) |

- **API**: `GET /api/admin/codes?groupKey=ACTION_TYPE` (및 CASE_STATUS, SEVERITY, ENTITY_TYPE, COUNTRY 등)
- **Response**: Accept-Language=ko → `name` 또는 `name_ko` 사용, Accept-Language=en → `name_en` 사용

### 2.2 menus/tree (메뉴명 다국어)

`GET /api/auth/menus/tree` 응답의 `menuName` 필드는 Accept-Language 헤더에 따라 한/영으로 반환되어야 합니다.

**FE는 menus/tree의 menuName을 그대로 표시합니다. 하드코딩된 fallback 없음.**

#### 2.2.1 통합 관제 센터 / 자율 운영 센터

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.command-center | 통합 관제 센터 | Integrated Control Center |
| menu.autonomous-operations | 자율 운영 센터 | Autonomous Operations |
| menu.autonomous-operations.cases | 케이스 작업함 | Cases |
| menu.autonomous-operations.anomalies | 이상 징후 탐지 | Anomaly Detection |
| menu.autonomous-operations.optimization | 채권·채무 최적화 | AR/AP Optimization |
| menu.autonomous-operations.actions | 조치 실행 센터 | Action Execution Center |
| menu.autonomous-operations.archive | 조치 이력 보관함 | Action Archive |

#### 2.2.2 원천 데이터·이력 허브 (Master Data & History)

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.master-data-history | 원천 데이터·이력 허브 | Master Data & History |
| menu.master-data-history.documents | 전표 조회 | Documents |
| menu.master-data-history.open-items | 미결제 항목 | Open Items |
| menu.master-data-history.entities | 거래처 허브 | Entities |
| menu.master-data-history.lineage | 계보·근거 뷰어 | Lineage |

#### 2.2.3 지식·정책 허브 (Knowledge & Policy)

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.knowledge-policy | 지식·정책 허브 | Knowledge & Policy |
| menu.knowledge-policy.rag | 규정·문서 라이브러리 | RAG |
| menu.knowledge-policy.policies | 정책 프로파일 | Policies |
| menu.knowledge-policy.guardrails | 조치 가드레일 | Guardrails |
| menu.knowledge-policy.dictionary | 용어·코드 사전 | Dictionary |
| menu.knowledge-policy.feedback | 피드백·라벨링 | Feedback |

#### 2.2.4 대사·감사 센터 (Reconciliation & Audit)

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.reconciliation-audit | 대사·감사 센터 | Reconciliation & Audit |
| menu.reconciliation-audit.reconciliation | 정합성 대사 리포트 | Reconciliation |
| menu.reconciliation-audit.action-recon | 조치 결과 대사 | Action Reconciliation |
| menu.reconciliation-audit.audit | 감사 추적 로그 | Audit |
| menu.reconciliation-audit.analytics | 효과·성과 분석 | Analytics |

#### 2.2.5 거버넌스·설정 (Governance & Config)

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.governance-config | 거버넌스·설정 | Governance & Config |
| menu.governance-config.governance | 자율성·통제 설정 | Governance |
| menu.governance-config.agent-config | 에이전트 구성 관리 | Agent Config |
| menu.governance-config.integrations | 연동·데이터 운영 | Integrations |
| menu.governance-config.admin | 시스템 관리 | Admin |

#### 2.2.6 Admin (DWP 통합 Admin)

| menuKey | menuName (ko) | menuName (en) |
|---------|---------------|---------------|
| menu.admin | Admin | Admin |
| menu.admin.monitoring | 통합 모니터링 | Monitoring |
| menu.admin.users | 사용자 관리 | Users |
| menu.admin.roles | 역할 관리 | Roles |
| menu.admin.resources | 리소스 관리 | Resources |
| menu.admin.audit | 감사 로그 | Audit Logs |
| menu.admin.menus | 메뉴 관리 | Menus |
| menu.admin.codes | 코드 관리 | Codes |
| menu.admin.code-usages | 코드 사용정의 | Code Usages |

- **API**: `GET /api/auth/menus/tree`
- **Headers**: `Accept-Language: ko` 또는 `Accept-Language: en`
- **Response**: `menuName`이 요청 언어에 맞게 반환

### 2.3 codes API 호출 형식 (확인)

FE는 `GET /api/admin/codes?groupKey=CASE_TYPE` 형식으로 호출합니다. (path 파라미터 ` /api/admin/codes/CASE_TYPE` 아님)

### 2.4 화면별 BE 반영 필요 데이터 요약

| 화면 | BE API | 반환 필드 | 비고 |
|------|--------|-----------|------|
| **사이드바 전체** | `GET /api/auth/menus/tree` | `menuName` | §2.2 표 전체. Accept-Language 기반 |
| **Cases, Dashboard** | `GET /api/admin/codes?groupKey=CASE_TYPE` | `name` (또는 name_ko/name_en) | useCodes('CASE_TYPE') |
| **Cases, StatusPill** | `GET /api/admin/codes?groupKey=CASE_STATUS` | `name` | useCodes('CASE_STATUS') |
| **Cases, SeverityBadge** | `GET /api/admin/codes?groupKey=SEVERITY` | `name` | useCodes('SEVERITY') |
| **Actions, Archive** | `GET /api/admin/codes?groupKey=ACTION_TYPE` | `name` | useCodes('ACTION_TYPE') |
| **Entities** | `GET /api/admin/codes?groupKey=ENTITY_TYPE` (선택) | VENDOR, CUSTOMER 등 | FE 현재 하드코딩. BE 제공 시 useCodes로 전환 |
| **Entities** | `GET /api/admin/codes?groupKey=COUNTRY` (선택) | KOR, USA 등 ISO 3166-1 | FE 현재 하드코딩. BE 제공 시 useCodes로 전환 |
| **Documents, 기타** | 해당 도메인 API | 문서 유형, 상태 등 라벨 | Accept-Language 헤더 반영 권장 |

---

## 3. BE 작업 체크리스트

### 3.1 sys_codes
- [ ] `ACTION_TYPE` 코드 그룹: POST_REVERSAL, BLOCK_PAYMENT, FLAG_REVIEW, CLEAR_ITEM, UPDATE_MASTER (name_ko, name_en)
- [ ] `CASE_STATUS` 코드 그룹: 위 표에 맞게 name_ko, name_en
- [ ] `SEVERITY` 코드 그룹: CRITICAL, HIGH, MEDIUM, LOW (name_ko, name_en)
- [ ] `GET /api/admin/codes?groupKey=XXX` 응답 시 Accept-Language 기반 name 반환

### 3.2 menus/tree (전체 메뉴·하위 메뉴)
- [ ] `GET /api/auth/menus/tree` 응답 시 Accept-Language 기반 menuName 반환
- [ ] §2.2.1 ~ §2.2.6 표의 **모든 menuKey**에 대해 menuName_ko, menuName_en (또는 동등 필드) 저장
- [ ] 원천데이터이력허브·지식정책허브·대사감사센터·거버넌스설정 4개 및 그 하위 메뉴 전부 포함

### 3.3 화면별 API (선택·권장)
- [ ] ENTITY_TYPE, COUNTRY 등 codes 제공 시 FE에서 useCodes로 전환 가능
- [ ] 도메인 API(문서, 거래처, 감사 등) 응답 라벨에 Accept-Language 반영 권장

---

## 4. 백엔드 전달용 요약 (Return 체크리스트)

BE 작업 완료 후 아래를 확인하여 FE에 반환해 주세요.

| 구분 | 항목 | 완료 시 |
|------|------|---------|
| **menus/tree** | §2.2 전체 menuKey에 menuName_ko, menuName_en 저장 | `Accept-Language: ko` → 한글, `en` → 영문 menuName 반환 |
| **sys_codes** | ACTION_TYPE, CASE_STATUS, SEVERITY (필수) | `GET /api/admin/codes?groupKey=XXX` 응답 시 Accept-Language 기반 name 반환 |
| **sys_codes** | ENTITY_TYPE, COUNTRY (선택) | Entities 화면 필터 라벨 BE 제공 시 FE 전환 |
| **동작 확인** | 언어 전환 후 사이드바·코드 라벨 변경 | FE에서 언어 토글 시 즉시 반영 |

---

## 5. FE-BE 동기화 원칙

- FE `common.json`의 `actions.actionTypes.*`, `archive.status.*` 등은 **sys_codes가 없을 때 fallback**으로 사용됨.
- BE에 sys_codes가 정상 반환되면 FE의 `useCodes(groupKey)`가 해당 label을 우선 사용.
- 메뉴명은 **BE가 단일 소스**이므로, FE는 menus/tree의 menuName을 그대로 표시.
- 언어 전환 시 `Accept-Language` 헤더가 변경되며, BE는 이에 맞춰 응답해야 함.
