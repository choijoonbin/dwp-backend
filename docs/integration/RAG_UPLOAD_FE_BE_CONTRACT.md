# RAG 문서 업로드 (Multipart) — FE·BE 계약

> **목적**: `POST /api/synapse/rag/documents` multipart 규격 정리  
> **일자**: 2026-02-11

---

## 백엔드 수신 스펙 (현재 구현)

| Part/Param | 타입 | 필수 | 설명 |
|------------|------|------|------|
| **file** | File (multipart part) | ✅ | 업로드 파일. **허용 확장자: .pdf, .txt, .md** (Aura와 동일). 그 외는 400 |
| **title** | form field (string) | ❌ | 문서 제목. 없으면 **원본 파일명**으로 저장 |
| **docType** | form field (string) | ❌ | 문서 유형. 예: `REGULATION`, `MANUAL`, `POLICY`, `GENERAL`. 없으면 `GENERAL` |

- **Content-Type**: `multipart/form-data` (boundary는 브라우저 자동 설정 — FE에서 `Content-Type` 헤더 생략 권장)
- **헤더**: `X-Tenant-ID` 필수

백엔드는 **`metadata` part를 파싱하지 않습니다.**  
`title`, `docType`은 **form 필드로만** 받습니다.

---

## 프론트 수정 사항 (현재 FE 구현 대비)

현재 FE가 보내는 형식:

- `formData.append('metadata', JSON.stringify(metadata))`  
  → 백엔드에서 사용하지 않음
- `formData.append('file', file)`  
  → ✅ 유지

**수정 제안:**

1. **`metadata` part 제거**  
   - `metadata` JSON 문자열 part는 보내지 않음.

2. **form 필드로 `title`, `docType` 전송**  
   - 제목: `formData.append('title', title)`  
   - 문서 유형: `formData.append('docType', docType)`  
   - 예: `docType`은 `REGULATION` | `MANUAL` | `POLICY` | `GENERAL` 중 하나 (선택).

3. **전송 예시 (참고)**  
   ```ts
   const formData = new FormData();
   formData.append('file', file);           // 필수
   if (title?.trim()) formData.append('title', title.trim());
   if (docType?.trim()) formData.append('docType', docType.trim());
   // metadata part 사용하지 않음
   ```

4. **URL/S3 전용 등록**  
   - 파일 없이 메타만 등록하는 경우(URL/S3)는  
     **POST /api/synapse/rag/documents/register**  
     + **Content-Type: application/json**  
     + body: `RegisterRagDocumentRequest` (title, sourceType, s3Key?, url?, docType? 등)  
   - 로컬 파일 업로드는 **POST /api/synapse/rag/documents** (multipart)만 사용.

---

## 요약

| 항목 | FE 현재 | BE 기대 | 조치 |
|------|---------|---------|------|
| file | ✅ append('file', file) | ✅ @RequestParam("file") | 유지 |
| metadata | ❌ append('metadata', JSON) | 사용 안 함 | **제거** |
| title | metadata 안에 포함? | @RequestParam("title") | **form 필드로 title 전송** |
| docType | metadata 안에 포함? | @RequestParam("docType") | **form 필드로 docType 전송** |

프론트는 **metadata part 제거** 후 **title, docType을 form 필드로만** 보내면 됩니다.
