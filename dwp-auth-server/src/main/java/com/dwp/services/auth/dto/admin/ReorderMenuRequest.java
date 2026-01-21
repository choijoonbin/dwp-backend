package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PR-05D: 메뉴 정렬/이동 요청 DTO (DragDrop 대비)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReorderMenuRequest {
    
    @NotNull(message = "메뉴 목록은 필수입니다")
    private List<MenuOrderItem> items;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenuOrderItem {
        @NotNull(message = "menuId는 필수입니다")
        private Long menuId;
        
        private Long parentId; // nullable (루트로 이동 시 null)
        private String parentMenuKey; // nullable (parentId 대신 사용 가능)
        
        @NotNull(message = "sortOrder는 필수입니다")
        private Integer sortOrder;
    }
}
