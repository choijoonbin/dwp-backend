# Pull Request

## 변경 사항 요약
<!-- 이번 PR에서 변경한 내용을 간단히 설명해주세요 -->

## 변경 유형
- [ ] 기능 추가 (Feature)
- [ ] 버그 수정 (Bug Fix)
- [ ] 리팩토링 (Refactoring)
- [ ] 문서 업데이트 (Documentation)
- [ ] 설정 변경 (Configuration)
- [ ] 테스트 추가 (Test)

## 체크리스트

### 코드 품질
- [ ] ApiResponse<T> Envelope 형식 유지
- [ ] 표준 헤더 7개 전파 계약 준수 (Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, X-DWP-Source, X-DWP-Caller-Type, X-Correlation-ID)
- [ ] Native Query 사용 시 예외 승인 문서 작성 (`docs/audit/NATIVE_QUERY_EXCEPTION_APPROVAL.md`)
- [ ] 하드코딩 제거 (환경변수 사용)
- [ ] ddl-auto:update 사용 금지 (Flyway 사용)

### API 계약 변경 (C32 - 계약 드리프트 방지)
- [ ] API 응답 DTO 필드 추가/삭제/타입 변경 시 `docs/specs/API_CHANGELOG.md` 업데이트
- [ ] OpenAPI 문서 확인 (`/v3/api-docs`) 및 프론트엔드 팀 공유 (Breaking Change 시)
- [ ] Breaking Change 발생 시 마이그레이션 가이드 작성

### 테스트
- [ ] 단위 테스트 추가/수정
- [ ] 통합 테스트 추가/수정 (필요 시)
- [ ] 헤더 전파 테스트 확인 (Feign 사용 시)
- [ ] 모든 테스트 통과 (`./gradlew test`)

### 빌드 및 배포
- [ ] 빌드 성공 (`./gradlew build`)
- [ ] Spotless 체크 통과 (`./gradlew spotlessCheck`)
- [ ] Flyway 마이그레이션 검증 (스키마 변경 시)

### 문서
- [ ] API 스펙 업데이트 (`docs/specs/`)
- [ ] 변경 사항 문서화 (`docs/reference/` 또는 `docs/workdone/`)
- [ ] README 업데이트 (필요 시)

### 보안 및 RBAC
- [ ] 멀티테넌시 격리 보장 (tenantId 필터)
- [ ] 권한 체크 적용 (`@PreAuthorize` 또는 `PermissionEvaluator`)
- [ ] JWT 검증 적용

## 관련 이슈
<!-- 관련된 이슈 번호를 명시해주세요 (예: #123) -->
Closes #

## 스크린샷 (선택)
<!-- UI 변경이 있는 경우 스크린샷을 첨부해주세요 -->

## 추가 정보
<!-- 리뷰어가 알아야 할 추가 정보를 작성해주세요 -->
