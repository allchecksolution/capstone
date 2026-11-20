package com.allcheck.driver.infrastructure.repository.registration

import com.allcheck.driver.domain.entity.registration.DriverHistory
import org.springframework.data.jpa.repository.JpaRepository

interface DriverHistoryRepository : JpaRepository<DriverHistory, String> {
    fun findAllByDriverIdAndEventTypeOrderByOccurredAtAsc(driverId: String, eventType: String): List<DriverHistory>
}
