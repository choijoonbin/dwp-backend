package com.dwp.services.synapsex.dto.workbench;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 워크벤치 관련 설정 메뉴 엔트리 (규정 수정, 정책 변경 등 deepLink용).
 * auth /auth/menus/entries 응답과 동일 필드로 역직렬화.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchSettingMenuDto {

    private String menuKey;
    private String label;
    private String deepLink;
}
