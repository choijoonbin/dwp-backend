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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
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

    @Column(name = "session_family_id", nullable = false)
    private UUID sessionFamilyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "session_started_at", nullable = false)
    private Instant sessionStartedAt;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Builder.Default
    @Column(name = "authentication_method", nullable = false, length = 32)
    private String authenticationMethod = "LEGACY";

    @Column(name = "authenticated_at")
    private Instant authenticatedAt;

    @Column(name = "assurance_acr", length = 200)
    private String assuranceAcr;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assurance_amr", nullable = false, columnDefinition = "jsonb")
    private List<String> assuranceAmr = List.of();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "idle_expires_at", nullable = false)
    private Instant idleExpiresAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "superseded_expires_at")
    private Instant supersededExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    public boolean isActiveAt(Instant now) {
        if (revokedAt != null
                || expiresAt == null
                || !expiresAt.isAfter(now)
                || idleExpiresAt == null
                || !idleExpiresAt.isAfter(now)) {
            return false;
        }
        return supersededAt == null
                || (supersededExpiresAt != null && supersededExpiresAt.isAfter(now));
    }
}
