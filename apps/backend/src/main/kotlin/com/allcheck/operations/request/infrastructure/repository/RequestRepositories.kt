package com.allcheck.operations.request.infrastructure.repository

import com.allcheck.operations.request.domain.entity.FollowUpDecision
import com.allcheck.operations.request.domain.entity.ServiceRequest
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ServiceRequestRepository : JpaRepository<ServiceRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.requestId = :id")
    fun lock(id: Long): ServiceRequest?
}
interface FollowUpDecisionRepository : JpaRepository<FollowUpDecision, Long> {
    fun findAllByRequestIdOrderByDecisionId(requestId: Long): List<FollowUpDecision>
}
