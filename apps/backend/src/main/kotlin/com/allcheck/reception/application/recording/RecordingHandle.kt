package com.allcheck.reception.application.recording

import com.allcheck.reception.domain.entity.recording.ReceptionRecording
import com.allcheck.reception.domain.vo.recording.RecordingUploadStatus
import java.time.Instant

/** 내부 저장소 연동용 문맥. 객체 위치와 체크섬은 관리자 응답 DTO에 직접 노출하지 않는다. */
class RecordingHandle private constructor(
    val recordingId: Long,
    val receptionId: Long,
    val uploaderAdminId: Long,
    val receptionVersion: Int,
    val bucket: String,
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    checksum: ByteArray,
    val status: RecordingUploadStatus,
    val receivedAt: Instant?,
) {
    private val digest = checksum.copyOf()
    val checksum: ByteArray get() = digest.copyOf()

    companion object {
        fun from(recording: ReceptionRecording, version: Int) = RecordingHandle(
            checkNotNull(recording.recordingFileId), recording.receptionId, recording.uploadedByAdminId, version,
            recording.bucket, recording.objectKey, recording.contentType, recording.sizeBytes,
            recording.checksumSha256, recording.status, recording.recordingReceivedAt,
        )
    }
}
