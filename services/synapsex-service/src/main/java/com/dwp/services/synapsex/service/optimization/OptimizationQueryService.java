package com.dwp.services.synapsex.service.optimization;

import com.dwp.services.synapsex.dto.optimization.OptimizationArApDto;
import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.repository.FiOpenItemRepository;
import com.dwp.services.synapsex.scope.TenantScopeResolver;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.dwp.services.synapsex.entity.QFiOpenItem.fiOpenItem;

/**
 * Phase 2 Optimization - open_item 기반 AR/AP 버킷/연체예측
 */
@Service
@RequiredArgsConstructor
public class OptimizationQueryService {

    private final JPAQueryFactory queryFactory;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final TenantScopeResolver tenantScopeResolver;

    @Transactional(readOnly = true)
    public OptimizationArApDto getArOptimization(Long tenantId) {
        return buildOptimization(tenantId, "AR");
    }

    @Transactional(readOnly = true)
    public OptimizationArApDto getApOptimization(Long tenantId) {
        return buildOptimization(tenantId, "AP");
    }

    private OptimizationArApDto buildOptimization(Long tenantId, String type) {
        Set<String> allowedBukrs = tenantScopeResolver.resolveEnabledBukrs(tenantId);
        BooleanExpression predicate = fiOpenItem.tenantId.eq(tenantId)
                .and(fiOpenItem.itemType.eq(type))
                .and(fiOpenItem.cleared.eq(false));
        if (!allowedBukrs.isEmpty()) {
            predicate = predicate.and(fiOpenItem.bukrs.in(allowedBukrs));
        }

        LocalDate now = LocalDate.now();
        List<OptimizationArApDto.BucketDto> buckets = new ArrayList<>();

        for (var bucket : List.of(
                new BucketRange("current", now, null),
                new BucketRange("1-30", now.minusDays(30), now.minusDays(1)),
                new BucketRange("31-90", now.minusDays(90), now.minusDays(31)),
                new BucketRange("90+", null, now.minusDays(91)))) {

            var bucketPred = predicate;
            if ("current".equals(bucket.key)) {
                bucketPred = bucketPred.and(fiOpenItem.dueDate.goe(now));
            } else if (bucket.from != null && bucket.to != null) {
                bucketPred = bucketPred.and(fiOpenItem.dueDate.between(bucket.to, bucket.from));
            } else if (bucket.from == null && bucket.to != null) {
                bucketPred = bucketPred.and(fiOpenItem.dueDate.lt(bucket.to));
            }

            List<FiOpenItem> items = queryFactory.selectFrom(fiOpenItem).where(bucketPred).fetch();
            BigDecimal total = items.stream().map(FiOpenItem::getOpenAmount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
            String waers = items.isEmpty() ? "KRW" : items.get(0).getCurrency();
            buckets.add(OptimizationArApDto.BucketDto.builder()
                    .bucketKey(bucket.key)
                    .itemCount(items.size())
                    .totalAmount(total)
                    .currency(waers)
                    .build());
        }

        BooleanExpression overduePred = predicate.and(fiOpenItem.dueDate.lt(now));
        List<FiOpenItem> overdueItems = queryFactory.selectFrom(fiOpenItem).where(overduePred).fetch();
        BigDecimal overdueAmount = overdueItems.stream().map(FiOpenItem::getOpenAmount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        double avgDays = overdueItems.isEmpty() ? 0 : overdueItems.stream()
                .mapToLong(i -> java.time.temporal.ChronoUnit.DAYS.between(i.getDueDate(), now))
                .average().orElse(0);

        var overdueSummary = OptimizationArApDto.OverdueSummaryDto.builder()
                .overdueCount(overdueItems.size())
                .overdueAmount(overdueAmount)
                .currency(overdueItems.isEmpty() ? "KRW" : overdueItems.get(0).getCurrency())
                .avgDaysPastDue(avgDays)
                .build();

        List<OptimizationArApDto.AlertRecommendationDto> alerts = new ArrayList<>();
        if (overdueItems.size() > 10) {
            alerts.add(OptimizationArApDto.AlertRecommendationDto.builder()
                    .recommendationType("NUDGE")
                    .affectedCount(overdueItems.size())
                    .reason("연체 건수 " + overdueItems.size() + "건, 알림 발송 권장")
                    .build());
        }
        long blockedCount = overdueItems.stream().filter(i -> Boolean.TRUE.equals(i.getPaymentBlock())).count();
        if (blockedCount > 0) {
            alerts.add(OptimizationArApDto.AlertRecommendationDto.builder()
                    .recommendationType("REVIEW")
                    .affectedCount((int) blockedCount)
                    .reason("결제블록 " + blockedCount + "건 검토 필요")
                    .build());
        }

        return OptimizationArApDto.builder()
                .type(type)
                .buckets(buckets)
                .overdueSummary(overdueSummary)
                .alertRecommendations(alerts)
                .build();
    }

    private record BucketRange(String key, LocalDate from, LocalDate to) {}
}
