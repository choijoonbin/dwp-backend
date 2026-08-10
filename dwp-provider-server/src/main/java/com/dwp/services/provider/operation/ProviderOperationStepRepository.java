package com.dwp.services.provider.operation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderOperationStepRepository extends JpaRepository<ProviderOperationStep, Long> {

    List<ProviderOperationStep> findByOperationIdOrderByStepOrderAsc(UUID operationId);
}
