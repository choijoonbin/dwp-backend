package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetRuntimeEnableApprovalRepository extends JpaRepository<WidgetRuntimeEnableApproval, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WidgetRuntimeEnableApproval a where a.approvalId = :id")
    Optional<WidgetRuntimeEnableApproval> lockById(@Param("id") UUID id);
}
