package com.allcheck.operations.work.infrastructure.repository

import com.allcheck.operations.work.domain.entity.WorkCancellation
import com.allcheck.operations.work.domain.entity.WorkOrder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface WorkOrderRepository : JpaRepository<WorkOrder, Long> {
    fun findAllByRequestIdOrderByWorkId(requestId: Long): List<WorkOrder>

    @Query(value = "select exists(select 1 from work_orders where driver_id=:driverId and status not in ('WORK_DONE','CANCELLED'))", nativeQuery = true)
    fun occupied(driverId: Long): Boolean

    @Query("select distinct w.driverId from WorkOrder w where w.driverId in :ids and w.status not in (com.allcheck.operations.WorkStatus.WORK_DONE,com.allcheck.operations.WorkStatus.CANCELLED)")
    fun occupiedDrivers(ids: Set<Long>): Set<Long>
}
interface WorkCancellationRepository : JpaRepository<WorkCancellation, Long> {
    fun findByWorkId(workId: Long): WorkCancellation?
    fun findAllByWorkIdIn(workIds: Collection<Long>): List<WorkCancellation>

    @Query("select distinct c.driverId from WorkCancellation c where c.requestId=:requestId")
    fun excluded(requestId: Long): Set<Long>
}
