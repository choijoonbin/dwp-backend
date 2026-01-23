package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 리소스 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceSummary {
    
    private Long comResourceId; // resource_id
    private String resourceKey;
    private String resourceName;
    private String type;
    private Long parentResourceId;
    private String parentResourceName;
    private String path; // metadata에서 추출 가능
    private Integer sortOrder; // metadata에서 추출 가능
    private Boolean enabled;
    /** P1-8: enabled → ACTIVE/INACTIVE */
    private String status;
    /** P1-8: metadata 또는 엔티티에서 추출 */
    private String icon;
    /** P1-8: metadata 또는 엔티티에서 추출 */
    private String description;
    private LocalDateTime createdAt;
}
