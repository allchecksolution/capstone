package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionAnalysisRepository : JpaRepository<ReceptionAnalysis, String> {
    fun findByAnalysisRequestIdAndReceptionId(analysisRequestId: String, receptionId: String): ReceptionAnalysis?

    fun findByRecordingFileIdAndIsInitialTrue(recordingFileId: String): ReceptionAnalysis?

    /** 소비자가 PENDING으로 조회하고 claim을 커밋한 뒤 실행한다. 목록 조회 자체는 실행권을 주지 않는다. */
    fun findAllByStatusAndIsInitialTrueOrderByCreatedAtAscAnalysisRequestIdAsc(
        status: ReceptionAnalysisStatus,
        pageable: Pageable,
    ): List<ReceptionAnalysis>
}
