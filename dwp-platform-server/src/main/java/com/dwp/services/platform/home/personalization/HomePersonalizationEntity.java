package com.dwp.services.platform.home.personalization;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * Absolute audit clock for the Phase 2 home stores. The legacy platform base
 * entity uses wall-clock {@code LocalDateTime}; mixing that with PostgreSQL UTC
 * SQL writers made timestamps ambiguous whenever the JVM and database zones
 * differed.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class HomePersonalizationEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void stampCreation() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void stampUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
