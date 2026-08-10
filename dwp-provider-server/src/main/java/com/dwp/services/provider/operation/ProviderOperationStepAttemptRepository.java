package com.dwp.services.provider.operation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderOperationStepAttemptRepository
        extends JpaRepository<ProviderOperationStepAttempt, UUID> {

    List<ProviderOperationStepAttempt> findByOperationStepIdOrderByAttemptNumberAsc(Long operationStepId);
}
