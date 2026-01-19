package com.dwp.services.auth.dto;

import lombok.*;

/**
 * 권한 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionDTO {
    
    private String resourceType;      // MENU, UI_COMPONENT, PAGE_SECTION, API
    private String resourceKey;       // menu.mail.inbox, btn.mail.send
    private String resourceName;      // Inbox, Send Button
    private String permissionCode;    // VIEW, USE, EDIT, APPROVE, EXECUTE
    private String permissionName;    // 조회, 사용, 편집
    private String effect;            // ALLOW, DENY
}
