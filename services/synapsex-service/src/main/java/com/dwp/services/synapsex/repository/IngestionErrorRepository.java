package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.IngestionError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionErrorRepository extends JpaRepository<IngestionError, Long> {

    long countByRawEventId(Long rawEventId);

    java.util.List<IngestionError> findByRawEventId(Long rawEventId);

    @Query("SELECT COUNT(e) FROM IngestionError e WHERE e.rawEventId = :rawEventId")
    long countByRawEventIdJpql(@Param("rawEventId") Long rawEventId);

    long countByTenantId(Long tenantId);
}
