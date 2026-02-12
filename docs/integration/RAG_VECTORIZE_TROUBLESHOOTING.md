# RAG 라이브러리 등록(벡터화) 실패 시 확인 사항

RAG 문서 등록 후 벡터화 트리거가 계속 실패할 때 확인할 위치와 순서.

---

## 1. 백엔드 로그 (SynapseX)

| 로그 메시지 | 의미 |
|-------------|------|
| `Aura RAG vectorize trigger failed for docId=X, status remains READY: Type definition error: [simple type, class ... AuraRagVectorizeResponse]` | **Feign 응답 역직렬화 오류** (수정됨: 반환 타입 Void로 변경해 빈 본문 시에도 실패하지 않음). |
| `Aura RAG vectorize trigger failed for docId=X, status remains READY: ...` | Aura 호출 실패(연결 불가, 4xx/5xx, 타임아웃 등). 메시지 끝의 예외/상태 코드 확인. |

**확인**: `RagCommandService` 에서 `triggerVectorize()` 호출 직후 예외가 나면 위 WARN이 찍힘. 서버 재시작 후에도 동일하면 아래 2·3 확인.

---

## 2. Aura 연동 설정

| 설정 키 | 기본값 | 확인 |
|---------|--------|------|
| **aura.base-url** | `http://localhost:9000` | Aura 서버가 이 주소에서 떠 있는지. 벡터화 API: `POST {base-url}/aura/rag/documents/{docId}/vectorize` |
| **Feign timeout** | `feign.client.config.aura-case-tab` (application.yml) | 타임아웃이 너무 짧으면 504 등으로 실패할 수 있음. |

**확인**:  
- `curl -v -X POST http://localhost:9000/aura/rag/documents/6/vectorize -H "X-Tenant-ID: 1" -H "Content-Type: application/json" -d '{}'`  
- Aura가 202 또는 200을 주는지, 빈 본문인지 JSON 본문인지 확인.

---

## 3. DB 상태 (dwp_aura.rag_document)

| 컬럼 | 의미 |
|------|------|
| **status** | `READY` → 벡터화 트리거 전 또는 트리거 실패. `PROCESSING` → Aura 처리 중. `COMPLETED` / `FAILED` → Aura 콜백 반영. |
| **file_path, s3_key, url** | Aura가 파일을 읽을 수 있는 경로/키/URL이 들어갔는지. 로컬 파일이면 Aura 서버가 해당 경로에 접근 가능해야 함. |

**확인**:  
- `SELECT doc_id, title, status, source_type, file_path, created_at FROM dwp_aura.rag_document WHERE doc_id IN (6,7) ORDER BY doc_id;`  
- status가 계속 READY면 트리거가 실패한 것. PROCESSING이면 Aura 처리 대기 또는 콜백 미도착.

---

## 4. Aura → BE 콜백 (벡터화 완료 후)

Aura가 벡터화를 끝내면 **SynapseX로 상태 콜백**을 보냄. 콜백이 오지 않으면 status가 PROCESSING에서 멈추고, **chunks를 보내지 않으면 rag_chunk 테이블은 비어 있음**.

| 확인 | 내용 |
|------|------|
| **콜백 URL** | **`POST {gateway}/api/synapse/rag/status`** (예: `http://localhost:8080/api/synapse/rag/status`). Aura 설정(BACKEND_RAG_CHUNKS_SAVE_URL 등)에 이 URL이 등록되어 있는지. |
| **콜백 Body** | `docId` 또는 `rag_document_id`, `status`, `message`, **`chunks`**(배열). **chunks가 없거나 빈 배열이면 rag_chunk에 insert 되지 않음.** |
| **콜백 로그** | 콜백 수신 시 `RAG status callback endpoint hit`(컨트롤러), `RAG status callback received`(서비스) 로그가 나오는지. **이 로그가 전혀 없으면 Aura가 해당 URL로 요청을 보내지 않은 것.** |

---

## 5. rag_chunk가 비어 있는 경우

**rag_chunk** 는 Aura가 **POST /api/synapse/rag/status** 콜백을 보낼 때, 요청 body에 **chunks** 배열이 포함되어 있을 때만 저장됩니다.

| 원인 | 확인 방법 |
|------|-----------|
| **Aura가 콜백을 아예 호출하지 않음** | 로그에 `RAG status callback endpoint hit` 이 없음. → Aura 쪽에서 벡터화 완료 후 위 URL로 POST 하도록 설정 필요. |
| **Aura가 콜백을 호출하지만 chunks 미포함** | `RAG status callback endpoint hit ... chunksPresent=false` 또는 `chunksSize=0`. → Aura가 status만 보내고 chunks를 안 보내는지 확인. BE는 chunks가 있을 때만 rag_chunk에 insert 함. |
| **콜백 URL/네트워크 오류** | Aura와 BE가 다른 머신이면 방화벽·URL(호스트/포트) 확인. Gateway(8080) 경유 시 `/api/synapse/rag/status` 로 라우팅되는지 확인. |

---

## 6. 요약 체크리스트

1. **SynapseX 재시작** 후 다시 등록 시도 (Type definition error 수정 반영 여부).
2. **Aura 기동 여부** 및 **aura.base-url** 접근 가능 여부.
3. **rag_document.status** 가 READY에서 바뀌는지 (PROCESSING → COMPLETED/FAILED).
4. **Aura 벡터화 API**를 curl로 직접 호출했을 때 2xx 응답인지.
5. status가 PROCESSING인데 멈춰 있으면 **Aura 콜백 URL**(POST /api/synapse/rag/status)·인증 및 SynapseX 콜백 로그 확인.
6. **rag_chunk가 비어 있으면** 로그에 `RAG status callback endpoint hit` 이 있는지, 있다면 `chunksSize` 가 0보다 큰지 확인. 없으면 Aura가 콜백을 호출하지 않거나 chunks를 안 보내는 것.
