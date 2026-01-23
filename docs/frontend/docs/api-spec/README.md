# 프론트 공유용 API 스펙 문서 (BE → FE)

이 폴더에는 **백엔드가 프론트엔드와 공유**하는 API 스펙 관련 문서가 위치합니다.

## 파일 명명 규칙

| 용도 | 파일명 | 시점 |
|------|--------|------|
| **로드맵 (검토 요청)** | `{요청문서제목}_ROADMAP.md` | 작업 전, “이렇게 진행할 예정” 검토 요청 시 |
| **완료·결과** | `{요청문서제목}_result.md` | 작업 완료 후 |

- 예: `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_ROADMAP.md`, `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_result.md`
- 반복 수정 시: `_result_v1.md` 등으로 구분 가능

## 업무 흐름

1. 프론트: `docs/backend-src/docs/api-spec/`에 요청 문서 업로드
2. 백엔드: `docs/api-spec/`에서 요청 확인
3. 백엔드: **(선택)** 검토 요청 시 이 폴더에 `*_ROADMAP.md` 업로드 → 프론트 검토·피드백
4. 백엔드: 작업 수행 후 이 폴더에 `*_result.md` 업로드
5. 프론트: 이 폴더에서 로드맵·결과 확인 후 작업 또는 추가 질문(요청 문서 `_v1`, `_v2` 업데이트)

상세: `docs/essentials/FE_BE_API_SPEC_WORKFLOW.md`
