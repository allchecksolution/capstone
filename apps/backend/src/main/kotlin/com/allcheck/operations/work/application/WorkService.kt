package com.allcheck.operations.work.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.CancellationFact
import com.allcheck.operations.CancellationReason
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.work.MonthlyFacts
import com.allcheck.operations.work.WorkOperations
import com.allcheck.operations.work.WorkSnapshot
import com.allcheck.operations.work.domain.entity.WorkCancellation
import com.allcheck.operations.work.domain.entity.WorkOrder
import com.allcheck.operations.work.infrastructure.repository.WorkCancellationRepository
import com.allcheck.operations.work.infrastructure.repository.WorkOrderRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional(readOnly = true)
class WorkService(private val works: WorkOrderRepository, private val cancellations: WorkCancellationRepository, private val em: EntityManager) : WorkOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(requestId: Long, roundId: Long, offerId: Long, driverId: Long, previousWorkId: Long?, now: Instant) = snapshot(works.saveAndFlush(WorkOrder(requestId, roundId, offerId, driverId, previousWorkId, now)), null)
    override fun get(workId: Long) = snapshot(entity(workId), cancellations.findByWorkId(workId))
    override fun forRequest(requestId: Long) = snapshots(works.findAllByRequestIdOrderByWorkId(requestId))
    override fun occupied(driverId: Long) = works.occupied(driverId)
    override fun occupiedDrivers(driverIds: Set<Long>) = if (driverIds.isEmpty()) emptySet() else works.occupiedDrivers(driverIds)
    override fun excluded(requestId: Long) = cancellations.excluded(requestId)
    override fun cancelled(page: Int, size: Int): List<WorkSnapshot> = snapshots(em.createQuery("select w from WorkOrder w where w.status=com.allcheck.operations.WorkStatus.CANCELLED order by w.cancelledAt desc,w.workId desc", WorkOrder::class.java).setFirstResult((page - 1) * size).setMaxResults(size).resultList)
    override fun cancellation(cancellationId: Long): WorkSnapshot {
        val cancellation = cancellations.findById(cancellationId).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
        return snapshot(entity(cancellation.workId), cancellation)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun transition(workId: Long, driverId: Long, version: Int, target: WorkStatus, reason: String?, planned: Instant?, now: Instant): WorkSnapshot {
        owned(workId, driverId).transition(version, target, reason, planned, now)
        return get(workId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun planRevisit(workId: Long, driverId: Long, version: Int, planned: Instant?, now: Instant): WorkSnapshot {
        owned(workId, driverId).planRevisit(version, planned, now)
        return get(workId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun cancel(workId: Long, driverId: Long, version: Int, code: CancellationReason, detail: String, now: Instant): WorkSnapshot {
        val work = owned(workId, driverId)
        val cancellation = WorkCancellation(workId, work.requestId, driverId, code, detail, work.status, now)
        work.cancel(version, now)
        cancellations.saveAndFlush(cancellation)
        return get(workId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun complete(workId: Long, driverId: Long, version: Int, now: Instant): WorkSnapshot {
        owned(workId, driverId).complete(version, now)
        return get(workId)
    }
    override fun driverWorks(driverId: Long, history: Boolean, from: Instant?, to: Instant?, page: Int, size: Int): List<WorkSnapshot> {
        val query = if (history) {
            val month = if (from != null && to != null) " and coalesce(w.completedAt,w.cancelledAt)>=:from and coalesce(w.completedAt,w.cancelledAt)<:to" else ""
            "select w from WorkOrder w where w.driverId=:driver and w.status in (com.allcheck.operations.WorkStatus.WORK_DONE,com.allcheck.operations.WorkStatus.CANCELLED)$month order by coalesce(w.completedAt,w.cancelledAt) desc,w.workId desc"
        } else {
            "select w from WorkOrder w where w.driverId=:driver and w.status not in (com.allcheck.operations.WorkStatus.WORK_DONE,com.allcheck.operations.WorkStatus.CANCELLED) order by w.assignedAt desc,w.workId desc"
        }
        val typed = em.createQuery(query, WorkOrder::class.java).setParameter("driver", driverId)
        if (history && from != null && to != null) {
            typed.setParameter("from", from)
            typed.setParameter("to", to)
        }
        return snapshots(typed.setFirstResult((page - 1) * size).setMaxResults(size).resultList)
    }
    override fun statistics(driverId: Long, from: Instant, to: Instant): MonthlyFacts {
        fun count(column: String) = (em.createNativeQuery("select count(*) from work_orders where driver_id=:driver and $column>=:from and $column<:to", Long::class.java).setParameter("driver", driverId).setParameter("from", from).setParameter("to", to).singleResult as Number).toLong()
        val rows = em.createNativeQuery("select r.intake->>'workType',count(*) from work_orders w join service_requests r on r.request_id=w.request_id where w.driver_id=:driver and w.completed_at>=:from and w.completed_at<:to group by r.intake->>'workType'", Array<Any>::class.java).setParameter("driver", driverId).setParameter("from", from).setParameter("to", to).resultList
        val types = rows.associate { (it as Array<*>)[0].toString() to (it[1] as Number).toLong() }
        return MonthlyFacts(count("assigned_at"), count("completed_at"), count("cancelled_at"), types)
    }
    private fun snapshots(list: List<WorkOrder>): List<WorkSnapshot> {
        val cancellationsByWork = if (list.isEmpty()) emptyMap() else cancellations.findAllByWorkIdIn(list.map { checkNotNull(it.workId) }).associateBy { it.workId }
        return list.map { snapshot(it, cancellationsByWork[it.workId]) }
    }
    private fun entity(id: Long) = works.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun owned(id: Long, driverId: Long) = entity(id).also { if (it.driverId != driverId) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun snapshot(work: WorkOrder, cancellation: WorkCancellation?) = WorkSnapshot(checkNotNull(work.workId), work.requestId, work.roundId, work.offerId, work.driverId, work.previousWorkId, work.status, work.version, work.assignedAt, work.completedAt, work.cancelledAt, work.events(), cancellation?.let { CancellationFact(checkNotNull(it.cancellationId), it.reasonCode, it.reasonDetail, it.previousStatus, it.cancelledAt) })
}
