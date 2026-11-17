package com.allcheck.reception.domain.entity.analysis

import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import com.allcheck.reception.fixture.testReceptionAnalysis
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class ReceptionAnalysisTest {
    @Test
    fun `최초 분석은 고정된 접수 문맥으로 대기하며 아직 원문이나 결과가 없다`() {
        // when
        val analysis = analysis()

        // then
        assertThat(analysis.analysisRequestId).isEqualTo(1_005L)
        assertThat(analysis.receptionId).isEqualTo(1_001L)
        assertThat(analysis.recordingFileId).isEqualTo(1_007L)
        assertThat(analysis.requestedByAdminId).isEqualTo(1_003L)
        assertThat(analysis.receptionVersion).isEqualTo(3)
        assertThat(analysis.referenceTime).isEqualTo(REFERENCE_TIME)
        assertThat(analysis.timeZone).isEqualTo("Asia/Seoul")
        assertThat(analysis.mode).isEqualTo("FULL")
        assertThat(analysis.isInitial).isTrue()
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PENDING)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.QUEUED)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.transcriptFileId).isNull()
        assertThat(analysis.resultApplied).isFalse()
        assertThat(analysis.aiOriginal).isNull()
        assertThat(analysis.missingFields).isNull()
        assertThat(analysis.errorCode).isNull()
        assertThat(analysis.errorMessage).isNull()
        assertThat(analysis.traceId).isNull()
        assertThat(analysis.claimedBy).isNull()
        assertThat(analysis.leaseExpiresAt).isNull()
        assertThat(analysis.startedAt).isNull()
        assertThat(analysis.completedAt).isNull()
        assertThat(analysis.createdAt).isEqualTo(ACCEPTED_AT)
        assertThat(analysis.updatedAt).isEqualTo(ACCEPTED_AT)
    }

    @Test
    fun `대기 시도의 실행권을 얻으면 STT 진행 상태와 작업자 및 기한을 함께 기록한다`() {
        // given
        val analysis = analysis()

        // when
        val claimed = analysis.claim("worker-1", STARTED_AT, DEADLINE)

        // then
        assertThat(claimed).isTrue()
        assertThat(analysis.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(analysis.stage).isEqualTo(ReceptionAnalysisStage.STT)
        assertThat(analysis.sttStatus).isEqualTo(ReceptionProcessingStatus.PROCESSING)
        assertThat(analysis.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(analysis.claimedBy).isEqualTo("worker-1")
        assertThat(analysis.leaseExpiresAt).isEqualTo(DEADLINE)
        assertThat(analysis.startedAt).isEqualTo(STARTED_AT)
        assertThat(analysis.updatedAt).isEqualTo(STARTED_AT)
        assertThat(analysis.completedAt).isNull()
        assertThat(analysis.receptionVersion).isEqualTo(3)
        assertThat(analysis.referenceTime).isEqualTo(REFERENCE_TIME)
        assertThat(analysis.createdAt).isEqualTo(ACCEPTED_AT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["worker-1", "worker-2"])
    fun `같거나 다른 작업자의 중복 점유는 최초 시작 시각과 기한을 바꾸지 않는다`(workerId: String) {
        // given
        val analysis = analysis()
        analysis.claim("worker-1", STARTED_AT, DEADLINE)
        val before = snapshot(analysis)

        // when
        val claimed = analysis.claim(workerId, STARTED_AT.plusSeconds(1), DEADLINE.plusSeconds(1))

        // then
        assertThat(claimed).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @Test
    fun `처리 기한이 지난 진행 시도도 다른 작업자가 다시 실행하지 않는다`() {
        // given
        val analysis = analysis()
        analysis.claim("worker-1", STARTED_AT, DEADLINE)
        val before = snapshot(analysis)

        // when
        val claimed = analysis.claim("worker-2", DEADLINE.plusSeconds(1), DEADLINE.plusSeconds(60))

        // then
        assertThat(claimed).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisStatus::class, names = ["SUCCEEDED", "FAILED", "DISCARDED"])
    fun `종료된 분석은 다시 점유하거나 변경하지 않는다`(status: ReceptionAnalysisStatus) {
        // given
        val analysis = analysis()
        // 다음 단계의 종료 동작과 독립적으로 점유의 상태 경계만 준비한다.
        ReflectionTestUtils.setField(analysis, "status", status)
        val before = snapshot(analysis)

        // when
        val claimed = analysis.claim("worker-1", STARTED_AT, DEADLINE)

        // then
        assertThat(claimed).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["blank-worker", "long-worker", "early-start", "equal-deadline", "early-deadline"])
    fun `잘못된 작업자나 처리 시각은 시도를 변경하지 않고 거부한다`(invalid: String) {
        // given
        val analysis = analysis()
        val before = snapshot(analysis)
        val worker = when (invalid) {
            "blank-worker" -> " "
            "long-worker" -> "w".repeat(129)
            else -> "worker-1"
        }
        val start = if (invalid == "early-start") ACCEPTED_AT.minusSeconds(1) else STARTED_AT
        val deadline = when (invalid) {
            "equal-deadline" -> start
            "early-deadline" -> start.minusSeconds(1)
            else -> DEADLINE
        }

        // when & then
        assertThrows<IllegalArgumentException> { analysis.claim(worker, start, deadline) }
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @Test
    fun `기존 비최초 시도는 MVP 최초 실행 대상으로 가져가지 않는다`() {
        // given
        val analysis = analysis()
        ReflectionTestUtils.setField(analysis, "isInitial", false)
        val before = snapshot(analysis)

        // when
        val claimed = analysis.claim("worker-1", STARTED_AT, DEADLINE)

        // then
        assertThat(claimed).isFalse()
        assertThat(snapshot(analysis)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0])
    fun `접수 스냅샷 버전은 양수여야 한다`(version: Int) {
        // when & then
        assertThrows<IllegalArgumentException> {
            testReceptionAnalysis(1_005L, 1_001L, 1_007L, version, 1_003L, REFERENCE_TIME, ACCEPTED_AT)
        }
    }

    @Test
    fun `녹음 수신 시각 이전에는 분석을 접수할 수 없다`() {
        // when & then
        assertThrows<IllegalArgumentException> {
            testReceptionAnalysis(1_005L, 1_001L, 1_007L, 3, 1_003L, ACCEPTED_AT.plusSeconds(1), ACCEPTED_AT)
        }
    }

    private fun analysis() = testReceptionAnalysis(1_005L, 1_001L, 1_007L, 3, 1_003L, REFERENCE_TIME, ACCEPTED_AT)

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
        val REFERENCE_TIME = Instant.parse("2026-11-16T00:00:00Z")
        val ACCEPTED_AT = REFERENCE_TIME.plusSeconds(1)
        val STARTED_AT = ACCEPTED_AT.plusSeconds(1)
        val DEADLINE = STARTED_AT.plusSeconds(60)
    }
}
