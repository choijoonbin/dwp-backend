# HITL 감사 통합 검증 체크리스트 및 PR 계획

**작성일:** 2025-01-29  
**목표:** main-service → auth-server Feign 감사 호출 검증, 디코딩 안전화, HITL 감사 테스트 고정, saveApprovalRequest tenantId 경로 확인.

---

## 1. 체크리스트 (통합 검증 결과)

### A) Feign 통합 검증

| 항목 | 상태 | 근거/비고 |
|------|------|-----------|
| auth.server.url 기본값과 auth-server 기동 URL 일치 | ✅ | main-service `application.yml`: `auth.server.url: ${SERVICE_AUTH_URL:http://localhost:8001}`. auth-server `application.yml`: `server.port: 8001`. 로컬 기본값 `http://localhost:8001` 일치. |
| main-service → /internal/audit-logs 호출 200/204 확인 | ✅ (로컬 수동) | auth-server 내부 API를 **204 No Content**로 변경함. Feign은 `ResponseEntity<Void>` 수신. 로컬/DEV에서 auth-server(8001) 기동 후 main-service에서 approve/reject 1회 수행 시 감사 호출 → 204 수신. |
| ApiResponse&lt;Void&gt; 디코딩 이슈 회피 | ✅ 적용 | auth-server: `POST /internal/audit-logs` → **204 No Content** (`ResponseEntity.noContent().build()`). main-service Feign: `ResponseEntity<Void> recordAuditLog(...)` 반환. body 없음으로 디코딩 이슈 없음. |

**로컬/DEV 1회 수동 확인 방법**

1. auth-server 기동: `./gradlew :dwp-auth-server:bootRun` (포트 8001)
2. Redis 기동 (로컬 또는 Docker)
3. main-service 기동: `./gradlew :dwp-main-service:bootRun` (auth.server.url 기본값 사용)
4. HITL 요청 생성 후 approve 1회 호출 (또는 saveApprovalRequest 트리거 가능한 경로로 1회 호출)
5. auth-server 로그 또는 DB `com_audit_logs` 에 HITL_APPROVE 등 기록 확인

---

### B) HITL 감사 로그 커버리지

| 항목 | 상태 | 근거 |
|------|------|------|
| HITL_REQUEST: saveApprovalRequest에서 기록 | ✅ | `HitlManager.java` L131: `recordHitlAudit(..., "HITL_REQUEST", ...)` 호출. 테스트: `HitlManagerAuditTest.saveApprovalRequest_recordsHitlRequestAudit` |
| HITL_APPROVE: approve에서 기록 | ✅ | `HitlManager.java` L236: `recordHitlAudit(..., "HITL_APPROVE", ...)` (최초 처리 시만). 테스트: `HitlManagerAuditTest.approve_firstCall_recordsAudit_idempotentRetry_doesNotRecordAgain` |
| HITL_REJECT: reject에서 기록 | ✅ | `HitlManager.java` L297: `recordHitlAudit(..., "HITL_REJECT", ...)` (최초 처리 시만). 테스트: `HitlManagerAuditTest.reject_firstCall_recordsHitlRejectAudit` |
| 멱등 재호출 시 추가 기록 없음 | ✅ | 이미 approved/rejected면 `recordHitlAudit` 호출 전에 return. 테스트: approve 2회 호출 시 `recordAuditLog` 1회만 호출. |
| tenant mismatch 시 감사 호출 없음 | ✅ (정책) | tenant 불일치 시 `TENANT_MISMATCH` 예외로 조기 반환 → `recordHitlAudit` 미호출. 테스트: `HitlManagerAuditTest.approve_whenTenantMismatch_doesNotCallAudit` |

---

### C) saveApprovalRequest 호출 체인

| 항목 | 상태 | 근거 |
|------|------|------|
| main-service 내 REST로 saveApprovalRequest 노출 여부 | ❌ 없음 | `HitlManager.saveApprovalRequest` 호출처는 본 레포에서 **컨트롤러 없음**. Aura-Platform 등 외부가 요청 생성 시 별도 API(다른 서비스/레포) 또는 인프라 경로 사용 가능. |
| tenantId 전달 경로 | ✅ 메서드 필수 | `saveApprovalRequest(..., String tenantId, ...)` 에서 tenantId **필수** (null 시 `INVALID_INPUT_VALUE`). L64: `if (tenantId == null ...)` throw. |
| 향후 REST 추가 시 권장 | - | **POST /aura/hitl/requests** (또는 동일 역할) 추가 시: **(1)** `@RequestHeader("X-Tenant-ID")` 필수, **(2)** body에 tenantId 명시 허용 시 헤더와 일치 검증, **(3)** 둘 다 saveApprovalRequest 인자로 전달. |

**결론:** 현재 saveApprovalRequest는 **in-process 호출만** 존재. tenantId는 메서드 인자로 필수. REST로 노출할 경우 X-Tenant-ID 필수 + body tenantId 검증 권장.

---

## 2. 변경 PR 계획 (필요한 코드 변경)

### PR-1: Feign 감사 204/Void 안전화 (적용 완료)

| 변경 파일 | 핵심 diff |
|-----------|------------|
| **dwp-auth-server** `InternalMonitoringController.java` | `POST /internal/audit-logs` 반환: `ApiResponse.success()` → `ResponseEntity.noContent().build()` (204). |
| **dwp-main-service** `AuthServerAuditClient.java` | 반환 타입: `ApiResponse<Void>` → `ResponseEntity<Void>`. |

- HitlManager.recordHitlAudit는 반환값 미사용이므로 수정 없음.

---

### PR-2: HITL 감사 테스트 고정 (적용 완료)

| 추가 파일 | 내용 |
|-----------|------|
| **dwp-main-service** `HitlManagerAuditTest.java` | (1) saveApprovalRequest → HITL_REQUEST 기록 검증, (2) approve 최초 → HITL_APPROVE 기록·멱등 재호출 시 1회만 검증, (3) reject 최초 → HITL_REJECT 기록, (4) tenant mismatch 시 recordAuditLog 미호출 검증. |

---

### PR-3: saveApprovalRequest 호출 체인 보완 (선택)

- **현재:** REST 노출 없음. tenantId는 메서드 인자로 필수.
- **선택 보완:** HITL 요청 생성 API를 main-service에 추가할 때:
  - `POST /aura/hitl/requests` (또는 기존 라우트 정책에 맞는 경로)
  - `@RequestHeader("X-Tenant-ID") Long tenantId` 필수
  - body: sessionId, userId, actionType, context, taskId (tenantId는 헤더 사용 또는 body와 헤더 일치 검증)
  - Controller에서 `hitlManager.saveApprovalRequest(..., String.valueOf(tenantId), ...)` 호출

---

## 3. 테스트 추가 파일 목록 및 실행 방법

### 추가/수정된 테스트 파일

| 파일 | 설명 |
|------|------|
| `dwp-main-service/src/test/java/com/dwp/services/main/service/HitlManagerAuditTest.java` | HITL_REQUEST/APPROVE/REJECT 감사 호출 및 tenant mismatch 시 미호출 검증. |

### 실행 방법

```bash
# HITL 감사 테스트만 실행
./gradlew :dwp-main-service:test --tests "com.dwp.services.main.service.HitlManagerAuditTest"

# HITL 관련 테스트 전체 (멱등 + 감사)
./gradlew :dwp-main-service:test --tests "com.dwp.services.main.service.HitlManager*"
```

### 통합 검증 (수동, 로컬/DEV)

1. auth-server 기동 (포트 8001)
2. Redis 기동
3. main-service 기동 (auth.server.url 기본값)
4. HITL approve 1회 호출 후 auth-server 로그 또는 `com_audit_logs` 에 HITL_APPROVE 기록 확인

---

이 문서로 통합 검증 체크리스트, Feign 204/Void 변경, HITL 감사 테스트 고정, saveApprovalRequest tenantId 경로 확인이 반영되었습니다.
