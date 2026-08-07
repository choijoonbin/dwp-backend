package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sys_auth_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession extends BaseEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "token_id", nullable = false, unique = true, length = 64)
    private String tokenId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
}
