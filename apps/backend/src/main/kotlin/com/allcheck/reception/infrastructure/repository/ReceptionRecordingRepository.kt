package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.ReceptionRecording
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionRecordingRepository : JpaRepository<ReceptionRecording, String> {
    fun findByReceptionId(receptionId: String): ReceptionRecording?

    fun findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(
        recordingFileId: String,
        receptionId: String,
        uploadedByAdminId: String,
    ): ReceptionRecording?
}
