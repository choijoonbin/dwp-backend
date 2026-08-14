# HR Domain Operations Architecture

상태: Accepted, Local Baseline Implemented
최종 검토일: 2026-08-14

## Decision

DWP People Service는 `ppl_*` Workforce Projection을 Core HR Identity로 유지하고,
트랜잭션 수명주기가 다른 업무를 별도 Bounded Context로 분리한다.

| Context | Prefix | Responsibility |
| --- | --- | --- |
| Time | `tme_*` | Schedule, Time Card, Entry, Exception, Submit·Decision |
| Absence | `abs_*` | Leave Plan, Eligibility, Balance, Request, Decision |
| Benefits | `bnf_*` | Program, Plan, Enrollment Window, Enrollment Reference |
| Pay | `pay_*` | Pay Cycle Readiness and encrypted Statement Reference |
| Talent | `tal_*` | Journey, Goal, Learning Assignment |

업무 Context는 Person·Worker Identity를 복제하지 않고 `(tenant_id, worker_id)`로
`ppl_workers`를 참조한다. 외부 HRIS·Payroll·Benefits·LMS가 유지하는 원장은 DWP에
복제하지 않으며 승인된 Connector와 Opaque Reference로 연계한다.

## Identity and Authorization

1. Auth Server가 Session에 불변 `person_public_id`를 포함한다.
2. Gateway가 외부 Identity Header를 제거하고 검증된 Identity·Role·Permission만 전달한다.
3. People Security Filter가 서명된 내부 호출과 Tenant Context를 검증한다.
4. HR Service가 `person_public_id`를 Tenant의 활성 Worker와 다시 연결한다.
5. Self-service API는 Request의 Worker ID를 받지 않는다.
6. Manager 결정은 유효일 보고 관계의 실제 Target Population을 조회한다.
7. 위임 결정·운영은 `DATA.HR_{DOMAIN}:APPROVE|MANAGE`를 요구한다.

`TIME_ADMIN`, `ABSENCE_ADMIN`, `BENEFITS_ADMIN`, `PAYROLL_ADMIN`, `TALENT_ADMIN`은
독립 Privileged Role이다. Tenant Admin과 Provider Admin 역할만으로 민감 HR 데이터에
접근하지 않는다. `AUDITOR`와 각 HR 관리자 Role은 감사 독립성 Conflict Policy를 가진다.

## Transaction and Integrity

- 모든 쓰기는 Tenant Scope와 Public UUID Target을 함께 확인한다.
- Time Card, Leave Request, Goal은 `version`으로 Optimistic Lock을 강제한다.
- 제출·승인 상태 전이는 단일 SQL 조건과 Transaction으로 수행한다.
- 제출·승인 휴가의 시간 중복은 PostgreSQL `tstzrange &&` Exclusion Constraint가 차단한다.
- 애플리케이션 사전 검사 후 발생하는 동시 경합도 `RESOURCE_CONFLICT`로 변환한다.
- Leave Balance는 승인 성공 Transaction 안에서 Pending·Used를 함께 전이한다.
- 변경 성공은 동일 Transaction의 Audit Outbox에 Actor, Roles, Target, Correlation ID,
  Retention Class와 함께 기록한다.

## Reference Foundation

`seed_hr_domain_foundation(tenant_id)`는 개발·Pilot 검증을 위한 최소 구조를 멱등 생성한다.
모든 참조 Row는 `REFERENCE` 출처 또는 `reference:` URI를 사용한다. 이 Seed는 고객 운영
데이터, 법정 급여 계산, 실제 보험 가입 또는 학습 이수 증거가 아니다.

로컬 통합 환경은 `DWP_PEOPLE_FLYWAY_LOCATIONS`에 `classpath:db/local-seed`를 추가해
SKAX Workforce 전체에 재현 가능한 HR 경험 데이터를 적재한다. 운영 기본값은
`classpath:db/migration`만 사용하므로 로컬 참조 거래가 배포 환경으로 유입되지 않는다.
로컬 값은 `worker_id`에서 파생한 결정적 분산값을 사용하며, `REFERENCE`,
`local-seed:` 또는 `reference://local-seed/`로 식별한다. 사용자가 수정한 근태 행은
`self-service`로 출처가 전환되어 이후 Seed 변경 시에도 덮어쓰지 않는다.

Employee만 휴가·복리후생·급여명세 참조를 받는다. Contingent Worker는 근태와 성장·학습
데이터만 받으며, 실제 자격 정책을 흉내 내기 위해 Employee 전용 데이터를 생성하지 않는다.

## API Boundary

외부 경계는 Gateway `/api/people/v1/hr/**`, People Service 내부 경계는 `/v1/hr/**`다.
개인 Home·Time·Absence·Benefits·Pay·Talent 조회, 근태 저장·제출, 휴가 신청, 목표 갱신,
Manager 결정, 도메인 운영 조회를 제공한다.

## Delivery Gates

- 고객 HRIS Payload·Mapping·Delta/Full·Reconciliation Owner
- 국가별 근로·휴가·급여 정책과 노무·법무 승인
- Payroll·Benefits·LMS·Document Store Connector와 KMS
- 민감 필드 보존·삭제·반출·SIEM 정책
- 운영 규모 성능 시험, 장애 복구, Screen Reader 수용 시험

Local Migration과 Reference E2E 통과는 이 Gate를 대체하지 않는다.

## Verification

- `./gradlew :dwp-people-server:test :dwp-auth-server:test :dwp-gateway:test`
- 일반 구성원 Self-service 200, HR 운영 403
- HR 관리자 허용 Domain 운영 200
- Manager Time Card 제출·결정과 Audit Outbox 연계
- 휴가 중복 Range Constraint와 업무 Conflict 응답
