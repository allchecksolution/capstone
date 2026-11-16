package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.RecordingUploadStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class ReceptionRecordingTest {
    @Test
    fun `예약은 업로드 대기 상태로 시작하고 SHA256은 입력과 조회 양쪽에서 방어적으로 복사한다`() {
        // given
        val input = digest()
        val recording = recording(checksum = input)

        // when
        input[0] = 99
        val returned = recording.checksumSha256
        returned[1] = 99

        // then
        assertThat(recording.checksumSha256).containsExactly(*digest())
        assertThat(recording.status).isEqualTo(RecordingUploadStatus.PENDING)
        assertThat(recording.recordingReceivedAt).isNull()
        assertThat(recording.createdAt).isEqualTo(CREATED_AT)
        assertThat(recording.updatedAt).isEqualTo(CREATED_AT)
    }

    @Test
    fun `미완료 예약은 여러 번 재시도해도 파일 식별자와 메타데이터 및 시각이 같다`() {
        // given
        val recording = recording()
        val before = snapshot(recording)

        // when
        recording.ensureUploadCanBeRetried()
        recording.ensureUploadCanBeRetried()

        // then
        assertThat(snapshot(recording)).isEqualTo(before)
    }

    @Test
    fun `실제 MIME과 크기 및 체크섬이 일치해야 최초 완료 시각을 기록한다`() {
        // given
        val recording = recording()

        // when
        val changed = recording.completeUpload("audio/mp4", 1024, digest(), RECEIVED_AT)

        // then
        assertThat(changed).isTrue()
        assertThat(recording.status).isEqualTo(RecordingUploadStatus.UPLOADED)
        assertThat(recording.recordingReceivedAt).isEqualTo(RECEIVED_AT)
        assertThat(recording.updatedAt).isEqualTo(RECEIVED_AT)
        assertThat(recording.createdAt).isEqualTo(CREATED_AT)
        assertThat(recording.recordingFileId).isEqualTo("recording-1")
        assertThat(recording.objectKey).isEqualTo("recordings/recording-1.m4a")
        assertThat(recording.checksumSha256).containsExactly(*digest())
    }

    @ParameterizedTest
    @ValueSource(strings = ["mime", "size", "checksum", "missing-checksum"])
    fun `예약과 다른 실제 객체는 완료 처리하지 않는다`(mismatch: String) {
        // given
        val recording = recording()
        val before = snapshot(recording)
        val mime = if (mismatch == "mime") "audio/mpeg" else "audio/mp4"
        val size = if (mismatch == "size") 1025L else 1024L
        val checksum = when (mismatch) {
            "checksum" -> digest().also { it[0] = 99 }
            "missing-checksum" -> byteArrayOf()
            else -> digest()
        }

        // when
        val exception = assertThrows<BusinessException> {
            recording.completeUpload(mime, size, checksum, RECEIVED_AT)
        }

        // then
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        assertThat(snapshot(recording)).isEqualTo(before)
    }

    @Test
    fun `예약 이전의 서버 수신 시각은 저장하지 않는다`() {
        // given
        val recording = recording()
        val before = snapshot(recording)

        // when & then
        assertThrows<IllegalArgumentException> {
            recording.completeUpload("audio/mp4", 1024, digest(), CREATED_AT.minusNanos(1))
        }
        assertThat(snapshot(recording)).isEqualTo(before)
    }

    @Test
    fun `완료 통보가 반복되어도 최초 원본과 수신 시각은 바뀌지 않는다`() {
        // given
        val recording = recording()
        recording.completeUpload("audio/mp4", 1024, digest(), RECEIVED_AT)
        val before = snapshot(recording)

        // when
        val changed = recording.completeUpload("audio/mpeg", 9999, byteArrayOf(1), RECEIVED_AT.plusSeconds(60))

        // then
        assertThat(changed).isFalse()
        assertThat(snapshot(recording)).isEqualTo(before)
    }

    @Test
    fun `완료된 파일은 업로드 재시도를 거부한다`() {
        // given
        val recording = recording()
        recording.completeUpload("audio/mp4", 1024, digest(), RECEIVED_AT)
        val before = snapshot(recording)

        // when
        val exception = assertThrows<BusinessException> { recording.ensureUploadCanBeRetried() }

        // then
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.FILE_ALREADY_UPLOADED)
        assertThat(snapshot(recording)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["blank-name", "long-name", "blank-mime", "long-mime", "zero-size", "negative-size", "short-hash", "long-hash"])
    fun `명세와 스키마의 기본 파일 메타데이터 제약을 검사한다`(invalid: String) {
        // when
        val exception = assertThrows<BusinessException> {
            when (invalid) {
                "blank-name" -> recording(originalFilename = " \t")
                "long-name" -> recording(originalFilename = "가".repeat(256))
                "blank-mime" -> recording(contentType = "")
                "long-mime" -> recording(contentType = "a".repeat(101))
                "zero-size" -> recording(sizeBytes = 0)
                "negative-size" -> recording(sizeBytes = -1)
                "short-hash" -> recording(checksum = ByteArray(31))
                "long-hash" -> recording(checksum = ByteArray(33))
                else -> error("Unknown metadata case")
            }
        }

        // then
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.INVALID_INPUT_VALUE)
    }

    @Test
    fun `파일명은 문자 수와 객체 키는 UTF8 바이트 수를 기준으로 검사한다`() {
        // given & when
        val recording = recording(originalFilename = "😀".repeat(255), objectKey = "😀".repeat(256))

        // then
        assertThat(recording.originalFilename.codePointCount(0, recording.originalFilename.length)).isEqualTo(255)
        assertThat(recording.objectKey.toByteArray(Charsets.UTF_8)).hasSize(1024)
        assertThrows<IllegalArgumentException> { recording(objectKey = "😀".repeat(256) + "a") }
    }

    private fun snapshot(recording: ReceptionRecording): List<Any?> = listOf(
        recording.recordingFileId,
        recording.receptionId,
        recording.uploadedByAdminId,
        recording.originalFilename,
        recording.bucket,
        recording.objectKey,
        recording.contentType,
        recording.sizeBytes,
        recording.checksumSha256.toList(),
        recording.status,
        recording.recordingReceivedAt,
        recording.createdAt,
        recording.updatedAt,
    )

    private fun recording(
        originalFilename: String = "call.m4a",
        contentType: String = "audio/mp4",
        sizeBytes: Long = 1024,
        checksum: ByteArray = digest(),
        objectKey: String = "recordings/recording-1.m4a",
    ) = ReceptionRecording(
        recordingFileId = "recording-1",
        receptionId = "reception-1",
        uploadedByAdminId = "admin-1",
        originalFilename = originalFilename,
        bucket = "test-bucket",
        objectKey = objectKey,
        contentType = contentType,
        sizeBytes = sizeBytes,
        checksum = checksum,
        createdAt = CREATED_AT,
    )

    private fun digest() = ByteArray(32) { it.toByte() }

    private companion object {
        val CREATED_AT = Instant.parse("2026-11-16T00:00:00Z")
        val RECEIVED_AT = CREATED_AT.plusSeconds(60)
    }
}
