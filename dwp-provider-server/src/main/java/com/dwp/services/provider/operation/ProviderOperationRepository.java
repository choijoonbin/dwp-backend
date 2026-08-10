package com.dwp.services.provider.operation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderOperationRepository extends JpaRepository<ProviderOperation, UUID> {

    Optional<ProviderOperation> findByIdempotencyKey(String idempotencyKey);

    Page<ProviderOperation> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
