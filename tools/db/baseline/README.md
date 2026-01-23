# DWP DB Baseline Tools

## 개요
Flyway baseline 마이그레이션을 생성하기 위한 도구 모음

## 스크립트

### dump_schema.sh
PostgreSQL 데이터베이스의 스키마를 추출하여 Flyway V1__baseline.sql 파일을 생성합니다.

**사용법:**
```bash
./dump_schema.sh <db_name> <service_name>
```

**예시:**
```bash
# auth-server 스키마 추출 (이미 완료됨)
./dump_schema.sh dwp_auth auth-server

# main-service 스키마 추출 (향후 테이블 추가 후)
./dump_schema.sh dwp_main main-service

# mail-service 스키마 추출 (향후 테이블 추가 후)
./dump_schema.sh dwp_mail mail-service
```

**전제 조건:**
- PostgreSQL 클라이언트 (`pg_dump`) 설치
- 대상 DB 접근 권한

**환경 변수:**
- `DB_USERNAME` (기본값: dwp_user)
- `DB_HOST` (기본값: localhost)
- `DB_PORT` (기본값: 5432)

## 참고
- [Flyway Baseline 전략 문서](../../docs/specs/migrations/FLYWAY_BASELINE_STRATEGY.md)
