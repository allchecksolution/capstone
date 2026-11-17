package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface ReceptionAnalysisRepository : JpaRepository<ReceptionAnalysis, Long> {
    fun findAllByStatusAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAscAnalysisRequestIdAsc(status: ReceptionAnalysisStatus, deadline: Instant, pageable: Pageable): List<ReceptionAnalysis>
    fun findByAnalysisRequestIdAndReceptionId(analysisRequestId: Long, receptionId: Long): ReceptionAnalysis?

    fun findByRecordingFileIdAndIsInitialTrue(recordingFileId: Long): ReceptionAnalysis?

    /** 소비자가 PENDING으로 조회하고 claim을 커밋한 뒤 실행한다. 목록 조회 자체는 실행권을 주지 않는다. */
    fun findAllByStatusAndIsInitialTrueOrderByCreatedAtAscAnalysisRequestIdAsc(
        status: ReceptionAnalysisStatus,
        pageable: Pageable,
    ): List<ReceptionAnalysis>
}
