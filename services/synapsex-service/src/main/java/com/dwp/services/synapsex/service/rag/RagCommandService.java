package com.dwp.services.synapsex.service.rag;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.config.AuraTenantContext;
import com.dwp.services.synapsex.dto.rag.AuraRagVectorizeRequest;
import com.dwp.services.synapsex.dto.rag.AuraChunkItemDto;
import com.dwp.services.synapsex.dto.rag.RagChunksCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagStatusCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.event.RagDocumentReadyForVectorizeEvent;
import com.dwp.services.synapsex.event.RagDocumentStatusUpdatedEvent;
import com.dwp.services.synapsex.repository.RagChunkRepository;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.dwp.services.synapsex.config.LocalStorageConfig;
import com.dwp.services.synapsex.service.agent.AgentStudioCodeValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final RagChunkRepository ragChunkRepository;
    private final RAGStorageService ragStorageService;
    private final RagGovernanceService ragGovernanceService;
    private final AuraCaseTabClient auraCaseTabClient;
    private final ApplicationEventPublisher eventPublisher;
    private final LocalStorageConfig localStorageConfig;
    private final AgentStudioCodeValidator codeValidator;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

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
            AuraTenantContext.setTenantId(tenantId);
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
            } finally {
                AuraTenantContext.clear();
            }
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
        JsonNode qualityReport = request.getQualityReport();
        if ((qualityReport == null || qualityReport.isNull()) && request.getQualityGatePassed() != null) {
            var fallbackReport = objectMapper.createObjectNode();
            fallbackReport.put("quality_gate_passed", request.getQualityGatePassed());
            qualityReport = fallbackReport;
            log.info("RAG status callback using top-level quality_gate_passed fallback docId={} runId={} gatePassed={}",
                    docId, request.getRunId(), request.getQualityGatePassed());
        }
        if (qualityReport != null
                && qualityReport.isObject()
                && request.getQualityGatePassed() != null
                && !qualityReport.has("quality_gate_passed")
                && !qualityReport.has("qualityGatePassed")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) qualityReport)
                    .put("quality_gate_passed", request.getQualityGatePassed());
            log.info("RAG status callback merged top-level quality_gate_passed into quality_report docId={} runId={} gatePassed={}",
                    docId, request.getRunId(), request.getQualityGatePassed());
        }
        if ((qualityReport == null || qualityReport.isNull()) && request.getQualityGatePassed() == null) {
            log.warn("RAG status callback quality payload missing docId={} runId={} (quality_report/top-level quality_gate_passed both absent)",
                    docId, request.getRunId());
        }
        if (qualityReport != null && !qualityReport.isNull()) {
            log.info("RAG status callback quality payload docId={} runId={} has_quality_gate_passed_field={} top_level_quality_gate_passed={}",
                    docId,
                    request.getRunId(),
                    qualityReport.has("quality_gate_passed"),
                    request.getQualityGatePassed());
            ragGovernanceService.persistQualityReport(doc.getTenantId(), docId, request.getRunId(), qualityReport);
            log.info("RAG quality_report saved docId={} runId={}", docId, request.getRunId());
        }
        updateStatusFromCallback(doc.getTenantId(), docId, request.getStatus(), request.getMessage());
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
        // COMPLETED 갱신은 Aura가 청킹 완료 후 POST /rag/status 로 일원화함.
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
     * 직접 UPDATE 쿼리로 반영해 엔티티 캐시/리스너 예외로 인한 롤백 시에도 DB는 갱신되도록 함.
     */
    @Transactional
    public void updateStatusFromCallback(Long tenantId, Long docId, String status, String message) {
        if (status == null || status.isBlank()) {
            return;
        }
        String normalizedStatus = status.trim().toUpperCase();
        int updated = ragDocumentRepository.updateStatusByDocId(docId, normalizedStatus);
        if (updated > 0) {
            log.info("RAG status callback docId={} status={} updated={} message={}", docId, normalizedStatus, updated, message != null ? message : "");
            String statusInDb = ragDocumentRepository.findStatusByDocId(docId).orElse(null);
            log.info("RAG status callback docId={} after UPDATE (same tx): statusInDb={}", docId, statusInDb);
        } else {
            log.warn("RAG status callback docId={} status={} — no row updated (document may not exist)", docId, normalizedStatus);
        }
        eventPublisher.publishEvent(new RagDocumentStatusUpdatedEvent(this, docId, tenantId, normalizedStatus, message));
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
     * 재청킹 요청: 기존 청크 삭제 후 새로운 전략으로 재벡터화.
     * PROCESSING 저장은 별도 트랜잭션에서 먼저 커밋한 뒤 Aura(Feign)를 호출하여,
     * status 콜백(COMPLETED)이 나중에 커밋되어도 rechunk 트랜잭션이 덮어쓰지 않도록 함.
     */
    public com.dwp.services.synapsex.dto.rag.RechunkResponse rechunk(
            Long tenantId, Long docId, com.dwp.services.synapsex.dto.rag.RechunkRequest request) {

        RagDocument doc = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다. docId=" + docId));

        String currentStatus = doc.getStatus();
        log.info("Rechunk request: docId={} tenantId={} currentDocStatus={} (will set to PROCESSING)", docId, tenantId, currentStatus);

        boolean forceRetry = Boolean.TRUE.equals(request.getForce());
        if (!forceRetry && ("PROCESSING".equals(doc.getStatus()) || "VECTORIZING".equals(doc.getStatus()))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "문서가 현재 처리 중입니다. 완료 후 재시도하세요. 재시도하려면 force=true 로 요청하세요.");
        }
        if (forceRetry && ("PROCESSING".equals(doc.getStatus()) || "VECTORIZING".equals(doc.getStatus()))) {
            log.info("Rechunk force=true: docId={} previous status={}, allowing retry", docId, doc.getStatus());
        }

        transactionTemplate.executeWithoutResult(status -> {
            RagDocument d = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다. docId=" + docId));
            d.setStatus("PROCESSING");
            d.setDocType(request.getStrategy());
            ragDocumentRepository.save(d);
        });
        log.info("Rechunk PROCESSING committed for docId={}, triggering Aura", docId);

        try {
            AuraTenantContext.setTenantId(tenantId);
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
            } finally {
                AuraTenantContext.clear();
            }
        } catch (FeignException e) {
            log.error("Rechunk Aura trigger failed: docId={} error={}", docId, e.getMessage());
            transactionTemplate.executeWithoutResult(s -> {
                RagDocument d = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId).orElse(null);
                if (d != null) {
                    d.setStatus("FAILED");
                    ragDocumentRepository.save(d);
                }
            });
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Aura 재청킹 요청 실패: " + e.getMessage());
        }
    }

    /**
     * 문서 단위 교체 적재 API:
     * - 기존 active 청크를 inactive 처리
     * - 신규 청크를 active로 INSERT
     * - 트랜잭션 경계에서 원자적 전환
     */
    @Transactional
    public void replaceDocumentChunks(Long tenantId, Long docId, List<AuraChunkItemDto> chunks) {
        RagDocument doc = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다. docId=" + docId));
        List<AuraChunkItemDto> safeChunks = chunks != null ? chunks : Collections.emptyList();
        ragStorageService.saveChunks(tenantId, doc.getDocId(), safeChunks);
    }

    /**
     * 비활성 버전을 활성 버전으로 전환.
     * - 현재 active 청크 비활성화
     * - 요청 version 청크 활성화
     */
    @Transactional
    public void activateChunkVersion(Long tenantId, Long docId, String version) {
        if (version == null || version.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "version은 필수입니다.");
        }
        String normalizedVersion = version.trim();
        RagDocument doc = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다. docId=" + docId));

        long targetCount = ragChunkRepository.countByTenantIdAndDocIdAndVersion(tenantId, docId, normalizedVersion);
        if (targetCount <= 0) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "활성 전환할 버전을 찾을 수 없습니다. version=" + normalizedVersion);
        }

        ragChunkRepository.deactivateActiveByTenantIdAndDocId(tenantId, docId);
        int activated = ragChunkRepository.activateByTenantIdAndDocIdAndVersion(tenantId, docId, normalizedVersion);
        if (activated <= 0) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "버전 활성화에 실패했습니다. version=" + normalizedVersion);
        }

        doc.setVersion(normalizedVersion);
        doc.setLifecycleStatus("ACTIVE");
        doc.setActiveFrom(java.time.Instant.now());
        doc.setActiveTo(null);
        doc.setUpdatedAt(java.time.Instant.now());
        ragDocumentRepository.save(doc);

        log.info("RAG chunk version activated: tenantId={} docId={} version={} activatedCount={}",
                tenantId, docId, normalizedVersion, activated);
    }
}
