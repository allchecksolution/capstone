package com.allcheck.reception.fixture

import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.entity.analysis.ReceptionAnalysis
import com.allcheck.reception.domain.entity.analysis.ReceptionTranscript
import com.allcheck.reception.domain.entity.recording.ReceptionRecording
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.OffsetDateTime

/** DB 없는 도메인 테스트에서 이미 ID가 할당된 상태만 준비한다. 저장소 테스트는 실제 IDENTITY 생성을 사용한다. */
fun testReception(
    receptionId: Long?,
    ownerAdminId: Long?,
    receivedAt: Instant,
    callerPhone: String? = null,
    callStartedAt: OffsetDateTime? = null,
) = Reception(checkNotNull(ownerAdminId), receivedAt, callerPhone, callStartedAt).also {
    ReflectionTestUtils.setField(it, "receptionId", checkNotNull(receptionId))
}

fun testReceptionRecording(
    recordingFileId: Long?,
    receptionId: Long?,
    uploadedByAdminId: Long?,
    originalFilename: String,
    bucket: String,
    objectKey: String,
    contentType: String,
    sizeBytes: Long,
    checksum: ByteArray,
    createdAt: Instant,
) = ReceptionRecording(checkNotNull(receptionId), checkNotNull(uploadedByAdminId), originalFilename, bucket, objectKey, contentType, sizeBytes, checksum, createdAt).also {
    ReflectionTestUtils.setField(it, "recordingFileId", checkNotNull(recordingFileId))
}

fun testReceptionAnalysis(
    analysisRequestId: Long?,
    receptionId: Long?,
    recordingFileId: Long?,
    receptionVersion: Int,
    requestedByAdminId: Long?,
    referenceTime: Instant,
    createdAt: Instant,
) = ReceptionAnalysis(checkNotNull(receptionId), checkNotNull(recordingFileId), receptionVersion, checkNotNull(requestedByAdminId), referenceTime, createdAt).also {
    ReflectionTestUtils.setField(it, "analysisRequestId", analysisRequestId)
}

fun testReceptionTranscript(
    transcriptFileId: Long?,
    receptionId: Long?,
    recordingFileId: Long?,
    producedByAnalysisRequestId: Long?,
    transcript: String,
    storedAt: Instant,
) = ReceptionTranscript(checkNotNull(receptionId), checkNotNull(recordingFileId), checkNotNull(producedByAnalysisRequestId), transcript, storedAt).also {
    ReflectionTestUtils.setField(it, "transcriptFileId", transcriptFileId)
}
