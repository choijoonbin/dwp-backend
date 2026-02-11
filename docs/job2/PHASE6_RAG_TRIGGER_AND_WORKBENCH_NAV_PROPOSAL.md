# Phase 6: RAG Trigger & Workbench Navigation — Pre-Check 및 제안

## Pre-Check (MUST ANSWER BEFORE CODING)

### 1. 업로드된 원본 파일 보관 스토리지(S3 등) 경로 설정이 application.yml에 완료되어 있습니까?

**답변: 아니오. 현재 미완료입니다.**

- `services/synapsex-service/src/main/resources/application.yml`에는 **S3/스토리지 관련 설정이 없습니다.**
- `auth.server.url`, `aura.base-url` 등만 있으며, 업로드 파일 저장 경로(bucket, prefix, region 등)는 정의되어 있지 않습니다.

**권장 조치:**

- RAG 문서 업로드 플로우에서 **원본 파일을 저장할 스토리지**를 사용할 경우, 아래와 같이 설정을 추가하고, 업로드 API(또는 별도 Upload API)에서 해당 경로에 저장한 뒤 `s3_key`를 `RegisterRagDocumentRequest`에 넣어 등록하는 방식이 필요합니다.

```yaml
# 예시 (실제 환경에 맞게 bucket/region 등 치환)
storage:
  type: s3   # or local, minio
  s3:
    bucket: ${RAG_UPLOAD_BUCKET:dwp-rag-uploads}
    region: ${AWS_REGION:ap-northeast-2}
    prefix: ${RAG_UPLOAD_PREFIX:rag/documents}
```

- **파일 업로드 API**가 아직 없다면:  
  - 먼저 Multipart 파일 업로드 → S3 등에 저장 → 반환된 키를 `s3_key`로 사용하고,  
  - `POST /synapse/rag/documents`는 **메타만 등록**(title, sourceType=UPLOAD, s3Key) 후 벡터화 트리거로 이어지는 형태를 권장합니다.

---

### 2. 숨겨진 메뉴들에 대한 직접 접근 권한(RBAC)이 통합 워크벤치 사용자에게도 유효한지 확인했습니까?

**답변: 예. 유효합니다.**

- **권한 판단은 `com_role_permissions`의 (역할, 리소스 키, 권한 코드)로 이루어지며, `sys_menus.is_visible`과는 무관합니다.**
- V38에서 `menu.autonomous-operations.anomalies`, `menu.autonomous-operations.cases`, `menu.autonomous-operations.actions`는 GNB 비노출을 위해 `is_visible = 'N'`으로만 변경되었고, **동일 메뉴 키에 대한 `com_role_permissions`는 그대로 유지**됩니다.
- V23/V21 등에서 SYNAPSEX_OPERATOR, SYNAPSEX_VIEWER 등에게 `menu.autonomous-operations.cases`, `menu.knowledge-policy.rag`, `menu.knowledge-policy.policies` 등에 대한 VIEW(및 USE/EDIT 등)가 이미 부여되어 있습니다.
- 따라서 **워크벤치 진입 권한이 있는 사용자**는, 사이드바에는 안 보이더라도 **deepLink로 해당 경로에 직접 접근할 때 RBAC상 접근 가능**합니다.  
  (단, 실제 라우팅은 Gateway/프론트에서 해당 path를 허용하는지 확인 필요.)

---

## Task 1 제안: RAG 벡터화 트리거 및 상태 관리

### 1.1 상태 값 (sys_codes 기반)

- **auth DB**에 코드 그룹 `RAG_DOCUMENT_STATUS`를 추가하고, 다음 코드로 업로드 상태를 관리합니다.
  - `READY` — 업로드/등록 완료, 벡터화 대기
  - `PROCESSING` — Aura 벡터화 진행 중
  - `COMPLETED` — 벡터화 완료 (기존 INDEXED와 동일 의미)
  - (선택) `FAILED` — 벡터화 실패 유지

- **synapse DB**의 `rag_document.status`에는 위 값들을 그대로 저장합니다.  
  기존 `PENDING`/`INDEXED`는 호환을 위해 유지하되, 신규 플로우에서는 READY → PROCESSING → COMPLETED만 사용하도록 합니다.

### 1.2 Aura 벡터화 엔드포인트 계약 (제안)

- **BE → Aura 호출:**  
  `POST /aura/rag/documents/{docId}/vectorize`  
  - **Request:** tenant_id, doc_id, s3_key(또는 url), title 등 메타 (헤더: X-Tenant-ID, Authorization)  
  - **Response:** 202 Accepted + `{ "jobId": "...", "status": "ACCEPTED" }`  
  - 벡터화가 비동기로 끝나면 Aura가 **콜백**으로 BE에 완료/실패를 알리거나, BE에서 **폴링**으로 상태 조회하는 방식 중 하나 선택.

- **구현 방향:**  
  - Synapse에 **WebClient**(또는 기존 Feign Aura 클라이언트 확장)로 위 URL 호출.  
  - `RagCommandService.registerDocument()` 완료 후:  
    1. 문서 상태를 `READY`로 저장(또는 최초 저장 시 READY).  
    2. Aura 벡터화 API 호출.  
    3. 호출 성공 시 상태를 `PROCESSING`으로 업데이트.  
  - 완료/실패는 (1) Aura 콜백 API를 Synapse에 두어 수신 시 `COMPLETED`/`FAILED` 반영하거나, (2) 주기적 폴링으로 Aura 상태 조회 후 반영.

### 1.3 의존성

- **스토리지:** 위 Pre-Check 1대로, 실제 파일을 넣을 S3(또는 동등) 경로 설정이 필요합니다.  
  설정 전에는 `s3_key`를 프론트/업로드 서비스에서 이미 결정된 값으로 넘기는 방식으로 연동할 수 있습니다.

---

## Task 2 제안: 워크벤치 통합 네비게이션(관련 설정 메뉴 + deepLink)

### 2.1 API

- **GET** `/api/v1/synapse/workbench/navigation` (또는 `/synapse/workbench/settings-menus`)  
  - **역할:** 워크벤치 진입 시 “규정 수정”, “정책 변경” 등 **관련 설정 메뉴 목록**을 내려주어, 해당 메뉴로 즉시 점프할 수 있게 함.
  - **응답 DTO 제안:**

```java
// WorkbenchNavigationDto.java
public class WorkbenchNavigationDto {
    private List<WorkbenchSettingMenuDto> relatedSettingsMenus;
}

// WorkbenchSettingMenuDto.java
public class WorkbenchSettingMenuDto {
    private String menuKey;      // e.g. menu.knowledge-policy.rag
    private String label;        // 표시명 (e.g. 규정·문서 라이브러리, 정책 프로파일)
    private String deepLink;     // 프론트 라우트 경로 (e.g. /synapse/rag, /synapse/policies)
}
```

- `deepLink`는 **auth DB의 `sys_menus.menu_path`**와 동일하게 두면, 프론트에서 해당 path로 라우팅했을 때 기존 메뉴와 동일한 화면으로 진입할 수 있습니다.

### 2.2 메뉴 데이터 소스

- 메뉴 메타(menu_key, name_ko, menu_path)는 **auth-server**에 있으므로, 아래 중 하나가 필요합니다.
  1. **Auth에 메뉴 엔트리 조회 API 추가**  
     - 예: `GET /api/auth/menus/entries?keys=menu.knowledge-policy.rag,menu.knowledge-policy.policies,...`  
     - 응답: `List<{ menuKey, menuNameKo, menuPath }>`  
     - 권한: 워크벤치 VIEW가 있는 사용자만 호출 가능하거나, 요청한 키 중 사용자가 VIEW 권한이 있는 메뉴만 반환.
  2. **Synapse가 auth를 Feign/WebClient로 호출**  
     - 위 엔드포인트를 호출해, “워크벤치 관련 설정 메뉴”로 사용할 **고정 menu key 목록**(rag, policies, guardrails, dictionary, governance 등)에 대한 엔트리만 받아와서 `WorkbenchNavigationDto`로 가공해 반환.

### 2.3 권한

- 숨겨진 메뉴(is_visible='N')도 **리소스 키 기준으로 VIEW가 있으면 접근 가능**하므로(Pre-Check 2), `deepLink`로 이동한 화면도 동일 RBAC으로 통제됩니다.  
  별도 “워크벤치 전용 권한”을 새로 두지 않아도 됩니다.

---

## 구현 체크리스트

| 항목 | 담당 | 비고 |
|------|------|------|
| auth: sys_codes RAG_DOCUMENT_STATUS (READY, PROCESSING, COMPLETED) | auth Flyway | 필요 시 FAILED 포함 |
| synapse: application.yml storage placeholder | synapse | 실제 S3 설정은 환경별로 보강 |
| Aura 벡터화 API 계약 문서화 | docs | POST /aura/rag/documents/{docId}/vectorize |
| RagCommandService: 등록 후 READY → Aura 호출 → PROCESSING | synapse | WebClient 또는 Feign 확장 |
| Aura 콜백 또는 폴링으로 COMPLETED/FAILED 반영 | synapse | 별도 스펙 정리 |
| auth: GET /auth/menus/entries?keys=... | auth | MenuService + MenuController |
| synapse: AuthServerMenuClient (Feign) | synapse | auth 메뉴 엔트리 호출 |
| WorkbenchController: GET .../workbench/navigation | synapse | relatedSettingsMenus + deepLink |
| DTO: WorkbenchNavigationDto, WorkbenchSettingMenuDto | synapse | |

---

## 구현 완료 요약 (Phase 6)

- **Auth**: V41 RAG_DOCUMENT_STATUS 코드(READY, PROCESSING, COMPLETED, FAILED) 추가. GET /auth/menus/entries?keys=... (권한 필터된 menuKey, label, deepLink) 추가.
- **Synapse**: storage.rag placeholder (application.yml). RagCommandService: 등록 시 READY → Aura POST /aura/rag/documents/{docId}/vectorize 호출 → 성공 시 PROCESSING. WorkbenchController GET /synapse/workbench/navigation, AuthServerMenuClient, WorkbenchNavigationDto/WorkbenchSettingMenuDto.
- **Aura 계약**: POST /aura/rag/documents/{docId}/vectorize 요청/응답 스펙은 위 §1.2 및 AuraRagVectorizeRequest/Response 참고. 완료/실패 시 콜백 또는 폴링은 별도 스펙.

이 문서를 기준으로 구현 시, Pre-Check 1(스토리지 설정)은 반드시 운영/배포 환경에서 완료할 것.
