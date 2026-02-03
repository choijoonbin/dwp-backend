package com.dwp.services.synapsex.service.openitem;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.openitem.OpenItemDetailDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.scope.TenantScopeResolver;
import com.dwp.services.synapsex.util.OpenItemKeyUtil;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenItemQueryService {

    private static final QFiDocHeader h = QFiDocHeader.fiDocHeader;

    private final JPAQueryFactory queryFactory;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final BpPartyRepository bpPartyRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final TenantScopeResolver tenantScopeResolver;

    private static final QFiOpenItem oi = QFiOpenItem.fiOpenItem;

    @Transactional(readOnly = true)
    public PageResponse<OpenItemListRowDto> findOpenItems(Long tenantId, OpenItemListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(oi.tenantId.eq(tenantId));

        Set<String> allowedBukrs = tenantScopeResolver.resolveEnabledBukrs(tenantId);
        if (query.getBukrs() != null && !query.getBukrs().isBlank()) {
            String bukrs = query.getBukrs().toUpperCase();
            if (allowedBukrs.isEmpty() || allowedBukrs.contains(bukrs)) {
                predicate.and(oi.bukrs.eq(bukrs));
            } else {
                predicate.and(oi.bukrs.eq("__SCOPE_EXCLUDED__"));
            }
        } else if (!allowedBukrs.isEmpty()) {
            predicate.and(oi.bukrs.in(allowedBukrs));
        }

        LocalDate fromDue = query.getFromDueDate() != null ? query.getFromDueDate() : query.getDueFrom();
        LocalDate toDue = query.getToDueDate() != null ? query.getToDueDate() : query.getDueTo();
        if (fromDue != null) predicate.and(oi.dueDate.goe(fromDue));
        if (toDue != null) predicate.and(oi.dueDate.loe(toDue));

        if (query.getDaysPastDueMin() != null) {
            LocalDate minDate = LocalDate.now().minusDays(query.getDaysPastDueMin());
            predicate.and(oi.dueDate.loe(minDate));
        }
        if (query.getDaysPastDueMax() != null) {
            LocalDate maxDate = LocalDate.now().minusDays(query.getDaysPastDueMax());
            predicate.and(oi.dueDate.goe(maxDate));
        }

        if (query.getCleared() != null) {
            predicate.and(oi.cleared.eq(query.getCleared()));
        }
        if (query.getPaymentBlock() != null) {
            predicate.and(oi.paymentBlock.eq(query.getPaymentBlock()));
        }
        if (query.getDisputeFlag() != null) {
            predicate.and(oi.disputeFlag.eq(query.getDisputeFlag()));
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            predicate.and(oi.itemType.eq(query.getType().toUpperCase()));
        } else if (query.getItemType() != null && !query.getItemType().isBlank()) {
            predicate.and(oi.itemType.eq(query.getItemType().toUpperCase()));
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            String status = query.getStatus().toUpperCase();
            if ("CLEARED".equals(status)) predicate.and(oi.cleared.eq(true));
            else if ("OPEN".equals(status) || "PARTIALLY_CLEARED".equals(status)) predicate.and(oi.cleared.eq(false));
        }
        if (query.getPartyId() != null) {
            BpParty party = bpPartyRepository.findById(query.getPartyId()).orElse(null);
            if (party != null && tenantId.equals(party.getTenantId())) {
                String code = party.getPartyCode();
                if ("VENDOR".equalsIgnoreCase(party.getPartyType())) {
                    predicate.and(oi.lifnr.eq(code));
                } else if ("CUSTOMER".equalsIgnoreCase(party.getPartyType())) {
                    predicate.and(oi.kunnr.eq(code));
                } else {
                    predicate.and(oi.lifnr.eq(code).or(oi.kunnr.eq(code)));
                }
            }
        }
        if (query.getLifnr() != null && !query.getLifnr().isBlank()) {
            predicate.and(oi.lifnr.eq(query.getLifnr()));
        }
        if (query.getKunnr() != null && !query.getKunnr().isBlank()) {
            predicate.and(oi.kunnr.eq(query.getKunnr()));
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String q = query.getQ().trim();
            predicate.and(
                    oi.lifnr.containsIgnoreCase(q)
                            .or(oi.kunnr.containsIgnoreCase(q))
                            .or(JPAExpressions.selectOne().from(h)
                                    .where(h.tenantId.eq(tenantId)
                                            .and(h.bukrs.eq(oi.bukrs))
                                            .and(h.belnr.eq(oi.belnr))
                                            .and(h.gjahr.eq(oi.gjahr))
                                            .and(h.xblnr.containsIgnoreCase(q)))
                                    .exists()));
        }

        OrderSpecifier<?> orderBy = oi.lastUpdateTs.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            String prop = parts[0].trim();
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1].trim());
            orderBy = switch (prop.toLowerCase()) {
                case "duedate" -> asc ? oi.dueDate.asc() : oi.dueDate.desc();
                case "openamount", "amount" -> asc ? oi.openAmount.asc() : oi.openAmount.desc();
                default -> asc ? oi.lastUpdateTs.asc() : oi.lastUpdateTs.desc();
            };
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));

        List<FiOpenItem> items = queryFactory.selectFrom(oi)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();

        long total = queryFactory.selectFrom(oi)
                .where(predicate)
                .fetchCount();

        List<OpenItemListRowDto> rows = items.stream()
                .map(item -> buildOpenItemListRow(tenantId, item))
                .toList();

        return PageResponse.of(rows, total, page, size);
    }

    private OpenItemListRowDto buildOpenItemListRow(Long tenantId, FiOpenItem item) {
        Long partyId = resolvePartyId(tenantId, item.getLifnr(), item.getKunnr(), item.getItemType());
        String partyName = null;
        if (partyId != null) {
            partyName = bpPartyRepository.findById(partyId).map(BpParty::getNameDisplay).orElse(null);
        }
        String status = Boolean.TRUE.equals(item.getCleared()) ? "CLEARED" : "OPEN";
        Integer daysPastDue = item.getDueDate() != null && item.getDueDate().isBefore(LocalDate.now())
                ? (int) ChronoUnit.DAYS.between(item.getDueDate(), LocalDate.now()) : null;
        String openItemKey = OpenItemKeyUtil.format(item.getBukrs(), item.getBelnr(), item.getGjahr(), item.getBuzei());
        String docKey = item.getBukrs() + "-" + item.getBelnr() + "-" + item.getGjahr();

        return OpenItemListRowDto.builder()
                .openItemKey(openItemKey)
                .bukrs(item.getBukrs())
                .belnr(item.getBelnr())
                .gjahr(item.getGjahr())
                .buzei(item.getBuzei())
                .type(item.getItemType())
                .dueDate(item.getDueDate())
                .amount(item.getOpenAmount())
                .currency(item.getCurrency())
                .status(status)
                .daysPastDue(daysPastDue)
                .disputeFlag(item.getDisputeFlag())
                .paymentBlock(item.getPaymentBlock())
                .blockReason(null)
                .recommendedAction(null)
                .guardrailStatus("ALLOWED")
                .lastChangeTs(item.getLastChangeTs())
                .partyId(partyId)
                .partyName(partyName)
                .docKey(docKey)
                .build();
    }

    private Long resolvePartyId(Long tenantId, String lifnr, String kunnr, String itemType) {
        if (lifnr != null && !lifnr.isBlank()) {
            return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", lifnr)
                    .map(BpParty::getPartyId)
                    .orElse(null);
        }
        if (kunnr != null && !kunnr.isBlank()) {
            return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", kunnr)
                    .map(BpParty::getPartyId)
                    .orElse(null);
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Optional<OpenItemDetailDto> findOpenItemDetail(Long tenantId, String bukrs, String belnr, String gjahr, String buzei) {
        return fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(tenantId, bukrs, belnr, gjahr, buzei)
                .map(item -> buildOpenItemDetail(tenantId, item));
    }

    @Transactional(readOnly = true)
    public Optional<OpenItemDetailDto> findOpenItemDetailByOpenItemKey(Long tenantId, String openItemKey) {
        OpenItemKeyUtil.ParsedOpenItemKey parsed = OpenItemKeyUtil.parse(openItemKey);
        if (parsed == null) return Optional.empty();
        return findOpenItemDetail(tenantId, parsed.getBukrs(), parsed.getBelnr(), parsed.getGjahr(), parsed.getBuzei());
    }

    private OpenItemDetailDto buildOpenItemDetail(Long tenantId, FiOpenItem item) {
        var docHeader = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                tenantId, item.getBukrs(), item.getBelnr(), item.getGjahr());
        var party = resolveParty(tenantId, item.getLifnr(), item.getKunnr());
        var cases = agentCaseRepository.findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
                tenantId, item.getBukrs(), item.getBelnr(), item.getGjahr(), item.getBuzei());
        String openItemKey = OpenItemKeyUtil.format(item.getBukrs(), item.getBelnr(), item.getGjahr(), item.getBuzei());

        return OpenItemDetailDto.builder()
                .openItemKey(openItemKey)
                .bukrs(item.getBukrs())
                .belnr(item.getBelnr())
                .gjahr(item.getGjahr())
                .buzei(item.getBuzei())
                .itemType(item.getItemType())
                .dueDate(item.getDueDate() != null ? item.getDueDate().toString() : null)
                .openAmount(item.getOpenAmount() != null ? item.getOpenAmount().toString() : null)
                .currency(item.getCurrency())
                .cleared(item.getCleared())
                .paymentBlock(item.getPaymentBlock())
                .disputeFlag(item.getDisputeFlag())
                .lastChangeTs(item.getLastChangeTs() != null ? item.getLastChangeTs().toString() : null)
                .docHeaderSummary(docHeader.map(h -> OpenItemDetailDto.DocHeaderSummaryDto.builder()
                        .bukrs(h.getBukrs())
                        .belnr(h.getBelnr())
                        .gjahr(h.getGjahr())
                        .budat(h.getBudat() != null ? h.getBudat().toString() : null)
                        .xblnr(h.getXblnr())
                        .bktxt(h.getBktxt())
                        .build()).orElse(null))
                .partySummary(party.map(p -> OpenItemDetailDto.PartySummaryDto.builder()
                        .partyId(p.getPartyId())
                        .partyType(p.getPartyType())
                        .partyCode(p.getPartyCode())
                        .nameDisplay(p.getNameDisplay())
                        .build()).orElse(null))
                .relatedCases(cases.stream()
                        .map(c -> OpenItemDetailDto.RelatedCaseDto.builder()
                                .caseId(c.getCaseId())
                                .caseType(c.getCaseType())
                                .severity(c.getSeverity())
                                .detectedAt(c.getDetectedAt() != null ? c.getDetectedAt().toString() : null)
                                .build())
                        .toList())
                .clearingHistory(List.of())
                .build();
    }

    private Optional<BpParty> resolveParty(Long tenantId, String lifnr, String kunnr) {
        if (lifnr != null && !lifnr.isBlank()) {
            return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", lifnr);
        }
        if (kunnr != null && !kunnr.isBlank()) {
            return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", kunnr);
        }
        return Optional.empty();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OpenItemListQuery {
        private LocalDate dueFrom;
        private LocalDate dueTo;
        private LocalDate fromDueDate;
        private LocalDate toDueDate;
        private Integer daysPastDueMin;
        private Integer daysPastDueMax;
        private Boolean cleared;
        private Boolean paymentBlock;
        private Boolean disputeFlag;
        private String itemType;
        private String type;
        private String status;
        private String bukrs;
        private Long partyId;
        private String lifnr;
        private String kunnr;
        private String q;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
    }
}
