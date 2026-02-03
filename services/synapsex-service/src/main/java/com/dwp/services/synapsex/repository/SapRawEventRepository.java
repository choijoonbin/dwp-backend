package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.SapRawEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SapRawEventRepository extends JpaRepository<SapRawEvent, Long> {
}
