package com.allcheck.driver.infrastructure.repository

import com.allcheck.driver.domain.entity.DriverIdempotencyRecord
import com.allcheck.driver.domain.entity.DriverNotificationEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DriverIdempotencyRepository : JpaRepository<DriverIdempotencyRecord, String> {
    fun findByActorIdAndOperationScopeAndKeyHash(actorId: String, operationScope: String, keyHash: String): DriverIdempotencyRecord?

    @Query(value = "select pg_try_advisory_xact_lock(:lockKey)", nativeQuery = true)
    fun tryLock(@Param("lockKey") lockKey: Long): Boolean
}

interface DriverNotificationEventRepository : JpaRepository<DriverNotificationEvent, String> {
    @Query(value = "select * from driver_notification_events where status = 'PENDING' order by created_at, event_id limit 1 for update skip locked", nativeQuery = true)
    fun nextPending(): DriverNotificationEvent?
}
