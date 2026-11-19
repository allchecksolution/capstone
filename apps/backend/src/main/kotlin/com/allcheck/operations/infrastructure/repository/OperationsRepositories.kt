package com.allcheck.operations.infrastructure.repository

import com.allcheck.operations.domain.entity.OperationsReceipt
import com.allcheck.operations.domain.entity.OperationsTask
import com.allcheck.operations.domain.entity.TaskKind
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface OperationsReceiptRepository : JpaRepository<OperationsReceipt, Long> {
    fun findByActorRoleAndActorIdAndOperationAndScopeIdAndIdempotencyKey(role: String, actorId: Long, operation: String, scopeId: Long, key: String): OperationsReceipt?

    @Query(value = "select pg_try_advisory_xact_lock(:id)", nativeQuery = true)
    fun tryLock(id: Long): Boolean
}
interface OperationsTaskRepository : JpaRepository<OperationsTask, Long> {
    @Query(value = "select * from operations_tasks where state='PENDING' and due_at<=:now order by due_at,task_id for update skip locked limit 1", nativeQuery = true)
    fun claimable(now: Instant): OperationsTask?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OperationsTask t where t.taskId=:id")
    fun lock(id: Long): OperationsTask?
    fun findAllByKindAndSubjectId(kind: TaskKind, subjectId: Long): List<OperationsTask>

    @Query(value = "select task_id from operations_tasks where state='RUNNING' and lease_until<=:now order by lease_until,task_id limit :limit", nativeQuery = true)
    fun expired(now: Instant, limit: Int): List<Long>
}
