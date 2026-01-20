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
    
    private String resourceType;      // MENU, UI_COMPONENT, PAGE_SECTION, API (기존 필드, 하위 호환)
    private String resourceKey;       // menu.mail.inbox, btn.mail.send
    private String resourceName;      // Inbox, Send Button
    private String permissionCode;    // VIEW, USE, EDIT, APPROVE, EXECUTE
    private String permissionName;    // 조회, 사용, 편집
    private String effect;            // ALLOW, DENY
    
    // 확장 필드 (BE P1-5)
    private String resourceCategory;  // MENU, UI_COMPONENT
    private String resourceKind;      // MENU_GROUP, PAGE, BUTTON, TAB, SELECT, FILTER, SEARCH, TABLE_ACTION, DOWNLOAD, UPLOAD, MODAL, API_ACTION
    private String eventKey;          // 예: menu.admin.users:view
    private Boolean trackingEnabled;  // 이벤트 추적 활성화 여부
    private String eventActions;      // JSON 문자열: ["VIEW","CLICK","SUBMIT"]
    private Object meta;               // 기존 metadata_json (호환성)
}
