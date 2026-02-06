package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 코드 수정 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCodeRequest {
    
    private String codeName; // name
    /** 한국어 라벨 (i18n, null이면 기존 값 유지) */
    private String nameKo;
    /** 영어 라벨 (i18n, null이면 기존 값 유지) */
    private String nameEn;
    private String description;
    private Integer sortOrder;
    private Boolean enabled; // isActive
    private String ext1;
    private String ext2;
    private String ext3;
}
