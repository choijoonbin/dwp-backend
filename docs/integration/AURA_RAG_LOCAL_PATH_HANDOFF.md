# Aura RAG 로컬 경로 수집 — 백엔드 전달·확인 사항

> **기준**: Aura 팀 작업 내용 (ingest-from-path, 경로 검증, 청킹/벡터화 스택)  
> **일자**: 2026-02-11

---

## 1. Aura 측 정리 (참고)

- **경로 검증**: `validate_local_document_path(document_path, allowed_base=settings.rag_allowed_document_base_path)` — 존재·파일·읽기 권한·allowed_base 하위 여부 검사.
- **API**: **POST /aura/rag/ingest-from-path**  
  - Body: `document_path`(필수), `rag_document_id`(선택), `metadata`(선택)  
  - 확장자: **.pdf, .txt, .md** 만 허용  
  - 응답: 202 + job_id, rag_document_id, document_path
- **설정**: `rag_allowed_document_base_path` (RAG_ALLOWED_DOCUMENT_BASE_PATH) — 이 경로 하위만 허용.
- **청킹**: langchain-text-splitters (RecursiveCharacterTextSplitter / CharacterTextSplitter), **벡터화**: langchain-openai (text-embedding-3-small), **PDF**: pypdf.

---

## 2. 백엔드 → Aura 전달 내용

### 2.1 저장 경로 (allowed_base 정합 필요)

백엔드가 로컬 파일을 저장하는 경로는 아래와 같습니다.

| 항목 | 값 |
|------|-----|
| **설정 키** | `storage.local.path` |
| **기본값** | `/data/dwp-storage/documents` |
| **환경변수** | `RAG_STORAGE_LOCAL_PATH` |

**Aura 측 설정 권장**:  
`RAG_ALLOWED_DOCUMENT_BASE_PATH`를 **위 경로 또는 그 상위 디렉터리**로 두어야 합니다.  
예: `/data/dwp-storage/documents` 또는 `/data/dwp-storage`.  
동일 서버/공유 스토리지가 아니면, Aura가 접근 가능한 공유 경로로 맞춰야 합니다.

### 2.2 현재 백엔드 호출 방식

| 상황 | 백엔드 호출 |
|------|-------------|
| 로컬 파일 업로드 후 | **POST /aura/rag/documents/{docId}/vectorize** (기존)  
  Body: tenantId, docId, docType, title, **document_path**(절대 경로) 등 |

Aura에서 **POST /aura/rag/ingest-from-path** 를 로컬 경로 수집용으로 사용한다면, 백엔드는 다음 중 하나로 정리할 수 있습니다.

- **옵션 A**: 기존대로 **documents/{docId}/vectorize** + body에 `document_path` 전달 (Aura가 이 엔드포인트에서 경로 수집 지원 시).
- **옵션 B**: 로컬 파일인 경우 **ingest-from-path** 호출로 전환  
  - `POST /aura/rag/ingest-from-path`  
  - Body: `document_path`(필수), `rag_document_id`(docId), `metadata`(선택)  
  - Aura가 202 + job_id 반환 시, 백엔드에서 status = PROCESSING 등으로 처리.

**확인 요청**: Aura에서 로컬 경로 수집 시 **vectorize**와 **ingest-from-path** 중 어떤 API를 사용할지, 그리고 백엔드가 호출해야 할 최종 스펙(URL, 필수/선택 필드)을 알려주시면 백엔드 호출을 그에 맞춰 수정하겠습니다.

### 2.3 확장자

Aura는 **.pdf, .txt, .md** 만 허용합니다.  
백엔드는 업로드 시 확장자 제한을 두지 않았으므로, 필요 시 다음 중 하나로 정합할 수 있습니다.

- **FE**: 업로드 허용 확장자를 .pdf, .txt, .md 로 제한 (권장).  
- **BE**: (선택) `POST /api/synapse/rag/documents` multipart 수신 시 확장자 검사 추가.

---

## 3. 요약 (따로 전달할 내용)

| 대상 | 전달 내용 |
|------|------------|
| **Aura** | 백엔드 로컬 저장 경로는 **storage.local.path** 기본값 `/data/dwp-storage/documents`. **RAG_ALLOWED_DOCUMENT_BASE_PATH**를 이 경로(또는 상위)로 설정해 주세요. 로컬 경로 수집 시 **vectorize** vs **ingest-from-path** 중 어떤 API를 백엔드가 호출해야 하는지 스펙 알려주시면 반영하겠습니다. |
| **백엔드** | Aura 응답에 따라 필요 시 **ingest-from-path** 호출로 전환. 확장자 제한(.pdf, .txt, .md)은 FE 또는 BE에서 적용 검토. |
