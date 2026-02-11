# Aura ↔ 백엔드 RAG 청크(벡터화 결과) 연동 맞춤

> 청크 기능 구현 시 **Aura 팀과 백엔드가 서로 확인해 맞춰야 할 항목** 정리.  
> 일자: 2026-02-11

**Aura 측 회신(aura.txt)**: back.txt §3 체크리스트에 대해 Aura 전부 ✅ 확인. `BACKEND_RAG_CHUNKS_SAVE_URL`에 해당 status URL 설정 시 배치마다 POST, 전송 형식(rag_document_id, content, metadata, page_number 등)은 현재 그대로 사용. 백엔드 반영 완료로 **동일 URL·현재 형식 그대로 연동** 가능.

---

## 1. 흐름 요약

| 단계 | 주체 | 동작 |
|------|------|------|
| 1 | 백엔드 | 문서 등록 후 **POST /aura/rag/documents/{docId}/vectorize** 호출 (document_path 등 전달) |
| 2 | Aura | 벡터화 수행 (청킹·embedding 생성) |
| 3 | Aura | 완료 시 **POST /api/synapse/rag/status** 콜백 호출. **chunks** 포함 가능 |
| 4 | 백엔드 | chunks 수신 시 **rag_chunk** 테이블에 Bulk Insert (chunk_index 순서 유지) |

---

## 2. 맞춤 확인 항목

### 2.1 콜백 URL·메서드

| 항목 | 백엔드 제공 | Aura 확인 |
|------|-------------|-----------|
| URL | `POST {gateway-origin}/api/synapse/rag/status` (예: http://localhost:8080/api/synapse/rag/status) | Aura가 위 URL로 콜백 호출하는가? |
| 인증 | Gateway 경유 시 JWT/헤더 정책에 따름. 내부 호출 시 생략 가능 | Aura 호출 시 헤더(X-Tenant-ID 등) 필요 여부? |

### 2.2 콜백 Body (RagStatusCallbackRequest)

백엔드는 **두 가지 형식** 모두 수용합니다 (Aura 전달사항 aura.txt 반영).

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| docId | number | docId 또는 rag_document_id 중 하나 | rag_document.doc_id |
| **rag_document_id** | **string** | **Aura 전송 형식**. 숫자 문자열이면 docId로 파싱 |
| status | string | 권장 | COMPLETED, FAILED, PROCESSING 등 |
| message | string | 선택 | 오류 시 메시지 |
| chunks | array | 저장 시 | 벡터화 결과 청크 배열 |
| batch_index, total_batches | number | 선택 | Aura 배치 정보. 백엔드는 ignoreUnknown으로 무시 가능 |

### 2.3 청크 1건 형식 (AuraChunkItemDto)

Aura 실제 전송(aura.txt): **chunk_index**, **content**, **embedding**, **metadata**(내부 **page_number**). 백엔드가 모두 수용합니다.

| Aura 전송 키 | 백엔드 수신 필드 | DB 컬럼 | 비고 |
|--------------|------------------|---------|------|
| chunk_index | chunkIndex | chunk_index | 순서 보장 ✓ |
| **content** (또는 chunk_content) | chunkContent | chunk_text | @JsonAlias("content") ✓ |
| embedding | embedding | embedding (vector) | 1536차원 number[] ✓ |
| **metadata** (또는 metadata_json) | metadataJson | metadata_json | 객체 전체 저장 |
| metadata.page_number | — | page_no | 추출해 page_no 컬럼에 설정 ✓ |

- **embedding 차원**: 반드시 **1536**. 그 외는 경고 로그 후 해당 청크는 embedding 없이 저장.

### 2.4 chunk_index 순서

- 백엔드는 **리스트 순서대로** INSERT하며, 각 행의 `chunk_index`는  
  - Aura가 값을 주면 그대로 사용,  
  - 없으면 `(배치 내 인덱스)`로 설정해 **Aura가 넘긴 순서가 DB에 그대로 유지**되도록 함.
- Aura는 **문서 내 등장 순서대로** chunks 배열을 구성해 주면 됨.

### 2.5 저장 전 삭제

- 백엔드는 chunks를 받으면 **해당 doc_id의 기존 rag_chunk를 전부 삭제한 뒤** 새 chunks만 INSERT함.
- 같은 doc에 대해 Aura가 **일부만 보내는 증분 갱신**을 하지 않는다고 가정함. (전체 치환)

---

## 3. Aura 측 확인 요청 (체크리스트)

- [ ] 벡터화 완료 시 **POST {BE Gateway}/api/synapse/rag/status** 를 호출하는가?
- [ ] Body에 **docId**, **status**(COMPLETED/FAILED 등), 선택 **message** 를 넣는가?
- [ ] status=COMPLETED 시 **chunks** 배열을 넣어 주는가? (안 넣으면 BE는 상태만 갱신)
- [ ] chunks[].**chunk_content** (또는 chunk_content) 를 반드시 포함하는가?
- [ ] chunks[].**embedding** 은 **1536차원 float 배열**로 보내는가?
- [ ] chunks[].**chunk_index** (또는 chunkIndex) 로 문서 내 순서를 보내는가? (0-based 권장)
- [ ] **metadata_json** (또는 metadataJson) 에 page_no, file_path 등 필요한 메타를 넣는가?

---

## 4. 백엔드 측 확인 (현재 구현, Aura 전달사항 반영)

- [x] POST /api/synapse/rag/status 수신 시 RagStatusCallbackRequest 파싱 (docId 또는 **rag_document_id**)
- [x] **rag_document_id**(string) 수신 시 Long으로 파싱해 docId로 사용. **batch_index, total_batches**는 ignoreUnknown으로 수신
- [x] chunks 존재 시 RAGStorageService.saveChunks() → JdbcTemplate.batchUpdate (500건 단위), chunk_index 순서 유지
- [x] AuraChunkItemDto: **content** → chunkContent(@JsonAlias), **metadata** → metadataJson, **metadata.page_number** → page_no 추출
- [x] embedding 1536 검사 후 PGvector로 저장. metadata에서 page_no 또는 **page_number** 추출해 page_no 컬럼 설정

---

## 5. 참고 문서

| 문서 | 내용 |
|------|------|
| **aura.txt** (Aura 전달) | RAG 청크 연동 체크리스트, 수신 body 형식(rag_document_id, content, metadata, page_number) |
| AURA_RAG_LOCAL_PATH_HANDOFF.md | 백엔드 저장 경로, vectorize vs ingest-from-path |
| AURA_BACKEND_HANDOFF.md | Aura → BE 전달 사항 전반, Redis workbench:rag:status |
| BACKEND_DATA_HARDENING_BATCH_AND_NAMING.md | BE Batch Insert·CamelCase DTO 정리 |

---

**정리**: Aura 전달사항(aura.txt) 기준으로 백엔드가 **content**·**metadata**·**page_number**·**rag_document_id**·batch_index/total_batches를 반영했습니다. Aura는 동일 URL(`POST /api/synapse/rag/status`)로 현재 형식 그대로 전송하면 연동됩니다.
