package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.BuiltinRoleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuiltinRoleDefinitionRepository
        extends JpaRepository<BuiltinRoleDefinition, String> {
}
