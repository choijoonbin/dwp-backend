package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.dto.rag.RagSearchResultDto;
import com.dwp.services.synapsex.entity.QRagChunk;
import com.dwp.services.synapsex.entity.RagChunk;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.RagChunkRepository;
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
    private final RagChunkRepository ragChunkRepository;
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
                    List<RagChunk> chunks = ragChunkRepository.findByTenantIdAndDocIdOrderByPageNoAscChunkIdAsc(tenantId, doc.getDocId());
                    return RagDocumentDetailDto.builder()
                            .docId(doc.getDocId())
                            .title(doc.getTitle())
                            .sourceType(doc.getSourceType())
                            .s3Key(doc.getS3Key())
                            .url(doc.getUrl())
                            .checksum(doc.getChecksum())
                            .status(doc.getStatus())
                            .createdAt(doc.getCreatedAt())
                            .chunks(chunks.stream()
                                    .map(c -> RagDocumentDetailDto.RagChunkDto.builder()
                                            .chunkId(c.getChunkId())
                                            .pageNo(c.getPageNo())
                                            .chunkText(c.getChunkText())
                                            .embeddingId(c.getEmbeddingId())
                                            .build())
                                    .toList())
                            .build();
                });
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

        long total = queryFactory.selectFrom(c)
                .where(c.tenantId.eq(tenantId), c.chunkText.likeIgnoreCase(pattern))
                .fetchCount();

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
}
