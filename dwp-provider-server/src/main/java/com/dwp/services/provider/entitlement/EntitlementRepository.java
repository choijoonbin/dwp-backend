package com.dwp.services.provider.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    List<Entitlement> findByLifecycleStateOrderByEntitlementKeyAsc(String state);
}
