package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.ScimConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScimConnectorRepository extends JpaRepository<ScimConnector, UUID> {

    Optional<ScimConnector> findByTokenPrefixAndLifecycleState(String tokenPrefix, String state);

    Optional<ScimConnector> findByScimConnectorIdAndTenantId(UUID connectorId, Long tenantId);

    List<ScimConnector> findByTenantIdOrderByConnectorKeyAsc(Long tenantId);
}
