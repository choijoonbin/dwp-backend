package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 3 RAG 명령 서비스
 */
@Service
@RequiredArgsConstructor
public class RagCommandService {

    private final RagDocumentRepository ragDocumentRepository;

    @Transactional
    public RagDocumentDetailDto registerDocument(Long tenantId, RegisterRagDocumentRequest request) {
        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .sourceType(request.getSourceType() != null ? request.getSourceType() : "UPLOAD")
                .s3Key(request.getS3Key())
                .url(request.getUrl())
                .checksum(request.getChecksum())
                .status("PENDING")
                .build();
        doc = ragDocumentRepository.save(doc);

        return RagDocumentDetailDto.builder()
                .docId(doc.getDocId())
                .title(doc.getTitle())
                .sourceType(doc.getSourceType())
                .s3Key(doc.getS3Key())
                .url(doc.getUrl())
                .checksum(doc.getChecksum())
                .status(doc.getStatus())
                .createdAt(doc.getCreatedAt())
                .chunks(List.of())
                .build();
    }
}
