package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 메뉴 엔트리 DTO — 워크벤치 deepLink 등 연동용.
 * menu_key, 표시명, 라우트 경로만 반환.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuEntryDto {

    private String menuKey;
    private String label;
    private String deepLink;
}
