# DWP Backend Documentation

## 문서 구조

이 폴더는 다음과 같이 구성되어 있습니다:

### 📁 standardization/ (18 files)
**PR-01 ~ PR-11 표준화 작업 문서**
- PR-01: Admin Guard 표준화
- PR-02: Users CRUD 표준화
- PR-03: Roles & Permissions 표준화
- PR-04: Resource CRUD 표준화
- PR-05: Menu Management 표준화
- PR-06: Code Management 표준화
- PR-07: Code Usage 표준화
- PR-08: Audit Logs 표준화
- PR-09: RBAC Enforcement 표준화
- PR-10: SSO Integration 표준화
- PR-11: 통합 검증 보고서

### 📁 reference/ (4 files)
**운영 및 개발 참조 문서**
- `ADMIN_API_QUICKREF.md`: Admin API 전체 목록 및 사용법 (Quick Reference)
- `SECURITY_RBAC_ENFORCEMENT.md`: RBAC Enforcement 정책 및 보안 가이드
- `OPENAPI_ARTIFACT_POLICY.md`: OpenAPI Artifact 정책 및 CI/CD 가이드
- `README.md`: Reference 문서 인덱스

### 📁 release-notes/ (2 files)
**릴리즈 노트 및 배포 문서**
- `RELEASE_NOTES_PR04_PR10.md`: PR-04~PR-10 통합 배포 노트
- `README.md`: 릴리즈 노트 인덱스

### 📁 archive/workdone/ (41 files)
**작업 완료 문서 모음 (아카이브)**
- BE_P1-5 시리즈 작업 완료 요약
- BE_SUB_PROMPT 시리즈 작업 완료 요약
- BE_HOTFIX 시리즈 작업 완료 요약
- P0, P1 시리즈 작업 완료 요약
- 리팩토링 작업 완료 요약
- PR_ROLES_* 시리즈 작업 완료 요약

### 📁 api-spec/ (12 files)
**API 스펙 문서 (프론트 요청 문서 수신)**
- 프론트가 `docs/backend-src/docs/api-spec/`에 올린 요청·검토 문서가 이 경로와 동일(백엔드 레포 기준).
- **BE는 여기서 요청 문서를 확인한 뒤 작업**하고, 완료 결과는 `docs/frontend/docs/api-spec/`에 `{요청제목}_result` 형식으로 업로드.
- Admin Monitoring API 스펙, Frontend API 스펙, Auth Policy 스펙, Admin CRUD API 스펙, Event Logs API 스펙, Frontend API 요청 템플릿
- **Admin API 보완 로드맵** (`ADMIN_API_COMPLETION_ROADMAP.md`): FE Gap/요청 명세·남은 작업 분석 + BE 검증 기반

### 📁 frontend/docs/api-spec/
**프론트 공유용 API 스펙 결과물 (BE → FE)**
- **BE 작업 완료 후** `{요청문서제목}_result.md` 형식으로 업로드.
- 프론트는 이 폴더에서 결과를 확인 후 작업하거나, 요청 문서를 `_v1`, `_v2`로 업데이트하여 재요청.

### 📁 essentials/ (4 files)
**필수 문서·업무 정의**
- `GETTING_STARTED_BACKEND.md`, `PROJECT_RULES_BACKEND.md`, `RUNBOOK_BACKEND.md`
- **`FE_BE_API_SPEC_WORKFLOW.md`**: FE-BE API 스펙 협업 업무 정의 (요청/결과 폴더, 파일명, 흐름)

### 📁 testdoc/ (13 files)
**테스트 관련 문서**
- 통합 테스트 가이드
- 검증 체크리스트
- 테스트 결과 문서
- HITL API 테스트 가이드

### 📁 guides/ (15 files)
**가이드 및 정책 문서**
- Service 리팩토링 가이드
- RBAC 계산 정책
- RBAC Enforcement 가이드
- Code Management 가이드
- CORS 설정 가이드
- Monitoring API 비교 문서
- JWT 호환성 가이드
- Flyway 수리 가이드
- BYTEA 에러 예방 가이드
- Admin CRUD 템플릿

### 📁 integration/ (13 files)
**통합 관련 문서**
- Aura Platform 통합 가이드
- Aura Gateway Single Point 스펙
- Aura UI 통합 문서
- AI Agent 인프라 문서
- Aura Platform 핸드오프 문서

### 📁 setup/ (7 files)
**설정 및 설치 가이드**
- IDE 설정 가이드
- 데이터베이스 설정 가이드
- 서비스 시작 가이드
- Gradle 새로고침 가이드
- 데이터베이스 검증 문서

### 📁 archive/ (67 files)
**아카이브 문서**
- **backend-audit/**: 백엔드 최적화 검증 보고서 (C01~C34)
- **workdone/**: 작업 완료 문서 모음 (40개)
- **troubleshooting/**: 트러블슈팅 문서 (22개)

## 주요 문서 빠른 링크

### 표준화 작업 문서 (PR-01 ~ PR-11)
- [PR-11 통합 검증 보고서](./standardization/PR11_INTEGRATION_VERIFICATION_REPORT.md)
- [PR-10 SSO Integration](./standardization/PR10_SSO_INTEGRATION_STANDARDIZATION.md)
- [PR-09 RBAC Enforcement](./standardization/PR09_RBAC_ENFORCEMENT_STANDARDIZATION.md)
- [PR-08 Audit Logs](./standardization/PR08_AUDIT_LOGS_STANDARDIZATION.md)
- [PR-07 Code Usage](./standardization/PR07_CODE_USAGE_STANDARDIZATION.md)
- [PR-06 Code Management](./standardization/PR06_CODE_CRUD_STANDARDIZATION.md)
- [PR-05 Menu Management](./standardization/PR05_MENU_CRUD_STANDARDIZATION.md)
- [PR-04 Resource CRUD](./standardization/PR04_RESOURCE_CRUD_STANDARDIZATION.md)

### 참조 문서
- [Admin API Quick Reference](./reference/ADMIN_API_QUICKREF.md)
- [RBAC Enforcement 가이드](./reference/SECURITY_RBAC_ENFORCEMENT.md)

### 릴리즈 노트
- [PR-04 ~ PR-10 릴리즈 노트](./release-notes/RELEASE_NOTES_PR04_PR10.md)

### 개발 가이드
- [Service 리팩토링 가이드](./guides/SERVICE_REFACTOR_GUIDE.md)
- [RBAC 계산 정책](./guides/RBAC_CALCULATION_POLICY.md)
- [RBAC Enforcement](./guides/RBAC_ENFORCEMENT.md)

### API 스펙
- [Admin Monitoring API](./api-spec/ADMIN_MONITORING_API_SPEC.md)
- [Frontend API](./api-spec/FRONTEND_API_SPEC.md)
- [Admin CRUD API](./api-spec/USER_ADMIN_CRUD_API.md)
- [Admin API 보완 로드맵 (FE 요청·BE 검증)](./api-spec/ADMIN_API_COMPLETION_ROADMAP.md)

### 통합 가이드
- [Aura Platform 통합 가이드](./integration/AURA_PLATFORM_INTEGRATION_GUIDE.md)
- [Aura Gateway Single Point 스펙](./integration/AURA_GATEWAY_SINGLE_POINT_SPEC.md)

### 업무 정의·협업
- [FE-BE API 스펙 협업 업무 정의](./essentials/FE_BE_API_SPEC_WORKFLOW.md): 요청/결과 폴더, 파일명 규칙, 흐름

### 설정 가이드
- [IDE 설정](./setup/IDE_SETUP.md)
- [데이터베이스 설정](./setup/DATABASE_SETUP_COMPLETE.md)
- [서비스 시작 가이드](./setup/SERVICE_START_GUIDE.md)

## 문서 통계

- **총 문서 수**: 162개
- **standardization**: 18개 (PR-01~PR-11 표준화 문서)
- **reference**: 4개 (참조 문서)
- **release-notes**: 2개 (릴리즈 노트)
- **archive/workdone**: 41개 (작업 완료 문서, 아카이브)
- **archive/troubleshooting**: 22개 (트러블슈팅 문서, 아카이브)
- **archive/backend-audit**: 5개 (백엔드 최적화 검증 보고서)
- **api-spec**: 12개 (API 스펙)
- **testdoc**: 13개 (테스트 문서)
- **guides**: 15개 (가이드 문서)
- **integration**: 13개 (통합 문서)
- **setup**: 7개 (설정 문서)
- **essentials**: 4개 (필수 문서, FE-BE API 스펙 협업 업무 정의 포함)
- **audit**: 3개 (코드 리뷰/감사 문서)
- **specs**: 1개 (마이그레이션 스펙)
