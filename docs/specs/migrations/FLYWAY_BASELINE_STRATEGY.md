# Flyway Baseline 전략

## 목적
신규 환경(새 DB)에서 "스키마 자동 생성/누락" 없이 Flyway로만 DB가 재현되도록 보장

---

## 서비스별 DB 분리 현황

| 서비스 | DB 이름 | 테이블 상태 | Flyway 상태 | 비고 |
|--------|---------|-------------|-------------|------|
| dwp-auth-server | dwp_auth | ✅ 설계 완료 | ✅ 운영 중 (V1~V4) | IAM/RBAC/Menu/Code 등 |
| dwp-main-service | dwp_main | ⚠️ 미설계 | 🔄 Skeleton 준비 | AgentTask/HITL (향후 추가) |
| mail-service | dwp_mail | ⚠️ 미설계 | 🔄 Skeleton 준비 | Mail 도메인 (향후 추가) |
| chat-service | dwp_chat | ⚠️ 미설계 | 🔄 Skeleton 준비 | Chat 도메인 (향후 추가) |
| approval-service | dwp_approval | ⚠️ 미설계 | 🔄 Skeleton 준비 | Approval 도메인 (향후 추가) |

**결론**: 현재는 **auth-server만 Flyway 운영 중**, 나머지는 향후 확장 대비 구조 준비

---

## Baseline 생성 방식

### ✅ 원칙
1. **현재 운영 스키마 스냅샷을 V1__baseline.sql로 고정**
2. **이후 변경은 V2, V3... incremental로만 진행**
3. **Hibernate ddl-auto 변경 금지 (validate 유지)**

### 방법 A: 운영 DB 기준 (auth-server 적용됨)
```bash
# PostgreSQL에서 스키마 추출
pg_dump -h localhost -U dwp_user -d dwp_auth \
  --schema-only --no-owner --no-privileges \
  > V1__baseline.sql
```

### 방법 B: 엔티티 기반 (향후 서비스 확장 시 적용)
```bash
# 로컬에서 JPA가 생성한 스키마를 확인
# 1. ddl-auto=create로 임시 기동
# 2. 생성된 스키마를 pg_dump로 추출
# 3. V1__baseline.sql로 저장
# 4. ddl-auto=validate로 복원
```

**⚠️ 주의**: 방법 B는 초기 개발 시에만 사용. 운영 후에는 절대 금지.

---

## 운영 원칙

### ✅ DO (반드시 준수)
- **Flyway만 스키마 변경의 Source of Truth**
- **신규 테이블/컬럼 추가**: V{N}__add_*.sql 생성
- **스키마 변경 시**: 로컬 → 개발 → 스테이징 → 운영 순으로 검증
- **롤백 계획**: 각 마이그레이션마다 rollback SQL 주석 추가 (권장)

### ❌ DON'T (절대 금지)
- ❌ `spring.jpa.hibernate.ddl-auto: update` 사용
- ❌ `spring.jpa.hibernate.ddl-auto: create` 운영 사용
- ❌ Flyway 없이 수동 DDL 실행
- ❌ 마이그레이션 파일 수정 (이미 적용된 파일은 절대 변경 금지)

---

## 신규 서비스 스키마 추가 절차

### 1단계: 엔티티 설계
```java
// 예: AgentTask 엔티티 설계
@Entity
@Table(name = "agent_tasks")
public class AgentTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;
    
    @Column(nullable = false)
    private Long tenantId;
    
    // ...
}
```

### 2단계: 로컬 스키마 생성 (임시)
```yaml
# application-local.yml (임시)
spring:
  jpa:
    hibernate:
      ddl-auto: create  # 임시로만!
```

### 3단계: 스키마 추출
```bash
# 생성된 스키마 확인
psql -h localhost -U dwp_user -d dwp_main -c "\dt"

# 스키마 추출
pg_dump -h localhost -U dwp_user -d dwp_main \
  --schema-only --no-owner --no-privileges \
  > dwp-main-service/src/main/resources/db/migration/V1__baseline.sql
```

### 4단계: ddl-auto 복원
```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # 복원!
```

### 5단계: Flyway 검증
```bash
# DB 삭제 후 재생성
dropdb dwp_main && createdb dwp_main

# 서비스 기동 → Flyway가 V1__baseline.sql 적용
./gradlew :dwp-main-service:bootRun
```

---

## Baseline 파일 표준 구조

### V1__baseline.sql 템플릿

> **시스템 컬럼 디폴트**: 모든 신규 테이블에 `created_at`, `created_by`, `updated_at`, `updated_by` 포함. 테넌트 단위는 `tenant_id`+인덱스 포함. → [SYSTEM_COLUMNS_POLICY.md](../../essentials/SYSTEM_COLUMNS_POLICY.md)

```sql
-- ========================================
-- DWP {Service} Baseline Schema
-- 생성일: YYYY-MM-DD
-- 목적: 초기 스키마 정의 (Flyway baseline)
-- ========================================

-- ========================================
-- 1. Extensions (필요 시)
-- ========================================
-- CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ========================================
-- 2. Tables
-- ========================================
CREATE TABLE {table_name} (
    {column_id} BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,                    -- 테넌트 단위: 디폴트 포함
    -- {비즈니스 컬럼}
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 시스템 컬럼 디폴트
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

-- ========================================
-- 3. Indexes
-- ========================================
CREATE INDEX idx_{table}_tenant_id ON {table_name}(tenant_id);

-- ========================================
-- 4. Comments (선택)
-- ========================================
COMMENT ON TABLE {table_name} IS '{설명}';
COMMENT ON COLUMN {table_name}.{column_id} IS '{설명}';
COMMENT ON COLUMN {table_name}.created_at IS '생성일시';
COMMENT ON COLUMN {table_name}.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN {table_name}.updated_at IS '수정일시';
COMMENT ON COLUMN {table_name}.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ========================================
-- Baseline 요약
-- ========================================
-- 테이블 수: N개
-- 인덱스 수: M개
-- ========================================
```

---

## Flyway 설정 표준

### application.yml
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: ${JPA_DDL_AUTO:validate}  # validate 고정
    show-sql: ${JPA_SHOW_SQL:false}
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        "[format_sql]": true
    open-in-view: false
  
  # Flyway 설정
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true  # 기존 DB에 Flyway 도입 시 필요
    baseline-version: 0
    validate-on-migrate: true  # 마이그레이션 검증 필수
    out-of-order: false  # 순서 엄격 적용
```

---

## 트러블슈팅

### 문제: "Found non-empty schema(s) ... but no schema history table"
**원인**: 이미 테이블이 있는데 Flyway를 처음 도입
**해결**:
```yaml
spring.flyway.baseline-on-migrate: true
```

### 문제: "Validate failed: Migration checksum mismatch"
**원인**: 이미 적용된 마이그레이션 파일을 수정함
**해결**:
```bash
# ❌ 마이그레이션 파일 수정 금지!
# ✅ 새 마이그레이션 파일(V{N+1})로 변경 적용
```

### 문제: "Schema-validation: missing table [xxx]"
**원인**: Flyway 마이그레이션에 테이블이 없는데 Entity는 있음
**해결**:
1. V{N}__add_xxx_table.sql 생성
2. 또는 baseline을 다시 생성 (초기 단계만!)

---

## 도구 스크립트

### 스키마 추출 스크립트
위치: `tools/db/baseline/dump_schema.sh`

```bash
#!/bin/bash
# 사용법: ./dump_schema.sh dwp_main main-service

DB_NAME=$1
SERVICE_NAME=$2

if [ -z "$DB_NAME" ] || [ -z "$SERVICE_NAME" ]; then
  echo "Usage: $0 <db_name> <service_name>"
  exit 1
fi

OUTPUT_FILE="dwp-${SERVICE_NAME}/src/main/resources/db/migration/V1__baseline.sql"

pg_dump -h localhost -U dwp_user -d "$DB_NAME" \
  --schema-only --no-owner --no-privileges \
  > "$OUTPUT_FILE"

echo "✅ Baseline generated: $OUTPUT_FILE"
```

---

## 다음 단계
- [x] C21: 본 문서 작성 완료
- [ ] C22: main-service baseline 생성 (테이블 설계 후)
- [ ] C23: mail/chat/approval baseline 생성 (테이블 설계 후)

---

## 참고
- [Flyway 공식 문서](https://flywaydb.org/documentation/)
- [Spring Boot Flyway Integration](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.flyway)
- DWP Backend Rules: [docs/essentials/PROJECT_RULES_BACKEND.md](../../essentials/PROJECT_RULES_BACKEND.md)
