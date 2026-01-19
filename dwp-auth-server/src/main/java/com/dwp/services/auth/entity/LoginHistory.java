package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 로그인 이력 엔티티 (sys_login_histories)
 */
@Entity
@Table(name = "sys_login_histories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginHistory extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "login_history_id")
    private Long loginHistoryId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "user_id")
    private Long userId;  // nullable (실패 시)
    
    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType;
    
    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;
    
    @Column(name = "principal", nullable = false, length = 255)
    private String principal;
    
    @Column(name = "success", nullable = false)
    private Boolean success;
    
    @Column(name = "failure_reason", length = 255)
    private String failureReason;
    
    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
    
    @Column(name = "trace_id", length = 100)
    private String traceId;
}
