# DWP Backend 문서

프로젝트 관련 문서 모음입니다.

## 📚 문서 목록

### 🚀 시작하기
- [서비스 실행 가이드](./SERVICE_START_GUIDE.md) - 모든 서비스를 실행하는 방법
- [IDE 설정 가이드](./IDE_SETUP.md) - Cursor/VS Code IDE 설정 방법

### 🗄️ 데이터베이스
- [데이터베이스 연결 정보](./DATABASE_INFO.md) - PostgreSQL 및 Redis 연결 정보
- [데이터베이스 검증 결과](./DATABASE_VERIFICATION.md) - 데이터베이스 설정 검증 결과

### 🔧 개발 도구
- [Cursor IDE Gradle 새로고침](./CURSOR_GRADLE_REFRESH.md) - Gradle 프로젝트 새로고침 방법
- [IDE 오류 해결](./FIX_IDE_ERRORS.md) - IDE 오류 해결 가이드
- [오류 수정 내역](./ERRORS_FIXED.md) - 수정된 오류 목록

### 🌐 API & Gateway
- [Gateway 라우팅 테스트](./GATEWAY_ROUTING_TEST.md) - API Gateway 라우팅 테스트 가이드
- [API 응답 규격 가이드](../dwp-core/API_RESPONSE_GUIDE.md) - 공통 API 응답 규격 사용법

## 빠른 링크

### 서비스 실행
```bash
# Docker Compose (인프라)
docker-compose up -d

# 서비스 실행
./gradlew :dwp-gateway:bootRun
./gradlew :dwp-main-service:bootRun
# ... 기타 서비스
```

### 문제 해결
- IDE 오류: [IDE 오류 해결](./FIX_IDE_ERRORS.md)
- Gradle 새로고침: [Cursor IDE Gradle 새로고침](./CURSOR_GRADLE_REFRESH.md)
- 데이터베이스 연결: [데이터베이스 연결 정보](./DATABASE_INFO.md)

## 문서 구조

```
docs/
├── README.md (이 파일)
├── SERVICE_START_GUIDE.md
├── IDE_SETUP.md
├── DATABASE_INFO.md
├── DATABASE_VERIFICATION.md
├── CURSOR_GRADLE_REFRESH.md
├── FIX_IDE_ERRORS.md
├── ERRORS_FIXED.md
└── GATEWAY_ROUTING_TEST.md
```
