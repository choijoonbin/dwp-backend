# Customer Policy and Release Gate Register

Status: Active single source of truth

Last verified: 2026-08-20

Scope: DWP 고객 딜리버리 의사결정, 환경별 운영 준비 Gate, 외부 시스템 연동,
보안·개인정보·법무 승인과 아직 닫히지 않은 구현 TODO

## 1. 관리 원칙

이 문서는 고객 또는 운영 환경의 결정이 필요한 항목을 관리하는 유일한 활성 등록부다.
다른 ADR, 기능 문서와 수용 테스트는 이 문서의 ID만 참조하고 별도 TODO 목록을 만들지
않는다. 과거 조사 문서의 표는 역사적 Snapshot이며 현재 상태 판정의 근거로 사용하지
않는다.

- 고객별 값은 코드나 Seed에 고정하지 않고 `tenant + environment + gate key`로 관리한다.
- 설정값, 증빙, 검증, 승인과 만료를 분리하고 모든 변경에 버전과 사유를 요구한다.
- DB에는 Secret 원문, Key Material, Token, Password를 저장하지 않는다. 승인된 Secret/KMS
  참조, 외부 증빙 URI와 선택적 SHA-256만 저장한다.
- 기능 노출용 Feature Flag와 운영 허가용 Release Gate를 혼용하지 않는다. Feature Flag가
  켜져 있어도 필수 Gate가 승인되지 않으면 운영 기능은 실패 차단한다.
- `DEVELOPMENT`, `STAGING`, `PRODUCTION` 결정과 증빙은 서로 승계하지 않는다.
- 구성 리비전이 바뀌면 과거 증빙은 감사 이력으로 남지만 새 검증의 근거로 재사용하지 않는다.
- 구성자 또는 검증자는 같은 Gate의 승인자가 될 수 없다. 승인 만료 뒤에는 재구성·재검증한다.

## 2. 상태 모델

### 2.1 구현 상태

| 상태 | 의미 |
| --- | --- |
| `IMPLEMENTED` | 코드, 저장, 권한, 테스트와 UI 기준선이 존재한다. |
| `PARTIAL` | 안전한 내부 기준선은 있으나 고객 Adapter 또는 운영 자동 검증이 남았다. |
| `EXTERNAL_GATE` | 고객·Provider·법무·보안 결정과 실제 환경 증빙 없이는 닫을 수 없다. |
| `PLANNED` | 설계만 승인됐고 구현이 시작되지 않았다. |

### 2.2 실행 상태

운영 화면은 `NOT_CONFIGURED → CONFIGURING → READY_FOR_APPROVAL → APPROVED`를 기본
흐름으로 사용한다. 검증 실패는 `BLOCKED`, 승인 유효기간 종료는 `EXPIRED`로 표시한다.
낙관적 버전 충돌은 `409`, 저장소 장애는 `503`, 권한 부족은 `403`으로 실패한다.

## 3. 제품·딜리버리 결정 등록부

| ID | 상태 | 결정 또는 계약 범위 | 종료 증거 |
| --- | --- | --- | --- |
| `D-01` | `EXTERNAL_GATE` | Entra/Okta 우선순위, 도메인 소유 검증, MFA·복구, JIT/SCIM, Break-glass | 위협 모델, Sandbox 로그인·프로비저닝·회수·복구 E2E |
| `D-02` | `PARTIAL` | 운영 모델, 데이터 위치, DLP, Prompt/Response 보존, Tool 승인 | TEVV·Red-team, 품질·비용·지연 Gate, Incident·Rollback |
| `D-03` | `PARTIAL` | 외부 지식 Connector, Index, Query-time ACL, Freshness, 삭제·감사 SLA | 권한 누출·삭제·Stale Citation·부분 장애 회귀 시험 |
| `D-04` | `EXTERNAL_GATE` | 조직 Scenario 참여 ACL, 다중 편집, Bulk 한도, HCM 실행 계약 | Schema/API ADR, 충돌·승인·Rollback E2E |
| `D-05` | `EXTERNAL_GATE` | Skill Ontology 소유자, 출처, 숙련도, 갱신 주기, 민감도 | 실제 인터페이스와 데이터 품질·개인정보 승인 |
| `D-06` | `PARTIAL` | Team View 역할, 퇴직자 소유권 이관과 고객별 보존 | Team 권한·이관·삭제·감사 E2E |
| `D-07` | `PARTIAL` | Broker·Partition·보존, Producer 온보딩, Replay·DLQ 운영 | 순서 역전·중복·Replay·장애 복구 시험 |
| `D-08` | `EXTERNAL_GATE` | Microsoft/Google·결재 원천, OAuth Scope, Webhook·동기화 SLA | 실제 Sandbox와 Partial Failure·삭제 E2E |
| `D-09` | `EXTERNAL_GATE` | 조직도 Export·Print 역할, Masking, Watermark, 만료·수신자 | 보안 검토와 다운로드·재배포 통제 시험 |
| `D-10` | `PARTIAL` | CI Browser/OS Matrix와 수동 Screen Reader 승인 | 자동 Visual 기준선과 수동 접근성 증거 |
| `D-11` | `EXTERNAL_GATE` | HRIS Mapping, Delta/Full, 재처리·정합성 Owner와 SLA | 고객 Sandbox Dry Run과 오류 복구·감사 |
| `D-12` | `PARTIAL` | 비동기 Export Worker, KMS Object Storage, WORM·만료·취소 | 장애·재시도·권한·무결성·만료 E2E |
| `D-13` | `PARTIAL` | 배포 실행기, Idempotency·Compensation, 통지 채널 | 장애 주입 Rollback·중복 방지·통지 감사 |
| `D-14` | `PARTIAL` | Tenant 미디어 Storage·KMS·Versioning, Malware Scan, CDN·보존 | 장애·복구·Purge·Legal Hold·감사 E2E |
| `D-15` | `PARTIAL` | 공지 승인 분리, 긴급 채널, 수신 확인, 지역별 보존 | Sandbox 채널·중복 방지·확인·재처리 E2E |
| `D-16` | `PARTIAL` | 외부 IAM Mapping, Credential, Drift Reconciliation SLA | Sandbox 요청→승인→부여→회수·복구·감사 E2E |
| `D-17` | `PARTIAL` | 외부 Catalog/GitOps Adapter, Owner, Schema, 삭제·충돌 | 실제 원천 Drift·삭제·충돌·복구·감사 E2E |
| `D-18` | `EXTERNAL_GATE` | 전자서명 Provider, 지역별 효력, 직인, 인증서, KMS·WORM | Provider Sandbox, 법무, Webhook·Hash·보존 E2E |

## 4. DWAI·ON 고객 운영 Gate

아래 13개 운영 전환 검증 항목은 DWAI·ON의 **운영 전환 검증**
(`/dwaion/admin/gates`)에서 환경별로 정책 설정·증빙 등록·검증 기록·독립 승인한다.
표의 기본안은 신규 고객에게 제안하는 운영 기준이며 고객 계약과 규제에 따라 승인된
대안을 선택할 수 있다.

| ID / Key | 기본안 | 현재 구현 | 고객·운영 종료 조건 |
| --- | --- | --- | --- |
| `G-01 MODEL_CREDENTIALS` | Workload Managed Identity | `PARTIAL` | 운영 Identity, 최소 권한, Credential 회전·폐기와 호출 증거 |
| `G-02 MODEL_LIFECYCLE_CAPACITY` | 고정 GA 모델·명시적 승격 | `PARTIAL` | 모델 버전, 용량·Quota, 비용·지연 SLO와 Rollback 증거 |
| `G-03 NETWORK_ISOLATION` | Private Endpoint | `EXTERNAL_GATE` | DNS·Egress·Firewall·Private 연결과 차단 시험 |
| `G-04 DATA_PROCESSING_LOCATION` | 고객 승인 Region | `EXTERNAL_GATE` | 처리·저장·백업·평가 Region과 법무 승인 |
| `G-05 SOURCE_CONNECTORS` | 원천 우선, 필요 시 Hybrid | `PARTIAL` | 실제 Connector Credential 참조, 동기화·삭제·장애 SLA |
| `G-06 SOURCE_ACL` | Query-time ACL 우선 | `PARTIAL` | 원천 권한 상속, Group Mapping, 회수 지연과 누출 회귀 시험 |
| `G-07 DATA_CLASSIFICATION_DLP` | Strict | `PARTIAL` | 분류 체계, DLP Provider, 차단·예외·감사 시험 |
| `G-08 EVALUATION_DATASET` | 고객 승인 평가셋 | `PARTIAL` | 대표성·개인정보 승인, 품질·근거·안전·Tool 정확도 기준선 |
| `G-09 RELEASE_APPROVAL` | Maker-checker | `IMPLEMENTED` | 독립 승인자, 회귀 임계치, 배포·Rollback 증빙 |
| `G-10 ACTION_APPROVAL` | 위험별 사용자 확인 또는 별도 승인 | `PARTIAL` | Action별 위험등급, Scope, 만료 Token, Compensation E2E |
| `G-11 TENANT_KMS` | Tenant별 Customer-managed Key | `PARTIAL` | Key Provider, Rotation·Rewrap, 삭제 보호, RTO/RPO 증거 |
| `G-12 RETENTION_LEGAL_HOLD` | 고객 보존 Schedule | `PARTIAL` | Artifact별 보존·삭제, Hold 우선순위, 법무 승인·복구 시험 |
| `G-13 AUDIT_RESILIENCE` | DWP 관측성 + 고객 SIEM | `PARTIAL` | 불변 반출, Alert SLO, 장애·Replay·지역 복구 증거 |

## 5. 환경별 권장 Profile

| 영역 | Development | Staging | Production |
| --- | --- | --- | --- |
| 모델 인증 | ignored `.env.local`의 Secret 참조 | Workload Identity 우선 | Workload Identity 필수, 예외 승인 시 Secret Manager 참조 |
| 네트워크 | 명시적 개발 Public 허용 | Restricted Public 또는 Private | Private Endpoint |
| 평가 데이터 | Synthetic, 비식별 | 고객 승인 사본 | 승인된 Dataset + 샘플링된 운영 Trace |
| 암호화 | 버전형 Local Keyring | Managed KMS | Tenant CMK 또는 승인된 Tenant 격리 Key |
| 보존 | DWP 개발 기본값 | 출시 후보 정책 | 고객 Schedule + Legal Hold |
| 관측성 | DWP Local | DWP 통합 | 고객 SIEM + DWP 운영 SLO |

개발 Profile은 운영 승인을 대신하지 않는다. Development에서 `APPROVED`된 Gate를
Production으로 복제하거나 자동 승격하지 않는다.

## 6. 권한과 승인 경계

| 역할 | 허용 범위 |
| --- | --- |
| `DWAION_GOVERNANCE_MANAGER` | Gate 조회·구성·증빙·검증, 승인 불가 |
| `DWAION_EVALUATOR` | Gate와 평가 증거 조회, Gate 변경·승인 불가 |
| `DWAION_AUDITOR` | Gate 조회와 독립 승인, 구성·검증 불가 |
| `DWAION_ADMIN` | 복합 운영 권한을 보유하지만 동일 Gate의 구성자·검증자는 승인 불가 |

권한 판정은 `ADMIN.DWAION_GATES`의 `VIEW`, `CREATE`, `UPDATE`, `APPROVE`, `MANAGE`를
동작별로 사용한다. 기존 aggregate `ADMIN.DWAION`은 이 경로를 허용하지 않는다.

## 7. 구현 증거

| 영역 | 증거 |
| --- | --- |
| Auth Resource·Role Permission | `dwp-auth-server` migration `V78__authorize_dwaion_operational_delivery_gates.sql` |
| Gate·증빙 저장과 감사 | Agent migrations `V9__manage_tenant_operational_delivery_gates.sql`, `V10__scope_operational_gate_evidence_to_configuration.sql`, `V11__make_operational_gate_validation_synchronous.sql` |
| 계약·카탈로그·상태 전이 | Agent `operational_gate_contracts.py`, `operational_gate_catalog.py`, `operational_gate_store.py` |
| 권한별 API | Agent `operational_gate_api.py`, RFC 9457 Problem Details |
| 계약 Drift 방지 | Agent `contracts/openapi/agent-public.json`, Frontend `libs/api-contracts/src/agent-public.ts` |
| 관리자 UX | Frontend `dwaion-admin-gates.tsx`, `dwaion-gate-dialogs.tsx`, `dwaion-gate-review.tsx` |
| 정책 회귀 | Agent `test_operational_gate_policy.py`, `test_operational_gate_api.py`, `test_operational_gate_postgres.py` |

## 8. 구현 Queue

다음 작업은 고객 값 없이도 계약과 Adapter 골격을 개발할 수 있다. 실제 연결 성공 또는
운영 준비 완료 표시는 해당 Gate 증거가 승인될 때까지 금지한다.

| 우선순위 | 작업 | 연결 Gate |
| --- | --- | --- |
| `P0` | 공통 `KeyProvider`와 Managed Identity Credential Provider, 환경 시작 Guard | `G-01`, `G-11` |
| `P0` | 원천별 ACL Adapter, Group Mapping, 회수·삭제 Freshness 측정 | `G-05`, `G-06` |
| `P0` | 평가 Metric·임계치·Regression 비교와 Release Pipeline 차단 연계 | `G-08`, `G-09` |
| `P0` | Action 위험등급·승인 Token·Idempotency·Compensation 계약 | `G-10` |
| `P1` | Private Endpoint·Region·DLP 구성 자동 검증 Adapter | `G-03`, `G-04`, `G-07` |
| `P1` | Artifact별 보존·Legal Hold·삭제 Worker와 Key Rewrap Job | `G-11`, `G-12` |
| `P1` | SIEM Export, Alert SLO, Replay·DR 검증 Adapter | `G-13` |

## 9. 변경과 종료 절차

1. 이 등록부에 ID, Owner, 환경, 선택안, 위험과 종료 증거를 먼저 기록한다.
2. 시스템에서는 Secret이 아닌 구성 참조와 책임자를 저장한다.
3. 현재 구성 리비전에 필요한 증빙 유형을 모두 첨부한다.
4. 구성자와 다른 검증자가 실제 환경 시험 결과를 `PASS` 또는 `FAIL`로 기록한다.
5. 소유자·구성자·검증자와 다른 승인자가 유효기간을 지정해 결정한다.
6. 승인 만료, 구성 변경, Provider 변경 또는 중대 Incident 발생 시 Gate를 다시 연다.
7. TODO 종료 시 구현 링크, 자동 테스트와 외부 승인 증거를 모두 갱신한다.

## 10. 공식 기준

- [NIST AI RMF Core](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/)
- [Microsoft Foundry evaluation](https://learn.microsoft.com/en-ie/azure/ai-foundry/how-to/evaluate-generative-ai-app?view=foundry)
- [Microsoft Foundry observability](https://learn.microsoft.com/azure/ai-foundry/concepts/observability)
- [Microsoft Graph external item ACL](https://learn.microsoft.com/en-us/graph/api/resources/externalconnectors-externalitem?view=graph-rest-1.0)
- [Azure multitenant App Configuration](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/service/app-configuration)
- [Azure Key Vault rotation](https://learn.microsoft.com/en-us/azure/key-vault/keys/how-to-configure-key-rotation)
- [Microsoft Purview retention](https://learn.microsoft.com/en-us/purview/retention-settings)
- [Microsoft Purview eDiscovery hold](https://learn.microsoft.com/en-us/purview/edisc-hold-manage)
