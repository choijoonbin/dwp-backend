# Flyway 체크섬 불일치 수동 해결 방법

## 문제 상황

V6 마이그레이션 파일을 수정한 후 체크섬 불일치 오류가 발생합니다.

## 해결 방법

### 방법 1: SQL로 직접 체크섬 업데이트 (권장)

DB에 직접 접속하여 다음 SQL을 실행:

```sql
-- flyway_schema_history 테이블에서 V6의 체크섬 업데이트
UPDATE flyway_schema_history 
SET checksum = -744789939 
WHERE version = '6' AND description LIKE '%sys_menus%';

-- 확인
SELECT version, description, checksum 
FROM flyway_schema_history 
WHERE version = '6';
```

### 방법 2: validate-on-migrate 비활성화 (임시)

`application.yml`에서 `validate-on-migrate: false`로 설정하면 repair가 실행됩니다.

**주의**: repair 후에는 반드시 `validate-on-migrate: true`로 다시 변경하세요.

### 방법 3: V6 원래대로 되돌리기

V6를 원래대로 되돌리고 V8 마이그레이션만 사용:

1. V6 파일에서 `VARCHAR(1)` → `CHAR(1)`로 복원
2. V8 마이그레이션으로만 타입 변경

## 현재 설정

`application.yml`에서 `validate-on-migrate: false`로 설정되어 있어, 서버 재시작 시 repair가 실행됩니다.

**중요**: repair가 완료되면 `validate-on-migrate: true`로 다시 변경하세요!
