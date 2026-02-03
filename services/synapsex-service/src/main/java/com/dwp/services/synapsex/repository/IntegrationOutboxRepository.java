package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.IntegrationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationOutboxRepository extends JpaRepository<IntegrationOutbox, Long> {

    List<IntegrationOutbox> findByTenantIdAndStatus(Long tenantId, String status);
}
