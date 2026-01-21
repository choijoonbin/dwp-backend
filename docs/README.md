# DWP Backend Documentation

## 문서 구조

이 폴더는 다음과 같이 구성되어 있습니다:

### 📁 standardization/ (17 files)
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

### 📁 reference/ (2 files)
**운영 및 개발 참조 문서**
- `ADMIN_API_QUICKREF.md`: Admin API 전체 목록 및 사용법 (Quick Reference)
- `SECURITY_RBAC_ENFORCEMENT.md`: RBAC Enforcement 정책 및 보안 가이드

### 📁 release-notes/ (1 file)
**릴리즈 노트 및 배포 문서**
- `RELEASE_NOTES_PR04_PR10.md`: PR-04~PR-10 통합 배포 노트

### 📁 workdone/ (37 files)
**작업 완료 문서 모음**
- BE_P1-5 시리즈 작업 완료 요약
- BE_SUB_PROMPT 시리즈 작업 완료 요약
- BE_HOTFIX 시리즈 작업 완료 요약
- P0, P1 시리즈 작업 완료 요약
- 리팩토링 작업 완료 요약

### 📁 api-spec/ (8 files)
**API 스펙 문서**
- Admin Monitoring API 스펙
- Frontend API 스펙
- Auth Policy 스펙
- Admin CRUD API 스펙
- Event Logs API 스펙

### 📁 testdoc/ (13 files)
**테스트 관련 문서**
- 통합 테스트 가이드
- 검증 체크리스트
- 테스트 결과 문서
- HITL API 테스트 가이드

### 📁 guides/ (11 files)
**가이드 및 정책 문서**
- Service 리팩토링 가이드
- RBAC 계산 정책
- RBAC Enforcement 가이드
- Code Management 가이드
- CORS 설정 가이드
- Monitoring API 비교 문서
- JWT 호환성 가이드
- Flyway 수리 가이드

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

### 📁 troubleshooting/ (11 files)
트러블슈팅 문서
- IDE 오류 해결 가이드
- 로그인 API 트러블슈팅
- JWT 이슈 요약
- 에러 수정 문서
- Gateway 수정 문서

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

### 통합 가이드
- [Aura Platform 통합 가이드](./integration/AURA_PLATFORM_INTEGRATION_GUIDE.md)
- [Aura Gateway Single Point 스펙](./integration/AURA_GATEWAY_SINGLE_POINT_SPEC.md)

### 설정 가이드
- [IDE 설정](./setup/IDE_SETUP.md)
- [데이터베이스 설정](./setup/DATABASE_SETUP_COMPLETE.md)
- [서비스 시작 가이드](./setup/SERVICE_START_GUIDE.md)

## 문서 통계

- **총 문서 수**: 126개
- **standardization**: 17개 (PR-01~PR-11 표준화 문서)
- **reference**: 2개 (참조 문서)
- **release-notes**: 1개 (릴리즈 노트)
- **workdone**: 37개 (작업 완료 문서)
- **api-spec**: 8개 (API 스펙)
- **testdoc**: 13개 (테스트 문서)
- **guides**: 11개 (가이드 문서)
- **integration**: 13개 (통합 문서)
- **setup**: 7개 (설정 문서)
- **troubleshooting**: 14개 (트러블슈팅 문서)
