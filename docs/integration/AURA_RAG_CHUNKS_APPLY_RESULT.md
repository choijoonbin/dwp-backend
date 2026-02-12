# Aura RAG 청크 연동 — 적용 정리 결과

> Aura 상태 공유(aura.txt) 반영. **적용 관련 정리** 후 공유용 결과.

---

## 1. 현재 상태 (Aura 공유 내용 요약)

- **Aura가 벡터화 완료 후 chunks를 백엔드로 보내는 조건**  
  → **`BACKEND_RAG_CHUNKS_SAVE_URL`** 이 Aura에 설정되어 있을 때만, 배치마다 해당 URL로  
  `{ "rag_document_id", "chunks", "batch_index", "total_batches" }` 를 POST 함.
- **현재**  
  → `save_url_set=False` (위 URL 미설정) → Aura는 저장용 POST를 하지 않음.  
  → chunks는 벡터화 API의 200 응답 body(batches 배열)에만 있음.  
  → 따라서 백엔드는 chunks를 콜백으로 받지 못하고, **rag_chunk 테이블이 비어 있는 것이 정상**.

---

## 2. 백엔드 측 준비 상태

| 항목 | 상태 |
|------|------|
| **수신 URL** | **`POST {gateway}/api/synapse/rag/status`** (예: `http://localhost:8080/api/synapse/rag/status`) 이미 구현됨. |
| **수신 Body** | `rag_document_id`(string), `chunks`, `batch_index`, `total_batches` 수용. `docId`, `status`, `message` 등 추가 필드도 지원. |
| **저장** | 수신한 chunks를 **dwp_aura.rag_chunk** 에 Bulk Insert (chunk_index 순서 유지). |

→ **방법 A**(Aura가 배치마다 백엔드로 POST)를 쓰기 위한 **백엔드 쪽 구현은 완료**되어 있음.

---

## 3. rag_chunk를 채우기 위한 적용 사항 (Aura 측)

**방법 A – Aura가 배치마다 백엔드로 POST (권장)**

1. **Aura .env에 저장 URL 설정**
   ```bash
   BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8080/api/synapse/rag/status
   ```
   (실 서버/게이트웨이 주소로 배포 시에는 해당 origin으로 변경.)

2. **동작**
   - Aura가 벡터화 완료 후 위 URL로  
     `{ "rag_document_id", "chunks", "batch_index", "total_batches" }` 를 POST.
   - 백엔드는 기존 API로 수신 후 **rag_chunk** 에 INSERT.

**방법 B** (참고만)  
- `BACKEND_RAG_CHUNKS_SAVE_URL` 미사용.  
- 백엔드가 벡터화 API를 호출한 뒤, 200 응답 body의 **batches**를 파싱해 직접 rag_chunk에 INSERT하도록 구현.  
- 현재 백엔드는 벡터화 API를 “트리거만” 하고 응답 body를 파싱해 저장하는 로직은 없음 → 방법 B를 쓰려면 백엔드 추가 개발 필요.

---

## 4. 한 줄 요약 (공유용)

- **현재**: 저장 URL이 비어 있어 Aura가 chunks를 콜백으로 보내지 않고, 백엔드도 받지 못해 rag_chunk가 비어 있음.
- **적용**: Aura에 **`BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8080/api/synapse/rag/status`** (또는 배포 환경의 gateway URL) 설정 후 재기동하면, 벡터화 완료 시 Aura가 배치마다 POST하고, 백엔드는 기존 구현으로 수신·저장하여 **rag_chunk를 채울 수 있음.**
