package com.dwp.services.auth.dto.admin;

import com.dwp.services.auth.entity.Tenant;
import lombok.Builder;
import lombok.Data;

/**
 * Tenant Selector용 요약 DTO.
 */
@Data
@Builder
public class TenantSummaryDto {
    private Long id;
    private String name;
    private String domain;  // 선택 (서브도메인 등, 없으면 null)

    public static TenantSummaryDto from(Tenant t) {
        if (t == null) return null;
        return TenantSummaryDto.builder()
                .id(t.getTenantId())
                .name(t.getName())
                .domain(t.getCode())  // code를 domain 대체용으로 사용 (없으면 null)
                .build();
    }
}
