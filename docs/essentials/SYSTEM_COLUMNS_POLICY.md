# 시스템 컬럼 정책 (System Columns Policy)

프로젝트 전반에서 **시스템이 관리하는 공통 컬럼**의 정의, 사용처, JPA 연동 방법을 정리합니다.

---

## 0. 디폴트 규칙 (필수)

> **모든 서비스의 DB에서 테이블 생성 시, 시스템 컬럼은 디폴트로 모두 포함한다.**

- **대상**: dwp-auth-server, dwp-main-service 및 향후 추가되는 **모든 서비스**의 Flyway 마이그레이션(V*__*.sql)에서 **신규 CREATE TABLE** 시.
- **포함할 컬럼**:
  - **감사 4컬럼**: `created_at`, `created_by`, `updated_at`, `updated_by` → **항상** 포함.
  - **테넌트 단위 테이블**: `tenant_id` + `idx_{table}_tenant_id` 인덱스 → 해당 시 **추가** 포함.
- **예외**: 특수한 사유(예: 외부 연동 전용 로그, 임시 스테이징 테이블 등)로 일부 생략이 불가피한 경우에 한해, **마이그레이션 파일에 예외 사유를 주석으로 명시**하고 팀 협의 후 진행. **기본값은 “포함”**이다.

---

## 1. 시스템 컬럼 목록

### 1.1 감사(Audit) 컬럼 — 디폴트 필수

| 컬럼명       | 타입         | NULL | 설명 |
|-------------|--------------|------|------|
| `created_at`  | TIMESTAMP    | NOT NULL | 등록 시각 |
| `created_by`  | BIGINT       | NULL | 등록자 user_id (논리적 참조: com_users.user_id) |
| `updated_at`  | TIMESTAMP    | NOT NULL | 수정 시각 |
| `updated_by`  | BIGINT       | NULL | 수정자 user_id (논리적 참조: com_users.user_id) |

- **적용**: 사용자/관리자가 생성·수정하는 모든 테이블. **신규 테이블은 디폴트로 위 4컬럼 모두 포함.**

### 1.2 멀티테넌시 컬럼 — 테넌트 단위 테이블 디폴트 필수

| 컬럼명     | 타입   | NULL | 설명 |
|-----------|--------|------|------|
| `tenant_id` | BIGINT | NOT NULL* | 테넌트 식별자 (데이터 격리용) |

- `*` `com_resources` 등 일부는 `tenant_id` NULL 허용(글로벌 리소스).
- **적용**: 테넌트 단위로 격리되는 모든 테이블. `BaseEntity`에 포함하지 않고 엔티티별로 선언. **해당 테이블은 디폴트로 `tenant_id` 및 인덱스 포함.**

---

## 2. 선택적/미도입 시스템 컬럼

현재 프로젝트에서는 **미사용**. 도입 시 팀 협의 후 정책에 반영.

| 컬럼/어노테이션 | 용도 |
|-----------------|------|
| `deleted_at` (TIMESTAMP) | 소프트 삭제 시각 |
| `is_deleted` (BOOLEAN)   | 소프트 삭제 플래그 |
| `@Version` (Long)        | 낙관적 잠금(Optimistic Locking) |

---

## 3. JPA 연동

### 3.1 BaseEntity (감사 4컬럼) — **dwp-core 공통**

- **위치**: `com.dwp.core.entity.BaseEntity` (dwp-core, JPA 사용 서비스만 로드)
- **dwp-auth-server / dwp-main-service**: 엔티티에서 `extends com.dwp.core.entity.BaseEntity` 사용

구조:

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by")
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;
}
```

- **엔티티**: 감사 대상 테이블은 `extends BaseEntity` 후 `created_at/created_by/updated_at/updated_by` 필드 **중복 선언 금지**.

### 3.2 JPA Auditing 설정

- `@EnableJpaAuditing(auditorAwareRef = "auditorProvider")` 를 Application 클래스에 선언.
- `AuditorAware<Long> auditorProvider()` Bean 구현:
  - **dwp-auth-server**: JWT `sub` → `Long` 파싱 (Spring Security `Jwt`).
  - **dwp-main-service**: 현재 `Optional.empty()` 반환 (인증 컨텍스트 미연동). 추후 JWT/인증 연동 시 `Long` 반환으로 변경.

동작:

- **Insert**: `created_at`, `created_by`, `updated_at`, `updated_by` 모두 자동 설정.
- **Update**: `updated_at`, `updated_by`만 자동 갱신. `created_at`, `created_by` 유지.
- 로그인/컨텍스트 없으면 `created_by`, `updated_by` = null.

### 3.3 tenant_id

- `BaseEntity`에 포함하지 않음. 테넌트 단위 엔티티는 각자 `@Column(name = "tenant_id")` 선언.
- 조회/저장 시 `tenant_id` 필터 및 설정은 서비스/레포지토리 레벨에서 필수.

---

## 4. Flyway 마이그레이션 규칙

- **신규 테이블 (디폴트)**: `created_at`, `created_by`, `updated_at`, `updated_by` 4컬럼 **항상** 포함 + `COMMENT` 추가. 테넌트 단위면 `tenant_id` 및 `idx_{table}_tenant_id` 인덱스 포함.
- **기존 마이그레이션**: INSERT/UPDATE 시 `created_by`/`updated_by` 생략 가능(시드/마이그레이션 편의). 테이블 DDL에는 4컬럼 존재해야 함.

### 4.1 신규 테이블 DDL 스니펫 (복사용)

```sql
-- 감사 4컬럼 (모든 신규 테이블 디폴트)
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT

-- 테넌트 단위 테이블인 경우, PK/비즈니스 컬럼 앞에 추가
--     tenant_id BIGINT NOT NULL,
--     ... (비즈니스 컬럼) ...
--     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     created_by BIGINT,
--     updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     updated_by BIGINT
-- 인덱스: CREATE INDEX idx_{table}_tenant_id ON {table}(tenant_id);

-- COMMENT (테이블별로 컬럼명만 바꿔서 사용)
COMMENT ON COLUMN {table}.created_at IS '생성일시';
COMMENT ON COLUMN {table}.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN {table}.updated_at IS '수정일시';
COMMENT ON COLUMN {table}.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';
```

---

## 5. 서비스별 적용 현황

| 서비스 | BaseEntity | @EnableJpaAuditing | AuditorAware | 비고 |
|--------|------------|--------------------|--------------|------|
| dwp-auth-server | ✅ | ✅ | ✅ (JWT sub→Long) | 22개 엔티티 상속 |
| dwp-main-service | ✅ | ✅ | ✅ (현재 empty) | AgentTask 상속 |

---

## 6. 신규 테이블/엔티티 체크리스트

- [ ] **Flyway DDL**: `created_at`, `created_by`, `updated_at`, `updated_by` **디폴트 포함** + COMMENT (위 4.1 스니펫 참고)
- [ ] 테넌트 단위면 `tenant_id` + `idx_{table}_tenant_id` **디폴트 포함**
- [ ] 엔티티: `extends BaseEntity` (해당 서비스), `created_at`/`created_by`/`updated_at`/`updated_by` 별도 필드 없음
- [ ] `@CreationTimestamp`/`@UpdateTimestamp` 대신 BaseEntity(`@CreatedDate`/`@LastModifiedDate`) 사용

---

## 7. 참고

- **dwp-core**: `com.dwp.core.entity.BaseEntity` (시스템 컬럼 공통)
- dwp-auth-server: `AuditorAwareConfig`, `AuthServerApplication`(`@EnableJpaAuditing`)
- dwp-main-service: `AuditorAwareConfig`, `MainServiceApplication`(`@EnableJpaAuditing`), `V3__add_agent_task_audit_columns.sql`

---

## 8. 공통화 현황 및 개선 방향

### 8.1 현재 구성

**BaseEntity(감사 4컬럼)는 dwp-core에서 공통 제공됩니다.** AuditorAware·@EnableJpaAuditing은 “현재 사용자” 해석·클래스패스 이슈로 **서비스별로 두고 있습니다.**

| 구성요소 | dwp-auth-server | dwp-main-service | dwp-core |
|----------|-----------------|------------------|----------|
| **BaseEntity** | `extends com.dwp.core.entity.BaseEntity` | `extends com.dwp.core.entity.BaseEntity` | `com.dwp.core.entity.BaseEntity` ✅ |
| **AuditorAware** | `AuditorAwareConfig` (JWT sub→Long) | `AuditorAwareConfig` (Optional.empty()) | 없음 (서비스별) |
| **@EnableJpaAuditing** | `AuthServerApplication`에 선언 | `MainServiceApplication`에 선언 | 없음 (서비스별) |

- **BaseEntity**: dwp-core `com.dwp.core.entity.BaseEntity`로 **공통화 완료**. auth 20개·main 1개 엔티티가 상속.
- **AuditorAware**: “현재 사용자 ID”를 어디서 가져올지가 서비스마다 다르므로, **구현체는 서비스별 유지**가 적합함 (auth: JWT/SecurityContext, main: 인증 미연동 시 empty 등).
- **@EnableJpaAuditing**: 각 Application에 중복 선언.

### 8.2 공통화 결과 (BaseEntity 적용 완료)

| 구성요소 | 공통화 | 비고 |
|----------|--------|------|
| **BaseEntity** | ✅ **dwp-core로 이전 완료** | `com.dwp.core.entity.BaseEntity`. auth/main 엔티티가 `extends` 사용. |
| **AuditorAware** | ❌ 서비스별 유지 | 현재 사용자 해석이 서비스마다 상이. `auditorProvider` Bean은 각 서비스에서 정의. |
| **@EnableJpaAuditing** | ⚠️ 서비스별 유지 | JPA 미사용 서비스(mail, chat 등) 대비, **각 Application에 선언 유지.** |

- **BaseEntity**: dwp-core `com.dwp.core.entity.BaseEntity`로 통일. auth 20개 + main 1개 엔티티가 상속.
- **AuditorAware**·**@EnableJpaAuditing**: “현재 사용자”·클래스패스 이슈로 **서비스별 구성 유지**.
