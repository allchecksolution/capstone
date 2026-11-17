package com.allcheck.driver.infrastructure.repository

import com.allcheck.driver.domain.entity.DriverPushDelivery
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface DriverPushDeliveryRepository : JpaRepository<DriverPushDelivery, String> {
    @Query(value = "select * from driver_push_deliveries where status = 'PENDING' or (status = 'PROCESSING' and lease_expires_at <= :now) order by created_at, delivery_id limit 1 for update skip locked", nativeQuery = true)
    fun nextPending(@Param("now") now: Instant): DriverPushDelivery?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from DriverPushDelivery delivery where delivery.id = :id")
    fun findForUpdate(@Param("id") id: String): DriverPushDelivery?
}
