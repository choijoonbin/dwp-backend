# DWP Backend 프로젝트 규칙

## 아키텍처 원칙
1. **dwp-core**: 공통 기반 (Auto-Configuration Starter)
2. **dwp-gateway**: 단일 진입점 (헤더 전파, 라우팅)
3. **dwp-auth-server**: 인증/인가 + Admin 영역
4. **dwp-main-service**: 상태 관리 (AgentTask, HITL)
5. **services/***: 도메인 서비스

## 필수 헤더 (Header Contract)
모든 downstream 호출 시 다음 헤더 전파 필수:
- `Authorization`: Bearer JWT
- `X-Tenant-ID`: 멀티테넌트 식별자
- `X-User-ID`: 사용자 식별자
- `X-Agent-ID`: AI 에이전트 식별자
- `X-DWP-Source`: 요청 출처
- `X-DWP-Caller-Type`: 호출자 타입

## 코드 품질 기준
- Native Query 금지 (QueryDSL/JPA 사용)
- ddl-auto:update 금지 (Flyway 사용)
- 모든 API는 `ApiResponse<T>` Envelope 사용
- 환경변수 기반 설정 (하드코딩 금지)

## 테스트 기준
- 단위 테스트: 핵심 로직
- 통합 테스트: Testcontainers 사용
- 헤더 전파 테스트 필수

자세한 내용은 [프로젝트 루트 README.md](../../README.md) 참조
