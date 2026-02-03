package com.dwp.services.synapsex.dto.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * List 응답 표준 형식 (COMMON RULES 4)
 * { items: [...], total: number, pageInfo: {page, size, hasNext} }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> items;
    private long total;
    private PageInfo pageInfo;

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        boolean hasNext = (long) (page + 1) * size < total;
        return PageResponse.<T>builder()
                .items(items)
                .total(total)
                .pageInfo(PageInfo.builder()
                        .page(page)
                        .size(size)
                        .hasNext(hasNext)
                        .build())
                .build();
    }
}
