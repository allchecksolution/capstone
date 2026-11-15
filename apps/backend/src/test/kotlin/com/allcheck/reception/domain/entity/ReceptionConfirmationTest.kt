package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.ErrorCode
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionData
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.reception.exception.ReceptionErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.stream.Stream

class ReceptionConfirmationTest {
    @Test
    fun `수동 접수는 전체 최종값으로 검토 완료하고 버전과 서버 시각을 함께 반영한다`() {
        // given
        val reception = manualReception()
        val data = validData().copy(notes = "주차장 출입 시 연락")

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 2)
        assertThat(reception.aiOriginal).isNull()
        assertThat(reception.callerPhone).isEqualTo("02-1234-5678")
        assertThat(reception.finalData.contactPhone).isEqualTo("010-1234-5678")
    }

    @Test
    fun `AI 원본과 자료 연결을 보존하면서 모든 편집값을 제출값으로 교체한다`() {
        // given
        val reception = analyzedReception()
        val original = reception.aiOriginal
        val data = validData().copy(notes = "건물 뒤편에 주차")

        // when
        reception.confirm(reception.version, data, ReceptionAnalysisStatus.SUCCEEDED, NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 5)
        assertThat(reception.aiOriginal).isSameAs(original)
        assertThat(reception.aiOriginal?.notes).isEqualTo("방문 30분 전 연락")
        assertThat(reception.recordingFileId).isEqualTo("recording-1")
        assertThat(reception.transcriptFileId).isEqualTo("transcript-1")
        assertThat(reception.latestAnalysisRequestId).isEqualTo("analysis-1")
        assertThat(reception.requestId).isNull()
    }

    @Test
    fun `선택 notes를 생략하면 기존 편집 notes를 비우고 AI 원본 notes는 보존한다`() {
        // given
        val reception = analyzedReception()
        val original = reception.aiOriginal
        val data = validData()

        // when
        reception.confirm(reception.version, data, ReceptionAnalysisStatus.SUCCEEDED, NOW)

        // then
        assertThat(reception.finalData.notes).isNull()
        assertThat(reception.aiOriginal).isSameAs(original)
        assertThat(reception.aiOriginal?.notes).isEqualTo("방문 30분 전 연락")
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisStatus::class, names = ["SUCCEEDED", "FAILED", "DISCARDED"])
    fun `분석이 종료되면 필수값을 보완하여 검토 완료할 수 있다`(status: ReceptionAnalysisStatus) {
        // given
        val reception = if (status == ReceptionAnalysisStatus.SUCCEEDED) analyzedReception() else receptionWithAnalysis()
        val versionBefore = reception.version
        val data = validData()

        // when
        reception.confirm(reception.version, data, status, NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = versionBefore + 1)
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisStatus::class, names = ["PENDING", "PROCESSING"])
    fun `분석 대기와 진행 중에는 완전한 입력도 거부하고 접수를 변경하지 않는다`(status: ReceptionAnalysisStatus) {
        // given
        val reception = receptionWithAnalysis()

        // when & then
        assertRejected(reception, ReceptionErrorCode.REVIEW_NOT_READY) {
            reception.confirm(reception.version, validData(), status, NOW)
        }
    }

    @Test
    fun `연결된 분석이 있는데 현재 상태가 전달되지 않으면 검토를 허용하지 않는다`() {
        // given
        val reception = receptionWithAnalysis()

        // when & then
        assertRejected(reception, ReceptionErrorCode.REVIEW_NOT_READY) {
            reception.confirm(reception.version, validData(), analysisStatus = null, now = NOW)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3, 5])
    fun `현재 버전과 다른 요청은 기존 데이터와 상태를 변경하지 않는다`(expectedVersion: Int) {
        // given
        val reception = analyzedReception()

        // when & then
        assertRejected(reception, GlobalErrorCode.VERSION_CONFLICT) {
            reception.confirm(expectedVersion, validData(), ReceptionAnalysisStatus.SUCCEEDED, NOW)
        }
    }

    @Test
    fun `검토 완료한 접수는 다른 최종값으로 다시 확정할 수 없다`() {
        // given
        val reception = analyzedReception()
        reception.confirm(reception.version, validData(), ReceptionAnalysisStatus.SUCCEEDED, NOW)

        // when & then
        assertRejected(reception, ReceptionErrorCode.RECEPTION_ALREADY_CONFIRMED) {
            reception.confirm(
                reception.version,
                validData().copy(notes = "변경 시도"),
                ReceptionAnalysisStatus.SUCCEEDED,
                NOW.plusSeconds(60),
            )
        }
    }

    @Test
    fun `의뢰로 등록된 접수의 최종값을 바꿀 수 없다`() {
        // given
        val reception = analyzedReception()
        reception.confirm(reception.version, validData(), ReceptionAnalysisStatus.SUCCEEDED, NOW)
        ReflectionTestUtils.setField(reception, "requestId", "request-1")

        // when & then
        assertRejected(reception, ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED) {
            reception.confirm(reception.version, validData(), ReceptionAnalysisStatus.SUCCEEDED, NOW.plusSeconds(60))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidData")
    fun `잘못된 필수값과 길이는 기존 AI 편집값을 지우지 않고 거부한다`(
        scenario: String,
        data: ReceptionConfirmationData,
    ) {
        // given
        val reception = analyzedReception()

        // when & then
        assertRejected(reception, GlobalErrorCode.INVALID_INPUT_VALUE, scenario) {
            reception.confirm(reception.version, data, ReceptionAnalysisStatus.SUCCEEDED, NOW)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVisitWindows")
    fun `유효하지 않은 방문 구간은 접수 값과 확정 상태를 변경하지 않는다`(
        scenario: String,
        data: ReceptionConfirmationData,
    ) {
        // given
        val reception = analyzedReception()

        // when & then
        assertRejected(reception, GlobalErrorCode.INVALID_INPUT_VALUE, scenario) {
            reception.confirm(reception.version, data, ReceptionAnalysisStatus.SUCCEEDED, NOW)
        }
    }

    @Test
    fun `방문 시작이 지났어도 종료가 서버 현재 시각 이후이면 허용한다`() {
        // given
        val reception = manualReception()
        val data = validData()
        assertThat(data.desiredVisitFrom.toInstant()).isBefore(NOW)

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 2)
    }

    @Test
    fun `종료가 현재보다 1나노초 뒤이면 허용한다`() {
        // given
        val reception = manualReception()
        val data = validData().copy(desiredVisitTo = NOW.plusNanos(1).atOffset(ZoneOffset.UTC))

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 2)
    }

    @Test
    fun `서로 다른 오프셋의 방문 구간도 실제 시각으로 비교하고 입력한 오프셋을 보존한다`() {
        // given
        val reception = manualReception()
        val data = validData().copy(
            desiredVisitFrom = OffsetDateTime.parse("2026-11-15T15:10:00+09:00"),
            desiredVisitTo = OffsetDateTime.parse("2026-11-15T14:30:00+08:00"),
        )

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["123456789", "12345678901234567890", "+82-10-1234-5678"])
    fun `현재 명세의 연락처 길이 경계와 허용 문자를 지원한다`(phone: String) {
        // given
        val reception = manualReception()
        val data = validData().copy(contactPhone = phone)

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertThat(reception.finalData.contactPhone).isEqualTo(phone)
    }

    @Test
    fun `텍스트 최대 길이는 JSON Schema와 같이 유니코드 코드 포인트 기준으로 허용한다`() {
        // given
        val reception = manualReception()
        val data = validData().copy(
            symptoms = "😀".repeat(2000),
            address = "가".repeat(300),
            notes = "😀".repeat(2000),
        )

        // when
        reception.confirm(reception.version, data, analysisStatus = null, now = NOW)

        // then
        assertConfirmed(reception, data, expectedVersion = 2)
    }

    private fun assertConfirmed(reception: Reception, data: ReceptionConfirmationData, expectedVersion: Int) {
        assertThat(reception.reviewStatus).isEqualTo(ReceptionReviewStatus.CONFIRMED)
        assertThat(reception.version).isEqualTo(expectedVersion)
        assertThat(reception.confirmedAt).isEqualTo(NOW)
        assertThat(reception.updatedAt).isEqualTo(NOW)
        assertThat(reception.finalData).isEqualTo(
            ReceptionData(
                data.workType,
                data.symptoms,
                data.address,
                data.desiredVisitFrom,
                data.desiredVisitTo,
                data.contactPhone,
                data.notes,
            ),
        )
    }

    private fun assertRejected(
        reception: Reception,
        errorCode: ErrorCode,
        scenario: String = "검토 완료 거부",
        action: () -> Unit,
    ) {
        val before = snapshot(reception)
        val exception = assertThrows<BusinessException> { action() }
        assertThat(exception.errorCode).`as`(scenario).isSameAs(errorCode)
        assertThat(snapshot(reception)).`as`("%s: 기존 값 보존", scenario).isEqualTo(before)
    }

    private fun snapshot(reception: Reception): List<Any?> = listOf(
        reception.reviewStatus,
        reception.version,
        reception.finalData,
        reception.aiOriginal,
        reception.recordingFileId,
        reception.transcriptFileId,
        reception.latestAnalysisRequestId,
        reception.requestId,
        reception.confirmedAt,
        reception.updatedAt,
    )

    private fun manualReception() = Reception(
        receptionId = "reception-1",
        ownerAdminId = "admin-1",
        receivedAt = NOW.minusSeconds(7200),
        callerPhone = "02-1234-5678",
    )

    // 아직 없는 업로드·AI 결과 적용 동작을 구현하는 대신 테스트에서 저장된 상태만 준비한다.
    private fun receptionWithAnalysis() = manualReception().also {
        ReflectionTestUtils.setField(it, "version", 3)
        ReflectionTestUtils.setField(it, "recordingFileId", "recording-1")
        ReflectionTestUtils.setField(it, "latestAnalysisRequestId", "analysis-1")
        ReflectionTestUtils.setField(it, "updatedAt", NOW.minusSeconds(60))
    }

    private fun analyzedReception() = receptionWithAnalysis().also {
        val original = ReceptionAiOriginal(
            workType = WorkType.EXTERIOR_LEAK,
            symptoms = "창틀 누수",
            address = "서울시 강남구 AI 추출 주소",
            desiredVisitFrom = OffsetDateTime.parse("2026-11-15T14:00:00+09:00"),
            desiredVisitTo = OffsetDateTime.parse("2026-11-15T16:00:00+09:00"),
            notes = "방문 30분 전 연락",
        )
        ReflectionTestUtils.setField(it, "version", 4)
        ReflectionTestUtils.setField(it, "aiOriginal", original)
        ReflectionTestUtils.setField(it, "transcriptFileId", "transcript-1")
        ReflectionTestUtils.setField(
            it,
            "finalData",
            ReceptionData(
                original.workType,
                original.symptoms,
                original.address,
                original.desiredVisitFrom,
                original.desiredVisitTo,
                notes = original.notes,
            ),
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-11-15T06:00:00Z")

        private fun validData() = ReceptionConfirmationData(
            workType = WorkType.BLOCKAGE,
            symptoms = "싱크대 배수 불가",
            address = "서울시 강남구 예시로 10",
            desiredVisitFrom = OffsetDateTime.parse("2026-11-15T14:00:00+09:00"),
            desiredVisitTo = OffsetDateTime.parse("2026-11-15T16:00:00+09:00"),
            contactPhone = "010-1234-5678",
        )

        @JvmStatic
        fun invalidData(): Stream<Arguments> = Stream.of(
            Arguments.of("증상 누락", validData().copy(symptoms = "")),
            Arguments.of("증상 공백", validData().copy(symptoms = " \t\n")),
            Arguments.of("주소 누락", validData().copy(address = "")),
            Arguments.of("주소 공백", validData().copy(address = " \t\n")),
            Arguments.of("연락처 누락", validData().copy(contactPhone = "")),
            Arguments.of("연락처 공백", validData().copy(contactPhone = "         ")),
            Arguments.of("연락처 문자", validData().copy(contactPhone = "phone123456")),
            Arguments.of("연락처 내부 공백", validData().copy(contactPhone = "010 1234 5678")),
            Arguments.of("연락처 줄바꿈", validData().copy(contactPhone = "010-1234-5678\n")),
            Arguments.of("연락처 최소 길이 미달", validData().copy(contactPhone = "1".repeat(8))),
            Arguments.of("연락처 최대 길이 초과", validData().copy(contactPhone = "1".repeat(21))),
            Arguments.of("증상 최대 길이 초과", validData().copy(symptoms = "가".repeat(2001))),
            Arguments.of("주소 최대 길이 초과", validData().copy(address = "가".repeat(301))),
            Arguments.of("부가정보 최대 길이 초과", validData().copy(notes = "😀".repeat(2001))),
        )

        @JvmStatic
        fun invalidVisitWindows(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "시작과 종료가 같은 구간",
                validData().copy(desiredVisitFrom = validData().desiredVisitTo),
            ),
            Arguments.of(
                "종료가 시작 이전인 구간",
                validData().copy(desiredVisitFrom = validData().desiredVisitTo.plusHours(1)),
            ),
            Arguments.of(
                "종료가 서버 현재 시각과 같은 구간",
                validData().copy(desiredVisitTo = NOW.atOffset(ZoneOffset.UTC)),
            ),
            Arguments.of(
                "종료가 현재보다 1나노초 이전인 구간",
                validData().copy(desiredVisitTo = NOW.minusNanos(1).atOffset(ZoneOffset.UTC)),
            ),
            Arguments.of(
                "종료가 이미 지난 구간",
                validData().copy(desiredVisitTo = OffsetDateTime.parse("2026-11-15T14:30:00+09:00")),
            ),
            Arguments.of(
                "오프셋이 다르지만 시작과 종료의 실제 시각이 같은 구간",
                validData().copy(desiredVisitFrom = OffsetDateTime.parse("2026-11-15T17:00:00+10:00")),
            ),
            Arguments.of(
                "표시 시각과 다르게 종료가 실제 시작 이전인 구간",
                validData().copy(
                    desiredVisitFrom = OffsetDateTime.parse("2026-11-15T15:30:00+08:00"),
                    desiredVisitTo = OffsetDateTime.parse("2026-11-15T16:00:00+09:00"),
                ),
            ),
        )
    }
}
