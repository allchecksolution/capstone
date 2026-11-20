package com.allcheck.driver.infrastructure.repository.command

import com.allcheck.driver.domain.entity.command.DriverIdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DriverIdempotencyRepository : JpaRepository<DriverIdempotencyRecord, Long> {
    fun findByActorIdAndOperationScopeAndKeyHash(actorId: Long, operationScope: String, keyHash: String): DriverIdempotencyRecord?

    @Query(value = "select pg_try_advisory_xact_lock(:lockKey)", nativeQuery = true)
    fun tryLock(@Param("lockKey") lockKey: Long): Boolean
}
