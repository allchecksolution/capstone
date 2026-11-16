package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionAnalysisEvent
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionAnalysisEventRepository : JpaRepository<ReceptionAnalysisEvent, Long> {
    fun findByAnalysisRequestId(analysisRequestId: Long): ReceptionAnalysisEvent?
}
