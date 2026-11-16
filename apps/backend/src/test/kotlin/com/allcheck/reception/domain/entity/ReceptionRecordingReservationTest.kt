package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.ErrorCode
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.exception.ReceptionErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class ReceptionRecordingReservationTest {
    @Test
    fun `녹음을 예약하면 파일 ID를 연결하고 업무 버전을 한 번 증가시킨다`() {
        // given
        val reception = reception()
        val data = reception.finalData

        // when
        reception.reserveRecording(1, "recording-1", RESERVED_AT)

        // then
        assertThat(reception.recordingFileId).isEqualTo("recording-1")
        assertThat(reception.version).isEqualTo(2)
        assertThat(reception.updatedAt).isEqualTo(RESERVED_AT)
        assertThat(reception.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(reception.confirmedAt).isNull()
        assertThat(reception.finalData).isSameAs(data)
        assertThat(reception.aiOriginal).isNull()
        assertThat(reception.transcriptFileId).isNull()
        assertThat(reception.latestAnalysisRequestId).isNull()
        assertThat(reception.requestId).isNull()
        assertThat(reception.ownerAdminId).isEqualTo("admin-1")
        assertThat(reception.receivedAt).isEqualTo(RECEIVED_AT)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `현재 버전과 다른 예약 요청은 값을 변경하지 않는다`(expectedVersion: Int) {
        // given
        val reception = reception()

        // when & then
        assertRejected(reception, GlobalErrorCode.VERSION_CONFLICT) {
            reception.reserveRecording(expectedVersion, "recording-1", RESERVED_AT)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["recording-1", "recording-2"])
    fun `이미 예약된 접수에는 같은 파일이든 다른 파일이든 새 예약을 만들지 않는다`(fileId: String) {
        // given
        val reception = reception()
        reception.reserveRecording(1, "recording-1", RESERVED_AT)

        // when & then
        assertRejected(reception, ReceptionErrorCode.RECORDING_ALREADY_EXISTS) {
            reception.reserveRecording(2, fileId, RESERVED_AT.plusSeconds(60))
        }
    }

    @Test
    fun `검토가 완료된 접수는 녹음을 예약할 수 없다`() {
        // given
        val reception = reception()
        ReflectionTestUtils.setField(reception, "reviewStatus", ReceptionReviewStatus.CONFIRMED)
        ReflectionTestUtils.setField(reception, "confirmedAt", RESERVED_AT)

        // when & then
        assertRejected(reception, ReceptionErrorCode.RECEPTION_ALREADY_CONFIRMED) {
            reception.reserveRecording(1, "recording-1", RESERVED_AT)
        }
    }

    @Test
    fun `의뢰가 등록된 접수는 녹음을 예약할 수 없다`() {
        // given
        val reception = reception()
        ReflectionTestUtils.setField(reception, "reviewStatus", ReceptionReviewStatus.CONFIRMED)
        ReflectionTestUtils.setField(reception, "confirmedAt", RESERVED_AT)
        ReflectionTestUtils.setField(reception, "requestId", "request-1")

        // when & then
        assertRejected(reception, ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED) {
            reception.reserveRecording(1, "recording-1", RESERVED_AT)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "too-long"])
    fun `서버가 잘못 생성한 파일 ID는 예약하지 않는다`(invalid: String) {
        // given
        val reception = reception()
        val fileId = if (invalid == "too-long") "r".repeat(65) else invalid
        val before = snapshot(reception)

        // when & then
        assertThrows<IllegalArgumentException> { reception.reserveRecording(1, fileId, RESERVED_AT) }
        assertThat(snapshot(reception)).isEqualTo(before)
    }

    @Test
    fun `업무 버전 증가가 불가능하면 파일 연결도 변경하지 않는다`() {
        // given
        val reception = reception()
        ReflectionTestUtils.setField(reception, "version", Int.MAX_VALUE)
        val before = snapshot(reception)

        // when & then
        assertThrows<ArithmeticException> { reception.reserveRecording(Int.MAX_VALUE, "recording-1", RESERVED_AT) }
        assertThat(snapshot(reception)).isEqualTo(before)
    }

    private fun assertRejected(reception: Reception, code: ErrorCode, action: () -> Unit) {
        val before = snapshot(reception)
        assertThat(assertThrows<BusinessException> { action() }.errorCode).isSameAs(code)
        assertThat(snapshot(reception)).isEqualTo(before)
    }

    private fun snapshot(reception: Reception) = listOf(
        reception.recordingFileId,
        reception.version,
        reception.updatedAt,
        reception.reviewStatus,
        reception.confirmedAt,
        reception.finalData,
        reception.aiOriginal,
        reception.transcriptFileId,
        reception.latestAnalysisRequestId,
        reception.requestId,
    )

    private fun reception() = Reception("reception-1", "admin-1", RECEIVED_AT)

    private companion object {
        val RECEIVED_AT = Instant.parse("2026-11-16T00:00:00Z")
        val RESERVED_AT = RECEIVED_AT.plusSeconds(60)
    }
}
