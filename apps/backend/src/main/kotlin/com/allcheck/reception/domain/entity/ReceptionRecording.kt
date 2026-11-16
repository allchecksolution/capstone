package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.RecordingUploadStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

/** 변경 불가 녹음 메타데이터와 최초 검증 완료 시각을 관리한다. 파일 본문과 서명 URL은 저장하지 않는다. */
@Entity
@Table(
    name = "reception_recordings",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_reception_recordings_reception", columnNames = ["reception_id"]),
        UniqueConstraint(name = "uq_reception_recordings_object", columnNames = ["bucket", "object_key"]),
        UniqueConstraint(
            name = "uq_reception_recordings_source",
            columnNames = ["recording_file_id", "reception_id", "recording_received_at"],
        ),
    ],
)
class ReceptionRecording(
    @field:Column(name = "reception_id", nullable = false, updatable = false)
    val receptionId: Long,
    @field:Column(name = "uploaded_by_admin_id", nullable = false, updatable = false)
    val uploadedByAdminId: Long,
    @field:Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    val originalFilename: String,
    @field:Column(name = "bucket", nullable = false, length = 63, updatable = false)
    val bucket: String,
    @field:Column(name = "object_key", nullable = false, columnDefinition = "text", updatable = false)
    val objectKey: String,
    @field:Column(name = "content_type", nullable = false, length = 100, updatable = false)
    val contentType: String,
    @field:Column(name = "size_bytes", nullable = false, updatable = false)
    val sizeBytes: Long,
    checksum: ByteArray,
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "recording_file_id", nullable = false, updatable = false)
    var recordingFileId: Long? = null
        protected set

    init {
        require(receptionId > 0)
        require(uploadedByAdminId > 0)
        require(bucket.isNotBlank() && bucket.length <= 63)
        require(objectKey.toByteArray(Charsets.UTF_8).size in 1..1024)
        if (originalFilename.isBlank() ||
            originalFilename.codePointCount(0, originalFilename.length) > 255 ||
            contentType.isBlank() ||
            contentType.codePointCount(0, contentType.length) > 100 ||
            sizeBytes <= 0 ||
            checksum.size != SHA256_BYTES
        ) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
    }

    @field:Column(name = "checksum_sha256", nullable = false, columnDefinition = "bytea", updatable = false)
    private val storedChecksumSha256: ByteArray = checksum.copyOf()

    val checksumSha256: ByteArray
        get() = storedChecksumSha256.copyOf()

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 20)
    var status: RecordingUploadStatus = RecordingUploadStatus.PENDING
        protected set

    @field:Column(name = "recording_received_at", nullable = true)
    var recordingReceivedAt: Instant? = null
        protected set

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    /** 재발급할 URL은 기존 메타데이터로 생성한다. 접수 소유권·현재 업무 권한은 애플리케이션에서 확인한다. */
    fun ensureUploadCanBeRetried() {
        if (status != RecordingUploadStatus.PENDING) {
            throw BusinessException(GlobalErrorCode.FILE_ALREADY_UPLOADED)
        }
    }

    /**
     * 애플리케이션이 실제 객체를 조회한 결과와 서버 수신 시각을 전달한다.
     * 최초 전환은 true, 이미 완료된 파일은 값과 시각을 바꾸지 않고 false를 반환한다.
     * 접수 버전·연결·분석 접수와의 원자적 저장 및 중복 응답 재현은 애플리케이션의 책임이다.
     */
    fun completeUpload(
        actualContentType: String,
        actualSizeBytes: Long,
        actualChecksumSha256: ByteArray,
        receivedAt: Instant,
    ): Boolean {
        if (status == RecordingUploadStatus.UPLOADED) {
            return false
        }
        if (actualContentType != contentType ||
            actualSizeBytes != sizeBytes ||
            !storedChecksumSha256.contentEquals(actualChecksumSha256)
        ) {
            throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
        require(!receivedAt.isBefore(createdAt)) { "Recording receive time must not precede reservation." }

        status = RecordingUploadStatus.UPLOADED
        recordingReceivedAt = receivedAt
        updatedAt = receivedAt
        return true
    }

    private companion object {
        const val SHA256_BYTES = 32
    }
}
