package com.dwp.services.synapsex.service.document;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.document.DocumentReversalChainDto;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.entity.QAgentCase;
import com.dwp.services.synapsex.entity.QIngestionError;
import com.dwp.services.synapsex.scope.TenantScopeResolver;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1 Documents 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final JPAQueryFactory queryFactory;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final BpPartyRepository bpPartyRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final IngestionErrorRepository ingestionErrorRepository;
    private final TenantScopeResolver tenantScopeResolver;

    private static final QFiDocHeader h = QFiDocHeader.fiDocHeader;
    private static final QFiDocItem i = QFiDocItem.fiDocItem;

    @Transactional(readOnly = true)
    public PageResponse<DocumentListRowDto> findDocuments(Long tenantId, DocumentListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(h.tenantId.eq(tenantId));

        if (query.getDateFrom() != null) {
            predicate.and(h.budat.goe(query.getDateFrom()));
        }
        if (query.getDateTo() != null) {
            predicate.and(h.budat.loe(query.getDateTo()));
        }
        Set<String> allowedBukrs = tenantScopeResolver.resolveEnabledBukrs(tenantId);
        if (query.getBukrs() != null && !query.getBukrs().isBlank()) {
            String bukrs = query.getBukrs().toUpperCase();
            if (allowedBukrs.isEmpty() || allowedBukrs.contains(bukrs)) {
                predicate.and(h.bukrs.eq(bukrs));
            } else {
                predicate.and(h.bukrs.eq("__SCOPE_EXCLUDED__")); // bukrs not in scope → no results
            }
        } else if (!allowedBukrs.isEmpty()) {
            predicate.and(h.bukrs.in(allowedBukrs));
        }
        if (query.getBelnr() != null && !query.getBelnr().isBlank()) {
            predicate.and(h.belnr.eq(query.getBelnr()));
        }
        if (query.getGjahr() != null && !query.getGjahr().isBlank()) {
            predicate.and(h.gjahr.eq(query.getGjahr()));
        }
        if (query.getUsnam() != null && !query.getUsnam().isBlank()) {
            predicate.and(h.usnam.eq(query.getUsnam()));
        }
        if (query.getTcode() != null && !query.getTcode().isBlank()) {
            predicate.and(h.tcode.eq(query.getTcode()));
        }
        if (query.getXblnr() != null && !query.getXblnr().isBlank()) {
            predicate.and(h.xblnr.containsIgnoreCase(query.getXblnr()));
        }
        if (query.getStatusCode() != null && !query.getStatusCode().isBlank()) {
            predicate.and(h.statusCode.eq(query.getStatusCode()));
        }
        if (Boolean.TRUE.equals(query.getHasReversal())) {
            predicate.and(h.reversalBelnr.isNotNull());
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String q = "%" + query.getQ().trim() + "%";
            predicate.and(h.xblnr.containsIgnoreCase(q)
                    .or(h.bktxt.containsIgnoreCase(q))
                    .or(h.belnr.containsIgnoreCase(q))
                    .or(h.usnam.containsIgnoreCase(q))
                    .or(h.tcode.containsIgnoreCase(q)));
        }
        if (Boolean.TRUE.equals(query.getHasCase())) {
            predicate.and(JPAExpressions.selectOne()
                    .from(QAgentCase.agentCase)
                    .where(QAgentCase.agentCase.tenantId.eq(tenantId)
                            .and(QAgentCase.agentCase.bukrs.eq(h.bukrs))
                            .and(QAgentCase.agentCase.belnr.eq(h.belnr))
                            .and(QAgentCase.agentCase.gjahr.eq(h.gjahr)))
                    .exists());
        }
        if (query.getPartyId() != null) {
            var partyOpt = bpPartyRepository.findById(query.getPartyId())
                    .filter(p -> tenantId.equals(p.getTenantId()));
            if (partyOpt.isPresent()) {
                String code = partyOpt.get().getPartyCode();
                String ptype = partyOpt.get().getPartyType();
                if ("VENDOR".equalsIgnoreCase(ptype)) {
                    predicate.and(JPAExpressions.selectOne()
                            .from(i)
                            .where(i.tenantId.eq(tenantId)
                                    .and(i.bukrs.eq(h.bukrs))
                                    .and(i.belnr.eq(h.belnr))
                                    .and(i.gjahr.eq(h.gjahr))
                                    .and(i.lifnr.eq(code)))
                            .exists());
                } else if ("CUSTOMER".equalsIgnoreCase(ptype)) {
                    predicate.and(JPAExpressions.selectOne()
                            .from(i)
                            .where(i.tenantId.eq(tenantId)
                                    .and(i.bukrs.eq(h.bukrs))
                                    .and(i.belnr.eq(h.belnr))
                                    .and(i.gjahr.eq(h.gjahr))
                                    .and(i.kunnr.eq(code)))
                            .exists());
                }
            }
        }
        if (query.getIntegrityStatus() != null && !query.getIntegrityStatus().isBlank()) {
            String status = query.getIntegrityStatus().toUpperCase();
            if ("FAIL".equals(status)) {
                predicate.and(JPAExpressions.selectOne().from(i)
                        .where(i.tenantId.eq(h.tenantId).and(i.bukrs.eq(h.bukrs))
                                .and(i.belnr.eq(h.belnr)).and(i.gjahr.eq(h.gjahr)))
                        .exists().not());
            } else if ("WARN".equals(status)) {
                predicate.and(h.rawEventId.isNotNull());
                predicate.and(JPAExpressions.selectOne().from(QIngestionError.ingestionError)
                        .where(QIngestionError.ingestionError.rawEventId.eq(h.rawEventId))
                        .exists());
            } else if ("PASS".equals(status)) {
                predicate.and(JPAExpressions.selectOne().from(i)
                        .where(i.tenantId.eq(h.tenantId).and(i.bukrs.eq(h.bukrs))
                                .and(i.belnr.eq(h.belnr)).and(i.gjahr.eq(h.gjahr)))
                        .exists());
                predicate.and(h.rawEventId.isNull().or(
                        JPAExpressions.selectOne().from(QIngestionError.ingestionError)
                                .where(QIngestionError.ingestionError.rawEventId.eq(h.rawEventId))
                                .exists().not()));
            }
        }
        if (query.getLifnr() != null && !query.getLifnr().isBlank()) {
            predicate.and(JPAExpressions.selectOne()
                    .from(i)
                    .where(i.tenantId.eq(tenantId)
                            .and(i.bukrs.eq(h.bukrs))
                            .and(i.belnr.eq(h.belnr))
                            .and(i.gjahr.eq(h.gjahr))
                            .and(i.lifnr.eq(query.getLifnr())))
                    .exists());
        }
        if (query.getKunnr() != null && !query.getKunnr().isBlank()) {
            predicate.and(JPAExpressions.selectOne()
                    .from(i)
                    .where(i.tenantId.eq(tenantId)
                            .and(i.bukrs.eq(h.bukrs))
                            .and(i.belnr.eq(h.belnr))
                            .and(i.gjahr.eq(h.gjahr))
                            .and(i.kunnr.eq(query.getKunnr())))
                    .exists());
        }

        OrderSpecifier<?> orderBy = h.updatedAt.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            String prop = parts[0].trim();
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1].trim());
            orderBy = switch (prop.toLowerCase()) {
                case "budat" -> asc ? h.budat.asc() : h.budat.desc();
                case "belnr" -> asc ? h.belnr.asc() : h.belnr.desc();
                default -> asc ? h.updatedAt.asc() : h.updatedAt.desc();
            };
        }

        if (query.getAmountMin() != null || query.getAmountMax() != null) {
            var amountSub = JPAExpressions.select(i.tenantId, i.bukrs, i.belnr, i.gjahr)
                    .from(i)
                    .where(i.tenantId.eq(tenantId))
                    .groupBy(i.tenantId, i.bukrs, i.belnr, i.gjahr);
            if (query.getAmountMin() != null && query.getAmountMax() != null) {
                amountSub = amountSub.having(i.wrbtr.sum().between(query.getAmountMin(), query.getAmountMax()));
            } else if (query.getAmountMin() != null) {
                amountSub = amountSub.having(i.wrbtr.sum().goe(query.getAmountMin()));
            } else {
                amountSub = amountSub.having(i.wrbtr.sum().loe(query.getAmountMax()));
            }
            predicate.and(Expressions.list(h.tenantId, h.bukrs, h.belnr, h.gjahr).in(amountSub));
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));

        List<FiDocHeader> headers = queryFactory.selectFrom(h)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();

        long total = queryFactory.selectFrom(h)
                .where(predicate)
                .fetchCount();

        List<DocumentListRowDto> rows = buildDocumentListRows(tenantId, headers);
        return PageResponse.of(rows, total, page, size);
    }

    private List<DocumentListRowDto> buildDocumentListRows(Long tenantId, List<FiDocHeader> headers) {
        if (headers.isEmpty()) return List.of();
        List<DocumentListRowDto> rows = new ArrayList<>();
        for (FiDocHeader header : headers) {
            List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                    tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
            BigDecimal totalWrbtr = items.stream()
                    .map(FiDocItem::getWrbtr)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<String> topLifnr = items.stream()
                    .map(FiDocItem::getLifnr)
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
            List<String> topKunnr = items.stream()
                    .map(FiDocItem::getKunnr)
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
            String primaryLifnr = topLifnr.isEmpty() ? null : topLifnr.get(0);
            String primaryKunnr = topKunnr.isEmpty() ? null : topKunnr.get(0);
            String counterpartyName = null;
            if (primaryLifnr != null) {
                counterpartyName = bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", primaryLifnr)
                        .map(BpParty::getNameDisplay).orElse(null);
            }
            if (counterpartyName == null && primaryKunnr != null) {
                counterpartyName = bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", primaryKunnr)
                        .map(BpParty::getNameDisplay).orElse(null);
            }
            List<AgentCase> cases = agentCaseRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                    tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
            int ingestionErrorCount = header.getRawEventId() != null
                    ? (int) ingestionErrorRepository.countByRawEventId(header.getRawEventId())
                    : 0;
            String integrityStatus = deriveIntegrityStatus(items.size(), header.getRawEventId() != null, ingestionErrorCount);
            String docKey = header.getBukrs() + "-" + header.getBelnr() + "-" + header.getGjahr();
            String reversesDocKey = null;
            String reversedByDocKey = null;
            if (header.getReversalBelnr() != null && !header.getReversalBelnr().isBlank()) {
                reversesDocKey = header.getBukrs() + "-" + header.getReversalBelnr() + "-" + header.getGjahr();
            }
            List<FiDocHeader> reversers = fiDocHeaderRepository.findByTenantIdAndBukrsAndReversalBelnrAndGjahr(
                    tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
            if (!reversers.isEmpty()) {
                FiDocHeader rev = reversers.get(0);
                reversedByDocKey = rev.getBukrs() + "-" + rev.getBelnr() + "-" + rev.getGjahr();
            }
            rows.add(DocumentListRowDto.builder()
                    .docKey(docKey)
                    .bukrs(header.getBukrs())
                    .belnr(header.getBelnr())
                    .gjahr(header.getGjahr())
                    .budat(header.getBudat())
                    .bldat(header.getBldat())
                    .blart(header.getBlart())
                    .tcode(header.getTcode())
                    .usnam(header.getUsnam())
                    .kunnr(primaryKunnr)
                    .lifnr(primaryLifnr)
                    .counterpartyName(counterpartyName)
                    .wrbtr(totalWrbtr)
                    .waers(header.getWaers())
                    .xblnr(header.getXblnr())
                    .bktxt(header.getBktxt())
                    .integrityStatus(integrityStatus)
                    .reversalFlag(header.getReversalBelnr() != null && !header.getReversalBelnr().isBlank())
                    .reversesDocKey(reversesDocKey)
                    .reversedByDocKey(reversedByDocKey)
                    .linkedCasesCount(cases.size())
                    .statusCode(header.getStatusCode())
                    .reversalBelnr(header.getReversalBelnr())
                    .lastChangeTs(header.getLastChangeTs())
                    .totals(DocumentListRowDto.DocumentTotalsDto.builder()
                            .itemCount(items.size())
                            .totalWrbtr(totalWrbtr)
                            .build())
                    .partnerSummary(DocumentListRowDto.PartnerSummaryDto.builder()
                            .topLifnr(topLifnr)
                            .topKunnr(topKunnr)
                            .build())
                    .links(DocumentListRowDto.DocumentLinksDto.builder()
                            .docKey(docKey)
                            .build())
                    .build());
        }
        return rows;
    }

    private String deriveIntegrityStatus(int itemCount, boolean hasRawEvent, int ingestionErrorCount) {
        if (itemCount == 0) return "FAIL";
        if (ingestionErrorCount > 0) return "WARN";
        return "PASS";
    }

    @Transactional(readOnly = true)
    public Optional<DocumentDetailDto> findDocumentDetail(Long tenantId, String bukrs, String belnr, String gjahr) {
        return fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr)
                .map(header -> buildDocumentDetail(tenantId, header));
    }

    private DocumentDetailDto buildDocumentDetail(Long tenantId, FiDocHeader header) {
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
        List<FiOpenItem> openItems = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
        List<AgentCase> cases = agentCaseRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());

        BigDecimal totalWrbtr = items.stream()
                .map(FiDocItem::getWrbtr)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int paymentBlockCount = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.getPaymentBlock())).count();
        int disputeCount = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.getDisputeFlag())).count();

        Map<String, Long> relatedParties = new HashMap<>();
        for (FiDocItem item : items) {
            if (item.getLifnr() != null && !item.getLifnr().isBlank()) {
                bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", item.getLifnr())
                        .ifPresent(p -> relatedParties.put("LIFNR:" + item.getLifnr(), p.getPartyId()));
            }
            if (item.getKunnr() != null && !item.getKunnr().isBlank()) {
                bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", item.getKunnr())
                        .ifPresent(p -> relatedParties.put("KUNNR:" + item.getKunnr(), p.getPartyId()));
            }
        }

        List<DocumentDetailDto.ReversalNodeDto> nodes = new ArrayList<>();
        List<DocumentDetailDto.ReversalEdgeDto> edges = new ArrayList<>();
        String docKey = header.getBukrs() + "-" + header.getBelnr() + "-" + header.getGjahr();
        nodes.add(DocumentDetailDto.ReversalNodeDto.builder()
                .docKey(docKey)
                .belnr(header.getBelnr())
                .reversalBelnr(header.getReversalBelnr())
                .build());
        if (header.getReversalBelnr() != null && !header.getReversalBelnr().isBlank()) {
            fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                    tenantId, header.getBukrs(), header.getReversalBelnr(), header.getGjahr())
                    .ifPresent(rev -> {
                        String revKey = rev.getBukrs() + "-" + rev.getBelnr() + "-" + rev.getGjahr();
                        nodes.add(DocumentDetailDto.ReversalNodeDto.builder()
                                .docKey(revKey)
                                .belnr(rev.getBelnr())
                                .reversalBelnr(rev.getReversalBelnr())
                                .build());
                        edges.add(DocumentDetailDto.ReversalEdgeDto.builder()
                                .fromDocKey(docKey)
                                .toDocKey(revKey)
                                .build());
                    });
        }
        List<FiDocHeader> reverseRefs = fiDocHeaderRepository.findByTenantIdAndBukrsAndReversalBelnrAndGjahr(
                tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
        for (FiDocHeader rev : reverseRefs) {
            String revKey = rev.getBukrs() + "-" + rev.getBelnr() + "-" + rev.getGjahr();
            if (nodes.stream().noneMatch(n -> revKey.equals(n.getDocKey()))) {
                nodes.add(DocumentDetailDto.ReversalNodeDto.builder()
                        .docKey(revKey)
                        .belnr(rev.getBelnr())
                        .reversalBelnr(rev.getReversalBelnr())
                        .build());
            }
            edges.add(DocumentDetailDto.ReversalEdgeDto.builder()
                    .fromDocKey(revKey)
                    .toDocKey(docKey)
                    .build());
        }

        int ingestionErrorCount = header.getRawEventId() != null
                ? (int) ingestionErrorRepository.countByRawEventId(header.getRawEventId())
                : 0;

        return DocumentDetailDto.builder()
                .header(DocumentDetailDto.DocumentHeaderDto.builder()
                        .bukrs(header.getBukrs())
                        .belnr(header.getBelnr())
                        .gjahr(header.getGjahr())
                        .docSource(header.getDocSource())
                        .budat(header.getBudat() != null ? header.getBudat().toString() : null)
                        .bldat(header.getBldat() != null ? header.getBldat().toString() : null)
                        .usnam(header.getUsnam())
                        .tcode(header.getTcode())
                        .blart(header.getBlart())
                        .waers(header.getWaers())
                        .xblnr(header.getXblnr())
                        .bktxt(header.getBktxt())
                        .statusCode(header.getStatusCode())
                        .reversalBelnr(header.getReversalBelnr())
                        .lastChangeTs(header.getLastChangeTs() != null ? header.getLastChangeTs().toString() : null)
                        .rawEventId(header.getRawEventId())
                        .build())
                .items(items.stream()
                        .map(item -> DocumentDetailDto.DocumentItemDto.builder()
                                .buzei(item.getBuzei())
                                .hkont(item.getHkont())
                                .lifnr(item.getLifnr())
                                .kunnr(item.getKunnr())
                                .wrbtr(item.getWrbtr() != null ? item.getWrbtr().toString() : null)
                                .waers(item.getWaers())
                                .paymentBlock(item.getPaymentBlock())
                                .disputeFlag(item.getDisputeFlag())
                                .build())
                        .toList())
                .derived(DocumentDetailDto.DocumentDerivedDto.builder()
                        .itemCount(items.size())
                        .totalWrbtr(totalWrbtr != null ? totalWrbtr.toString() : null)
                        .paymentBlockCount(paymentBlockCount)
                        .disputeCount(disputeCount)
                        .build())
                .reversalChain(DocumentDetailDto.ReversalChainDto.builder()
                        .nodes(nodes)
                        .edges(edges)
                        .build())
                .integrityChecks(DocumentDetailDto.IntegrityChecksDto.builder()
                        .headerExists(true)
                        .itemCountPositive(items.size() > 0)
                        .sumWrbtrNotNull(totalWrbtr != null)
                        .openItemsConsistency(openItems.size())
                        .ingestionErrorCount(ingestionErrorCount)
                        .build())
                .linkedObjects(DocumentDetailDto.LinkedObjectsDto.builder()
                        .openItems(openItems.stream()
                                .map(oi -> DocumentDetailDto.OpenItemSummaryDto.builder()
                                        .bukrs(oi.getBukrs())
                                        .belnr(oi.getBelnr())
                                        .gjahr(oi.getGjahr())
                                        .buzei(oi.getBuzei())
                                        .openAmount(oi.getOpenAmount() != null ? oi.getOpenAmount().toString() : null)
                                        .currency(oi.getCurrency())
                                        .dueDate(oi.getDueDate() != null ? oi.getDueDate().toString() : null)
                                        .build())
                                .toList())
                        .relatedParties(relatedParties)
                        .linkedCases(cases.stream()
                                .map(c -> DocumentDetailDto.LinkedCaseDto.builder()
                                        .caseId(c.getCaseId())
                                        .caseType(c.getCaseType())
                                        .severity(c.getSeverity())
                                        .detectedAt(c.getDetectedAt() != null ? c.getDetectedAt().toString() : null)
                                        .build())
                                .toList())
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<DocumentReversalChainDto> findReversalChain(Long tenantId, String docKey) {
        var parsed = com.dwp.services.synapsex.util.DocKeyUtil.parse(docKey);
        if (parsed == null) return Optional.empty();
        return fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                        tenantId, parsed.getBukrs(), parsed.getBelnr(), parsed.getGjahr())
                .map(header -> buildReversalChain(tenantId, header));
    }

    private DocumentReversalChainDto buildReversalChain(Long tenantId, FiDocHeader start) {
        Set<String> visited = new HashSet<>();
        List<DocumentReversalChainDto.ReversalNodeDto> nodes = new ArrayList<>();
        List<DocumentReversalChainDto.ReversalEdgeDto> edges = new ArrayList<>();
        Deque<FiDocHeader> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            FiDocHeader h = queue.poll();
            String dk = com.dwp.services.synapsex.util.DocKeyUtil.format(h.getBukrs(), h.getBelnr(), h.getGjahr());
            if (dk == null || !visited.add(dk)) continue;

            nodes.add(DocumentReversalChainDto.ReversalNodeDto.builder()
                    .docKey(dk)
                    .belnr(h.getBelnr())
                    .reversalBelnr(h.getReversalBelnr())
                    .budat(h.getBudat() != null ? h.getBudat().toString() : null)
                    .build());

            if (h.getReversalBelnr() != null && !h.getReversalBelnr().isBlank()) {
                fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                                tenantId, h.getBukrs(), h.getReversalBelnr(), h.getGjahr())
                        .ifPresent(rev -> {
                            String revKey = com.dwp.services.synapsex.util.DocKeyUtil.format(rev.getBukrs(), rev.getBelnr(), rev.getGjahr());
                            edges.add(DocumentReversalChainDto.ReversalEdgeDto.builder()
                                    .fromDocKey(dk)
                                    .toDocKey(revKey)
                                    .build());
                            queue.add(rev);
                        });
            }
            fiDocHeaderRepository.findByTenantIdAndBukrsAndReversalBelnrAndGjahr(
                            tenantId, h.getBukrs(), h.getBelnr(), h.getGjahr())
                    .forEach(rev -> {
                        String revKey = com.dwp.services.synapsex.util.DocKeyUtil.format(rev.getBukrs(), rev.getBelnr(), rev.getGjahr());
                        edges.add(DocumentReversalChainDto.ReversalEdgeDto.builder()
                                .fromDocKey(revKey)
                                .toDocKey(dk)
                                .build());
                        queue.add(rev);
                    });
        }
        return DocumentReversalChainDto.builder()
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentListQuery {
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private String bukrs;
        private String belnr;
        private String gjahr;
        private Long partyId;
        private String usnam;
        private String tcode;
        private String xblnr;
        private String statusCode;
        private String integrityStatus;
        private String lifnr;
        private String kunnr;
        private Boolean hasReversal;
        private Boolean hasCase;
        private BigDecimal amountMin;
        private BigDecimal amountMax;
        private String q;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
    }
}
