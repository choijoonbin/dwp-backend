# [FE→BE 확인 요청] Admin API 연동 — FE 연동 시 고려사항 관련

> **작성일**: 2026-01-23  
> **관련 문서**: FE `docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_result.md` § 5. FE 연동 시 고려사항  
> **목적**: FE에서 BE 확인이 필요한 항목만 정리. 답변 주시면 FE 반영을 마무리하겠습니다.

---

## 1. 확인 요청 (답변만 주시면 FE 반영 가능)

### 1.1. Audit Logs Detail (`GET /api/admin/audit-logs/{id}`)

| # | 항목 | 내용 | 질문 |
|---|------|------|------|
| 1 | **응답 필드명** | 고려사항 5.1: "`actorUserId`(Long)만 반환" | 응답 JSON에 **`actor`** 필드가 있나요, **`actorUserId`** 필드만 있나요? 둘 다 있다면 각각의 타입(string/number)과 용도를 알려주세요. |
| 2 | **actorName / resourceName** | 현재 미제공. FE는 당장 `actor`/`actorUserId`는 ID 그대로 표시, `resourceName`은 `resourceType`+`resourceId`로 대체 표시 가능. | **추가 보완 예정**이 있나요? (예: `actorName`, `resourceName` 필드 추가) 있다면 일정이 어떻게 되는지 알려주시면, FE에서는 그때 UI를 보강하겠습니다. |

---

### 1.2. Code Usages Detail (`GET /api/admin/code-usages/{id}`)

| # | 항목 | 내용 | 질문 |
|---|------|------|------|
| 3 | **createdBy / updatedBy 타입** | BE: **Long (user id)**. FE는 `string \| number \| null`로 수용해 두었습니다. | 현재 스펙 그대로 **Long 유지**로 이해해도 될까요? 표시명(문자열) 확장 시, 별도 API(예: `GET /users/{id}`)로 조회하는 방식을 가정해도 될까요? |

---

### 1.3. UserSummary / RoleDetail (`lastLoginAt`, `updatedAt`)

| # | 항목 | 내용 | 질문 |
|---|------|------|------|
| 4 | **null 가능성** | FE는 두 필드 모두 **optional·null 가능**으로 처리합니다. | 이번 BE 작업에서 User/Role **서비스·매핑**에서 `lastLoginAt`, `updatedAt`을 채우도록 수정하셨나요? 안 하셨다면, **추가 보완 예정** 여부와 대략 일정을 알려주시면 FE 표시 여부를 결정하겠습니다. |

---

## 2. FE에서 이미 반영한 사항 (참고)

- **AuditLogDetail**
  - `actor`: `string \| number \| null` 수용, `actorUserId` 필드 추가.
  - 실행자 표시: `actor ?? actorUserId` 사용, 없으면 `-`.
  - `ipAddress`, `userAgent`, `beforeValue`, `afterValue`: 감사 로그 상세 Drawer에 표시 블록 추가.
- **CodeUsageDetail**
  - `createdBy`, `updatedBy`: `string \| number \| null` (BE Long 수용).
- **UserSummary / RoleDetail**
  - `lastLoginAt`, `updatedAt`: 기존 optional 유지, UI에서 null 처리.
- **결과 문서**
  - FE `docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_result.md`에 **§ 5. FE 연동 시 고려사항**, **§ 6. 다음 단계 제안** 반영.

---

## 3. 답변 형식 (편하신 대로)

아래 형식으로 답변해 주시거나, 이 문서에 직접 보완해 주셔도 됩니다.

```text
1.1 #1: [ actor / actorUserId 필드 구성 설명 ]
1.1 #2: [ actorName, resourceName 추후 추가 여부 및 일정 ]
1.2 #3: [ createdBy/updatedBy Long 유지 여부, 표시명 조회 방식 가정 가능 여부 ]
1.3 #4: [ lastLoginAt/updatedAt 서비스 매핑 반영 여부, 추후 보완 예정 여부 ]
```

---

## 4. BE 답변 (2026-01-23)

### 1.1 #1: 응답 필드 — `actor` / `actorUserId`

- **`actorUserId`(Long)만** 응답에 있습니다. **`actor` 필드는 없습니다.**
- **`actorUserName`(String)** 필드는 DTO에 정의되어 있으나, 현재 `toAuditLogDetail`에서 값을 넣지 않아 **항상 null**입니다.
- **정리**: FE는 `actorUserId`(number)만 사용하시면 됩니다. `actor ?? actorUserId` 대신 **`actorUserId`만** 참조하시고, 없으면 `-` 처리해 주세요.

---

### 1.1 #2: actorName / resourceName 추후 추가

- **`actorUserName`**: DTO 필드는 있으나 미매핑. `com_users` 조인 등으로 채우는 보완은 **추후 검토 대상**이며, **일정은 미정**입니다.
- **`resourceName`**: DTO에 없음. `com_resources` 조인으로 필드 추가·매핑하는 보완은 **추후 검토 대상**이며, **일정은 미정**입니다.
- FE에서는 당분간 **실행자:** `actorUserId` 그대로 또는 `-`, **리소스:** `resourceType` + `resourceId` 조합으로 표시하시면 됩니다. 보완 시 스펙이 바뀌면 공유하겠습니다.

---

### 1.2 #3: createdBy / updatedBy (CodeUsageDetail)

- **Long 유지**로 이해하시면 됩니다. 현재 스펙 그대로 **`createdBy`, `updatedBy`는 Long (user_id) 또는 null**입니다.
- **표시명(문자열)**: `GET /api/admin/users/{comUserId}`로 사용자 상세를 조회한 뒤 `userName`(displayName) 등을 쓰는 방식을 **가정해도 됩니다.** `createdBy`/`updatedBy`가 null이면 호출 생략·`-` 처리하시면 됩니다.

---

### 1.3 #4: UserSummary / RoleDetail — lastLoginAt, updatedAt

- **UserSummary**
  - **`lastLoginAt`**: `UserQueryService.toUserSummary`에서 `sys_login_histories` 기반으로 매핑하고 있습니다. 로그인 이력이 없으면 **null**입니다.
  - **`updatedAt`**: `user.getUpdatedAt()`(BaseEntity)에서 매핑하고 있습니다. **null 가능성은 낮으나**, optional로 두고 FE에서 null 처리하신다는 전제면 문제 없습니다.
- **RoleDetail**
  - **`lastLoginAt`**: 역할에는 해당 없어 **필드 없음**.
  - **`updatedAt`**: `RoleQueryService.getRoleDetail`에서 `role.getUpdatedAt()`로 **매핑되어 있습니다.**  
- **정리**: 이번 BE 작업에서 **UserSummary의 lastLoginAt·updatedAt, RoleDetail의 updatedAt**은 **서비스·매핑에 반영된 상태**입니다. 별도 추가 보완 계획 없고, FE에서 두 필드 모두 optional·null 가능으로 처리하시면 됩니다.

---

## 5. FE 추가 답변 (추가로 전달할 사항)

*(§4 BE 답변 확인 후, FE에서 추가로 전달할 의견·질문이 있으면 기재)*

§4 BE 답변 확인했습니다. 아래와 같이 FE 반영·이해하고, 추가 전달사항만 적습니다.

---

### 1.1 #1 반영

- **`actor` 필드 제거**, **`actorUserId`(number)만** 참조하도록 수정하겠습니다. (`actor ?? actorUserId` → `actorUserId`만 사용, 없으면 `-`)
- DTO의 `actorUserName`은 항상 null이므로 FE에서는 사용하지 않겠습니다.

---

### 1.1 #2 반영

- 당분간 **실행자:** `actorUserId` 그대로 또는 `-`, **리소스:** `resourceType` + `resourceId` 조합으로 표시하겠습니다.
- `actorUserName`, `resourceName` 보완 시 스펙이 바뀌면 공유해 주시면 그때 UI 반영하겠습니다. **추가 질문 없음.**

---

### 1.2 #3 반영

- **`createdBy`, `updatedBy` Long (user_id) 또는 null** 스펙 유지로 이해했습니다.
- 표시명(문자열)은 **당분간 ID 그대로 표시**하고, 필요 시 `GET /api/admin/users/{comUserId}`로 `userName`(displayName) 등을 조회하는 방식은 **추후 연동 검토**하겠습니다. null이면 `-` 처리합니다.

---

### 1.3 #4 반영

- **UserSummary** `lastLoginAt`, `updatedAt` · **RoleDetail** `updatedAt` 서비스·매핑 반영 확인했습니다. FE에서 **optional·null 가능**으로 그대로 두겠습니다.
- **RoleDetail `lastLoginAt` 필드 없음** 안내에 따라, FE 타입·UI에서는 **해당 필드를 기대하지 않고**, 미제공 시 표시 생략하겠습니다.

---

### 추가 전달·질문

- **없습니다.** 위 반영으로 연동 마무리하겠습니다. 보완 스펙 공유 시 해당 문서·API 스펙 위치만 알려 주시면 FE 반영하겠습니다.

---

감사합니다.
