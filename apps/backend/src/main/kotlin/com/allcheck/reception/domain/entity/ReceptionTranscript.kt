package com.allcheck.reception.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.Immutable
import java.security.MessageDigest
import java.time.Instant

/** 성공적으로 보관한 STT 원문. 분석 응답의 문자열을 변경하지 않고 유지한다. */
@Entity
@Immutable
@Table(
    name = "reception_transcripts",
    indexes = [Index(name = "ix_reception_transcripts_recording", columnList = "recording_file_id, created_at")],
    uniqueConstraints = [
        UniqueConstraint(name = "uq_reception_transcripts_producer", columnNames = ["produced_by_analysis_request_id"]),
        UniqueConstraint(
            name = "uq_reception_transcripts_source",
            columnNames = ["transcript_file_id", "recording_file_id", "reception_id"],
        ),
    ],
)
class ReceptionTranscript(
    @field:Id
    @field:Column(name = "transcript_file_id", nullable = false, length = 64, updatable = false)
    val transcriptFileId: String,
    @field:Column(name = "reception_id", nullable = false, length = 64, updatable = false)
    val receptionId: String,
    @field:Column(name = "recording_file_id", nullable = false, length = 64, updatable = false)
    val recordingFileId: String,
    @field:Column(name = "produced_by_analysis_request_id", nullable = false, length = 64, updatable = false)
    val producedByAnalysisRequestId: String,
    @field:Column(name = "transcript_text", nullable = false, columnDefinition = "text", updatable = false)
    val transcript: String,
    // 기존 열 이름은 유지한다. 새 원문에서는 S3 업로드가 아닌 DB 보관 시각이다.
    @field:Column(name = "uploaded_at", nullable = false, updatable = false)
    val storedAt: Instant,
) {
    init {
        require(transcriptFileId.isNotBlank() && transcriptFileId.length <= 64)
        require(receptionId.isNotBlank() && receptionId.length <= 64)
        require(recordingFileId.isNotBlank() && recordingFileId.length <= 64)
        require(producedByAnalysisRequestId.isNotBlank() && producedByAnalysisRequestId.length <= 64)
        require(transcript.isNotBlank()) { "A canonical transcript must contain transcription content." }
        require(transcript.codePointCount(0, transcript.length) <= MAX_CODE_POINTS) { "Transcript exceeds the AI contract limit." }
    }

    // V1 파일 메타데이터와의 호환 정보만 계산한다. S3 객체나 복사본은 만들지 않는다.
    @field:Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private val contentType: String = "text/plain; charset=utf-8"

    @field:Column(name = "size_bytes", nullable = false, updatable = false)
    private val sizeBytes: Long = transcript.toByteArray(Charsets.UTF_8).size.toLong()

    @field:Column(name = "checksum_sha256", nullable = false, columnDefinition = "bytea", updatable = false)
    private val checksumSha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))

    @field:Column(name = "status", nullable = false, length = 20, updatable = false)
    private val status: String = "UPLOADED"

    @field:Column(name = "created_at", nullable = false, updatable = false)
    private val createdAt: Instant = storedAt

    @field:Column(name = "updated_at", nullable = false, updatable = false)
    private val updatedAt: Instant = storedAt

    // 새 ID를 가진 원문은 persist로 삽입하고, ID 재사용이 merge로 원본을 바꾸지 않도록 한다.
    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    companion object {
        const val MAX_CODE_POINTS = 100_000
    }
}
