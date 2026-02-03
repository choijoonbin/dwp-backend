package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ReconResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconResultRepository extends JpaRepository<ReconResult, Long> {

    List<ReconResult> findByRunIdOrderByResultIdAsc(Long runId);
}
