package com.dwp.services.synapsex.service.rag;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.dto.rag.AuraRagVectorizeRequest;
import com.dwp.services.synapsex.dto.rag.AuraChunkItemDto;
import com.dwp.services.synapsex.dto.rag.RagChunksCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagStatusCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.event.RagDocumentReadyForVectorizeEvent;
import com.dwp.services.synapsex.event.RagDocumentStatusUpdatedEvent;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.dwp.services.synapsex.config.LocalStorageConfig;
import com.dwp.services.synapsex.service.agent.AgentStudioCodeValidator;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private final RAGStorageService ragStorageService;
    private final AuraCaseTabClient auraCaseTabClient;
    private final ApplicationEventPublisher eventPublisher;
    private final LocalStorageConfig localStorageConfig;
    private final AgentStudioCodeValidator codeValidator;

    @Transactional
    public RagDocumentDetailDto registerDocument(Long tenantId, RegisterRagDocumentRequest request) {
        // docType 검증 및 기본값 설정
        String docType = request.getDocType();
        if (docType != null && !docType.isBlank()) {
            codeValidator.validateDocType(docType);
        } else {
            docType = "GENERAL"; // 기본값
        }
        
        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .sourceType(request.getSourceType() != null ? request.getSourceType() : "UPLOAD")
                .docType(docType)
                .s3Key(request.getS3Key())
                .url(request.getUrl())
                .checksum(request.getChecksum())
                .status(STATUS_READY)
                .build();
        doc = ragDocumentRepository.save(doc);

        eventPublisher.publishEvent(new RagDocumentReadyForVectorizeEvent(this, tenantId, doc.getDocId()));

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

        // docType 검증 및 기본값 설정
        String finalDocType = docType != null && !docType.isBlank() ? docType : "GENERAL";
        if (!"GENERAL".equals(finalDocType)) {
            codeValidator.validateDocType(finalDocType);
        }
        
        RagDocument doc = RagDocument.builder()
                .tenantId(tenantId)
                .title(title != null && !title.isBlank() ? title : originalFilename)
                .sourceType("UPLOAD")
                .docType(finalDocType)
                .filePath(absolutePath)
                .status(STATUS_READY)
                .build();
        doc = ragDocumentRepository.save(doc);

        eventPublisher.publishEvent(new RagDocumentReadyForVectorizeEvent(this, tenantId, doc.getDocId()));

        return toDetailDto(doc, List.of());
    }

    /**
     * 커밋 후 리스너에서 호출. docId로 문서를 다시 조회한 뒤 Aura 벡터화 트리거.
     * 콜백이 findById로 문서를 찾을 수 있도록 트랜잭션 커밋 이후에만 호출됨.
     */
    public void triggerVectorizeForDocId(Long tenantId, Long docId) {
        RagDocument doc = ragDocumentRepository.findById(docId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "RAG 문서를 찾을 수 없습니다. docId=" + docId));
        triggerVectorize(tenantId, doc);
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
        Long docId = doc.getDocId();
        log.info("Aura RAG vectorize trigger start docId={} tenantId={} title={} sourceType={} docType={} hasFilePath={} hasS3Key={} hasUrl={}",
                docId, tenantId, doc.getTitle(), doc.getSourceType(), doc.getDocType(),
                doc.getFilePath() != null && !doc.getFilePath().isBlank(),
                doc.getS3Key() != null && !doc.getS3Key().isBlank(),
                doc.getUrl() != null && !doc.getUrl().isBlank());
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
            log.info("Aura RAG vectorize trigger success docId={} status={}", docId, STATUS_PROCESSING);
        } catch (FeignException e) {
            String responseBody = e.contentUTF8();
            log.warn("Aura RAG vectorize trigger failed for docId={}, status remains READY: status={} message={} responseBody={}",
                    docId, e.status(), e.getMessage(), responseBody != null && responseBody.length() > 500 ? responseBody.substring(0, 500) + "…" : responseBody);
            if (log.isDebugEnabled()) {
                log.debug("FeignException for docId={}", docId, e);
            }
        } catch (Exception e) {
            log.warn("Aura RAG vectorize trigger failed for docId={} exceptionType={} message={}",
                    docId, e.getClass().getSimpleName(), e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Exception for docId={}", docId, e);
            }
        }
    }

    /**
     * Phase 6: Aura RAG 상태 콜백 처리 (단일 진입점).
     * chunks가 있으면 RAGStorageService로 rag_chunk 저장 후, rag_document 상태 갱신 및 이벤트 발행.
     */
    @Transactional
    public void handleStatusCallback(RagStatusCallbackRequest request) {
        Long docId = resolveDocId(request);
        log.info("RAG status callback received docId={} status={} message={} chunksCount={}",
                docId, request.getStatus(), request.getMessage(),
                request.getChunks() != null ? request.getChunks().size() : 0);
        RagDocument doc = ragDocumentRepository.findById(docId)
                .orElseThrow(() -> {
                    log.warn("RAG status callback: document not found docId={}", docId);
                    return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "RAG 문서를 찾을 수 없습니다. docId=" + docId);
                });
        if (request.getChunks() != null && !request.getChunks().isEmpty()) {
            ragStorageService.saveChunks(doc.getTenantId(), docId, request.getChunks());
            log.info("RAG status callback saved chunks docId={} count={}", docId, request.getChunks().size());
        }
        updateStatusFromCallback(docId, request.getStatus(), request.getMessage());
    }

    /**
     * Aura 청크 전용 콜백: POST /api/synapse/rag/chunks.
     * rag_document_id 유효성 검사 후 chunks를 rag_chunk에 저장. 배치(batch_index/total_batches) 지원.
     */
    @Transactional
    public void processChunksCallback(RagChunksCallbackRequest request) {
        log.info("processChunksCallback start: ragDocumentId={} batchIndex={} totalBatches={} chunksSize={}",
                request.getRagDocumentId(), request.getBatchIndex(), request.getTotalBatches(),
                request.getChunks() != null ? request.getChunks().size() : 0);
        Long docId = parseDocIdFromRagDocumentId(request.getRagDocumentId());
        RagDocument doc = ragDocumentRepository.findById(docId)
                .orElseThrow(() -> {
                    log.warn("RAG chunks callback: document not found rag_document_id={}", request.getRagDocumentId());
                    return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "RAG 문서를 찾을 수 없습니다. rag_document_id=" + request.getRagDocumentId());
                });
        int batchIndex = request.getBatchIndex() != null ? request.getBatchIndex() : 0;
        int totalBatches = request.getTotalBatches() != null && request.getTotalBatches() > 0 ? request.getTotalBatches() : 1;
        List<AuraChunkItemDto> chunks = request.getChunks() != null ? request.getChunks() : Collections.emptyList();
        ragStorageService.saveChunkBatch(doc.getTenantId(), docId, chunks, batchIndex, totalBatches);
    }

    /** 문자열 또는 숫자(11, 11.0) 형식 모두 수용 */
    private static Long parseDocIdFromRagDocumentId(String ragDocumentId) {
        if (ragDocumentId == null || ragDocumentId.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "rag_document_id는 필수입니다.");
        }
        String s = ragDocumentId.trim();
        try {
            if (s.contains(".")) {
                return (long) Double.parseDouble(s);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "rag_document_id는 숫자 형식이어야 합니다: " + ragDocumentId);
        }
    }

    /**
     * docId 기준으로 rag_document 상태 갱신 후, WebSocket/SSE용 이벤트 발행.
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

    /** Aura 형식(rag_document_id string) 또는 docId(Long)에서 문서 ID 결정 */
    private Long resolveDocId(RagStatusCallbackRequest request) {
        if (request.getDocId() != null) {
            return request.getDocId();
        }
        if (request.getRagDocumentId() != null && !request.getRagDocumentId().isBlank()) {
            try {
                return Long.parseLong(request.getRagDocumentId().trim());
            } catch (NumberFormatException e) {
                log.warn("RAG callback invalid rag_document_id: value={}", request.getRagDocumentId());
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "rag_document_id는 숫자 형식이어야 합니다: " + request.getRagDocumentId());
            }
        }
        log.warn("RAG callback missing docId and rag_document_id");
        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "docId 또는 rag_document_id는 필수입니다.");
    }

    /**
     * 재청킹 요청: 기존 청크 삭제 후 새로운 전략으로 재벡터화
     */
    @Transactional
    public com.dwp.services.synapsex.dto.rag.RechunkResponse rechunk(
            Long tenantId, Long docId, com.dwp.services.synapsex.dto.rag.RechunkRequest request) {
        
        RagDocument doc = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다. docId=" + docId));

        if ("PROCESSING".equals(doc.getStatus()) || "VECTORIZING".equals(doc.getStatus())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "문서가 현재 처리 중입니다. 완료 후 재시도하세요.");
        }

        doc.setStatus("PROCESSING");
        doc.setDocType(request.getStrategy());
        ragDocumentRepository.save(doc);

        try {
            AuraRagVectorizeRequest vectorizeRequest = AuraRagVectorizeRequest.builder()
                    .ragDocumentId(String.valueOf(docId))
                    .documentPath(doc.getFilePath())
                    .docType(request.getStrategy())
                    .callbackUrl("/api/synapse/rag/status")
                    .chunkSize(request.getChunkSize())
                    .chunkOverlap(request.getChunkOverlap())
                    .build();

            auraCaseTabClient.triggerRagVectorize(docId, tenantId, null, vectorizeRequest);
            log.info("Rechunk triggered: docId={} strategy={}", docId, request.getStrategy());

            return com.dwp.services.synapsex.dto.rag.RechunkResponse.builder()
                    .docId(docId)
                    .status("PROCESSING")
                    .message("재청킹이 시작되었습니다.")
                    .build();

        } catch (FeignException e) {
            log.error("Rechunk Aura trigger failed: docId={} error={}", docId, e.getMessage());
            doc.setStatus("FAILED");
            ragDocumentRepository.save(doc);
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Aura 재청킹 요청 실패: " + e.getMessage());
        }
    }
}
