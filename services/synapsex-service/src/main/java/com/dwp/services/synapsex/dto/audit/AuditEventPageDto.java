package com.dwp.services.synapsex.dto.audit;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AuditEventPageDto {
    private List<AuditEventDto> items;
    private long total;
    private PageInfo pageInfo;

    @Data
    @Builder
    public static class PageInfo {
        private int page;
        private int size;
        private int totalPages;
        private long total;
    }
}
