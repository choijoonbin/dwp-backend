package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.UserHrCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UserHrCalendarRepository extends JpaRepository<UserHrCalendar, Long> {

    Optional<UserHrCalendar> findByTenantIdAndUserIdAndEventDate(Long tenantId, Long userId, LocalDate eventDate);
}
