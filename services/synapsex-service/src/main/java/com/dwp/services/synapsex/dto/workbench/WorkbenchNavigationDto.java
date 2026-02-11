package com.dwp.services.synapsex.dto.workbench;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /synapse/workbench/navigation 응답 — 워크벤치 진입 시 관련 설정 메뉴 목록(deepLink 포함).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchNavigationDto {

    private List<WorkbenchSettingMenuDto> relatedSettingsMenus;
}
