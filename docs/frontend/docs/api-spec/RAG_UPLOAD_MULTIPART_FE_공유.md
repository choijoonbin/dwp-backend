# RAG 규정집 업로드 — 프론트 공유 사항

> **대상**: 프론트엔드 팀  
> **목적**: POST /api/synapse/rag/documents multipart 업로드 최종 스펙 및 수정 사항  
> **일자**: 2026-02-11

---

## 1. API 요약

| 항목 | 내용 |
|------|------|
| **URL** | `POST /api/synapse/rag/documents` |
| **Content-Type** | `multipart/form-data` (boundary는 브라우저 자동 설정 → **헤더에 Content-Type 넣지 않음**) |
| **필수 헤더** | `X-Tenant-ID` |

---

## 2. Form 필드 (백엔드 수신 스펙)

| 필드명 | 필수 | 설명 |
|--------|------|------|
| **file** | ✅ | 업로드 파일. **허용 확장자: .pdf, .txt, .md 만** (그 외는 400) |
| **title** | ❌ | 문서 제목. 없으면 원본 파일명 사용 |
| **docType** | ❌ | `REGULATION` \| `MANUAL` \| `POLICY` \| `GENERAL`. 없으면 `GENERAL` |

- **`metadata` part는 사용하지 않습니다.**  
  title, docType은 **form 필드로만** 보내주세요.

---

## 3. 프론트 수정 사항

1. **metadata part 제거**  
   - `formData.append('metadata', JSON.stringify(metadata))` 제거.

2. **title, docType을 form 필드로 전송**  
   - `formData.append('title', title)`  
   - `formData.append('docType', docType)`

3. **파일 확장자 제한**  
   - 업로드 허용: **.pdf, .txt, .md** 만.  
   - input `accept=".pdf,.txt,.md"` 및 업로드 전 검증 권장 (백엔드에서도 동일 검증하여 400 반환).

---

## 4. 전송 예시

```ts
const formData = new FormData();
formData.append('file', file);   // 필수, .pdf / .txt / .md
if (title?.trim()) formData.append('title', title.trim());
if (docType?.trim()) formData.append('docType', docType.trim());
// metadata part 사용하지 않음

await axiosInstance.postFormData('/api/synapse/rag/documents', formData);
```

---

## 5. URL/S3만 등록하는 경우

파일 없이 메타만 등록할 때:

- **URL**: `POST /api/synapse/rag/documents/register`
- **Content-Type**: `application/json`
- **Body**: `{ title, sourceType, s3Key?, url?, docType? }`

로컬 파일 업로드는 **POST /api/synapse/rag/documents** (multipart)만 사용하면 됩니다.

---

## 6. 에러 (참고)

| 상황 | HTTP | 메시지 예시 |
|------|------|-------------|
| file 없음 | 400 | 업로드 파일이 없습니다. |
| 허용 확장자 아님 | 400 | 허용 확장자는 .pdf, .txt, .md 입니다. 현재: xxx.xxx |
| X-Tenant-ID 없음 | 4xx | Gateway/인증 정책에 따름 |

이 스펙으로 연동하시면 됩니다.
