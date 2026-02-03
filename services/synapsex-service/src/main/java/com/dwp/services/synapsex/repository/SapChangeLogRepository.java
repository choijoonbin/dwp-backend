package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.SapChangeLog;
import com.dwp.services.synapsex.entity.SapChangeLogId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SapChangeLogRepository extends JpaRepository<SapChangeLog, SapChangeLogId> {

    List<SapChangeLog> findByTenantIdAndObjectidOrderByUdateDescUtimeDesc(
            Long tenantId, String objectid, Pageable pageable);

    long countByTenantIdAndObjectid(Long tenantId, String objectid);
}
