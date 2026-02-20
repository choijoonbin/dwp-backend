package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.dto.rag.RagHybridSearchRequest;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchResponse;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchResponse.ChildChunk;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchResponse.ParentGroup;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchResponse.SearchMeta;
import com.dwp.services.synapsex.entity.RagChunk;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.RagChunkRepository;
import com.dwp.services.synapsex.repository.RagChunkRepositoryCustom.ChunkRank;
import com.dwp.services.synapsex.repository.RagChunkRepositoryCustom.ParentChunkInfo;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise RAG Hybrid Search Service
 * RRF(Reciprocal Rank Fusion) 알고리즘 적용: Vector(7) : Keyword(3)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    private static final int RRF_K = 60;

    private final RagChunkRepository ragChunkRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagEmbeddingService embeddingService;

    @Transactional(readOnly = true)
    public RagHybridSearchResponse search(Long tenantId, RagHybridSearchRequest request) {
        long startTime = System.currentTimeMillis();
        
        int topK = request.getTopK() != null ? request.getTopK() : 30;
        double minScore = request.getMinScore() != null ? request.getMinScore() : 0.45;
        double vectorWeight = request.getVectorWeight() != null ? request.getVectorWeight() : 0.7;
        double keywordWeight = request.getKeywordWeight() != null ? request.getKeywordWeight() : 0.3;
        boolean returnParents = request.getReturnParents() != null ? request.getReturnParents() : true;
        
        List<ChunkRank> vectorResults = List.of();
        List<ChunkRank> keywordResults = List.of();

        if (request.getStrategy() == RagHybridSearchRequest.SearchStrategy.HYBRID ||
            request.getStrategy() == RagHybridSearchRequest.SearchStrategy.VECTOR_ONLY) {
            float[] queryEmbedding = embeddingService.getQueryEmbedding(request.getQuery());
            if (queryEmbedding != null) {
                vectorResults = ragChunkRepository.searchVector(tenantId, queryEmbedding, topK * 2, request.getFilters());
                log.debug("Vector search: {} results", vectorResults.size());
            }
        }

        if (request.getStrategy() == RagHybridSearchRequest.SearchStrategy.HYBRID) {
            keywordResults = ragChunkRepository.searchKeyword(tenantId, request.getQuery(), topK * 2, request.getFilters());
            log.debug("Keyword search: {} results", keywordResults.size());
        }

        List<RankedChunk> mergedResults;
        boolean rrfApplied = false;

        if (request.getStrategy() == RagHybridSearchRequest.SearchStrategy.HYBRID && 
            !vectorResults.isEmpty() && !keywordResults.isEmpty()) {
            mergedResults = applyRRF(vectorResults, keywordResults, vectorWeight, keywordWeight, topK);
            rrfApplied = true;
        } else if (!vectorResults.isEmpty()) {
            mergedResults = vectorResults.stream()
                    .limit(topK)
                    .map(r -> new RankedChunk(r.chunkId(), r.score(), r.rank(), null))
                    .toList();
        } else if (!keywordResults.isEmpty()) {
            mergedResults = keywordResults.stream()
                    .limit(topK)
                    .map(r -> new RankedChunk(r.chunkId(), r.score(), null, r.rank()))
                    .toList();
        } else {
            mergedResults = List.of();
        }

        mergedResults = mergedResults.stream()
                .filter(r -> r.score >= minScore)
                .toList();

        List<ParentGroup> parentGroups;
        if (returnParents && !mergedResults.isEmpty()) {
            parentGroups = buildParentGroups(tenantId, mergedResults);
        } else {
            parentGroups = buildFlatGroups(tenantId, mergedResults);
        }

        long elapsed = System.currentTimeMillis() - startTime;

        return RagHybridSearchResponse.builder()
                .strategy(request.getStrategy().name())
                .totalHits(mergedResults.size())
                .queryHash(generateQueryHash(request.getQuery()))
                .parents(parentGroups)
                .meta(SearchMeta.builder()
                        .elapsedMs(elapsed)
                        .vectorCandidates(vectorResults.size())
                        .keywordCandidates(keywordResults.size())
                        .rrfApplied(rrfApplied)
                        .lowConfidenceIncluded(minScore < 0.5)
                        .build())
                .build();
    }

    /**
     * RRF (Reciprocal Rank Fusion) 알고리즘
     * score = w1 * (1 / (k + rank_vector)) + w2 * (1 / (k + rank_keyword))
     */
    private List<RankedChunk> applyRRF(List<ChunkRank> vectorResults, List<ChunkRank> keywordResults,
                                        double vectorWeight, double keywordWeight, int topK) {
        Map<Long, Integer> vectorRanks = new HashMap<>();
        Map<Long, Integer> keywordRanks = new HashMap<>();

        for (ChunkRank r : vectorResults) {
            vectorRanks.put(r.chunkId(), r.rank());
        }
        for (ChunkRank r : keywordResults) {
            keywordRanks.put(r.chunkId(), r.rank());
        }

        Set<Long> allChunkIds = new HashSet<>();
        allChunkIds.addAll(vectorRanks.keySet());
        allChunkIds.addAll(keywordRanks.keySet());

        List<RankedChunk> results = new ArrayList<>();
        for (Long chunkId : allChunkIds) {
            Integer vRank = vectorRanks.get(chunkId);
            Integer kRank = keywordRanks.get(chunkId);

            double rrfScore = 0.0;
            if (vRank != null) {
                rrfScore += vectorWeight * (1.0 / (RRF_K + vRank));
            }
            if (kRank != null) {
                rrfScore += keywordWeight * (1.0 / (RRF_K + kRank));
            }

            results.add(new RankedChunk(chunkId, rrfScore, vRank, kRank));
        }

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .toList();
    }

    private List<ParentGroup> buildParentGroups(Long tenantId, List<RankedChunk> rankedChunks) {
        List<Long> chunkIds = rankedChunks.stream().map(RankedChunk::chunkId).toList();
        List<RagChunk> chunks = ragChunkRepository.findAllById(chunkIds);
        
        Map<Long, RankedChunk> rankMap = rankedChunks.stream()
                .collect(Collectors.toMap(RankedChunk::chunkId, r -> r));

        Set<Long> parentIds = chunks.stream()
                .map(RagChunk::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, ParentChunkInfo> parentMap = new HashMap<>();
        if (!parentIds.isEmpty()) {
            List<ParentChunkInfo> parents = ragChunkRepository.findParentChunks(tenantId, new ArrayList<>(parentIds));
            for (ParentChunkInfo p : parents) {
                parentMap.put(p.chunkId(), p);
            }
        }

        Set<Long> docIds = chunks.stream().map(RagChunk::getDocId).collect(Collectors.toSet());
        Map<Long, String> docTitleMap = ragDocumentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(RagDocument::getDocId, d -> d.getTitle() != null ? d.getTitle() : ""));

        Map<Long, List<RagChunk>> groupedByParent = new LinkedHashMap<>();
        List<RagChunk> orphans = new ArrayList<>();
        
        for (RagChunk chunk : chunks) {
            if (chunk.getParentId() != null) {
                groupedByParent.computeIfAbsent(chunk.getParentId(), k -> new ArrayList<>()).add(chunk);
            } else {
                orphans.add(chunk);
            }
        }

        List<ParentGroup> result = new ArrayList<>();

        for (Map.Entry<Long, List<RagChunk>> entry : groupedByParent.entrySet()) {
            Long parentId = entry.getKey();
            List<RagChunk> children = entry.getValue();
            ParentChunkInfo parentInfo = parentMap.get(parentId);

            List<ChildChunk> childChunks = children.stream()
                    .map(c -> buildChildChunk(c, rankMap.get(c.getChunkId())))
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .toList();

            double maxScore = childChunks.stream()
                    .mapToDouble(ChildChunk::getScore)
                    .max().orElse(0.0);

            String docTitle = children.isEmpty() ? "" : docTitleMap.getOrDefault(children.get(0).getDocId(), "");

            result.add(ParentGroup.builder()
                    .parentId(String.valueOf(parentId))
                    .articleNo(parentInfo != null ? parentInfo.regulationArticle() : null)
                    .title(parentInfo != null ? extractTitle(parentInfo.chunkText()) : null)
                    .text(parentInfo != null ? parentInfo.chunkText() : null)
                    .docId(parentInfo != null ? String.valueOf(parentInfo.docId()) : null)
                    .docTitle(docTitle)
                    .maxScore(maxScore)
                    .children(childChunks)
                    .build());
        }

        for (RagChunk orphan : orphans) {
            RankedChunk rank = rankMap.get(orphan.getChunkId());
            ChildChunk child = buildChildChunk(orphan, rank);
            
            result.add(ParentGroup.builder()
                    .parentId(String.valueOf(orphan.getChunkId()))
                    .articleNo(orphan.getRegulationArticle())
                    .title(extractTitle(orphan.getChunkText()))
                    .text(orphan.getChunkText())
                    .docId(String.valueOf(orphan.getDocId()))
                    .docTitle(docTitleMap.getOrDefault(orphan.getDocId(), ""))
                    .maxScore(child.getScore())
                    .children(List.of(child))
                    .build());
        }

        result.sort((a, b) -> Double.compare(b.getMaxScore(), a.getMaxScore()));
        return result;
    }

    private List<ParentGroup> buildFlatGroups(Long tenantId, List<RankedChunk> rankedChunks) {
        List<Long> chunkIds = rankedChunks.stream().map(RankedChunk::chunkId).toList();
        List<RagChunk> chunks = ragChunkRepository.findAllById(chunkIds);
        
        Map<Long, RankedChunk> rankMap = rankedChunks.stream()
                .collect(Collectors.toMap(RankedChunk::chunkId, r -> r));

        Set<Long> docIds = chunks.stream().map(RagChunk::getDocId).collect(Collectors.toSet());
        Map<Long, String> docTitleMap = ragDocumentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(RagDocument::getDocId, d -> d.getTitle() != null ? d.getTitle() : ""));

        return chunks.stream()
                .map(chunk -> {
                    RankedChunk rank = rankMap.get(chunk.getChunkId());
                    ChildChunk child = buildChildChunk(chunk, rank);
                    return ParentGroup.builder()
                            .parentId(String.valueOf(chunk.getChunkId()))
                            .articleNo(chunk.getRegulationArticle())
                            .title(extractTitle(chunk.getChunkText()))
                            .text(chunk.getChunkText())
                            .docId(String.valueOf(chunk.getDocId()))
                            .docTitle(docTitleMap.getOrDefault(chunk.getDocId(), ""))
                            .maxScore(child.getScore())
                            .children(List.of(child))
                            .build();
                })
                .sorted((a, b) -> Double.compare(b.getMaxScore(), a.getMaxScore()))
                .toList();
    }

    private ChildChunk buildChildChunk(RagChunk chunk, RankedChunk rank) {
        String location = buildLocation(chunk);
        String chunkIdStr = String.valueOf(chunk.getChunkId());
        return ChildChunk.builder()
                .chunkId(chunkIdStr)
                .chunkIndex(chunk.getChunkIndex())
                .nodeType(chunk.getNodeType())
                .snippet(chunk.getChunkText())
                .pageNo(chunk.getPageNo())
                .score(rank != null ? rank.score : 0.0)
                .finalScore(rank != null ? rank.score : 0.0)
                .vectorRank(rank != null ? rank.vectorRank : null)
                .keywordRank(rank != null ? rank.keywordRank : null)
                .clause(chunk.getRegulationClause())
                .location(location)
                .anchorId(chunkIdStr)
                .hierarchyPath(buildHierarchyPath(chunk))
                .metadata(chunk.getMetadataJson())
                .build();
    }

    private List<RagHybridSearchResponse.HierarchyPathItem> buildHierarchyPath(RagChunk chunk) {
        List<RagHybridSearchResponse.HierarchyPathItem> path = new ArrayList<>();
        
        if (chunk.getRegulationArticle() != null) {
            String articleNumber = RagMetadataExtractor.extractNumber(chunk.getRegulationArticle());
            String articleTitle = extractTitleFromChunkText(chunk.getChunkText(), "ARTICLE");
            
            path.add(RagHybridSearchResponse.HierarchyPathItem.builder()
                    .level("ARTICLE")
                    .number(articleNumber)
                    .title(articleTitle)
                    .anchorId(String.valueOf(chunk.getParentId() != null ? chunk.getParentId() : chunk.getChunkId()))
                    .build());
        }
        
        if (chunk.getRegulationClause() != null) {
            String clauseNumber = RagMetadataExtractor.extractNumber(chunk.getRegulationClause());
            String clauseTitle = extractTitleFromChunkText(chunk.getChunkText(), "CLAUSE");
            
            path.add(RagHybridSearchResponse.HierarchyPathItem.builder()
                    .level("CLAUSE")
                    .number(clauseNumber)
                    .title(clauseTitle)
                    .anchorId(String.valueOf(chunk.getChunkId()))
                    .build());
        }
        
        return path.isEmpty() ? null : path;
    }

    private String extractTitleFromChunkText(String text, String level) {
        if (text == null || text.isBlank()) return null;
        
        String cleaned = text.trim();
        if ("ARTICLE".equals(level)) {
            cleaned = cleaned.replaceFirst("^제\\s*\\d+\\s*조\\s*", "");
        } else if ("CLAUSE".equals(level)) {
            cleaned = cleaned.replaceFirst("^제?\\s*\\d+\\s*항\\s*", "");
            cleaned = cleaned.replaceFirst("^제?\\s*\\d+\\s*호\\s*", "");
        }
        
        String[] lines = cleaned.split("\n", 2);
        String firstLine = lines[0].trim();
        
        if (firstLine.length() > 50) {
            firstLine = firstLine.substring(0, 50);
        }
        
        return firstLine.isBlank() ? null : firstLine;
    }

    private String buildLocation(RagChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.getRegulationArticle() != null) {
            sb.append("규정 ").append(chunk.getRegulationArticle());
        }
        if (chunk.getRegulationClause() != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(chunk.getRegulationClause());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String extractTitle(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.split("\n", 2);
        String firstLine = lines[0].trim();
        return firstLine.length() > 100 ? firstLine.substring(0, 100) + "..." : firstLine;
    }

    private String generateQueryHash(String query) {
        if (query == null || query.isBlank()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(query.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 8);
        } catch (Exception e) {
            return "";
        }
    }

    private record RankedChunk(Long chunkId, double score, Integer vectorRank, Integer keywordRank) {}
}
