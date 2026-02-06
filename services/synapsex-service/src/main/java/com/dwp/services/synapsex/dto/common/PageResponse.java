package com.dwp.services.synapsex.dto.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * List 응답 표준 형식 (Drill-down 계약)
 * { data/items: [...], page, size, total, sort, order, filtersApplied }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> items;
    private long total;
    private PageInfo pageInfo;

    /** FE "현재 필터 상태" 배지/칩 표시용 */
    private String sort;
    private String order;
    private Map<String, Object> filtersApplied;

    /** P0-2: Case list용 summary (total, open, triage, inReview) — optional */
    private Map<String, Long> summary;

    /** FE 계약: data는 items와 동일 */
    @JsonProperty("data")
    public List<T> getData() { return items; }

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return of(items, total, page, size, "createdAt", "desc", null);
    }

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size,
                                          String sort, String order, Map<String, Object> filtersApplied) {
        boolean hasNext = (long) (page + 1) * size < total;
        return PageResponse.<T>builder()
                .items(items)
                .total(total)
                .pageInfo(PageInfo.builder()
                        .page(page + 1)
                        .size(size)
                        .hasNext(hasNext)
                        .build())
                .sort(sort != null ? sort : "createdAt")
                .order(order != null ? order : "desc")
                .filtersApplied(filtersApplied)
                .build();
    }
}
