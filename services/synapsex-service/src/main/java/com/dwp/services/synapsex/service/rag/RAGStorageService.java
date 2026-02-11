package com.dwp.services.synapsex.service.rag;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.rag.AuraChunkItemDto;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.RagChunkRepository;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * RAG 청크 저장 소유권: 백엔드.
 * Aura 벡터화 결과(Chunk Text, Vector, Metadata)만 반환하고, 이 서비스가 rag_chunk에 저장.
 * 대용량 유입 대비 JdbcTemplate.batchUpdate로 Bulk Insert, chunk_index 순서 보장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGStorageService {

    private static final int EMBEDDING_DIM = 1536;
    /** 배치당 행 수 (수천 건 유입 시 메모리·드라이버 한도 고려) */
    private static final int BATCH_SIZE = 500;

    private static final String INSERT_SQL =
            "INSERT INTO dwp_aura.rag_chunk (tenant_id, doc_id, chunk_index, page_no, chunk_text, embedding, metadata_json, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final RagDocumentRepository ragDocumentRepository;
    private final RagChunkRepository ragChunkRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Aura 벡터화 결과를 받아 해당 doc_id의 기존 청크를 삭제한 뒤 Bulk Insert.
     * chunk_index 순서는 리스트 순서 그대로 유지.
     */
    @Transactional
    public void saveChunks(Long tenantId, Long docId, List<AuraChunkItemDto> chunks) {
        RagDocument doc = ragDocumentRepository.findById(docId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "RAG 문서를 찾을 수 없습니다. docId=" + docId));
        if (!doc.getTenantId().equals(tenantId)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "해당 문서에 대한 권한이 없습니다.");
        }
        if (chunks == null || chunks.isEmpty()) {
            log.debug("saveChunks: docId={} chunks empty, skipping save", docId);
            return;
        }

        ragChunkRepository.deleteByTenantIdAndDocId(tenantId, docId);

        final Instant now = Instant.now();
        for (int offset = 0; offset < chunks.size(); offset += BATCH_SIZE) {
            final int batchOffset = offset;
            int end = Math.min(offset + BATCH_SIZE, chunks.size());
            List<AuraChunkItemDto> batch = chunks.subList(offset, end);
            jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    AuraChunkItemDto dto = batch.get(i);
                    int chunkIndex = dto.getChunkIndex() != null ? dto.getChunkIndex() : (batchOffset + i);
                    int pageNo = 1;
                    if (dto.getMetadataJson() != null) {
                        Object p = dto.getMetadataJson().get("page_no");
                        if (p == null) p = dto.getMetadataJson().get("page_number");
                        if (p instanceof Number) pageNo = ((Number) p).intValue();
                    }
                    String chunkText = dto.getChunkContent() != null ? dto.getChunkContent() : "";
                    PGvector embedding = null;
                    if (dto.getEmbedding() != null && dto.getEmbedding().length == EMBEDDING_DIM) {
                        embedding = new PGvector(dto.getEmbedding());
                    } else if (dto.getEmbedding() != null && dto.getEmbedding().length != EMBEDDING_DIM) {
                        log.warn("RAG chunk docId={} index={} embedding length {} != {}", docId, chunkIndex, dto.getEmbedding().length, EMBEDDING_DIM);
                    }
                    String metadataJsonStr = toJsonString(dto.getMetadataJson());

                    ps.setLong(1, tenantId);
                    ps.setLong(2, docId);
                    ps.setInt(3, chunkIndex);
                    ps.setInt(4, pageNo);
                    ps.setString(5, chunkText);
                    if (embedding != null) {
                        ps.setObject(6, embedding, Types.OTHER);
                    } else {
                        ps.setNull(6, Types.OTHER);
                    }
                    if (metadataJsonStr != null) {
                        ps.setString(7, metadataJsonStr);
                    } else {
                        ps.setNull(7, Types.OTHER);
                    }
                    ps.setObject(8, Timestamp.from(now), Types.TIMESTAMP_WITH_TIMEZONE);
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        }
        log.info("RAG chunks saved: docId={} count={} (batch size={})", docId, chunks.size(), BATCH_SIZE);
    }

    private String toJsonString(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
