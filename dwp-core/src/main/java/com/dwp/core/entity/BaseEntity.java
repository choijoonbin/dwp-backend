package com.dwp.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공통 기본 엔티티 (시스템 컬럼) - dwp-core 공통
 *
 * 모든 JPA 엔티티의 감사(audit) 공통 필드:
 * - created_at, created_by, updated_at, updated_by
 *
 * 사용: JPA를 쓰는 서비스(dwp-auth-server, dwp-main-service 등)에서
 *       엔티티가 <code>extends BaseEntity</code> 하고
 *       해당 서비스에 <code>@EnableJpaAuditing(auditorAwareRef = "auditorProvider")</code> 및
 *       <code>AuditorAware&lt;Long&gt; auditorProvider()</code> Bean 정의.
 *
 * 정책: docs/essentials/SYSTEM_COLUMNS_POLICY.md
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by")
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;
}
