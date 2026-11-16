package com.allcheck.reception.domain.entity

import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisFailureReason
import com.allcheck.reception.domain.vo.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionAnalysisTransition
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionData
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.domain.vo.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.reception.fixture.testReception
import com.allcheck.reception.fixture.testReceptionAnalysis
import com.allcheck.reception.fixture.testReceptionTranscript
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.OffsetDateTime

class ReceptionStructuringTest {
    @ParameterizedTest
    @EnumSource(WorkType::class)
    fun `최초 결과를 한 번 자동 채우고 AI 성공과 관리자 검토 확정을 구분한다`(workType: WorkType) {
        // given
        val original = original().copy(workType = workType)
        val analysis = readyResult(original)
        val reception = reception()

        // when
        val applied = analysis.applyResultTo(reception, WORKER, TRACE, APPLIED_AT)

        // then
        assertThat(applied).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
        assertThat(reception.aiOriginal).isSameAs(original)
        assertThat(reception.finalData.workType).isEqualTo(workType)
        assertThat(reception.finalData.symptoms).isEqualTo(original.symptoms)
        assertThat(reception.finalData.address).isEqualTo(original.address)
        assertThat(reception.finalData.desiredVisitFrom).isEqualTo(original.desiredVisitFrom)
        assertThat(reception.finalData.desiredVisitTo).isEqualTo(original.desiredVisitTo)
        assertThat(reception.finalData.notes).isEqualTo(original.notes)
        assertThat(reception.finalData.contactPhone).isNull()
        assertThat(reception.callerPhone).isEqualTo("010-9999-9999")
        assertThat(reception.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(reception.confirmedAt).isNull()
        assertThat(reception.version).isEqualTo(4)
        assertThat(reception.updatedAt).isEqualTo(APPLIED_AT)
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.SUCCEEDED)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.COMPLETED)
        assertThat(analysis.resultApplied).isTrue()
        assertThat(analysis.completedAt).isEqualTo(APPLIED_AT)
        assertThat(analysis.receptionVersion).isEqualTo(3)
        assertThat(analysis.claimedBy).isNull()
        assertThat(analysis.leaseExpiresAt).isNull()
        assertThat(analysis.errorCode).isNull()

        // when & then
        val before = receptionSnapshot(reception)
        assertThat(analysis.applyResultTo(reception, WORKER, "late-trace", APPLIED_AT.plusSeconds(1)))
            .isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(receptionSnapshot(reception)).isEqualTo(before)
    }

    @Test
    fun `누락 목록이 비어 있어도 빈 AI 원본은 불완전한 초안으로 보존한다`() {
        // given
        val analysis = readyResult(ReceptionAiOriginal())
        val reception = reception()

        // when
        analysis.applyResultTo(reception, WORKER, TRACE, APPLIED_AT)

        // then
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.SUCCEEDED)
        assertThat(reception.aiOriginal).isNotNull().isEqualTo(ReceptionAiOriginal())
        assertThat(reception.finalData).isEqualTo(ReceptionData())
        assertThat(reception.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(reception.version).isEqualTo(4)
    }

    @Test
    fun `기존 수동 검토값이 있는 접수의 최초 AI 결과는 최신 편집값을 덮어쓰지 않는다`() {
        // given
        val reception = testReception(1_001L, 1_003L, REFERENCE_TIME.minusSeconds(60))
        reception.confirm(1, reviewedData(), analysisStatus = null, now = REFERENCE_TIME.minusSeconds(30))
        reception.reopen(2, REFERENCE_TIME.minusSeconds(20))
        reception.reserveRecording(3, 1_007L, REFERENCE_TIME.minusSeconds(10))
        bindStoredContext(reception, version = 5)
        val reviewed = reception.finalData
        val analysis = readyResult(version = 5)

        // when
        analysis.applyResultTo(reception, WORKER, TRACE, APPLIED_AT)

        // then
        assertThat(reception.aiOriginal).isEqualTo(original())
        assertThat(reception.finalData).isSameAs(reviewed)
        assertThat(reception.finalData.notes).isEqualTo("관리자 메모")
        assertThat(reception.finalData.contactPhone).isEqualTo("010-1234-5678")
        assertThat(reception.version).isEqualTo(6)
        assertThat(reception.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "attempt", "recording", "transcript", "owner", "reception", "confirmed", "registered", "original"])
    fun `오래된 문맥과 수정 불가 접수의 결과는 기존 값을 보존하고 미반영 종료한다`(stale: String) {
        // given
        val analysis = readyResult()
        val reception = reception(
            receptionId = if (stale == "reception") 9000001L else 1_001L,
            ownerAdminId = if (stale == "owner") 3L else 1_003L,
        )
        // 정상 MVP의 선확정 흐름을 만들지 않고 오래된 저장 상태에 대한 서버 방어조건을 준비한다.
        when (stale) {
            "version" -> ReflectionTestUtils.setField(reception, "version", 4)
            "attempt" -> ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", 9000003L)
            "recording" -> ReflectionTestUtils.setField(reception, "recordingFileId", 9000002L)
            "transcript" -> ReflectionTestUtils.setField(reception, "transcriptFileId", 9000004L)
            "confirmed", "registered" -> {
                ReflectionTestUtils.setField(reception, "reviewStatus", ReceptionReviewStatus.CONFIRMED)
                ReflectionTestUtils.setField(reception, "confirmedAt", STRUCTURED_AT)
                ReflectionTestUtils.setField(
                    reception,
                    "finalData",
                    ReceptionData(
                        workType = WorkType.BLOCKAGE,
                        symptoms = "보존할 증상",
                        address = "보존할 주소",
                        desiredVisitFrom = reviewedData().desiredVisitFrom,
                        desiredVisitTo = reviewedData().desiredVisitTo,
                        contactPhone = "010-1234-5678",
                        notes = "보존할 메모",
                    ),
                )
                ReflectionTestUtils.setField(reception, "updatedAt", STRUCTURED_AT)
                if (stale == "registered") ReflectionTestUtils.setField(reception, "requestId", 1_002L)
            }
            "original" -> ReflectionTestUtils.setField(reception, "aiOriginal", ReceptionAiOriginal(notes = "기존 AI 원본"))
        }
        val before = receptionSnapshot(reception)

        // when
        val outcome = analysis.applyResultTo(reception, WORKER, TRACE, APPLIED_AT)

        // then
        assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.FAILED)
        assertThat(receptionSnapshot(reception)).isEqualTo(before)
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.DISCARDED)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.RESULT_APPLICATION)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.errorCode).isEqualTo("STALE_ANALYSIS_RESULT")
        assertThat(analysis.resultApplied).isFalse()
        assertThat(analysis.completedAt).isEqualTo(APPLIED_AT)
        assertThat(analysis.claimedBy).isNull()
        assertThat(analysis.leaseExpiresAt).isNull()
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisFailureReason::class, names = ["AI_STRUCTURING_FAILED", "AI_TIMEOUT"])
    fun `구조화 실패는 앞 단계 성공과 원문 연결을 유지한다`(reason: ReceptionAnalysisFailureReason) {
        // given
        val analysis = pendingStructure()
        analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)

        // when
        val changed = analysis.failStructuring(WORKER, reason, TRACE, STRUCTURED_AT)

        // then
        assertThat(changed).isTrue()
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.STRUCTURE)
        assertThat(analysis.errorCode).isEqualTo(reason.name)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(analysis.transcriptFileId).isEqualTo(1_004L)
        assertThat(analysis.aiOriginal).isNull()
        assertThat(analysis.claimedBy).isNull()
        assertThat(analysis.leaseExpiresAt).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "receive", "apply"])
    fun `기한에 도달한 구조화 단계는 늦은 결과를 접수에 반영하지 않는다`(step: String) {
        // given
        val analysis = pendingStructure()
        val reception = reception()
        if (step != "start") analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)
        if (step == "apply") analysis.receiveStructuredResult(WORKER, result(), TRACE, STRUCTURED_AT)
        val before = receptionSnapshot(reception)

        // when
        val outcome = when (step) {
            "start" -> analysis.startStructuring(WORKER, TRACE, DEADLINE)
            "receive" -> analysis.receiveStructuredResult(WORKER, result(), TRACE, DEADLINE)
            else -> analysis.applyResultTo(reception, WORKER, TRACE, DEADLINE)
        }

        // then
        assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.FAILED)
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(analysis.errorCode).isEqualTo("ANALYSIS_TIMEOUT")
        assertThat(analysis.transcriptFileId).isEqualTo(1_004L)
        assertThat(analysis.resultApplied).isFalse()
        assertThat(receptionSnapshot(reception)).isEqualTo(before)
    }

    @Test
    fun `다른 작업자와 순서가 맞지 않거나 중복된 구조화 이벤트는 상태를 변경하지 않는다`() {
        // given
        val analysis = pendingStructure()
        val reception = reception()

        // when & then
        assertThat(analysis.startStructuring("worker-2", TRACE, STRUCTURING_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.receiveStructuredResult(WORKER, result(), TRACE, STRUCTURING_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.failStructuring(WORKER, ReceptionAnalysisFailureReason.AI_TIMEOUT, TRACE, STRUCTURING_AT)).isFalse()
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)

        assertThat(analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
        assertThat(analysis.startStructuring(WORKER, TRACE, STRUCTURED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.receiveStructuredResult("worker-2", result(), TRACE, STRUCTURED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.receiveStructuredResult(WORKER, result(), TRACE, STRUCTURED_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
        val original = analysis.aiOriginal
        assertThat(analysis.receiveStructuredResult(WORKER, result(), TRACE, APPLIED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.applyResultTo(reception, "worker-2", TRACE, APPLIED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.aiOriginal).isSameAs(original)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.RESULT_APPLICATION)
        assertThat(reception.version).isEqualTo(3)
    }

    @Test
    fun `구조화 요청 실패로 종료한 뒤 성공 응답이 와도 실패 원인과 시각을 유지한다`() {
        // given
        val analysis = pendingStructure()
        analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)
        analysis.failStructuring(WORKER, ReceptionAnalysisFailureReason.AI_TIMEOUT, TRACE, STRUCTURED_AT)
        val reception = reception()

        // when & then
        assertThat(analysis.receiveStructuredResult(WORKER, result(), "late-trace", APPLIED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.applyResultTo(reception, WORKER, "late-trace", APPLIED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.errorCode).isEqualTo("AI_TIMEOUT")
        assertThat(analysis.completedAt).isEqualTo(STRUCTURED_AT)
        assertThat(analysis.traceId).isEqualTo(TRACE)
        assertThat(reception.aiOriginal).isNull()
    }

    @Test
    fun `다른 단계의 실패 사유를 구조화 오류로 기록하지 않는다`() {
        // given
        val analysis = pendingStructure()
        analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)

        // when & then
        assertThrows<IllegalArgumentException> {
            analysis.failStructuring(WORKER, ReceptionAnalysisFailureReason.STT_NO_CONTENT, TRACE, STRUCTURED_AT)
        }
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PROCESSING)
        assertThat(analysis.errorCode).isNull()
        assertThat(analysis.updatedAt).isEqualTo(STRUCTURING_AT)
    }

    @Test
    fun `누락 목록은 입력 컬렉션의 변경으로 바뀌지 않으며 원본과 교차 보완하지 않는다`() {
        // given
        val missing = mutableListOf("address", "desiredVisitTo")
        val result = ReceptionStructuredResult(ReceptionAiOriginal(), missing)
        missing.clear()

        // when
        val analysis = pendingStructure()
        analysis.startStructuring(WORKER, TRACE, STRUCTURING_AT)
        analysis.receiveStructuredResult(WORKER, result, TRACE, STRUCTURED_AT)

        // then
        assertThat(analysis.missingFields).containsExactly("address", "desiredVisitTo")
        assertThat(analysis.aiOriginal).isEqualTo(ReceptionAiOriginal())
    }

    @ParameterizedTest
    @ValueSource(strings = ["notes", "contactPhone", "UNKNOWN", "duplicate"])
    fun `현재 AI 계약 밖의 누락 필드와 중복 목록은 거부한다`(value: String) {
        // given
        val fields = if (value == "duplicate") listOf("address", "address") else listOf(value)

        // when & then
        assertThrows<IllegalArgumentException> { ReceptionStructuredResult(original(), fields) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty-symptoms", "long-symptoms", "empty-address", "long-address", "long-notes"])
    fun `AI 응답 계약의 문자열 범위를 벗어난 결과는 자르지 않고 거부한다`(invalid: String) {
        // given
        val invalidOriginal = when (invalid) {
            "empty-symptoms" -> original().copy(symptoms = "")
            "long-symptoms" -> original().copy(symptoms = "가".repeat(2001))
            "empty-address" -> original().copy(address = "")
            "long-address" -> original().copy(address = "가".repeat(301))
            else -> original().copy(notes = "가".repeat(2001))
        }

        // when & then
        assertThrows<IllegalArgumentException> { ReceptionStructuredResult(invalidOriginal, emptyList()) }
    }

    private fun pendingStructure(version: Int = 3) = testReceptionAnalysis(
        1_005L,
        1_001L,
        1_007L,
        version,
        1_003L,
        REFERENCE_TIME,
        ACCEPTED_AT,
    ).also {
        check(it.claim(WORKER, STARTED_AT, DEADLINE))
        check(it.receiveTranscription(WORKER, "STT 원문", TRACE, TRANSCRIBED_AT) == ReceptionAnalysisTransition.ADVANCED)
        check(
            it.attachTranscript(
                WORKER,
                testReceptionTranscript(1_004L, 1_001L, 1_007L, 1_005L, "STT 원문", STORED_AT),
                TRACE,
                STORED_AT,
            ) == ReceptionAnalysisTransition.ADVANCED,
        )
    }

    private fun readyResult(aiOriginal: ReceptionAiOriginal = original(), version: Int = 3) = pendingStructure(version).also {
        check(it.startStructuring(WORKER, TRACE, STRUCTURING_AT) == ReceptionAnalysisTransition.ADVANCED)
        check(it.receiveStructuredResult(WORKER, ReceptionStructuredResult(aiOriginal, emptyList()), TRACE, STRUCTURED_AT) == ReceptionAnalysisTransition.ADVANCED)
    }

    private fun reception(receptionId: Long = 1_001L, ownerAdminId: Long = 1_003L) = testReception(receptionId, ownerAdminId, REFERENCE_TIME.minusSeconds(60), callerPhone = "010-9999-9999").also {
        it.reserveRecording(1, 1_007L, REFERENCE_TIME.minusSeconds(10))
        bindStoredContext(it, 3)
    }

    // 아직 연결하지 않은 업로드 완료·원문 보관 애플리케이션의 저장 상태를 준비한다.
    private fun bindStoredContext(reception: Reception, version: Int) {
        ReflectionTestUtils.setField(reception, "version", version)
        ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", 1_005L)
        ReflectionTestUtils.setField(reception, "transcriptFileId", 1_004L)
        ReflectionTestUtils.setField(reception, "updatedAt", STORED_AT)
    }

    private fun result() = ReceptionStructuredResult(original(), emptyList())

    private fun original() = ReceptionAiOriginal(
        workType = WorkType.WATERPROOFING,
        symptoms = "AI 추출 증상",
        address = "AI 추출 주소",
        desiredVisitFrom = OffsetDateTime.parse("2026-11-17T14:00:00+09:00"),
        desiredVisitTo = OffsetDateTime.parse("2026-11-17T16:00:00+09:00"),
        notes = "  방문 전 연락\r\n주차 가능  ",
    )

    private fun reviewedData() = ReceptionConfirmationData(
        WorkType.BLOCKAGE,
        "관리자 확인 증상",
        "관리자 확인 주소",
        OffsetDateTime.parse("2026-11-17T14:00:00+09:00"),
        OffsetDateTime.parse("2026-11-17T16:00:00+09:00"),
        "010-1234-5678",
        "관리자 메모",
    )

    private fun receptionSnapshot(reception: Reception) = listOf(
        reception.version,
        reception.reviewStatus,
        reception.requestId,
        reception.finalData,
        reception.aiOriginal,
        reception.confirmedAt,
        reception.updatedAt,
        reception.recordingFileId,
        reception.transcriptFileId,
        reception.latestAnalysisRequestId,
    )

    private companion object {
        const val WORKER = "worker-1"
        const val TRACE = "test-trace"
        val REFERENCE_TIME = Instant.parse("2026-11-16T00:00:00Z")
        val ACCEPTED_AT = REFERENCE_TIME.plusSeconds(1)
        val STARTED_AT = ACCEPTED_AT.plusSeconds(1)
        val TRANSCRIBED_AT = STARTED_AT.plusSeconds(1)
        val STORED_AT = TRANSCRIBED_AT.plusSeconds(1)
        val STRUCTURING_AT = STORED_AT.plusSeconds(1)
        val STRUCTURED_AT = STRUCTURING_AT.plusSeconds(1)
        val APPLIED_AT = STRUCTURED_AT.plusSeconds(1)
        val DEADLINE = STARTED_AT.plusSeconds(60)
    }
}
