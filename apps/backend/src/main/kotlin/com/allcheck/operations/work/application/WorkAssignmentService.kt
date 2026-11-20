package com.allcheck.operations.work.application

import com.allcheck.driver.DriverOperations
import com.allcheck.driver.DriverWorkload
import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.work.WorkOperations
import com.allcheck.operations.work.WorkReference
import com.allcheck.operations.work.domain.entity.WorkOrder
import com.allcheck.operations.work.domain.entity.WorkStatus
import com.allcheck.operations.work.exception.WorkErrorCode
import com.allcheck.operations.work.infrastructure.repository.WorkOrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock

@Service
@Transactional(readOnly = true)
class WorkAssignmentService(
    private val works: WorkOrderRepository,
    private val drivers: DriverOperations,
    private val clock: Clock = Clock.systemUTC(),
) : WorkOperations,
    DriverWorkload {
    override fun hasUnfinishedWork(driverId: Long): Boolean = works.existsByDriverIdAndStatusNotIn(driverId, TERMINAL)

    @Transactional
    override fun recordAssignment(requestId: Long, driverId: Long): WorkReference {
        require(requestId > 0)
        drivers.lockActiveDriver(driverId)
        val requestKey = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest("work-assignment:$requestId".toByteArray())).long
        if (!works.tryLockRequest(requestKey)) throw BusinessException(WorkErrorCode.REQUEST_ALREADY_ASSIGNED)
        if (hasUnfinishedWork(driverId)) throw BusinessException(WorkErrorCode.DRIVER_OCCUPIED)
        if (works.existsByRequestIdAndStatusNotIn(requestId, TERMINAL)) throw BusinessException(WorkErrorCode.REQUEST_ALREADY_ASSIGNED)
        val work = works.saveAndFlush(WorkOrder(requestId, driverId, createdAt = clock.instant()))
        return WorkReference(checkNotNull(work.id), work.requestId, work.driverId)
    }

    companion object {
        private val TERMINAL = listOf(WorkStatus.WORK_DONE, WorkStatus.CANCELLED)
    }
}
