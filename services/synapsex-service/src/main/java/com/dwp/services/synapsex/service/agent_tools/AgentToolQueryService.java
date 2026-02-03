package com.dwp.services.synapsex.service.agent_tools;

import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import com.dwp.services.synapsex.service.entity.EntityQueryService;
import com.dwp.services.synapsex.service.lineage.LineageQueryService;
import com.dwp.services.synapsex.service.openitem.OpenItemQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Agent Tool Read API: 기존 Query 서비스 위임
 */
@Service
@RequiredArgsConstructor
public class AgentToolQueryService {

    private final CaseQueryService caseQueryService;
    private final DocumentQueryService documentQueryService;
    private final EntityQueryService entityQueryService;
    private final OpenItemQueryService openItemQueryService;
    private final LineageQueryService lineageQueryService;

    @Transactional(readOnly = true)
    public Optional<CaseDetailDto> getCase(Long tenantId, Long caseId) {
        return caseQueryService.findCaseDetail(tenantId, caseId);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentListRowDto> getDocuments(Long tenantId,
                                                        String bukrs, String gjahr, Long vendorId, Long customerId,
                                                        LocalDate fromDate, LocalDate toDate,
                                                        BigDecimal amountMin, BigDecimal amountMax,
                                                        Boolean anomalyFlags,
                                                        int page, int size, String sort) {
        Long partyId = vendorId != null ? vendorId : customerId;
        var query = DocumentQueryService.DocumentListQuery.builder()
                .bukrs(bukrs)
                .gjahr(gjahr)
                .partyId(partyId)
                .dateFrom(fromDate)
                .dateTo(toDate)
                .amountMin(amountMin)
                .amountMax(amountMax)
                .hasCase(anomalyFlags)
                .page(page)
                .size(size)
                .sort(sort)
                .build();
        return documentQueryService.findDocuments(tenantId, query);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentDetailDto> getDocumentDetail(Long tenantId, String bukrs, String belnr, String gjahr) {
        return documentQueryService.findDocumentDetail(tenantId, bukrs.toUpperCase(), belnr, gjahr);
    }

    @Transactional(readOnly = true)
    public Optional<Entity360Dto> getEntity(Long tenantId, Long entityId) {
        return entityQueryService.findEntity360(tenantId, entityId);
    }

    @Transactional(readOnly = true)
    public PageResponse<OpenItemListRowDto> getOpenItems(Long tenantId, String type,
                                                         Integer overdueBucket,
                                                         int page, int size, String sort) {
        Integer daysPastDueMin = null;
        Integer daysPastDueMax = null;
        if (overdueBucket != null) {
            switch (overdueBucket) {
                case 0 -> { daysPastDueMin = 0; daysPastDueMax = 0; }  // current
                case 1 -> { daysPastDueMin = 1; daysPastDueMax = 30; }  // 1-30
                case 2 -> { daysPastDueMin = 31; daysPastDueMax = 90; } // 31-90
                case 3 -> daysPastDueMin = 91;  // 90+
                default -> {}
            }
        }
        var query = OpenItemQueryService.OpenItemListQuery.builder()
                .type(type)
                .daysPastDueMin(daysPastDueMin)
                .daysPastDueMax(daysPastDueMax)
                .page(page)
                .size(size)
                .sort(sort)
                .build();
        return openItemQueryService.findOpenItems(tenantId, query);
    }

    @Transactional(readOnly = true)
    public LineageResponseDto getLineage(Long tenantId, Long caseId, Instant asOf) {
        var query = LineageQueryService.LineageQuery.builder()
                .caseId(caseId)
                .asOf(asOf)
                .build();
        return lineageQueryService.findLineage(tenantId, query);
    }
}
