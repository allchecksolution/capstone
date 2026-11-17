package com.allcheck.reception.infrastructure.repository.recording

import com.allcheck.reception.domain.entity.recording.ReceptionRecording
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionRecordingRepository : JpaRepository<ReceptionRecording, Long> {
    fun findByReceptionId(receptionId: Long): ReceptionRecording?

    fun findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(
        recordingFileId: Long,
        receptionId: Long,
        uploadedByAdminId: Long,
    ): ReceptionRecording?
}
