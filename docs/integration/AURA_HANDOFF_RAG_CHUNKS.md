# Aura 전달사항 반영 — RAG 청크 연동 (aura.txt 기준)

> **Aura 전달사항(aura.txt)** 에 따라 백엔드 DTO·서비스를 맞춘 내역 요약.  
> 일자: 2026-02-11

---

## 1. Aura 전달 형식 요약 (aura.txt)

- **콜백 URL**: `POST {gateway}/api/synapse/rag/status` (BACKEND_RAG_CHUNKS_SAVE_URL에 동일 URL 설정)
- **Body 최상위**: `rag_document_id`(string), `chunks`, `batch_index`, `total_batches` (선택: docId, status, message)
- **청크 필드**: `chunk_index`, **content**(본문), `embedding`, **metadata**(object, 내부 **page_number**)
- **tenant_id**: Aura는 전달하지 않음. 백엔드가 doc 조회로 tenant_id 확보
- **doc_id**: URL path 또는 body `rag_document_id` 파싱 (숫자 문자열 → Long)

---

## 2. 백엔드 반영 사항

| aura.txt 항목 | 백엔드 조치 |
|---------------|-------------|
| **content** → chunk_text | AuraChunkItemDto에 `@JsonAlias("content")` 추가 (chunkContent에 매핑) |
| **metadata** (객체), **metadata.page_number** | @JsonAlias("metadata") 추가. RAGStorageService에서 page_no 추출 시 **page_number** 키도 확인 |
| **rag_document_id**(string) | RagStatusCallbackRequest에 `ragDocumentId` + @JsonAlias("rag_document_id") 추가. handleStatusCallback에서 docId 없으면 ragDocumentId 파싱해 Long 사용 |
| **batch_index, total_batches** | @JsonIgnoreProperties(ignoreUnknown = true) 로 수신만 허용 (추가 필드로 저장하지 않음) |
| snake_case 수신 | 기존 @JsonAlias 유지 (chunk_index, metadata_json 등) |

---

## 3. 수신 Body 예시 (Aura 형식)

```json
{
  "rag_document_id": "123",
  "chunks": [
    {
      "chunk_index": 0,
      "content": "청크 원문 텍스트",
      "embedding": [ 0.013, -0.018, ... ],
      "metadata": {
        "page_number": 1,
        "file_path": "/path/to/file.pdf",
        "doc_type": "REGULATION"
      }
    }
  ],
  "batch_index": 0,
  "total_batches": 5
}
```

위 형식 그대로 POST하면 백엔드가 수신·저장합니다.

---

## 4. 변경된 파일

| 파일 | 변경 내용 |
|------|-----------|
| AuraChunkItemDto | @JsonAlias("content"), @JsonAlias("metadata") 추가 |
| RagStatusCallbackRequest | ragDocumentId, @JsonIgnoreProperties(ignoreUnknown=true), docId 필수 제거 후 resolveDocId로 통합 |
| RagCommandService | resolveDocId() 추가 — docId 또는 ragDocumentId(string) → Long |
| RAGStorageService | page_no 추출 시 metadata_json에서 page_number 키 추가 확인 |
| AURA_BE_RAG_CHUNK_ALIGNMENT.md | Aura 실제 형식(aura.txt) 반영 |

---

Aura 전달사항(aura.txt)과 백엔드 연동이 위와 같이 맞춰져 있습니다.

---

## 5. Aura 측 최종 회신 요약 (aura.txt)

- **POST /api/synapse/rag/status** 호출: ✅ BACKEND_RAG_CHUNKS_SAVE_URL에 해당 URL 설정 시 배치마다 POST
- **Body**: rag_document_id, chunks, batch_index, total_batches (status/message는 선택·추가 가능)
- **chunks[]**: chunk_index, **content**, embedding(1536), **metadata**(page_number 등) ✅
- 백엔드가 rag_document_id·content·metadata·page_number 수용 완료 → **현재 형식 그대로 전송해 연동**
