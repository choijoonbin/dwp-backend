package com.dwp.services.platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PlatformAuditEventRepository extends JpaRepository<PlatformAuditEvent, UUID> {

    Page<PlatformAuditEvent> findByTenantId(Long tenantId, Pageable pageable);

    @Query("""
            SELECT event
              FROM PlatformAuditEvent event
             WHERE event.tenantId = :tenantId
               AND (
                    (event.targetType = 'REFERENCE_SET' AND event.targetId = :setKey)
                    OR
                    (event.targetType = 'REFERENCE_ITEM'
                        AND event.targetId LIKE CONCAT(:setKey, '/%'))
               )
            """)
    Page<PlatformAuditEvent> findReferenceSetActivity(
            @Param("tenantId") Long tenantId,
            @Param("setKey") String setKey,
            Pageable pageable);
}
