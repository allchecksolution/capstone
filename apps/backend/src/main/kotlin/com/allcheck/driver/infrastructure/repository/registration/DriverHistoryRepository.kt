package com.allcheck.driver.infrastructure.repository.registration

import com.allcheck.driver.domain.entity.registration.DriverHistory
import org.springframework.data.jpa.repository.JpaRepository

interface DriverHistoryRepository : JpaRepository<DriverHistory, Long> {
    fun findAllByDriverIdAndEventTypeOrderByOccurredAtAsc(driverId: Long, eventType: String): List<DriverHistory>
}
