# Backend Data Hardening: Batch Insert & JSON CamelCase

## 1. 대용량 벡터 데이터 Batch Insert

### 1.1 구현 위치

- **서비스**: `services/synapsex-service/.../service/rag/RAGStorageService.java`
- **방식**: `JdbcTemplate.batchUpdate` + `BatchPreparedStatementSetter`
- **배치 크기**: `BATCH_SIZE = 500` (수천 건 시 500건 단위로 나눠 실행)

### 1.2 chunk_index 순서 보장

- Aura가 넘긴 `chunks` 리스트 **순서를 그대로** 사용.
- 각 행의 `chunk_index` 설정:
  - `dto.getChunkIndex() != null` → Aura가 준 값 사용
  - null → 리스트 인덱스로 보정: `batchOffset + i` (배치 루프 내 인덱스)
- INSERT SQL에 `chunk_index`를 3번째 컬럼으로 넣어, DB에 저장 순서 = Aura 전달 순서.

### 1.3 핵심 코드 구조

```java
private static final int BATCH_SIZE = 500;
private static final String INSERT_SQL =
    "INSERT INTO dwp_aura.rag_chunk (tenant_id, doc_id, chunk_index, page_no, chunk_text, embedding, metadata_json, created_at) "
    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

@Transactional
public void saveChunks(Long tenantId, Long docId, List<AuraChunkItemDto> chunks) {
    // 1) doc 검증, 기존 청크 삭제
    ragChunkRepository.deleteByTenantIdAndDocId(tenantId, docId);
    final Instant now = Instant.now();
    // 2) 500건 단위 배치 루프
    for (int offset = 0; offset < chunks.size(); offset += BATCH_SIZE) {
        final int batchOffset = offset;
        List<AuraChunkItemDto> batch = chunks.subList(offset, Math.min(offset + BATCH_SIZE, chunks.size()));
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AuraChunkItemDto dto = batch.get(i);
                int chunkIndex = dto.getChunkIndex() != null ? dto.getChunkIndex() : (batchOffset + i);
                // page_no, chunk_text, embedding(PGvector), metadata_json(json string), created_at 설정
                ps.setLong(1, tenantId);
                ps.setLong(2, docId);
                ps.setInt(3, chunkIndex);  // 순서 보장
                // ... 나머지 바인딩
            }
            @Override
            public int getBatchSize() { return batch.size(); }
        });
    }
}
```

### 1.4 요약

| 항목 | 내용 |
|------|------|
| JPA save() 반복 | 사용 안 함. 전부 `JdbcTemplate.batchUpdate` |
| chunk_index | Aura `chunkIndex` 또는 (batchOffset + i) 로 고정, INSERT 순서 = 리스트 순서 |
| 배치 단위 | 500건씩 나눠 실행 (메모리·드라이버 한도 고려) |

---

## 2. JSON 직렬화 규격 통일 (CamelCase)

### 2.1 원칙

- DB 컬럼: `action_at`, `occurred_at`, `created_at`, `read_at` (snake_case)
- API 응답: **반드시** `actionAt`, `occurredAt`, `createdAt`, `readAt` (camelCase)
- 적용: DTO 필드에 **`@JsonProperty("actionAt")`** 등으로 직렬화 이름 고정 → 전역 ObjectMapper가 snake_case여도 해당 필드는 camelCase로 출력.

### 2.2 적용된 DTO 구조

| DTO | 필드 | @JsonProperty |
|-----|------|----------------|
| **CaseDetailDto.CaseActionHistoryItemRefDto** | actionAt, createdAt | `@JsonProperty("actionAt")`, `@JsonProperty("createdAt")` |
| **CaseDetailDto.AiThoughtItemDto** | occurredAt | `@JsonProperty("occurredAt")` |
| **CaseActionHistoryItemDto** | actionAt, createdAt | `@JsonProperty("actionAt")`, `@JsonProperty("createdAt")` |
| **WorkbenchTimelineItemDto** | occurredAt | `@JsonProperty("occurredAt")` |
| **NotificationDto** | occurredAt, createdAt, readAt | `@JsonProperty("occurredAt")`, `@JsonProperty("createdAt")`, `@JsonProperty("readAt")` |
| **DashboardActivitySummaryDto** | occurredAt | `@JsonProperty("occurredAt")` |
| **LineageNodeDto** | occurredAt | `@JsonProperty("occurredAt")` |

### 2.3 예시 (CaseActionHistoryItemDto)

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CaseActionHistoryItemDto {
    private Long id;
    private Long caseId;
    private String actionType;
    private String actorId;
    private String commentText;
    @JsonProperty("actionAt")
    private Instant actionAt;
    private Map<String, Object> metadataJson;
    @JsonProperty("createdAt")
    private Instant createdAt;
}
```

### 2.4 요약

- **action_at** → 응답 키 **actionAt**
- **occurred_at** → 응답 키 **occurredAt**
- **created_at** → 응답 키 **createdAt**
- **read_at** → 응답 키 **readAt**

위 DTO들은 모두 해당 필드에 `@JsonProperty`로 camelCase를 지정해 두었으므로, 프론트엔드 바인딩 시 snake_case로 인한 오류를 원천 차단할 수 있습니다.
