package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionAnalysisEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionAnalysisEventRepository : JpaRepository<ReceptionAnalysisEvent, Long> {
    fun findAllByStatusOrderByCreatedAtAscEventIdAsc(status: String, page: Pageable): List<ReceptionAnalysisEvent>
    fun findByAnalysisRequestId(analysisRequestId: Long): ReceptionAnalysisEvent?
}
