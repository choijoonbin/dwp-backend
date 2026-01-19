# Flyway 체크섬 불일치 해결 가이드

## 문제 상황

V6 마이그레이션 파일을 수정한 후 서버 시작 시 다음 오류가 발생:

```
Migration checksum mismatch for migration version 6
-> Applied to database : 1942921442
-> Resolved locally    : -744789939
```

## 원인

마이그레이션 파일을 수정하면 Flyway가 계산하는 체크섬이 변경됩니다. 이미 DB에 적용된 마이그레이션의 체크섬과 새로 계산된 체크섬이 일치하지 않아 발생합니다.

## 해결 방법

### 방법 1: 자동 Repair (권장)

`application.yml`에 `repair-on-migrate: true`를 추가하면 자동으로 체크섬을 업데이트합니다:

```yaml
spring:
  flyway:
    repair-on-migrate: true  # 체크섬 불일치 시 자동 repair
```

서버를 재시작하면 자동으로 체크섬이 업데이트됩니다.

### 방법 2: 수동 Repair (SQL)

DB에 직접 접속하여 Flyway repair를 실행:

```sql
-- flyway_schema_history 테이블에서 V6의 체크섬 업데이트
UPDATE flyway_schema_history 
SET checksum = -744789939 
WHERE version = '6' AND description LIKE '%sys_menus%';
```

### 방법 3: V6 원래대로 되돌리고 V8만 사용

V6를 원래대로 되돌리고 V8 마이그레이션만 사용하는 방법:

1. V6 파일을 원래대로 복원 (`CHAR(1)` 유지)
2. V8 마이그레이션으로만 타입 변경

## 주의사항

- **프로덕션 환경**: `repair-on-migrate: true`는 신중하게 사용하세요
- **개발 환경**: 자동 repair가 편리하지만, 마이그레이션 파일 수정은 최소화하세요
- **권장**: 마이그레이션 파일은 한 번 생성되면 수정하지 말고, 새로운 마이그레이션 파일로 수정사항을 반영하세요

## 현재 적용

`application.yml`에 `repair-on-migrate: true`가 추가되어 있어, 서버 재시작 시 자동으로 체크섬이 업데이트됩니다.
