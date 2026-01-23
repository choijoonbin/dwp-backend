package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 감사 로그 엔티티 (com_audit_logs)
 */
@Entity
@Table(name = "com_audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long auditLogId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "actor_user_id")
    private Long actorUserId;
    
    @Column(name = "action", nullable = false, length = 100, columnDefinition = "VARCHAR(100)")
    private String action; // USER_CREATE, ROLE_UPDATE, etc.
    
    @Column(name = "resource_type", length = 100, columnDefinition = "VARCHAR(100)")
    private String resourceType; // USER, ROLE, RESOURCE, CODE, etc.
    
    @Column(name = "resource_id")
    private Long resourceId;
    
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson; // JSON with before/after, ip, userAgent, etc.
}
