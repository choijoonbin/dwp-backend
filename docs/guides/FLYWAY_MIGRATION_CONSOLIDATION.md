# Flyway 마이그레이션 통합 완료 보고서

**작성일**: 2026-01-21  
**상태**: ✅ 통합 완료

---

## 통합 완료 내역

### 통합 전
- **파일 수**: 20개 (V1 ~ V20)
- **총 라인 수**: 약 1,500+ 라인
- **파일 위치**: `dwp-auth-server/src/main/resources/db/migration/`

### 통합 후
- **파일 수**: 1개 (`V1__create_iam_schema.sql`)
- **총 라인 수**: 약 1,500+ 라인 (동일)
- **백업 위치**: `dwp-auth-server/src/main/resources/db/migration/backup/` (20개 파일 보관)

---

## 통합된 내용 요약

### V1: 기본 IAM 스키마 생성
- 17개 테이블 생성 (com_tenants, com_users, com_roles, com_resources 등)
- 모든 인덱스 및 COMMENT 포함

### V2: Seed 데이터 삽입
- dev tenant, admin 사용자, 기본 권한 등

### V3~V20: 기능 추가 및 수정
- **V3**: 모니터링 컬럼 추가
- **V4**: latency_ms 타입 수정 (INTEGER → BIGINT)
- **V5**: Admin 메뉴 리소스 추가
- **V6**: sys_menus 테이블 생성
- **V7**: sys_menus Seed 데이터
- **V8**: sys_menus 컬럼 타입 수정
- **V9**: 코드 테이블 생성 (sys_code_groups, sys_codes)
- **V10**: 코드 Seed 데이터
- **V11**: sys_event_logs 테이블 생성
- **V12**: sys_code_usages 테이블 생성
- **V13**: sys_code_usages Seed 데이터
- **V14**: auth_policies 및 idp 확장
- **V15**: auth_policy 및 idp Seed 데이터
- **V16**: com_resources tracking 확장
- **V17**: sys_codes에 tenant_id 추가
- **V18**: 리소스 tracking 코드 Seed
- **V19**: event_logs에 resource_kind 추가
- **V20**: bytea 컬럼 수정

---

## 데이터베이스 초기화 방법

### ⚠️ 중요: 개발 환경에서만 실행하세요

### 방법 1: 데이터베이스 완전 초기화 (권장)

```bash
# PostgreSQL에 접속
psql -U dwp_user -d postgres

# 기존 데이터베이스 삭제
DROP DATABASE IF EXISTS dwp_auth;

# 새 데이터베이스 생성
CREATE DATABASE dwp_auth;

# 권한 부여
GRANT ALL PRIVILEGES ON DATABASE dwp_auth TO dwp_user;
\q

# 애플리케이션 재시작 (Flyway가 자동으로 V1 실행)
./gradlew :dwp-auth-server:bootRun
```

### 방법 2: Flyway 히스토리만 초기화

```bash
# flyway_schema_history 테이블 삭제
psql -U dwp_user -d dwp_auth -c "DROP TABLE IF EXISTS flyway_schema_history;"

# 애플리케이션 재시작
./gradlew :dwp-auth-server:bootRun
```

### 방법 3: 모든 테이블 삭제 후 재실행

```bash
# 모든 테이블 삭제 (주의: 데이터 손실)
psql -U dwp_user -d dwp_auth << EOF
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO dwp_user;
EOF

# 애플리케이션 재시작
./gradlew :dwp-auth-server:bootRun
```

---

## 주의사항

### ⚠️ 프로덕션 환경에서는 절대 사용 금지

이 통합은 **개발 단계에서만** 사용 가능합니다. 프로덕션 환경에서는:
- 기존 마이그레이션 파일을 그대로 유지해야 합니다
- 새로운 변경사항은 새로운 버전(V21, V22, ...)으로 추가해야 합니다

### ✅ 개발 환경에서의 장점

1. **파일 관리 단순화**: 하나의 파일로 모든 스키마 관리
2. **초기화 용이**: 데이터베이스 초기화 시 한 번에 모든 스키마 생성
3. **의존성 명확화**: 모든 변경사항이 순서대로 한 파일에 정리됨
4. **버전 관리 간소화**: 하나의 파일만 관리하면 됨

### ⚠️ 단점

1. **히스토리 추적 어려움**: 각 변경사항의 시점을 알기 어려움
2. **롤백 불가능**: 특정 버전으로 되돌릴 수 없음
3. **협업 시 주의**: 다른 개발자와 동기화 필요

---

## 백업 파일 복원 방법

필요시 백업 파일을 복원할 수 있습니다:

```bash
cd dwp-auth-server/src/main/resources/db/migration

# 통합 파일 백업
cp V1__create_iam_schema.sql V1__create_iam_schema.sql.backup

# 원본 파일 복원
cp backup/V*.sql .
```

---

## 다음 단계

### 프로덕션 배포 전 고려사항

1. **옵션 1**: 현재 통합 파일을 그대로 유지하고 새로운 변경사항만 V2, V3...로 추가
2. **옵션 2**: 마이그레이션 파일을 다시 분리 (V1~V20 복원 후 새로운 변경사항 추가)

### 권장 사항

**개발 단계**: 현재 통합 파일 유지  
**프로덕션 배포 전**: 팀과 협의하여 결정

---

## 파일 구조

```
dwp-auth-server/src/main/resources/db/migration/
├── V1__create_iam_schema.sql          # 통합 파일 (현재 사용)
└── backup/
    ├── V1__create_iam_schema.sql      # 원본 V1
    ├── V2__insert_seed_data.sql       # 원본 V2
    ├── ...
    └── V20__fix_bytea_columns.sql     # 원본 V20
```

---

**작성일**: 2026-01-21  
**작성자**: DWP Backend Team
