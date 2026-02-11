package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.SysNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 알림 센터: sys_notifications 조회/저장.
 */
public interface SysNotificationRepository extends JpaRepository<SysNotification, Long> {

    Page<SysNotification> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<SysNotification> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId, Pageable pageable);

    Page<SysNotification> findByTenantIdAndUserIdIsNullOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
}
