package com.allcheck.driver.infrastructure.repository.notification

import com.allcheck.driver.domain.entity.notification.DriverNotificationEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DriverNotificationEventRepository : JpaRepository<DriverNotificationEvent, String> {
    @Query(value = "select * from driver_notification_events where status = 'PENDING' order by created_at, event_id limit 1 for update skip locked", nativeQuery = true)
    fun nextPending(): DriverNotificationEvent?
}
