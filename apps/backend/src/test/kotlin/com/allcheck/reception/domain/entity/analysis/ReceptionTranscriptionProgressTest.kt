package com.allcheck.reception.domain.entity.analysis

import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisTransition
import com.allcheck.reception.fixture.testReceptionAnalysis
import com.allcheck.reception.fixture.testReceptionTranscript
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class ReceptionTranscriptionProgressTest {
    @Test
    fun `유효한 STT 응답은 실행 문맥과 기한을 유지하며 원문 보관 단계로 넘어간다`() {
        // given
        val analysis = claimed()

        // when
        val outcome = analysis.receiveTranscription(WORKER, ORIGINAL, TRACE, TRANSCRIBED_AT)

        // then
        assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.TRANSCRIPT_STORAGE)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.PROCESSING)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.transcriptFileId).isNull()
        assertThat(analysis.errorCode).isNull()
        assertThat(analysis.traceId).isNull()
        assertThat(analysis.claimedBy).isEqualTo(WORKER)
        assertThat(analysis.leaseExpiresAt).isEqualTo(DEADLINE)
        assertThat(analysis.startedAt).isEqualTo(STARTED_AT)
        assertThat(analysis.updatedAt).isEqualTo(TRANSCRIBED_AT)
        assertThat(analysis.receptionVersion).isEqualTo(3)
        assertThat(analysis.referenceTime).isEqualTo(REFERENCE_TIME)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\r\n", "\u00A0", "\u2003\u3000", "long-blank"])
    fun `빈 원문은 STT 실패로 종료하고 원문 보관과 구조화를 사용할 수 없게 한다`(value: String) {
        // given
        val analysis = claimed()
        val text = if (value == "long-blank") " ".repeat(100_001) else value

        // when
        val outcome = analysis.receiveTranscription(WORKER, text, TRACE, TRANSCRIBED_AT)

        // then
        assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.FAILED)
        assertFailure(analysis, "STT_NO_CONTENT", TRANSCRIBED_AT)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.STT)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(analysis.transcriptFileId).isNull()
        assertThat(analysis.aiOriginal).isNull()
        assertThat(analysis.resultApplied).isFalse()
    }

    @Test
    fun `원문 연결은 보관 성공을 기록하고 구조화 대기로 넘어간다`() {
        // given
        val analysis = awaitingStorage()
        val transcript = transcript()

        // when
        val attached = analysis.attachTranscript(WORKER, transcript, TRACE, STORED_AT)

        // then
        assertThat(attached).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.STRUCTURE)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.transcriptFileId).isEqualTo(transcript.transcriptFileId)
        assertThat(transcript.transcript).isEqualTo(ORIGINAL)
        assertThat(analysis.updatedAt).isEqualTo(STORED_AT)
        assertThat(analysis.leaseExpiresAt).isEqualTo(DEADLINE)
        assertThat(analysis.receptionVersion).isEqualTo(3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["producer", "recording", "reception", "early-storage", "future-storage"])
    fun `다른 문맥이나 잘못된 시각의 원문은 분석을 변경하지 않고 거부한다`(invalid: String) {
        // given
        val analysis = awaitingStorage()
        val artifact = testReceptionTranscript(
            transcriptFileId = 1_004L,
            receptionId = if (invalid == "reception") 9000001L else analysis.receptionId,
            recordingFileId = if (invalid == "recording") 9000002L else analysis.recordingFileId,
            producedByAnalysisRequestId = if (invalid == "producer") 9000003L else analysis.analysisRequestId,
            transcript = ORIGINAL,
            storedAt = when (invalid) {
                "early-storage" -> TRANSCRIBED_AT.minusSeconds(1)
                "future-storage" -> STORED_AT.plusSeconds(1)
                else -> STORED_AT
            },
        )
        val before = snapshot(analysis)

        // when & then
        assertThrows<IllegalArgumentException> { analysis.attachTranscript(WORKER, artifact, TRACE, STORED_AT) }
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @Test
    fun `원문 보관 실패는 STT 성공을 유지하고 이후 구조화를 수행하지 않는 상태로 종료한다`() {
        // given
        val analysis = awaitingStorage()

        // when
        val failed = analysis.failTranscriptStorage(WORKER, TRACE, STORED_AT)

        // then
        assertThat(failed).isTrue()
        assertFailure(analysis, "TRANSCRIPT_STORAGE_FAILED", STORED_AT)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.TRANSCRIPT_STORAGE)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(analysis.transcriptFileId).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 1])
    fun `STT 응답은 처리 기한 직전까지만 수용하며 기한과 같거나 늦으면 실패로 종료한다`(seconds: Int) {
        // given
        val analysis = claimed()
        val receivedAt = DEADLINE.plusSeconds(seconds.toLong())

        // when
        val outcome = analysis.receiveTranscription(WORKER, ORIGINAL, TRACE, receivedAt)

        // then
        if (seconds < 0) {
            assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
            assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        } else {
            assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.FAILED)
            assertFailure(analysis, "ANALYSIS_TIMEOUT", receivedAt)
            assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
            assertThat(analysis.transcriptFileId).isNull()
        }
    }

    @Test
    fun `기한 직전에는 만료 점검만으로 상태를 변경하지 않는다`() {
        // given
        val analysis = claimed()
        val before = snapshot(analysis)

        // when
        val expired = analysis.expireIfDue(DEADLINE.minusNanos(1), TRACE)

        // then
        assertThat(expired).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STT", "TRANSCRIPT_STORAGE", "STRUCTURE"])
    fun `기한 만료는 현재 단계에서 종료하고 앞 단계 성공과 원문 연결을 보존한다`(stage: String) {
        // given
        val analysis = claimed()
        if (stage != "STT") analysis.receiveTranscription(WORKER, ORIGINAL, TRACE, TRANSCRIBED_AT)
        if (stage == "STRUCTURE") analysis.attachTranscript(WORKER, transcript(), TRACE, STORED_AT)

        // when
        val expired = analysis.expireIfDue(DEADLINE, TRACE)

        // then
        assertThat(expired).isTrue()
        assertFailure(analysis, "ANALYSIS_TIMEOUT", DEADLINE)
        assertThat(analysis.stage.name).isEqualTo(stage)
        assertThat(analysis.sttStatus).isEqualTo(
            if (stage == "STT") ReceptionProcessingStatus.FAILED else ReceptionProcessingStatus.SUCCEEDED,
        )
        assertThat(analysis.transcriptStorageStatus).isEqualTo(
            when (stage) {
                "STT" -> ReceptionProcessingStatus.NOT_AVAILABLE
                "TRANSCRIPT_STORAGE" -> ReceptionProcessingStatus.FAILED
                else -> ReceptionProcessingStatus.SUCCEEDED
            },
        )
        assertThat(analysis.analysisStatus).isEqualTo(
            if (stage == "STRUCTURE") ReceptionProcessingStatus.FAILED else ReceptionProcessingStatus.NOT_AVAILABLE,
        )
        assertThat(analysis.transcriptFileId).isEqualTo(if (stage == "STRUCTURE") 1_004L else null)
    }

    @Test
    fun `원문을 늦게 연결하면 새 연결 없이 기한 만료로 종료한다`() {
        // given
        val analysis = awaitingStorage()

        // when
        val attached = analysis.attachTranscript(WORKER, transcript(), TRACE, DEADLINE)

        // then
        assertThat(attached).isEqualTo(ReceptionAnalysisTransition.FAILED)
        assertFailure(analysis, "ANALYSIS_TIMEOUT", DEADLINE)
        assertThat(analysis.transcriptFileId).isNull()
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
    }

    @Test
    fun `기한 도달 후 받은 보관 실패도 작업 기한 만료로 분류한다`() {
        // given
        val analysis = awaitingStorage()

        // when
        analysis.failTranscriptStorage(WORKER, TRACE, DEADLINE)

        // then
        assertFailure(analysis, "ANALYSIS_TIMEOUT", DEADLINE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "wrong-worker", "STRUCTURE", "FAILED", "SUCCEEDED", "DISCARDED"])
    fun `대기 다른 작업자 다른 단계 또는 종료된 시도의 응답은 반영하지 않는다`(state: String) {
        // given
        val analysis = if (state == "PENDING") analysis() else claimed()
        if (state == "STRUCTURE") {
            analysis.receiveTranscription(WORKER, ORIGINAL, TRACE, TRANSCRIBED_AT)
            analysis.attachTranscript(WORKER, transcript(), TRACE, STORED_AT)
        }
        if (state in listOf("FAILED", "SUCCEEDED", "DISCARDED")) {
            ReflectionTestUtils.setField(analysis, "status", ReceptionAnalysisStatus.valueOf(state))
        }
        val worker = if (state == "wrong-worker") "worker-2" else WORKER
        val before = snapshot(analysis)

        // when & then
        assertThat(analysis.receiveTranscription(worker, ORIGINAL, TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.attachTranscript(worker, transcript(), TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.failTranscriptStorage(worker, TRACE, STORED_AT)).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @Test
    fun `이미 수용한 STT 응답을 다시 받아도 원문 보관 진행 상태를 바꾸지 않는다`() {
        // given
        val analysis = awaitingStorage()
        val before = snapshot(analysis)

        // when
        val outcome = analysis.receiveTranscription(WORKER, "다른 원문", TRACE, STORED_AT)

        // then
        assertThat(outcome).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @Test
    fun `실패 확정 뒤 도착한 응답과 만료 점검은 최초 실패 원인과 시각을 변경하지 않는다`() {
        // given
        val analysis = claimed()
        analysis.receiveTranscription(WORKER, "", TRACE, TRANSCRIBED_AT)
        val before = snapshot(analysis)

        // when & then
        assertThat(analysis.receiveTranscription(WORKER, ORIGINAL, "new-trace", DEADLINE)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(analysis.expireIfDue(DEADLINE, "new-trace")).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["blank-trace", "early-time", "oversize"])
    fun `잘못된 내부 전환 입력은 기존 상태를 변경하지 않는다`(invalid: String) {
        // given
        val analysis = claimed()
        val before = snapshot(analysis)
        val trace = if (invalid == "blank-trace") " " else TRACE
        val now = if (invalid == "early-time") STARTED_AT.minusSeconds(1) else TRANSCRIBED_AT
        val original = if (invalid == "oversize") "가".repeat(100_001) else ORIGINAL

        // when & then
        assertThrows<IllegalArgumentException> { analysis.receiveTranscription(WORKER, original, trace, now) }
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    private fun assertFailure(analysis: ReceptionAnalysis, code: String, at: Instant) {
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(analysis.errorCode).isEqualTo(code)
        assertThat(analysis.errorMessage).isNotBlank()
        assertThat(analysis.traceId).isEqualTo(TRACE)
        assertThat(analysis.completedAt).isEqualTo(at)
        assertThat(analysis.updatedAt).isEqualTo(at)
        assertThat(analysis.claimedBy).isNull()
        assertThat(analysis.leaseExpiresAt).isNull()
    }

    private fun analysis() = testReceptionAnalysis(1_005L, 1_001L, 1_007L, 3, 1_003L, REFERENCE_TIME, ACCEPTED_AT)

    private fun claimed() = analysis().also { it.claim(WORKER, STARTED_AT, DEADLINE) }

    private fun awaitingStorage() = claimed().also { it.receiveTranscription(WORKER, ORIGINAL, TRACE, TRANSCRIBED_AT) }

    private fun transcript() = testReceptionTranscript(1_004L, 1_001L, 1_007L, 1_005L, ORIGINAL, STORED_AT)

    private fun snapshot(analysis: ReceptionAnalysis) = listOf(
        analysis.status,
        analysis.stage,
        analysis.sttStatus,
        analysis.transcriptStorageStatus,
        analysis.analysisStatus,
        analysis.transcriptFileId,
        analysis.resultApplied,
        analysis.aiOriginal,
        analysis.missingFields,
        analysis.errorCode,
        analysis.errorMessage,
        analysis.traceId,
        analysis.claimedBy,
        analysis.leaseExpiresAt,
        analysis.startedAt,
        analysis.completedAt,
        analysis.updatedAt,
    )

    private companion object {
        const val WORKER = "worker-1"
        const val TRACE = "test-trace"
        const val ORIGINAL = "  고객: 물이 샙니다.\r\n관리자: 방문하겠습니다.\n고객: 🙂 감사합니다.  "
        val REFERENCE_TIME = Instant.parse("2026-11-16T00:00:00Z")
        val ACCEPTED_AT = REFERENCE_TIME.plusSeconds(1)
        val STARTED_AT = ACCEPTED_AT.plusSeconds(1)
        val TRANSCRIBED_AT = STARTED_AT.plusSeconds(1)
        val STORED_AT = TRANSCRIBED_AT.plusSeconds(1)
        val DEADLINE = STARTED_AT.plusSeconds(60)
    }
}
