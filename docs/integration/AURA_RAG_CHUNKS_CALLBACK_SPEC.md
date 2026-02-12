# Aura → 백엔드 RAG 청크 콜백 연동 명세 (전달용)

Aura가 벡터화 완료 후 청크를 백엔드 DB(rag_chunk)에 저장하기 위해 호출하는 API 명세입니다.

---

## 1. 설정 (Aura .env)

벡터화 완료 시 아래 URL로 POST하도록 한 줄 설정합니다.

### Gateway 경유 (권장)

```bash
BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8080/api/synapse/rag/chunks
```

- **로컬**: `http://localhost:8080` (Gateway), 경로 `/api/synapse/rag/chunks`
- **배포**: 실제 Gateway origin으로 변경 (예: `https://gateway.example.com/api/synapse/rag/chunks`)

### SynapseX 직통 (선택)

Gateway 없이 SynapseX만 띄운 경우에만 사용합니다.

```bash
BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8085/synapse/rag/chunks
```

- **호스트**: SynapseX가 떠 있는 머신 (로컬이면 `localhost`)
- **포트**: `8085` (SynapseX 기본 포트), 경로 `/synapse/rag/chunks` (앞에 `/api` 없음)

### 호스트/포트 확인

- 400/502 발생 시: Aura가 호출하는 **호스트·포트**가 백엔드가 실제로 떠 있는 곳과 같은지 확인하세요.
- Gateway 사용 시 → `8080`에서 Gateway가 떠 있고, Gateway가 SynapseX(예: 8085)로 라우팅하는지 확인.
- SynapseX 직통 시 → `8085`에서 SynapseX가 떠 있는지 확인.
- 경로: Gateway일 때는 **반드시** `/api/synapse/rag/chunks`, SynapseX 직통일 때는 `/synapse/rag/chunks`.

---

## 2. 보안(개방) 및 Aura 환경설정

- **백엔드**: `POST /api/synapse/rag/chunks` 는 **Aura 콜백 전용으로 개방**되어 있습니다.
  - Gateway에서 **X-Tenant-ID 필수 검증 예외** 처리되어 있어, Aura는 별도 헤더 없이 POST만 하면 됩니다.
  - API Key는 사용하지 않으며, tenant는 요청 body의 `rag_document_id`로 문서 조회 후 자동 결정됩니다.
- **Aura .env 설정 (필수)**  
  아래 한 줄을 설정하면 벡터화 완료 시 위 URL로 청크가 전송됩니다.

```bash
# Gateway 경유 (로컬 기본)
BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8080/api/synapse/rag/chunks

# 배포 시 Gateway origin으로 변경
# BACKEND_RAG_CHUNKS_SAVE_URL=https://<gateway-host>/api/synapse/rag/chunks
```

---

## 3. API 요약

| 항목 | 값 |
|------|-----|
| **Method** | `POST` |
| **URL** | `{BACKEND_RAG_CHUNKS_SAVE_URL}` 위와 동일 (예: `http://localhost:8080/api/synapse/rag/chunks`) |
| **Content-Type** | `application/json` |
| **성공 응답** | `200 OK` (body: `{ "status": "SUCCESS", "data": null, ... }`) |

---

## 4. Request Body (JSON)

```json
{
  "rag_document_id": "8",
  "chunks": [
    {
      "chunk_index": 0,
      "content": "청크 텍스트 내용",
      "embedding": [ 0.01, -0.02, ... ],
      "metadata": { "page_number": 1 }
    }
  ],
  "batch_index": 0,
  "total_batches": 1
}
```

### 필드 설명

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| **rag_document_id** | string 또는 number | ✅ | 백엔드 `rag_document.doc_id`. 문자열 `"11"` 또는 숫자 `11`, `11.0` 모두 수용. 벡터화 요청 시 받은 docId와 동일. |
| **chunks** | array | ✅ | 청크 배열. 빈 배열이면 INSERT 없이 200 반환. |
| **batch_index** | int | 선택 | 배치 순서 (0부터). 미지정 시 0. **0일 때만** 해당 doc 기존 청크를 삭제한 뒤 insert. |
| **total_batches** | int | 선택 | 전체 배치 수. 미지정 시 1. |

### chunks[] 한 건 형식

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| **chunk_index** | int | 선택 | 문서 내 순서(0-based). 없으면 batch 기준으로 자동 부여. |
| **content** | string | 선택 | 분할된 텍스트. DB `chunk_text`에 저장. (별칭 `chunk_content`도 가능) |
| **embedding** | float[] | 선택 | 1536차원 벡터. 없으면 NULL로 저장. |
| **metadata** | object | 선택 | 메타. `page_number` 또는 `page_no` 있으면 DB `page_no`에 반영. (별칭 `metadata_json` 가능) |

**계층형(Hierarchical) 문서**: 조(條)·항(項) 인식 교차 검증을 위해, 계층형 업로드 시 `metadata`에 `article_number`, `section_number`(또는 `item_number`) 등을 포함하면 BE 저장 로그에서 1건 이상 확인 가능. 상세: [AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md](AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md).

---

## 5. 배치 전송 규칙

- **여러 배치로 나눠 보낼 때**: `batch_index` 0, 1, 2, … 순으로 전송.
- **batch_index == 0** 인 요청에서만 해당 `rag_document_id`의 기존 청크를 **전부 삭제**한 뒤, 그 배치의 chunks를 INSERT.
- **batch_index >= 1** 인 요청은 **삭제 없이** 해당 배치 chunks만 **추가 INSERT**.

---

## 6. 에러 응답 (참고)

| 상황 | HTTP | 메시지 예시 |
|------|------|-------------|
| rag_document_id 누락/빈값 | 400 | rag_document_id는 필수입니다. |
| rag_document_id 비숫자 | 400 | rag_document_id는 숫자 형식이어야 합니다. |
| 해당 doc_id가 DB에 없음 | 404 | RAG 문서를 찾을 수 없습니다. rag_document_id=… |

---

## 7. cURL 예시

**Gateway (8080)**

```bash
curl -X POST http://localhost:8080/api/synapse/rag/chunks \
  -H "Content-Type: application/json" \
  -d '{
    "rag_document_id": "8",
    "chunks": [
      {
        "chunk_index": 0,
        "content": "첫 번째 청크 텍스트",
        "metadata": { "page_number": 1 }
      },
      {
        "chunk_index": 1,
        "content": "두 번째 청크 텍스트",
        "metadata": { "page_number": 2 }
      }
    ],
    "batch_index": 0,
    "total_batches": 1
  }'
```

**SynapseX 직통 (8085)**

```bash
curl -X POST http://localhost:8085/synapse/rag/chunks \
  -H "Content-Type: application/json" \
  -d '{"rag_document_id":"8","chunks":[{"chunk_index":0,"content":"첫 번째 청크"}],"batch_index":0,"total_batches":1}'
```

---

**한 줄 요약**: Aura는 벡터화 완료 후 `BACKEND_RAG_CHUNKS_SAVE_URL` 로 `{ "rag_document_id", "chunks", "batch_index", "total_batches" }` 를 POST하면 되고, 백엔드가 유효성 검사 후 rag_chunk에 저장하고 200 OK를 반환합니다.
