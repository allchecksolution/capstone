package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionTranscript
import org.springframework.data.jpa.repository.JpaRepository

/** DB 보관이 끝난 정식 원문만 조회한다. 접근 권한은 애플리케이션에서 접수 소유권으로 검증한다. */
interface ReceptionTranscriptRepository : JpaRepository<ReceptionTranscript, Long> {
    fun findByTranscriptFileIdAndRecordingFileIdAndReceptionId(
        transcriptFileId: Long,
        recordingFileId: Long,
        receptionId: Long,
    ): ReceptionTranscript?

    fun findByProducedByAnalysisRequestId(producedByAnalysisRequestId: Long): ReceptionTranscript?
}
