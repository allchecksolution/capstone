package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionCommandReceipt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReceptionCommandReceiptRepository : JpaRepository<ReceptionCommandReceipt, Long> {
    @Query(value = "SELECT pg_try_advisory_xact_lock(:lockKey)", nativeQuery = true)
    fun tryTransactionLock(@Param("lockKey") lockKey: Long): Boolean

    fun findByActorAdminIdAndOperationAndScopeReceptionIdAndIdempotencyKey(actorAdminId: Long, operation: String, scopeReceptionId: Long, idempotencyKey: String): ReceptionCommandReceipt?
}
