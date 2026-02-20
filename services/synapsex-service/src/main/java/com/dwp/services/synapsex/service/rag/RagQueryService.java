package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.dto.rag.RagSearchResultDto;
import com.dwp.services.synapsex.entity.QRagChunk;
import com.dwp.services.synapsex.entity.RagChunk;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 3 RAG 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final RagDocumentRepository ragDocumentRepository;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public PageResponse<RagDocumentListDto> listDocuments(Long tenantId, String status, int page, int size) {
        List<RagDocument> docs = status != null && !status.isBlank()
                ? ragDocumentRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status)
                : ragDocumentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        long total = docs.size();
        int from = Math.max(0, page * size);
        int to = Math.min(from + size, docs.size());
        List<RagDocument> paged = docs.subList(from, to);

        List<RagDocumentListDto> items = paged.stream()
                .map(d -> RagDocumentListDto.builder()
                        .docId(d.getDocId())
                        .title(d.getTitle())
                        .sourceType(d.getSourceType())
                        .docType(d.getDocType())
                        .status(d.getStatus())
                        .createdAt(d.getCreatedAt())
                        .build())
                .toList();
        return PageResponse.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public Optional<RagDocumentDetailDto> getDocumentDetail(Long tenantId, Long docId) {
        return ragDocumentRepository.findById(docId)
                .filter(d -> tenantId.equals(d.getTenantId()))
                .map(doc -> {
                    // embedding 컬럼 제외 조회로 vector 역직렬화 오류 방지 (pgvector 컬럼은 JPA 기본 매핑 시 StreamCorruptedException 유발)
                    List<RagDocumentDetailDto.RagChunkDto> chunkDtos = findChunkSummariesByTenantIdAndDocId(tenantId, doc.getDocId());
                    return RagDocumentDetailDto.builder()
                            .docId(doc.getDocId())
                            .title(doc.getTitle())
                            .sourceType(doc.getSourceType())
                            .docType(doc.getDocType())
                            .s3Key(doc.getS3Key())
                            .url(doc.getUrl())
                            .filePath(doc.getFilePath())
                            .checksum(doc.getChecksum())
                            .status(doc.getStatus())
                            .createdAt(doc.getCreatedAt())
                            .chunks(chunkDtos)
                            .build();
                });
    }

    /** embedding 제외 컬럼만 프로젝션하여 조회 (vector 컬럼 역직렬화 오류 회피). */
    private List<RagDocumentDetailDto.RagChunkDto> findChunkSummariesByTenantIdAndDocId(Long tenantId, Long docId) {
        var c = QRagChunk.ragChunk;
        List<com.querydsl.core.Tuple> rows = queryFactory
                .select(c.chunkId, c.chunkIndex, c.pageNo, c.chunkText, c.embeddingId, c.metadataJson)
                .from(c)
                .where(c.tenantId.eq(tenantId), c.docId.eq(docId))
                .orderBy(c.chunkIndex.asc(), c.chunkId.asc())
                .fetch();
        return rows.stream()
                .map(t -> RagDocumentDetailDto.RagChunkDto.builder()
                        .chunkId(t.get(c.chunkId))
                        .chunkIndex(t.get(c.chunkIndex))
                        .pageNo(t.get(c.pageNo))
                        .chunkText(t.get(c.chunkText))
                        .embeddingId(t.get(c.embeddingId))
                        .metadataJson(t.get(c.metadataJson))
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<RagSearchResultDto> searchChunks(Long tenantId, String q, int page, int size, String sort) {
        if (q == null || q.isBlank()) {
            return PageResponse.of(List.of(), 0, page, size);
        }
        var c = QRagChunk.ragChunk;
        String pattern = "%" + q + "%";

        var chunks = queryFactory.selectFrom(c)
                .where(c.tenantId.eq(tenantId), c.chunkText.likeIgnoreCase(pattern))
                .orderBy(c.docId.asc(), c.pageNo.asc())
                .offset((long) page * size)
                .limit(size)
                .fetch();

        Long totalLong = queryFactory.select(c.count()).from(c)
                .where(c.tenantId.eq(tenantId), c.chunkText.likeIgnoreCase(pattern))
                .fetchOne();
        long total = totalLong != null ? totalLong : 0L;

        List<Long> docIds = chunks.stream().map(RagChunk::getDocId).distinct().toList();
        Map<Long, String> docTitles = ragDocumentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(RagDocument::getDocId, RagDocument::getTitle));

        List<RagSearchResultDto> items = chunks.stream()
                .map(chunk -> RagSearchResultDto.builder()
                        .chunkId(chunk.getChunkId())
                        .docId(chunk.getDocId())
                        .docTitle(docTitles.get(chunk.getDocId()))
                        .pageNo(chunk.getPageNo())
                        .chunkText(chunk.getChunkText())
                        .score(null)
                        .build())
                .toList();
        return PageResponse.of(items, total, page, size);
    }

    /**
     * 청킹 상태 조회
     */
    @Transactional(readOnly = true)
    public Optional<com.dwp.services.synapsex.dto.rag.ChunkingStatusResponse> getChunkingStatus(Long tenantId, Long docId) {
        return ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                .map(doc -> {
                    var c = QRagChunk.ragChunk;
                    Long chunkCount = queryFactory.select(c.count()).from(c)
                            .where(c.tenantId.eq(tenantId), c.docId.eq(docId))
                            .fetchOne();
                    
                    return com.dwp.services.synapsex.dto.rag.ChunkingStatusResponse.builder()
                            .docId(docId)
                            .status(doc.getStatus())
                            .chunkCount(chunkCount != null ? chunkCount.intValue() : 0)
                            .strategy(doc.getDocType())
                            .docType(doc.getDocType())
                            .build();
                });
    }
}
