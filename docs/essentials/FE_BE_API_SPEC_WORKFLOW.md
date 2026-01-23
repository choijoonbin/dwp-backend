# FE-BE API 스펙 협업 업무 정의

> **목적**: 프론트엔드와 백엔드 간 API 요청·검토·완료 문서 교환 절차를 정의한다.  
> **적용**: API 개발 요청, 스펙 검토, 보완 요청, 결과 공유

---

## 1. 폴더 정의

### 1.1. 프론트 → 백엔드 (요청·검토 문서)

| 구분 | 경로 | 설명 |
|------|------|------|
| **프론트 업로드 위치** | `docs/backend-src/docs/api-spec/` | 프론트 레포의 **백엔드 링크 폴더** 하위. `backend-src`는 백엔드 레포를 가리킴. |
| **백엔드 작업 위치** | `docs/api-spec/` | 위와 동일한 물리 경로(백엔드 레포 기준). **BE는 여기서 요청 문서를 확인 후 작업**한다. |

**규칙**
- 프론트는 작업 요청·검토·추가 질문 문서를 `docs/backend-src/docs/api-spec/`에 업로드한다.
- 백엔드는 `docs/api-spec/`에서 해당 문서를 확인하고, 작업을 수행한다.

### 1.2. 백엔드 → 프론트 (공유 문서)

| 구분 | 경로 | 설명 |
|------|------|------|
| **백엔드 업로드 위치** | `docs/frontend/docs/api-spec/` | 백엔드 레포의 **프론트 공유 폴더** 하위. 프론트가 확인·수령하는 폴더. |

**규칙**

| 용도 | 파일명 | 시점 |
|------|--------|------|
| **로드맵 (검토 요청)** | `{요청문서제목}_ROADMAP.md` | 요청 문서 확인 후, **작업 전**에 “이렇게 진행할 예정”을 정리해 프론트 검토 요청 시 |
| **완료·결과** | `{요청문서제목}_result.md` | **작업 완료 후** |

- 예: `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION.md`  
  - 검토 요청: `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_ROADMAP.md`  
  - 완료: `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_result.md`

---

## 2. 요청 문서 버전 관리 (프론트)

- 프론트가 **추가 질문·수정 요청**을 반영할 때는 **요청 문서**를 `_v1`, `_v2` 식으로 업데이트한다.
- 예:  
  - `FRONTEND_API_REQUEST_XXX.md` → `FRONTEND_API_REQUEST_XXX_v1.md` (1차 수정)  
  - 동일 제목 유지 + `_v2` (2차 수정)  
- 백엔드는 `docs/api-spec/`에서 최신 버전(`_v1`, `_v2` 등)을 확인하여 작업한다.

---

## 3. 업무 흐름 요약

```
[프론트]
  → 요청/검토 문서 작성
  → docs/backend-src/docs/api-spec/ 에 업로드
  → (추가 질문 시) 요청문서제목_v1, _v2 로 업데이트

[백엔드]
  → docs/api-spec/ 에서 요청 문서 확인
  → (선택) 검토 요청 시 docs/frontend/docs/api-spec/ 에
    {요청문서제목}_ROADMAP.md 로 로드맵 업로드 → 프론트 검토·피드백
  → 작업 수행
  → 완료 후 docs/frontend/docs/api-spec/ 에
    {요청문서제목}_result.md 로 결과 문서 업로드

[프론트]
  → docs/frontend/docs/api-spec/ (또는 backend-src를 통해 동일 경로) 에서
    *_result.md 확인
  → 작업 진행 또는 추가 질문 시 요청 문서 v1, v2로 업데이트
  → 최종 완료 시까지 반복
```

### 3.1. ROADMAP 검토 후 프론트 응답 예시

프론트가 `_ROADMAP.md`를 확인한 뒤 **이견이 없을 때** 백엔드에 전달할 수 있는 예시 문구:

> "ROADMAP 확인했습니다. P0-2, P1 항목은 이대로 진행해 주세요.  
> 완료되면 **FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_result.md**로 결과를 **docs/frontend/docs/api-spec/**에 업로드해 주시면, 그 기준으로 FE 작업 진행하겠습니다."

- **BE**: 프론트가 “docs/api-spec에 업로드”라고 할 경우, BE 기준 **결과 업로드 위치**는 `docs/frontend/docs/api-spec/`(프론트 공유 폴더)이다.
- **일부만 진행**할 때: 예시처럼 “P0-2, P1 항목은 이대로 진행” 등으로 범위를 지정할 수 있다.
- **이견이 있을 때**: 요청 문서를 `_v1`, `_v2`로 수정하여 재전달하거나, 별도 코멘트로 전달.

---

## 4. 백엔드 담당자 체크리스트

- [ ] **작업 전**: `docs/api-spec/`에 프론트 요청 문서가 있는지 확인.
- [ ] **검토 요청 시**(선택): `docs/frontend/docs/api-spec/`에 `{요청문서제목}_ROADMAP.md` 로 로드맵 업로드. “이렇게 작업할 예정이니 검토해달라”는 용도.
- [ ] **작업 후**: `docs/frontend/docs/api-spec/`에 `{요청문서제목}_result.md` 형태로 결과 문서 업로드.
- [ ] **반복 시**: `_v1`, `_v2` 등 수정된 요청 문서를 확인한 뒤, 동일한 `_result` 문서를 갱신하거나 `_result_v1` 등으로 구분하여 업로드 가능.

---

## 5. 참고

- 요청 문서 작성 템플릿: `docs/api-spec/FRONTEND_API_REQUEST_TEMPLATE.md`
- API 스펙·로드맵: `docs/api-spec/` 내 각 스펙 문서
