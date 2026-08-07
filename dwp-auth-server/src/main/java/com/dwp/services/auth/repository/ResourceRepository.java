package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, Long> {
}
