package com.dwp.services.synapsex.service.rag;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.dto.rag.AuraRagVectorizeRequest;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.event.RagDocumentStatusUpdatedEvent;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.dwp.services.synapsex.config.LocalStorageConfig;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 RAG 명령 서비스.
 * Phase 6: 등록 후 상태 READY → Aura 벡터화 트리거 → PROCESSING (sys_codes RAG_DOCUMENT_STATUS 활용).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCommandService {

    /** sys_codes RAG_DOCUMENT_STATUS: READY → PROCESSING → COMPLETED */
    private static final String STATUS_READY = "READY";
    private static final String STATUS_PROCESSING = "PROCESSING";

    /** Aura 로컬 경로 수집과 동일: .pdf, .txt, .md 만 허용 */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of("pdf", "txt", "md");

    private final RagDocumentRepository ragDocumentRepository;
    private final AuraCaseTabClient auraCaseTabClient;
    private final ApplicationEventPublisher eventPublisher;
    private final LocalStorageConfig localStorageConfig;

    @Transactional
    public RagDocumentDetailDto registerDocument(Long tenantId, RegisterRagDocumentRequest request) {
        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .sourceType(request.getSourceType() != null ? request.getSourceType() : "UPLOAD")
                .docType(request.getDocType())
                .s3Key(request.getS3Key())
                .url(request.getUrl())
                .checksum(request.getChecksum())
                .status(STATUS_READY)
                .build();
        doc = ragDocumentRepository.save(doc);

        triggerVectorize(tenantId, doc);

        return toDetailDto(doc, List.of());
    }

    /**
     * 로컬 파일 업로드: MultipartFile을 저장 경로에 저장 후 DB 등록 및 Aura 벡터화 트리거.
     * 파일명: UUID_원본파일명 (중복 방지).
     */
    @Transactional
    public RagDocumentDetailDto registerDocumentFromFile(Long tenantId, MultipartFile file, String title, String docType) {
        if (file == null || file.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "업로드 파일이 없습니다.");
        }
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String ext = getExtension(originalFilename);
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "허용 확장자는 .pdf, .txt, .md 입니다. 현재: " + originalFilename);
        }
        Path basePath = localStorageConfig.getAbsolutePath();
        String safeName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedFileName = UUID.randomUUID() + "_" + safeName;
        Path targetPath = basePath.resolve(storedFileName);
        try {
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());
        } catch (Exception e) {
            log.warn("Failed to save uploaded file: {}", e.getMessage());
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "파일 저장 실패: " + e.getMessage());
        }
        String absolutePath = targetPath.toAbsolutePath().toString();

        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .title(title != null && !title.isBlank() ? title : originalFilename)
                .sourceType("UPLOAD")
                .docType(docType != null && !docType.isBlank() ? docType : "GENERAL")
                .filePath(absolutePath)
                .status(STATUS_READY)
                .build();
        doc = ragDocumentRepository.save(doc);

        triggerVectorize(tenantId, doc);

        return toDetailDto(doc, List.of());
    }

    private static String getExtension(String filename) {
        if (filename == null || filename.isBlank()) return null;
        int i = filename.lastIndexOf('.');
        return i >= 0 && i < filename.length() - 1 ? filename.substring(i + 1) : null;
    }

    private static RagDocumentDetailDto toDetailDto(RagDocument doc, List<RagDocumentDetailDto.RagChunkDto> chunks) {
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
                .chunks(chunks)
                .build();
    }

    /**
     * Aura 벡터화 엔드포인트 호출. 성공 시 status = PROCESSING 으로 갱신.
     * 로컬 파일인 경우 documentPath(절대 경로) 전달.
     */
    private void triggerVectorize(Long tenantId, RagDocument doc) {
        try {
            AuraRagVectorizeRequest body = AuraRagVectorizeRequest.builder()
                    .tenantId(tenantId)
                    .docId(doc.getDocId())
                    .docType(doc.getDocType())
                    .title(doc.getTitle())
                    .s3Key(doc.getS3Key())
                    .url(doc.getUrl())
                    .sourceType(doc.getSourceType())
                    .documentPath(doc.getFilePath())
                    .build();
            auraCaseTabClient.triggerRagVectorize(doc.getDocId(), tenantId, null, body);
            doc.setStatus(STATUS_PROCESSING);
            ragDocumentRepository.save(doc);
        } catch (FeignException e) {
            log.warn("Aura RAG vectorize trigger failed for docId={}, status remains READY: {}", doc.getDocId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Aura RAG vectorize trigger failed for docId={}: {}", doc.getDocId(), e.getMessage());
        }
    }

    /**
     * Phase 6: Aura RAG 상태 콜백 처리.
     * docId 기준으로 rag_document 상태 갱신 후, WebSocket/SSE용 이벤트 발행(규정 벡터화 완료 알림 준비).
     * 유효하지 않은 docId 시 404 응답을 위해 예외 발생.
     */
    @Transactional
    public void updateStatusFromCallback(Long docId, String status, String message) {
        RagDocument doc = ragDocumentRepository.findById(docId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "RAG 문서를 찾을 수 없습니다. docId=" + docId));
        if (status != null && !status.isBlank()) {
            doc.setStatus(status.trim().toUpperCase());
            ragDocumentRepository.save(doc);
        }
        if (message != null && !message.isBlank()) {
            log.info("RAG status callback docId={} status={} message={}", docId, status, message);
        }
        eventPublisher.publishEvent(new RagDocumentStatusUpdatedEvent(this, doc.getDocId(), doc.getTenantId(), doc.getStatus(), message));
    }
}
