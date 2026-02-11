# Phase 6: 테스트 데이터·시드 검증 및 RAG 업로드 연동 점검 보고

## 1. Data Audit 결과

### 1.1 fi_doc_header — DEMO 전표 3건

| 항목 | 결과 |
|------|------|
| **존재 여부** | ✅ **정상 존재** (3건) |
| **전표번호** | DEMO00001, DEMO00002, DEMO00003 |
| **참조** | INV-DUP-001, INV-POL-002, INV-OVD-003 (xblnr), status_code=POSTED |

- V40 시드가 적용된 상태이며, 중복·정책위반·지연미결 시나리오용 최소 데이터는 갖춰져 있음.
- **Data Repair 불필요** (이미 존재).

### 1.2 rag_document

| 항목 | 결과 |
|------|------|
| **건수** | 0건 (비어 있음) |
| **비고** | V40은 rag_document를 TRUNCATE 대상에 넣지 않고 “유지”만 함. 시드 스크립트에는 rag_document INSERT 없음. |

- RAG 문서는 “사용자 등록” 또는 별도 시드로만 채워지는 구조이므로, 현재 비어 있는 것은 설계상 자연스러운 상태임.

### 1.3 rag_chunk — pgvector 타입 준비 여부

| 항목 | 결과 |
|------|------|
| **현재 구조** | chunk_id, tenant_id, doc_id, page_no, chunk_text(TEXT), **embedding_id(TEXT)**, created_at |
| **pgvector 컬럼** | ❌ **없음** |
| **비고** | V13 기준 `embedding_id`는 “벡터 DB 연동 시 사용”용 TEXT. PostgreSQL 내장 pgvector 타입 컬럼은 없음. |

- **결론**: pgvector 확장은 DB에 설치되어 있으나, **rag_chunk에는 아직 `vector` 타입 컬럼이 없음**.  
  벡터 검색을 BE/DB에서 하려면 `ALTER TABLE dwp_aura.rag_chunk ADD COLUMN embedding vector(1536);` 등 별도 마이그레이션으로 컬럼 추가가 필요함.  
  (차원 수 1536은 임베딩 모델에 맞게 조정.)

---

## 2. Missing Logic Check — 파일 업로드 시 RAG·Aura 연동

### 2.1 현재 구현된 흐름

| 단계 | 구현 여부 | 설명 |
|------|------------|------|
| 1. 문서 메타 등록 API | ✅ | `POST /synapse/rag/documents` (JSON: title, sourceType, s3Key, url, checksum) |
| 2. rag_document INSERT | ✅ | `RagCommandService.registerDocument()` — status=READY 후 저장 |
| 3. Aura 벡터화 트리거 | ✅ | 동일 트랜잭션 후 `AuraCaseTabClient.triggerRagVectorize()` → **POST /aura/rag/documents/{docId}/vectorize** 호출, 성공 시 status=PROCESSING |

- 즉, **“메타 등록 → rag_document INSERT → Aura API 호출”**까지의 로직은 구현되어 있음.

### 2.2 미구현·불일치 사항

| 항목 | 상태 | 설명 |
|------|------|------|
| **파일 바이너리 업로드** | ❌ 미구현 | BE에 **MultipartFile**을 받아 S3 등에 저장하고, 그 결과로 `s3_key`를 만들어 주는 엔드포인트는 없음. 현재는 JSON으로 `s3Key`를 넘기는 방식만 지원. |
| **Aura API 경로** | ⚠️ 명세와 상이 가능 | 요청하신 “/api/aura/rag/ingest”가 아닌, BE는 **/aura/rag/documents/{docId}/vectorize** 를 호출함. Aura 플랫폼에서 ingest 경로를 별도 제공한다면, 라우팅/프록시 정합 필요. |
| **업로드 후 콜백** | ⚠️ 선택 | 벡터화 완료/실패 시 status=COMPLETED/FAILED 로 갱신하는 콜백 또는 폴링은 Phase 6 제안서 기준 “별도 스펙”으로 두었음. |

### 2.3 완성도 요약

- **rag_document INSERT + Aura 벡터화 트리거**: ✅ 구현됨.
- **프론트에서 “파일 선택 → 업로드” 한 번으로 끝나게 하려면**:  
  - (A) FE가 파일을 S3(또는 지정 스토리지)에 업로드한 뒤, 받은 키로 `POST /synapse/rag/documents` 호출 → 현재 BE만으로 가능.  
  - (B) FE가 “파일만” BE로 보내고, BE가 저장·메타 등록·Aura 호출까지 처리하려면 → **BE에 Multipart 업로드 API 추가** 필요.

---

## 3. Data Repair 수행 여부

- **fi_doc_header DEMO 3건**: 이미 존재하므로 **수동 인서트 미실행**.
- **rag_document/rag_chunk**: V40 시드 범위에 없으며, “테스트 데이터 준비” 목적이라면 RAG용 시드 문서를 원할 때만 별도 시드 스크립트나 수동 INSERT를 추가하면 됨.

---

## 4. 권장 후속 작업

1. **pgvector 활용**: `rag_chunk`에 `vector` 타입 컬럼 추가 Flyway 마이그레이션 (필요 시).
2. **단일 업로드 UX**: FE에서 파일만 올리면 되도록 하려면, BE에 `POST /synapse/rag/documents/upload` (Multipart) 추가 후, 내부에서 스토리지 저장 → `registerDocument` 호출 흐름 구현.
3. **Aura 경로**: Aura 팀과 `/api/aura/rag/ingest` vs `/aura/rag/documents/{docId}/vectorize` 역할·라우팅 정리.
