package com.allcheck.operations.work.infrastructure.repository

import com.allcheck.operations.work.domain.entity.WorkOrder
import com.allcheck.operations.work.domain.entity.WorkStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WorkOrderRepository : JpaRepository<WorkOrder, Long> {
    fun existsByDriverIdAndStatusNotIn(driverId: Long, terminalStates: Collection<WorkStatus>): Boolean
    fun existsByRequestIdAndStatusNotIn(requestId: Long, terminalStates: Collection<WorkStatus>): Boolean

    @Query(value = "select pg_try_advisory_xact_lock(:lockKey)", nativeQuery = true)
    fun tryLockRequest(@Param("lockKey") lockKey: Long): Boolean
}
