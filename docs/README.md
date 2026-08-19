# DWP Backend Documentation

최종 검토일: 2026-08-19

이 디렉터리에는 현재 코드의 운영·개발 절차와 장기간 유지해야 하는 아키텍처 결정만
보관합니다. 일회성 조사 결과, 실행 로그, 생성 보고서와 화면 캡처는 보관하지 않습니다.

## Active Documents

| 문서                                                                                                           | 유형                         | 상태                                                   | 기준 소스                                                                  |
| -------------------------------------------------------------------------------------------------------------- | ---------------------------- | ------------------------------------------------------ | -------------------------------------------------------------------------- |
| [IntelliJ 백엔드 실행 및 테스트](intellij-backend-run-and-test.md)                                             | 개발·운영 가이드             | Active                                                 | Gradle 구성, `scripts/devctl.py`, 서비스 `application.yml`                 |
| [Role Delegation and Privilege Boundary](architecture/role-delegation-and-privilege-boundary.md)               | Architecture Decision Record | Accepted, 외부 IdP Gate 명시                           | Auth Migration, 권한 서비스와 회귀 테스트                                  |
| [Workforce Access and Export Governance](architecture/workforce-access-and-export-governance.md)               | Architecture Decision Record | Accepted, 반출 실행은 외부 Gate                        | People Migration, 정책·수명주기·Worker 테스트                              |
| [Domain Event Delivery Ledger](architecture/domain-event-delivery-ledger.md)                                   | Architecture Decision Record | Accepted, Transport는 외부 Gate                        | Core Repeatable Migration, 전달 런타임 테스트                              |
| [HR Domain Operations](architecture/hr-domain-operations.md)                                                   | Architecture Decision Record | Accepted, Local Baseline 구현·고객 Connector Gate 명시 | Auth V50, People V38·V39, HR API·권한·회귀 테스트                          |
| [Service Interface Boundary and Gateway Policy](architecture/service-interface-boundary-and-gateway-policy.md) | Architecture Decision Record | Accepted, Gateway/API/Internal 예외 중앙 정책          | `scripts/check-service-boundaries.py`, Frontend `check-api-boundaries.mjs` |
| [Notification Platform Backend Boundary](architecture/notification-platform-boundary.md)                       | Final Candidate Boundary     | 구현 착수 준비 완료, 구현 미착수                       | Notification ADR, 최종 Architecture·UX Review, 기존 Event Ledger           |

## Maintenance Rules

- 실행 방법, 포트, 모듈 또는 환경 변수 변경 시 실행 가이드와 루트 `README.md`를 함께
  갱신합니다.
- ADR은 결정 이력을 보존합니다. 결정이 폐기되면 삭제하지 않고 `Superseded` 상태와 대체
  ADR 링크를 기록합니다.
- 문서의 구현 완료 주장은 소스나 테스트 링크를 근거로 남깁니다. 설계만 존재하는 항목은
  `Planned`, 구현이 일부인 항목은 `Partial` 또는 `Gap`으로 표시합니다.
- 운영 비밀값, 실제 고객 데이터, 사용자별 IDE 상태 파일은 문서에 포함하지 않습니다.
  소스에 공개된 로컬 개발 기본값만 실행 가이드에 기록할 수 있습니다.
